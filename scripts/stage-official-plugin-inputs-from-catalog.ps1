<#
.SYNOPSIS
    Stage official plugin artifacts from the signed plugin repository catalog.

.DESCRIPTION
    Downloads manifest.json + manifest.json.sig from the official plugin repository, verifies the
    manifest with the built-in official trust root, then downloads the required official plugin
    artifacts (and optional artifacts when -IncludeOptional is set). Each artifact is verified using
    the structured package signature embedded in the catalog and is staged with adjacent .sig and
    .sha256 sidecars for downstream packaging scripts.
#>
[CmdletBinding()]
param(
    [string]$ManifestUrl = "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json",
    [string]$OutputDir,
    [Parameter(Mandatory = $true)][string]$SignatureToolJar,
    [switch]$IncludeOptional,
    [string]$CoreApiVersion = "1.2.0"
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot "build/plugin-inputs"
}
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$SignatureToolJar = Resolve-SignatureToolJar $ProjectRoot $SignatureToolJar

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-SafeRemovableDir {
    param([string]$Path, [string]$RepoRoot)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "Output dir path is empty; refusing to delete."
    }
    $full = [System.IO.Path]::GetFullPath($Path)
    $parent = [System.IO.Path]::GetDirectoryName($full)
    if ([string]::IsNullOrEmpty($parent)) {
        throw "Refusing to use a drive/filesystem root as the output dir: $full"
    }
    $sep = [System.IO.Path]::DirectorySeparatorChar
    $fullTrimmed = $full.TrimEnd($sep, '/')
    $repoTrimmed = ([System.IO.Path]::GetFullPath($RepoRoot)).TrimEnd($sep, '/')
    if ($fullTrimmed.Equals($repoTrimmed, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to use the repository root as the output dir: $full"
    }
    if ($repoTrimmed.StartsWith($fullTrimmed + $sep, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to use an ancestor of the repository root as the output dir: $full"
    }
    return $full
}

function Convert-ToRawCatalogUrl([string]$Url) {
    $trimmed = $Url.Trim()
    if ($trimmed -match '^https://github\.com/([^/?#]+)/([^/?#]+)/blob/([^/?#]+)/(.+\.json)(?:[?#].*)?$') {
        return "https://raw.githubusercontent.com/$($Matches[1])/$($Matches[2])/$($Matches[3])/$($Matches[4])"
    }
    return $trimmed
}

function Get-DetachedSignatureUrl([string]$Url) {
    $raw = Convert-ToRawCatalogUrl $Url
    $idx = $raw.IndexOf("?")
    if ($idx -ge 0) {
        return $raw.Substring(0, $idx) + ".sig" + $raw.Substring($idx)
    }
    return "$raw.sig"
}

function Invoke-DownloadFile([string]$Url, [string]$OutFile) {
    $directory = [System.IO.Path]::GetDirectoryName($OutFile)
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    }
    Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -TimeoutSec 300
}

