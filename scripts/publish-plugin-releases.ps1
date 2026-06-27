<#
.SYNOPSIS
    Per-plugin, version-gated build + publish: for each official optional plugin, publish a GitHub Release to
    the distribution repo ONLY when its plugin.version has no matching `<id>-v<version>` release yet.

.DESCRIPTION
    Version is the immutability key. For each plugin:
      - read plugin.version from its source plugin.properties (no build needed to decide);
      - if release `<id>-v<version>` already exists -> SKIP (never rebuild, never re-upload - immutable);
      - else build ONLY that module (`mvn -pl <module> -am package` - its dep subtree, not the whole reactor
        nor other plugins), verify it is a thin PF4J jar, then create the release and upload the jar + .sha256.

    So updating one plugin compiles and publishes only that plugin. The market manifest is generated separately
    (generate-market-manifest.ps1) from the published releases. ASCII source; runs under Windows PowerShell /
    pwsh. Needs gh + GH_TOKEN and Maven (mvnw / mvn).

.PARAMETER Repo
    owner/repo of the plugin distribution repository. Default Sywyar/PixivDownloader-plugins.

.PARAMETER ProjectRoot
    Repo root. Default = parent of this script's dir.
#>
[CmdletBinding()]
param(
    [string]$Repo = "Sywyar/PixivDownloader-plugins",
    [string]$ProjectRoot
)

$ErrorActionPreference = "Stop"
if (-not $ProjectRoot) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# Shared official-plugin list + thin-jar / checksum helpers.
. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

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

$mvn = Get-MavenCommand $ProjectRoot
$stageDir = Join-Path $ProjectRoot "build/release-plugins"
New-Item -ItemType Directory -Force -Path $stageDir | Out-Null
$plugins = @(Get-OfficialOptionalPlugins)
$published = @()

foreach ($plugin in $plugins) {
    $version = Read-SourceVersion $plugin.Module
    $tag = "$($plugin.Id)-v$version"

    # `gh release view` returns a non-zero exit code and writes to stderr when the release does not exist.
    # Temporarily relax ErrorActionPreference so "release not found" can be handled as normal control flow.
    $oldErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $viewOutput = & gh release view $tag --repo $Repo 2>&1
        $viewExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }

    if ($viewExitCode -eq 0) {
        Write-Host "= $tag already published; skip (immutable - bump plugin.version to publish changes)."
        continue
    }

    if (($viewOutput -join "`n") -notmatch 'release not found|HTTP 404') {
        throw "gh release view failed for ${tag}: $($viewOutput -join "`n")"
    }

    Write-Host "==> Building only module $($plugin.Module) for new release $tag"
    Push-Location $ProjectRoot
    try {
        & $mvn "-pl" $plugin.Module "-am" "package" "-DskipTests" "-Dexec.skip=true"
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed for module $($plugin.Module)." }
    } finally {
        Pop-Location
    }

    $builtJar = Find-ModuleJar $plugin.Module $ProjectRoot
    $descriptor = Assert-ThinPluginJar $builtJar $plugin.Id
    $pluginVersion = $descriptor["plugin.version"]
    if ($pluginVersion -ne $version) {
        throw "Built plugin.version '$pluginVersion' != source '$version' for $($plugin.Id)."
    }

    # Stage under the canonical asset name <module>-<pluginVersion>.jar (matches tag + manifest generator).
    $assetName = "$($plugin.Module)-$pluginVersion.jar"
    $stagedJar = Join-Path $stageDir $assetName
    Copy-Item $builtJar $stagedJar -Force
    $sha = Get-Sha256Hex $stagedJar
    $shaFile = "$stagedJar.sha256"
    [System.IO.File]::WriteAllText($shaFile, "$sha  $assetName`n", $Utf8NoBom)

    Write-Host "==> Publishing $tag ($assetName, sha256 $sha)"
    gh release create $tag --repo $Repo --title $tag --notes "Plugin $($plugin.Id) $pluginVersion"
    if ($LASTEXITCODE -ne 0) { throw "gh release create failed for $tag." }
    gh release upload $tag $stagedJar $shaFile --repo $Repo
    if ($LASTEXITCODE -ne 0) { throw "gh release upload failed for $tag." }
    $published += $tag
}

if ($published.Count -eq 0) {
    Write-Host "No plugin needed building or publishing; all versions already released."
} else {
    Write-Host "Published: $($published -join ', ')"
}
