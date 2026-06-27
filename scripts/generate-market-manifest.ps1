<#
.SYNOPSIS
    Generate the plugin-market manifest.json (consumer-facing strict catalog) from the ALREADY-PUBLISHED
    GitHub Releases of the distribution repo + a curation source.

.DESCRIPTION
    The manifest is derived from the published release assets, NOT from a local build — so it is correct
    whether or not anything was rebuilt this run (version-gated publishing keeps unchanged plugins' assets
    untouched, and a rebuilt jar of the same version can differ byte-wise). For each official plugin:

      - id / version / requires      : read from the module's source plugin.properties (literal, no build).
      - sha256 / expectedSizeBytes   : computed from the DOWNLOADED published jar asset (the real bytes).
      - downloadCount / releasedTime : read from the GitHub Releases API (asset download_count / publishedAt).
      - packageUrl                   : the GitHub Release asset link (github.com/.../releases/download/...).
      - display fields               : from the curation file, keyed by pluginId.

    The matching release MUST already exist (publish it first); a missing release is a hard error. Output is
    STRICT JSON (no comments), UTF-8 (no BOM), camelCase, asserted <= 1MB; `signature` and `rating`/`ratingCount`
    are omitted. Cross-shell (Windows PowerShell 5.1 + pwsh): ASCII source, no ternary / -AsHashtable. Needs gh + GH_TOKEN.

.PARAMETER Repo
    owner/repo of the plugin distribution repository. Default Sywyar/PixivDownloader-plugins.

.PARAMETER CurationFile
    Display-field curation source, keyed by pluginId. Default scripts/market-curation.json.

.PARAMETER OutputFile
    Where to write the generated manifest.json. Default build/manifest.json.

.PARAMETER ProjectRoot
    Repo root (to locate plugin modules' source plugin.properties). Default = parent of this script's dir.
#>
[CmdletBinding()]
param(
    [string]$Repo = "Sywyar/PixivDownloader-plugins",
    [string]$CurationFile = "scripts/market-curation.json",
    [string]$OutputFile = "build/manifest.json",
    [string]$ProjectRoot
)

$ErrorActionPreference = "Stop"
if (-not $ProjectRoot) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$nowUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")

# Shared official-plugin list (id / module).
. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

function Read-Json([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing file: $path" }
    return (Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Has-Property($obj, [string]$name) {
    return ($null -ne $obj) -and ($obj.PSObject.Properties.Name -contains $name)
}

# major.minor from a plugin.requires string (e.g. "1.0" / "1.0.0" -> "1.0").
function Get-RequiredCoreApi([string]$requires) {
    if ($requires -and ($requires -match '(\d+)\.(\d+)')) {
        return "$($Matches[1]).$($Matches[2])"
    }
    return $requires
}

# Parse a module's source plugin.properties (root descriptor) into a hashtable.
function Read-SourceDescriptor([string]$module) {
    $path = Join-Path $ProjectRoot "$module/src/main/resources/plugin.properties"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing source descriptor: $path" }
    $props = @{}
    foreach ($line in (Get-Content -LiteralPath $path -Encoding UTF8)) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
        $idx = $trimmed.IndexOf("=")
        if ($idx -lt 1) { continue }
        $props[$trimmed.Substring(0, $idx).Trim()] = $trimmed.Substring($idx + 1).Trim()
    }
    return $props
}

$curation = Read-Json $CurationFile
$plugins = @(Get-OfficialOptionalPlugins)
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("market-manifest-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

$entries = @()
try {
    foreach ($plugin in $plugins) {
        $d = Read-SourceDescriptor $plugin.Module
        $id = $d["plugin.id"]
        $version = $d["plugin.version"]
        $requires = $d["plugin.requires"]
        if ($id -ne $plugin.Id) {
            throw "plugin.id '$id' in module $($plugin.Module) does not match expected '$($plugin.Id)'."
        }
        $tag = "$id-v$version"
        $jarName = "$($plugin.Module)-$version.jar"

        # Release metadata (must already exist): asset download_count + release publishedAt.
        $relRaw = & gh release view $tag --repo $Repo --json assets,publishedAt
        if ($LASTEXITCODE -ne 0 -or -not $relRaw) {
            throw "Release $tag not found in $Repo. Publish it before generating the manifest."
        }
        $rel = $relRaw | ConvertFrom-Json
        $jarAsset = $rel.assets | Where-Object { $_.name -eq $jarName } | Select-Object -First 1
        if (-not $jarAsset) { throw "Asset $jarName not found on release $tag." }
        $downloadCount = [int]$jarAsset.downloadCount
        $releasedTime = $rel.publishedAt
        if (-not $releasedTime) { $releasedTime = $nowUtc }

        # sha256 / size from the ACTUAL published bytes (download the jar, compute locally).
        & gh release download $tag --repo $Repo --pattern $jarName --dir $tmp --clobber
        if ($LASTEXITCODE -ne 0) { throw "Failed to download $jarName from release $tag." }
        $jarPath = Join-Path $tmp $jarName
        $sizeBytes = (Get-Item -LiteralPath $jarPath).Length
        $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToLowerInvariant()

        $packageUrl = "https://github.com/$Repo/releases/download/$tag/$jarName"

        if (-not (Has-Property $curation $id)) {
            throw "No curation entry for plugin '$id' in $CurationFile (display fields are required)."
        }
        $c = $curation.$id
        $changeNotes = @()
        if (Has-Property $c "changeNotes") { $changeNotes = @($c.changeNotes) }

        $market = [ordered]@{
            displayName      = $c.displayName
            summary          = $c.summary
            description      = $c.description
            author           = $c.author
            sourceType       = $c.sourceType
            category         = $c.category
            tags             = @($c.tags)
            homepageUrl      = $c.homepageUrl
            license          = $c.license
            downloadCount    = $downloadCount
            latestVersion    = $version
            updatedTime      = $releasedTime
            iconToken        = $c.iconToken
            colorToken       = $c.colorToken
            recommended      = [bool]$c.recommended
            officialRequired = [bool]$c.officialRequired
        }

        $package = [ordered]@{
            version           = $version
            packageUrl        = $packageUrl
            expectedSizeBytes = $sizeBytes
            sha256            = $sha256
            requiredCoreApi   = (Get-RequiredCoreApi $requires)
            dependencies      = @()
            releasedTime      = $releasedTime
            changeNotes       = $changeNotes
            channel           = "stable"
            deprecated        = $false
        }

        $entries += [ordered]@{
            pluginId       = $id
            displayNameKey = $c.displayNameKey
            descriptionKey = $c.descriptionKey
            market         = $market
            packages       = @($package)
        }
        Write-Host "  + $id $version  ($sizeBytes bytes, sha256 $($sha256.Substring(0,12))..., downloads $downloadCount)"
    }
} finally {
    Remove-Item -Recurse -Force -LiteralPath $tmp -ErrorAction SilentlyContinue
}

$manifest = [ordered]@{
    schemaVersion = "1"
    generatedTime = $nowUtc
    entries       = @($entries)
}

$json = $manifest | ConvertTo-Json -Depth 12
$bytes = $Utf8NoBom.GetBytes($json)
if ($bytes.Length -gt 1MB) {
    throw "Generated manifest is $($bytes.Length) bytes (> 1MB limit). Trim display fields."
}

$outDir = Split-Path -Parent $OutputFile
if ($outDir -and -not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
[System.IO.File]::WriteAllText($OutputFile, $json, $Utf8NoBom)
Write-Host "Wrote $OutputFile ($($bytes.Length) bytes, $($entries.Count) plugin(s)) from published releases of $Repo."
