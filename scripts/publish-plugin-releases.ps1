<#
.SYNOPSIS
    Per-plugin, version-gated build + publish / repair: for each official required or optional plugin, create the
    GitHub Release when missing, supplement missing checksum/signature companions from immutable artifact bytes, or
    force rebuild and replace release assets. With -NightlyBuildVersion, rebuild every official plugin and refresh
    its rolling `<plugin-id>-nightly` prerelease using the source version plus the current Nightly build suffix.

.DESCRIPTION
    Version is the immutability key. For each plugin:
      - read plugin.version from its source plugin.properties (no build needed to decide);
      - if release `<id>-v<version>` already exists and already has artifact + .sha256 + .sig -> SKIP;
      - if release exists and the artifact is present but companions are missing -> regenerate only those companions
        from the published artifact bytes;
      - if release exists without the artifact -> FAIL and require a new plugin.version;
      - else build ONLY that module (`mvn -Pofficial-surveys -pl <module> -am verify` - its dep subtree, not the whole reactor
        nor other plugins), verify its official artifact format, then create the release and upload the artifact
        + .sha256 + .sig.

    So updating one plugin compiles and publishes only that plugin. Repairing a release that already has the
    artifact does not rebuild it; missing checksum / signature files are regenerated from the published bytes.
    The market manifest is generated separately (generate-market-manifest.ps1) from the published releases.
    ASCII source; runs under Windows PowerShell / pwsh. Needs gh + GH_TOKEN and Maven (mvnw / mvn).

    With -NightlyBuildVersion, every official plugin is rebuilt from current source, its staged plugin.properties is
    rewritten to the derived Nightly version, and the fixed `<plugin-id>-nightly` Release has all old assets replaced.
    The matching tag is advanced by the publishing action only after the signed Nightly manifest is committed.

    With -Force/-f, every official plugin is rebuilt for the source plugin.version. Existing expected release
    assets (artifact + .sha256 + .sig) are deleted before the freshly built files are uploaded, so a manual
    repair can replace an already-published asset set without changing the release tag.

.PARAMETER Repo
    owner/repo of the plugin distribution repository. Default Sywyar/PixivDownloader-plugins.

.PARAMETER ProjectRoot
    Repo root. Default = parent of this script's dir.

.PARAMETER Force
    Rebuild every official plugin and replace existing expected release assets for the current plugin.version.

.PARAMETER NightlyBuildVersion
    Nightly application build version. Only its nightly.date.run.attempt suffix is appended to each plugin's own
    source version.
#>
[CmdletBinding()]
param(
    [string]$Repo = "Sywyar/PixivDownloader-plugins",
    [string]$ProjectRoot,
    [string]$OfficialKeyId,
    [string]$PrivateKeyFile,
    [string]$SignatureToolJar,
    [string]$NightlyBuildVersion,
    [Alias("f")]
    [switch]$Force
)

$ErrorActionPreference = "Stop"
if (-not $ProjectRoot) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# Shared official-plugin list + artifact-shape / checksum helpers.
. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

if ([string]::IsNullOrWhiteSpace($OfficialKeyId)) { throw "OfficialKeyId is required." }
if ([string]::IsNullOrWhiteSpace($PrivateKeyFile) -or -not (Test-Path -LiteralPath $PrivateKeyFile -PathType Leaf)) {
    throw "PrivateKeyFile is required and must point to an Ed25519 PKCS#8 PEM file."
}
if (-not [string]::IsNullOrWhiteSpace($NightlyBuildVersion) -and
    $NightlyBuildVersion -notmatch '^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})-nightly\.[0-9]{8}\.[1-9][0-9]{0,8}\.[1-9][0-9]{0,8}$') {
    throw "NightlyBuildVersion must match major.minor.patch-nightly.yyyymmdd.run.attempt."
}
if (-not [string]::IsNullOrWhiteSpace($NightlyBuildVersion) -and $Force) {
    throw "Force is not supported for rolling Nightly plugin releases."
}
$nightlySuffix = if ([string]::IsNullOrWhiteSpace($NightlyBuildVersion)) {
    $null
} else {
    ($NightlyBuildVersion -split '-', 2)[1]
}
$SignatureToolJar = Resolve-SignatureToolJar $ProjectRoot $SignatureToolJar

function Read-SourceVersion([string]$module) {
    $path = Join-Path $ProjectRoot "$module/src/main/resources/plugin.properties"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing source descriptor: $path" }
    foreach ($line in (Get-Content -LiteralPath $path -Encoding UTF8)) {
        $trimmed = $line.Trim()
        if ($trimmed -match '^plugin\.version\s*=') {
            return ($trimmed -split '=', 2)[1].Trim()
        }
    }
    throw "plugin.version not found in $path"
}

