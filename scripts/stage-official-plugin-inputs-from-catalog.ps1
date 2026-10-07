<#
.SYNOPSIS
    Stage official plugin artifacts from the signed plugin repository catalog.

.DESCRIPTION
    Downloads manifest.json + manifest.json.sig from the official plugin repository, verifies the
    manifest with the built-in official trust root, then downloads the canonical default-installed
    official plugin artifacts (plus on-demand artifacts when -IncludeOptional is set). Each artifact
    is verified using the structured package signature embedded in the catalog and is staged with
    adjacent .sig and .sha256 sidecars for downstream packaging scripts.
#>
[CmdletBinding()]
param(
    [string]$ManifestUrl = "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json",
    [string]$OutputDir,
    [Parameter(Mandatory = $true)][string]$SignatureToolJar,
    [switch]$IncludeOptional,
    [switch]$RequireProguard
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SdkVersion = Get-PixivDownloadSdkVersion -ProjectRoot $ProjectRoot
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

function Test-Compatible([string]$Required) {
    return Test-PluginSdkCompatible $Required $SdkVersion
}

function Read-CatalogHistory($Entry, [string]$CatalogUrl, [string]$Directory) {
    $history = Get-Prop $Entry 'history'
    if ($null -eq $history) { return $null }
    $id = [string](Get-Prop $Entry 'pluginId')
    $relative = [string](Get-Prop $history 'path')
    $size = [int64](Get-Prop $history 'sizeBytes')
    $count = [int](Get-Prop $history 'versions')
    $sha = [string](Get-Prop $history 'sha256')
    if ($id -notmatch '^[a-z0-9][a-z0-9._-]{0,127}$' -or $relative -cne "history/$id-$sha.json" -or
        $sha -cnotmatch '^[a-f0-9]{64}$' -or $size -lt 1 -or $size -gt 1048576 -or $count -lt 1 -or $count -gt 900) {
        throw "Invalid signed history reference for $id."
    }
    $url = [Uri]::new([Uri]$CatalogUrl, $relative)
    $file = Join-Path $Directory "$id-history.json"
    Invoke-DownloadFile $url.AbsoluteUri $file
    if ((Get-Item -LiteralPath $file).Length -ne $size -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $sha) {
        throw "Plugin history digest mismatch for $id."
    }
    $document = Get-Content -LiteralPath $file -Raw -Encoding UTF8 | ConvertFrom-Json
    if ((Get-Prop $document 'pluginId') -cne $id -or (Get-Prop $document 'history') -or
        @(Get-Prop $document 'packages').Count -ne $count) { throw "Invalid plugin history for $id." }
    $versions = @{}
    foreach ($package in @((Get-Prop $Entry 'packages')) + @((Get-Prop $document 'packages'))) {
        $version = [string](Get-Prop $package 'version')
        if (-not $version -or $versions.ContainsKey($version)) { throw "Duplicate or missing history version for $id." }
        $versions[$version] = $true
    }
    return $document
}

function Get-PackageRequiredSdk($Package) {
    $required = [string](Get-Prop $Package "requiredSdk")
    if ([string]::IsNullOrWhiteSpace($required)) {
        $required = [string](Get-Prop $Package "requiredCoreApi")
    }
    return $required
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
                (Test-Compatible (Get-PackageRequiredSdk $pkg))) {
                return $pkg
            }
        }
    }
    foreach ($pkg in $packages) {
        if (Test-Compatible (Get-PackageRequiredSdk $pkg)) {
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
    $catalogIds = @(
        @(Get-Prop $manifest "entries") |
            ForEach-Object { [string](Get-Prop $_ "pluginId") } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique
    )
    $missingPluginIds = @()
    $incompatiblePluginIds = @()
    $stagingPlans = @()
    foreach ($plugin in $plugins) {
        $entry = Find-CatalogEntry $manifest $plugin.Id
        if ($null -eq $entry) {
            $missingPluginIds += $plugin.Id
            continue
        }
        $package = Select-CatalogPackage $entry
        if ($null -eq $package) {
            $history = Read-CatalogHistory $entry $rawManifestUrl $workDir
            if ($null -ne $history) { $package = Select-CatalogPackage $history }
        }
        if ($null -eq $package) {
            $incompatiblePluginIds += $plugin.Id
            continue
        }
        Assert-CatalogPackage $plugin $package
        $stagingPlans += [pscustomobject]@{
            Plugin = $plugin
            Package = $package
        }
    }

    $problems = @()
    if ($missingPluginIds.Count -gt 0) {
        $problems += "missing canonical plugin id(s): $($missingPluginIds -join ', ')"
    }
    if ($incompatiblePluginIds.Count -gt 0) {
        $problems += "no package compatible with SDK $SdkVersion for: $($incompatiblePluginIds -join ', ')"
    }
    if ($problems.Count -gt 0) {
        $available = if ($catalogIds.Count -gt 0) { $catalogIds -join ", " } else { "<none>" }
        $generatedTime = [string](Get-Prop $manifest "generatedTime")
        if ([string]::IsNullOrWhiteSpace($generatedTime)) { $generatedTime = "<unknown>" }
        throw ("Official catalog is not synchronized with this source tree ({0}). " +
            "Catalog URL: {1}. Catalog generatedTime: {2}. Available catalog plugin ids: {3}. " +
            "Publish the current official plugins first, pass a matching -ManifestUrl, or use " +
            "package-installer-with-plugins.ps1 -PluginSource Local with the official signing key.") -f `
            ($problems -join "; "), $rawManifestUrl, $generatedTime, $available
    }

    if (Test-Path -LiteralPath $OutputDir) {
        Remove-Item -LiteralPath $OutputDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

    foreach ($plan in $stagingPlans) {
        $plugin = $plan.Plugin
        $package = $plan.Package
        Write-Step "Staging signed plugin '$($plugin.Id)'"

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
        if ($RequireProguard) {
            Assert-ProguardProcessedArtifact $artifactPath
        }
        if ($descriptor["plugin.version"] -ne $version) {
            throw "Catalog version '$version' does not match plugin.properties version '$($descriptor["plugin.version"])' for $($plugin.Id)."
        }
        if ($descriptor["plugin.requires"] -cne (Get-PackageRequiredSdk $package)) {
            throw "Catalog SDK requirement does not match the artifact for $($plugin.Id)."
        }
        [System.IO.File]::WriteAllText("$artifactPath.sha256", "$sha256  $assetName`n", $Utf8NoBom)
        Write-Host ("    OK: {0} {1} ({2} bytes, sha256 {3})." -f $plugin.Id, $version, $expectedSize, $sha256) -ForegroundColor Green
    }

    Write-Step "Done"
    Write-Host "Plugin inputs: $OutputDir"
} finally {
    Remove-Item -LiteralPath $workDir -Recurse -Force -ErrorAction SilentlyContinue
}
