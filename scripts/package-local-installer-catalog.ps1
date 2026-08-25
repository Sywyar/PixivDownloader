<#
.SYNOPSIS
    Verifies and projects the signed plugin catalog for the Windows installer.

.DESCRIPTION
    This file owns only the package-local installer catalog snapshot and projections. Signature
    verification continues to use plugin-distribution-common.ps1.
    This file is ASCII-only for Windows PowerShell 5.1.
#>

if (-not (Get-Command Invoke-PluginSignatureTool -ErrorAction SilentlyContinue)) {
    . (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")
}

function ConvertTo-RawPluginCatalogUrl {
    param([Parameter(Mandatory = $true)][string]$Url)
    $trimmed = $Url.Trim()
    if ($trimmed -match "^https://github\.com/([^/?#]+)/([^/?#]+)/blob/([^/?#]+)/(.+\.json)(?:[?#].*)?$") {
        return "https://raw.githubusercontent.com/$($Matches[1])/$($Matches[2])/$($Matches[3])/$($Matches[4])"
    }
    return $trimmed
}

function Get-PluginCatalogSignatureUrl {
    param([Parameter(Mandatory = $true)][string]$Url)
    $raw = ConvertTo-RawPluginCatalogUrl $Url
    $queryIndex = $raw.IndexOf("?")
    if ($queryIndex -ge 0) {
        return $raw.Substring(0, $queryIndex) + ".sig" + $raw.Substring($queryIndex)
    }
    return "$raw.sig"
}

function Assert-InstallerPluginCatalogSignature {
    param(
        [Parameter(Mandatory = $true)][string]$SignatureToolJar,
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$SignaturePath
    )
    Invoke-PluginSignatureTool $SignatureToolJar @(
        "verify-manifest",
        "--manifest", $ManifestPath,
        "--signature", $SignaturePath,
        "--repository-id", "official",
        "--policy", "official"
    )
}

function Get-InstallerCatalogProp {
    param(
        $Object,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-InstallerCatalogTextValue {
    param(
        $Map,
        [AllowEmptyString()][string]$Fallback,
        [Parameter(Mandatory = $true)][string]$Language
    )
    if ($null -eq $Map) { return $Fallback }
    $base = $Language.Split("-")[0]
    foreach ($key in @($Language, $base, "zh", "en")) {
        $value = Get-InstallerCatalogProp $Map $key
        if (-not [string]::IsNullOrWhiteSpace([string]$value)) {
            return [string]$value
        }
    }
    foreach ($prop in $Map.PSObject.Properties) {
        if (-not [string]::IsNullOrWhiteSpace([string]$prop.Value)) {
            return [string]$prop.Value
        }
    }
    return $Fallback
}

function Escape-InstallerCatalogField {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    return $Value.Replace("%", "%25").Replace("|", "%7C").Replace("`r", " ").Replace("`n", " ")
}

function Parse-InstallerCatalogVersionPair {
    param([string]$Version)
    if ([string]::IsNullOrWhiteSpace($Version)) { return @(0, 0) }
    $parts = $Version.Split(".")
    $major = 0
    $minor = 0
    if ($parts.Length -ge 1) { [void][int]::TryParse($parts[0], [ref]$major) }
    if ($parts.Length -ge 2) { [void][int]::TryParse($parts[1], [ref]$minor) }
    return @($major, $minor)
}

function Test-InstallerCatalogCompatible {
    param(
        [string]$Required,
        [Parameter(Mandatory = $true)][string]$SdkVersion
    )
    if ([string]::IsNullOrWhiteSpace($Required)) { return $true }
    $core = Parse-InstallerCatalogVersionPair $SdkVersion
    $requiredPair = Parse-InstallerCatalogVersionPair $Required
    return ($core[0] -eq $requiredPair[0]) -and ($core[1] -ge $requiredPair[1])
}

function Get-InstallerCatalogRequiredSdk {
    param($Package)
    $required = [string](Get-InstallerCatalogProp $Package "requiredSdk")
    if ([string]::IsNullOrWhiteSpace($required)) {
        $required = [string](Get-InstallerCatalogProp $Package "requiredCoreApi")
    }
    return $required
}

function Select-InstallerCatalogPackage {
    param($Entry)
    $packages = @(Get-InstallerCatalogProp $Entry "packages")
    if ($packages.Count -eq 0) { return $null }
    $market = Get-InstallerCatalogProp $Entry "market"
    $latest = Get-InstallerCatalogProp $market "latestVersion"
    if (-not [string]::IsNullOrWhiteSpace([string]$latest)) {
        foreach ($pkg in $packages) {
            if ((Get-InstallerCatalogProp $pkg "version") -eq $latest) {
                return $pkg
            }
        }
    }
    return $packages[0]
}

function Test-InstallerCatalogInstallablePackage {
    param(
        $Package,
        [Parameter(Mandatory = $true)][string]$SdkVersion
    )
    if ($null -eq $Package) { return $false }
    if ([string]::IsNullOrWhiteSpace([string](Get-InstallerCatalogProp $Package "version"))) { return $false }
    if ([string]::IsNullOrWhiteSpace([string](Get-InstallerCatalogProp $Package "packageUrl"))) { return $false }
    if ([string]::IsNullOrWhiteSpace([string](Get-InstallerCatalogProp $Package "sha256"))) { return $false }
    $size = [int64](Get-InstallerCatalogProp $Package "expectedSizeBytes")
    if ($size -le 0) { return $false }
    if ($null -eq (Get-InstallerCatalogProp $Package "signature")) { return $false }
    return Test-InstallerCatalogCompatible (Get-InstallerCatalogRequiredSdk $Package) $SdkVersion
}

function New-InstallerCatalogProjectionRows {
    param(
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$Language,
        [Parameter(Mandatory = $true)][string]$SdkVersion
    )
    $manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($entry in @(Get-InstallerCatalogProp $manifest "entries")) {
        $pluginId = [string](Get-InstallerCatalogProp $entry "pluginId")
        if ([string]::IsNullOrWhiteSpace($pluginId)) { continue }
        $market = Get-InstallerCatalogProp $entry "market"
        if ([bool](Get-InstallerCatalogProp $market "officialRequired")) { continue }
        if ([bool](Get-InstallerCatalogProp $market "defaultInstalled")) { continue }
        $pkg = Select-InstallerCatalogPackage $entry
        if (-not (Test-InstallerCatalogInstallablePackage $pkg $SdkVersion)) { continue }
        $rows.Add([pscustomobject]@{
            PluginId = $pluginId
            Version = [string](Get-InstallerCatalogProp $pkg "version")
            DisplayName = Get-InstallerCatalogTextValue `
                (Get-InstallerCatalogProp $market "displayName") $pluginId $Language
            Summary = Get-InstallerCatalogTextValue `
                (Get-InstallerCatalogProp $market "summary") "" $Language
            Size = [string](Get-InstallerCatalogProp $pkg "expectedSizeBytes")
            Category = [string](Get-InstallerCatalogProp $market "category")
        })
    }
    return $rows.ToArray()
}

function Write-InstallerPluginCatalogProjection {
    param(
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$Language,
        [Parameter(Mandatory = $true)][string]$SdkVersion
    )
    $projectionUtf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("CATALOG|PACKAGED|installer-plugin-catalog.json")
    foreach ($row in @(New-InstallerCatalogProjectionRows -ManifestPath $ManifestPath `
                -Language $Language -SdkVersion $SdkVersion)) {
        $line = "ITEM|{0}|{1}|{2}|{3}|{4}|{5}" -f `
            (Escape-InstallerCatalogField $row.PluginId), (Escape-InstallerCatalogField $row.Version), `
            (Escape-InstallerCatalogField $row.DisplayName), (Escape-InstallerCatalogField $row.Summary), `
            (Escape-InstallerCatalogField $row.Size), (Escape-InstallerCatalogField $row.Category)
        $lines.Add($line)
    }
    [System.IO.File]::WriteAllText($OutputPath, (($lines -join "`n") + "`n"), $projectionUtf8NoBom)
}

function Escape-InstallerCatalogIssString {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) { return "''" }
    $escaped = $Value.Replace("'", "''").Replace("`r", " ").Replace("`n", " ")
    return "'$escaped'"
}

function Add-InstallerCatalogIncludeRows {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[string]]$Lines,
        $Rows
    )
    foreach ($row in @($Rows)) {
        $Lines.Add(("    AddPluginCatalogItem({0}, {1}, {2}, {3});" -f `
                    (Escape-InstallerCatalogIssString $row.PluginId), `
                    (Escape-InstallerCatalogIssString $row.Version), `
                    (Escape-InstallerCatalogIssString $row.DisplayName), `
                    (Escape-InstallerCatalogIssString $row.Summary)))
    }
}

function Write-InstallerPluginCatalogInclude {
    param(
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$SdkVersion
    )
    $projectionUtf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $enRows = @(New-InstallerCatalogProjectionRows -ManifestPath $ManifestPath `
            -Language "en" -SdkVersion $SdkVersion)
    $zhRows = @(New-InstallerCatalogProjectionRows -ManifestPath $ManifestPath `
            -Language "zh-CN" -SdkVersion $SdkVersion)
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("// Generated by scripts/package-local-installer-catalog.ps1. Do not edit.")
    $lines.Add("procedure LoadCompiledInstallerPluginCatalogItems;")
    $lines.Add("begin")
    $lines.Add("  if ActiveLanguage = 'zhcn' then")
    $lines.Add("  begin")
    Add-InstallerCatalogIncludeRows -Lines $lines -Rows $zhRows
    $lines.Add("  end")
    $lines.Add("  else")
    $lines.Add("  begin")
    Add-InstallerCatalogIncludeRows -Lines $lines -Rows $enRows
    $lines.Add("  end;")
    $lines.Add("end;")
    [System.IO.File]::WriteAllText($OutputPath, (($lines -join "`n") + "`n"), $projectionUtf8NoBom)
}

function Stage-InstallerPluginCatalogSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$AppDir,
        [Parameter(Mandatory = $true)][string]$SignatureToolJar,
        [Parameter(Mandatory = $true)][string]$CatalogUrl,
        [Parameter(Mandatory = $true)][string]$SdkVersion,
        [Parameter(Mandatory = $true)][string]$BuildRoot,
        [Parameter(Mandatory = $true)][string]$CatalogDirName,
        [Parameter(Mandatory = $true)][string]$CatalogIncludePath
    )
    $catalogDir = Join-Path $AppDir $CatalogDirName
    New-Item -ItemType Directory -Force -Path $catalogDir | Out-Null
    $manifestTarget = Join-Path $catalogDir "manifest.json"
    $signatureTarget = Join-Path $catalogDir "manifest.json.sig"
    $projectionEnTarget = Join-Path $catalogDir "catalog.en.txt"
    $projectionZhTarget = Join-Path $catalogDir "catalog.zh-CN.txt"

    $downloadDir = Join-Path $BuildRoot "installer-catalog-download"
    Remove-Item -LiteralPath $downloadDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null
    $manifestTemp = Join-Path $downloadDir "manifest.json"
    $signatureTemp = Join-Path $downloadDir "manifest.json.sig"
    $manifestUrl = ConvertTo-RawPluginCatalogUrl $CatalogUrl
    $signatureUrl = Get-PluginCatalogSignatureUrl $CatalogUrl

    Write-Host ("    Fetching signed installer plugin catalog: {0}" -f $CatalogUrl)
    Invoke-WebRequest -Uri $manifestUrl -OutFile $manifestTemp -UseBasicParsing -TimeoutSec 30
    Invoke-WebRequest -Uri $signatureUrl -OutFile $signatureTemp -UseBasicParsing -TimeoutSec 30
    Assert-InstallerPluginCatalogSignature -SignatureToolJar $SignatureToolJar `
        -ManifestPath $manifestTemp -SignaturePath $signatureTemp

    $parsed = Get-Content -LiteralPath $manifestTemp -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -eq $parsed -or $null -eq $parsed.entries) {
        throw "Installer plugin catalog manifest does not contain an entries array."
    }

    Copy-Item $manifestTemp $manifestTarget -Force
    Copy-Item $signatureTemp $signatureTarget -Force
    Write-InstallerPluginCatalogProjection -ManifestPath $manifestTemp -OutputPath $projectionEnTarget `
        -Language "en" -SdkVersion $SdkVersion
    Write-InstallerPluginCatalogProjection -ManifestPath $manifestTemp -OutputPath $projectionZhTarget `
        -Language "zh-CN" -SdkVersion $SdkVersion
    Write-InstallerPluginCatalogInclude -ManifestPath $manifestTemp -OutputPath $CatalogIncludePath `
        -SdkVersion $SdkVersion
    Write-Host ("    OK: signed installer plugin catalog staged under {0}." -f $CatalogDirName) `
        -ForegroundColor Green
}