function Get-MavenCommand([string]$root) {
    $wrapper = Join-Path $root "mvnw.cmd"
    if (Test-Path $wrapper) { return $wrapper }
    foreach ($name in @("mvn.cmd", "mvn")) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    throw "Missing Maven command. Install Maven or use the Maven wrapper."
}

function Get-ReleaseAssetState([string]$Tag) {
    # `gh release view` returns a non-zero exit code and writes to stderr when the release does not exist.
    # Temporarily relax ErrorActionPreference so "release not found" can be handled as normal control flow.
    $oldErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $viewOutput = & gh release view $Tag --repo $Repo --json assets 2>&1
        $viewExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }

    $viewText = ($viewOutput -join "`n")
    if ($viewExitCode -eq 0) {
        $view = $viewText | ConvertFrom-Json
        $assetNames = @()
        if ($view.assets) {
            $assetNames = @($view.assets | ForEach-Object { $_.name })
        }
        return [pscustomobject]@{ Exists = $true; AssetNames = $assetNames }
    }

    if ($viewText -notmatch 'release not found|HTTP 404') {
        throw "gh release view failed for ${Tag}: $viewText"
    }
    return [pscustomobject]@{ Exists = $false; AssetNames = @() }
}

function Build-StagedPluginArtifact {
    param(
        [Parameter(Mandatory = $true)]$Plugin,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$AssetName
    )

    Write-Host "==> Building only module $($Plugin.Module) for release $($Plugin.Id)-v$Version"
    Push-Location $ProjectRoot
    try {
        & $mvn "-Pofficial-surveys" "-pl" $Plugin.Module "-am" "verify" "-DskipTests" |
            ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed for module $($Plugin.Module)." }
    } finally {
        Pop-Location
    }

    $builtArtifact = Find-ModulePluginArtifact $Plugin $ProjectRoot
    $descriptor = Assert-OfficialPluginArtifact $builtArtifact $Plugin
    Assert-ProguardProcessedArtifact $builtArtifact
    $pluginVersion = $descriptor["plugin.version"]
    if ($pluginVersion -ne $Version) {
        throw "Built plugin.version '$pluginVersion' != source '$Version' for $($Plugin.Id)."
    }

    $stagedArtifact = Join-Path $stageDir $AssetName
    Copy-Item $builtArtifact $stagedArtifact -Force
    return $stagedArtifact
}

function Set-StagedPluginVersion {
    param(
        [Parameter(Mandatory = $true)][string]$StagedArtifact,
        [Parameter(Mandatory = $true)]$Plugin,
        [Parameter(Mandatory = $true)][string]$Version
    )

    $jarCommand = Get-Command "jar" -ErrorAction SilentlyContinue
    if (-not $jarCommand) { throw "Missing jar command from the configured JDK." }
    $rewriteDir = Join-Path ([System.IO.Path]::GetTempPath()) ("nightly-plugin-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $rewriteDir | Out-Null
    Push-Location $rewriteDir
    try {
        & $jarCommand.Source "--extract" "--file" $StagedArtifact "plugin.properties"
        if ($LASTEXITCODE -ne 0) { throw "Failed to extract plugin.properties from $StagedArtifact." }
        $descriptorPath = Join-Path $rewriteDir "plugin.properties"
        if (-not (Test-Path -LiteralPath $descriptorPath -PathType Leaf)) {
            throw "Root plugin.properties not found in $StagedArtifact."
        }
        $lines = @(Get-Content -LiteralPath $descriptorPath -Encoding UTF8)
        $versionLines = @($lines | Where-Object { $_ -match '^\s*plugin\.version\s*=' })
        if ($versionLines.Count -ne 1) {
            throw "Expected exactly one plugin.version in $StagedArtifact; found $($versionLines.Count)."
        }
        $rewritten = @($lines | ForEach-Object {
            if ($_ -match '^\s*plugin\.version\s*=') { "plugin.version=$Version" } else { $_ }
        })
        [System.IO.File]::WriteAllText($descriptorPath, (($rewritten -join "`n") + "`n"), $Utf8NoBom)
        & $jarCommand.Source "--update" "--file" $StagedArtifact "plugin.properties"
        if ($LASTEXITCODE -ne 0) { throw "Failed to update plugin.properties in $StagedArtifact." }
    } finally {
        Pop-Location
        Remove-Item -Recurse -Force -LiteralPath $rewriteDir -ErrorAction SilentlyContinue
    }

    $descriptor = Assert-OfficialPluginArtifact $StagedArtifact $Plugin
    if ($descriptor["plugin.version"] -ne $Version) {
        throw "Staged plugin.version '$($descriptor["plugin.version"])' != Nightly version '$Version' for $($Plugin.Id)."
    }
}

function Build-StagedNightlyPluginArtifact {
    param(
        [Parameter(Mandatory = $true)]$Plugin,
        [Parameter(Mandatory = $true)][string]$SourceVersion,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$AssetName
    )

    $sourceAssetName = Get-OfficialPluginArtifactName $Plugin $SourceVersion
    $sourceArtifact = Build-StagedPluginArtifact -Plugin $Plugin -Version $SourceVersion -AssetName $sourceAssetName
    $stagedArtifact = Join-Path $stageDir $AssetName
    Move-Item -LiteralPath $sourceArtifact -Destination $stagedArtifact -Force
    Set-StagedPluginVersion -StagedArtifact $stagedArtifact -Plugin $Plugin -Version $Version
    return $stagedArtifact
}

function Download-ReleaseAsset {
    param(
        [Parameter(Mandatory = $true)][string]$Tag,
        [Parameter(Mandatory = $true)][string]$AssetName
    )

    $target = Join-Path $stageDir $AssetName
    Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
    & gh release download $Tag --repo $Repo --pattern $AssetName --dir $stageDir --clobber |
        ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw "Failed to download existing asset $AssetName from release $Tag." }
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        throw "Downloaded asset not found after gh release download: $target"
    }
    return $target
}

