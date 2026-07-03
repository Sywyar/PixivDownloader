<#
.SYNOPSIS
    Per-plugin, version-gated build + publish / repair: for each official required or optional plugin, create the
    GitHub Release when missing, supplement missing release assets for the current plugin.version, or
    force rebuild and replace release assets.

.DESCRIPTION
    Version is the immutability key. For each plugin:
      - read plugin.version from its source plugin.properties (no build needed to decide);
      - if release `<id>-v<version>` already exists and already has artifact + .sha256 + .sig -> SKIP;
      - if release exists but misses assets -> supplement only the missing assets. When the artifact already exists,
        checksum / signature are regenerated from the published artifact bytes, not from a rebuild;
      - else build ONLY that module (`mvn -pl <module> -am package` - its dep subtree, not the whole reactor
        nor other plugins), verify its official artifact format, then create the release and upload the artifact
        + .sha256 + .sig.

    So updating one plugin compiles and publishes only that plugin. Repairing a release that already has the
    artifact does not rebuild it; missing checksum / signature files are regenerated from the published bytes.
    The market manifest is generated separately (generate-market-manifest.ps1) from the published releases.
    ASCII source; runs under Windows PowerShell / pwsh. Needs gh + GH_TOKEN and Maven (mvnw / mvn).

    With -Force/-f, every official plugin is rebuilt for the source plugin.version. Existing expected release
    assets (artifact + .sha256 + .sig) are deleted before the freshly built files are uploaded, so a manual
    repair can replace an already-published asset set without changing the release tag.

.PARAMETER Repo
    owner/repo of the plugin distribution repository. Default Sywyar/PixivDownloader-plugins.

.PARAMETER ProjectRoot
    Repo root. Default = parent of this script's dir.

.PARAMETER Force
    Rebuild every official plugin and replace existing expected release assets for the current plugin.version.
#>
[CmdletBinding()]
param(
    [string]$Repo = "Sywyar/PixivDownloader-plugins",
    [string]$ProjectRoot,
    [string]$OfficialKeyId,
    [string]$PrivateKeyFile,
    [string]$SignatureToolJar,
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
        & $mvn "-pl" $Plugin.Module "-am" "package" "-DskipTests" "-Dexec.skip=true" |
            ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed for module $($Plugin.Module)." }
    } finally {
        Pop-Location
    }

    $builtArtifact = Find-ModulePluginArtifact $Plugin $ProjectRoot
    $descriptor = Assert-OfficialPluginArtifact $builtArtifact $Plugin
    $pluginVersion = $descriptor["plugin.version"]
    if ($pluginVersion -ne $Version) {
        throw "Built plugin.version '$pluginVersion' != source '$Version' for $($Plugin.Id)."
    }

    $stagedArtifact = Join-Path $stageDir $AssetName
    Copy-Item $builtArtifact $stagedArtifact -Force
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
            $stagedArtifact = Build-StagedPluginArtifact -Plugin $plugin -Version $version -AssetName $assetName
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
