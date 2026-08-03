<#
.SYNOPSIS
    Per-plugin, version-gated build + publish / repair: for each official required or optional plugin, create the
    GitHub Release when missing, or supplement missing checksum/signature companions from immutable artifact bytes.

.DESCRIPTION
    Version is the immutability key. For each plugin:
      - read plugin.version from its source plugin.properties (no build needed to decide);
      - if release `<id>-v<version>` already exists and already has artifact + .sha256 + .sig -> SKIP;
      - if release exists and the artifact is present but companions are missing -> regenerate only those companions
        from the published artifact bytes;
      - if release exists without the artifact -> FAIL and require a new plugin.version;
      - else build ONLY that module (`mvn -pl <module> -am package` - its dep subtree, not the whole reactor
        nor other plugins), verify its official artifact format, then create the release and upload the artifact
        + .sha256 + .sig.

    So updating one plugin compiles and publishes only that plugin. Repairing a release that already has the
    artifact does not rebuild it; missing checksum / signature files are regenerated from the published bytes.
    The market manifest is generated separately (generate-market-manifest.ps1) from the published releases.
    ASCII source; runs under Windows PowerShell / pwsh. Needs gh + GH_TOKEN and Maven (mvnw / mvn).

.PARAMETER Repo
    owner/repo of the plugin distribution repository. Default Sywyar/PixivDownloader-plugins.

.PARAMETER ProjectRoot
    Repo root. Default = parent of this script's dir.

#>
[CmdletBinding()]
param(
    [string]$Repo = "Sywyar/PixivDownloader-plugins",
    [string]$ProjectRoot,
    [string]$OfficialKeyId,
    [string]$PrivateKeyFile,
    [string]$SignatureToolJar
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

    # download-workbench 的布局偏好调查公开客户端配置：先验证（上游缺配置即失败），
    # 构建完成后把最终生成文件替换进插件 jar，再对最终字节计算 sha256 / 签名。
    $layoutSurveyConfig = $null
    if ($Plugin.Id -eq "download-workbench") {
        $layoutSurveyConfig = New-LayoutSurveyPublicConfig
    }

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
    if ($layoutSurveyConfig) {
        Update-JarFileEntry `
            -JarPath $stagedArtifact `
            -EntryName "static/pixiv-layout-feedback/public-config.js" `
            -SourceFile $layoutSurveyConfig
        # 字节级验证：entry 必须存在、与生成文件逐字节一致、enabled=true 且四项值完整，
        # 之后才计算 sha256 / 签名（签名必须覆盖最终 artifact 字节）。
        Assert-JarFileEntryEqualsFile `
            -JarPath $stagedArtifact `
            -EntryName "static/pixiv-layout-feedback/public-config.js" `
            -SourceFile $layoutSurveyConfig
        $bakedBytes = Read-JarFileEntryBytes `
            -JarPath $stagedArtifact `
            -EntryName "static/pixiv-layout-feedback/public-config.js"
        $bakedText = [System.Text.Encoding]::UTF8.GetString($bakedBytes)
        if ($bakedText -notmatch "enabled: true") {
            throw "Baked layout survey public-config.js is not enabled=true in $stagedArtifact"
        }
        foreach ($needle in @("projectToken:", "surveyId:", "apiHost:", "uiHost:")) {
            if ($bakedText -notmatch [regex]::Escape($needle)) {
                throw "Baked layout survey public-config.js is missing '$needle' in $stagedArtifact"
            }
        }
        Write-Host "==> Verified layout survey public-config.js baked into $stagedArtifact (enabled, byte-identical)."
    }
    return $stagedArtifact
}

function New-LayoutSurveyPublicConfig {
    # Public client configuration for the layout preference survey. Reads the
    # PIXIV_LAYOUT_SURVEY_* environment variables (GitHub Actions Repository
    # Variables, vars context - never secrets). Partial configuration fails the
    # build; empty configuration returns $null (disabled plugin config stays in
    # the jar as generated by the source build).
    $generator = Join-Path $PSScriptRoot "generate-layout-survey-public-config.ps1"
    $configPath = Join-Path $stageDir "layout-survey-public-config.js"
    Remove-Item -LiteralPath $configPath -Force -ErrorAction SilentlyContinue
    $env:PIXIV_LAYOUT_SURVEY_OUTPUT_PATH = $configPath
    & powershell -NoProfile -NonInteractive -File $generator |
        ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        throw "Layout survey public config generation failed; refusing to publish download-workbench without a complete configuration."
    }
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw "Layout survey public config file was not generated: $configPath"
    }
    $content = [System.IO.File]::ReadAllText($configPath, [System.Text.Encoding]::UTF8)
    if ($content -match "enabled: true") {
        Write-Host "==> Layout survey public config enabled; will bake into the download-workbench jar."
        return $configPath
    }
    Write-Host "==> Layout survey public config disabled; download-workbench keeps the source default."
    return $null
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