function Get-Prop($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Parse-VersionPair([string]$Version) {
    if ([string]::IsNullOrWhiteSpace($Version) -or $Version.Trim() -eq "*") { return @(0, 0) }
    $parts = $Version.Split(".")
    $major = 0
    $minor = 0
    if ($parts.Length -ge 1) { [void][int]::TryParse($parts[0], [ref]$major) }
    if ($parts.Length -ge 2) { [void][int]::TryParse($parts[1], [ref]$minor) }
    return @($major, $minor)
}

function Test-Compatible([string]$Required) {
    if ([string]::IsNullOrWhiteSpace($Required) -or $Required.Trim() -eq "*") { return $true }
    $core = Parse-VersionPair $CoreApiVersion
    $requiredPair = Parse-VersionPair $Required
    return ($core[0] -eq $requiredPair[0]) -and ($core[1] -ge $requiredPair[1])
}

function Find-CatalogEntry($Manifest, [string]$PluginId) {
    foreach ($entry in @(Get-Prop $Manifest "entries")) {
        if ([string](Get-Prop $entry "pluginId") -eq $PluginId) {
            return $entry
        }
    }
    return $null
}

function Select-CatalogPackage($Entry) {
    $packages = @(Get-Prop $Entry "packages")
    if ($packages.Count -eq 0) { return $null }
    $market = Get-Prop $Entry "market"
    $latest = [string](Get-Prop $market "latestVersion")
    if (-not [string]::IsNullOrWhiteSpace($latest)) {
        foreach ($pkg in $packages) {
            if (((Get-Prop $pkg "version") -eq $latest) -and
                (Test-Compatible ([string](Get-Prop $pkg "requiredCoreApi")))) {
                return $pkg
            }
        }
    }
    foreach ($pkg in $packages) {
        if (Test-Compatible ([string](Get-Prop $pkg "requiredCoreApi"))) {
            return $pkg
        }
    }
    return $null
}

function Assert-CatalogPackage($Plugin, $Package) {
    if ($null -eq $Package) { throw "No compatible catalog package found for $($Plugin.Id)." }
    if ([string]::IsNullOrWhiteSpace([string](Get-Prop $Package "version"))) { throw "Missing version for $($Plugin.Id)." }
    if ([string]::IsNullOrWhiteSpace([string](Get-Prop $Package "packageUrl"))) { throw "Missing packageUrl for $($Plugin.Id)." }
    if ([string]::IsNullOrWhiteSpace([string](Get-Prop $Package "sha256"))) { throw "Missing sha256 for $($Plugin.Id)." }
    if ([int64](Get-Prop $Package "expectedSizeBytes") -le 0) { throw "Missing expectedSizeBytes for $($Plugin.Id)." }
    if ($null -eq (Get-Prop $Package "signature")) { throw "Missing structured signature for $($Plugin.Id)." }
}

$OutputDir = Assert-SafeRemovableDir $OutputDir $ProjectRoot
if (Test-Path -LiteralPath $OutputDir) {
    Remove-Item -LiteralPath $OutputDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$workDir = Join-Path ([System.IO.Path]::GetTempPath()) ("pixivdownload-plugin-catalog-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $workDir | Out-Null

try {
    $manifestPath = Join-Path $workDir "manifest.json"
    $signaturePath = Join-Path $workDir "manifest.json.sig"
    $rawManifestUrl = Convert-ToRawCatalogUrl $ManifestUrl
    $rawSignatureUrl = Get-DetachedSignatureUrl $ManifestUrl

    Write-Step "Downloading signed official plugin catalog"
    Invoke-DownloadFile $rawManifestUrl $manifestPath
    Invoke-DownloadFile $rawSignatureUrl $signaturePath
    Invoke-PluginSignatureTool $SignatureToolJar @(
        "verify-manifest",
        "--manifest", $manifestPath,
        "--signature", $signaturePath,
        "--repository-id", "official",
        "--policy", "official"
    )

    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -eq $manifest -or $null -eq (Get-Prop $manifest "entries")) {
        throw "Catalog manifest does not contain an entries array."
    }

    $plugins = @(Get-OfficialDistributionPlugins -IncludeOptional:$IncludeOptional)
    foreach ($plugin in $plugins) {
        Write-Step "Staging signed plugin '$($plugin.Id)'"
        $entry = Find-CatalogEntry $manifest $plugin.Id
        if ($null -eq $entry) { throw "Plugin $($plugin.Id) not found in official catalog." }
        $package = Select-CatalogPackage $entry
        Assert-CatalogPackage $plugin $package

        $version = [string](Get-Prop $package "version")
        $assetName = Get-OfficialPluginArtifactName $plugin $version
        $artifactPath = Join-Path $OutputDir $assetName
        $artifactSignaturePath = "$artifactPath.sig"
        $sha256 = [string](Get-Prop $package "sha256")
        $expectedSize = [int64](Get-Prop $package "expectedSizeBytes")
        $packageUrl = [string](Get-Prop $package "packageUrl")

        Invoke-DownloadFile $packageUrl $artifactPath
        $signatureJson = (Get-Prop $package "signature") | ConvertTo-Json -Compress -Depth 8
        [System.IO.File]::WriteAllText($artifactSignaturePath, $signatureJson + "`n", $Utf8NoBom)
        [void](Assert-PluginArtifactSignature $SignatureToolJar $artifactPath $artifactSignaturePath `
            $plugin.Id $version $expectedSize $sha256)

        $descriptor = Assert-OfficialPluginArtifact $artifactPath $plugin
        if ($descriptor["plugin.version"] -ne $version) {
            throw "Catalog version '$version' does not match plugin.properties version '$($descriptor["plugin.version"])' for $($plugin.Id)."
        }
        [System.IO.File]::WriteAllText("$artifactPath.sha256", "$sha256  $assetName`n", $Utf8NoBom)
        Write-Host ("    OK: {0} {1} ({2} bytes, sha256 {3})." -f $plugin.Id, $version, $expectedSize, $sha256) -ForegroundColor Green
    }

    Write-Step "Done"
    Write-Host "Plugin inputs: $OutputDir"
} finally {
    Remove-Item -LiteralPath $workDir -Recurse -Force -ErrorAction SilentlyContinue
}