function Write-StagedCompanionFiles {
    param(
        [Parameter(Mandatory = $true)][string]$StagedArtifact,
        [Parameter(Mandatory = $true)][string]$AssetName,
        [Parameter(Mandatory = $true)]$Plugin,
        [Parameter(Mandatory = $true)][string]$Version
    )

    $sha = Get-Sha256Hex $StagedArtifact
    $shaFile = "$StagedArtifact.sha256"
    [System.IO.File]::WriteAllText($shaFile, "$sha  $AssetName`n", $Utf8NoBom)
    $sigFile = "$StagedArtifact.sig"
    Invoke-PluginSignatureTool $SignatureToolJar @(
        "artifact",
        "--artifact", $StagedArtifact,
        "--plugin-id", $Plugin.Id,
        "--version", $Version,
        "--key-id", $OfficialKeyId,
        "--private-key", $PrivateKeyFile,
        "--out", $sigFile
    )
    return [pscustomobject]@{ Sha = $sha; ShaFile = $shaFile; SigFile = $sigFile }
}

function Upload-ReleaseAssetFiles {
    param(
        [Parameter(Mandatory = $true)][string]$Tag,
        [Parameter(Mandatory = $true)][string[]]$Paths
    )

    if (-not $Paths -or $Paths.Count -eq 0) {
        return
    }
    gh release upload $Tag $Paths --repo $Repo
    if ($LASTEXITCODE -ne 0) { throw "gh release upload failed for $Tag." }
}

function Remove-ExistingReleaseAssets {
    param(
        [Parameter(Mandatory = $true)][string]$Tag,
        [Parameter(Mandatory = $true)][string[]]$AssetNames,
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$ExistingAssetNames
    )

    foreach ($assetName in $AssetNames) {
        if ($ExistingAssetNames -notcontains $assetName) {
            continue
        }
        Write-Host "==> Deleting existing asset $assetName from $Tag before force upload."
        gh release delete-asset $Tag $assetName --repo $Repo --yes
        if ($LASTEXITCODE -ne 0) { throw "gh release delete-asset failed for $Tag asset $assetName." }
    }
}

$mvn = Get-MavenCommand $ProjectRoot
$stageDir = Join-Path $ProjectRoot "build/release-plugins"
New-Item -ItemType Directory -Force -Path $stageDir | Out-Null
$plugins = @(Get-OfficialDistributionPlugins -IncludeOptional)
$published = @()

if (-not [string]::IsNullOrWhiteSpace($NightlyBuildVersion)) {
    foreach ($plugin in $plugins) {
        $sourceVersion = Read-SourceVersion $plugin.Module
        $version = Get-NightlyPluginVersion $sourceVersion $nightlySuffix
        $tag = "$($plugin.Id)-nightly"
        $title = "Nightly Build $($plugin.Id)-v$version"
        $release = Get-ReleaseAssetState $tag
        $assetName = Get-OfficialPluginArtifactName $plugin $version
        $stagedArtifact = Build-StagedNightlyPluginArtifact -Plugin $plugin -SourceVersion $sourceVersion `
            -Version $version -AssetName $assetName
        $companions = Write-StagedCompanionFiles -StagedArtifact $stagedArtifact -AssetName $assetName `
            -Plugin $plugin -Version $version

        $uploadPaths = @($stagedArtifact, $companions.ShaFile, $companions.SigFile)
        if ($release.Exists) {
            $assetNames = @($release.AssetNames)
            if ($assetNames.Count -gt 0) {
                Remove-ExistingReleaseAssets -Tag $tag -AssetNames $assetNames -ExistingAssetNames $assetNames
            }
            gh release edit $tag --repo $Repo --title $title `
                --notes "Plugin $($plugin.Id) Nightly $version." --prerelease
            if ($LASTEXITCODE -ne 0) { throw "gh release edit failed for $tag." }
            Upload-ReleaseAssetFiles -Tag $tag -Paths $uploadPaths
        } else {
            gh release create $tag $uploadPaths --repo $Repo --title $title `
                --notes "Plugin $($plugin.Id) Nightly $version." --prerelease
            if ($LASTEXITCODE -ne 0) { throw "gh release create failed for $tag." }
        }
        $published += $tag
    }

    Write-Host "Refreshed Nightly plugin releases: $($published -join ', ')"
    return
}

foreach ($plugin in $plugins) {
    $version = Read-SourceVersion $plugin.Module
    $tag = "$($plugin.Id)-v$version"
    $assetName = Get-OfficialPluginArtifactName $plugin $version
    $shaAssetName = "$assetName.sha256"
    $sigAssetName = "$assetName.sig"
    $expectedAssets = @($assetName, $shaAssetName, $sigAssetName)
    $release = Get-ReleaseAssetState $tag
    $assetNames = @($release.AssetNames)

    if ($Force) {
        if ($release.Exists) {
            Write-Host "==> Force publishing $tag; rebuilding and replacing expected assets."
        } else {
            Write-Host "==> Force publishing $tag; release does not exist yet."
        }

        $stagedArtifact = Build-StagedPluginArtifact -Plugin $plugin -Version $version -AssetName $assetName
        $companions = Write-StagedCompanionFiles -StagedArtifact $stagedArtifact -AssetName $assetName -Plugin $plugin -Version $version

        if ($release.Exists) {
            Remove-ExistingReleaseAssets -Tag $tag -AssetNames $expectedAssets -ExistingAssetNames $assetNames
        } else {
            gh release create $tag --repo $Repo --title $tag --notes "Plugin $($plugin.Id) $version"
            if ($LASTEXITCODE -ne 0) { throw "gh release create failed for $tag." }
        }

        Upload-ReleaseAssetFiles -Tag $tag -Paths @($stagedArtifact, $companions.ShaFile, $companions.SigFile)
        $published += "$tag (forced)"
        continue
    }

    if ($release.Exists) {
        $missingAssets = @($expectedAssets | Where-Object { $assetNames -notcontains $_ })
        if ($missingAssets.Count -eq 0) {
            Write-Host "= $tag already published with expected assets; skip."
            continue
        }

        Write-Host "==> $tag already exists but missing asset(s): $($missingAssets -join ', '); supplementing."
        $artifactAssetExists = $assetNames -contains $assetName
        if ($artifactAssetExists) {
            $stagedArtifact = Download-ReleaseAsset -Tag $tag -AssetName $assetName
        } else {
            throw "Release $tag already exists without $assetName. Bump plugin.version instead of publishing new bytes under an existing tag."
        }
        $companions = Write-StagedCompanionFiles -StagedArtifact $stagedArtifact -AssetName $assetName -Plugin $plugin -Version $version

        $uploadPaths = @()
        if (-not $artifactAssetExists) { $uploadPaths += $stagedArtifact }
        if ($missingAssets -contains $shaAssetName) { $uploadPaths += $companions.ShaFile }
        if ($missingAssets -contains $sigAssetName) { $uploadPaths += $companions.SigFile }
        Upload-ReleaseAssetFiles -Tag $tag -Paths $uploadPaths
        $published += "$tag (supplemented)"
        continue
    }

    $stagedArtifact = Build-StagedPluginArtifact -Plugin $plugin -Version $version -AssetName $assetName
    $companions = Write-StagedCompanionFiles -StagedArtifact $stagedArtifact -AssetName $assetName -Plugin $plugin -Version $version

    Write-Host "==> Publishing $tag ($assetName, sha256 $($companions.Sha))"
    gh release create $tag --repo $Repo --title $tag --notes "Plugin $($plugin.Id) $version"
    if ($LASTEXITCODE -ne 0) { throw "gh release create failed for $tag." }
    Upload-ReleaseAssetFiles -Tag $tag -Paths @($stagedArtifact, $companions.ShaFile, $companions.SigFile)
    $published += "$tag (created)"
}

if ($published.Count -eq 0) {
    Write-Host "No plugin needed building, publishing, or asset supplementation; all releases have expected assets."
} else {
    Write-Host "Changed releases: $($published -join ', ')"
}
