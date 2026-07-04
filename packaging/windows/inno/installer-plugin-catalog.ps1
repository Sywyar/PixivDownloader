[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ManifestUrl,
    [Parameter(Mandatory = $true)][string]$PackagedManifest,
    [Parameter(Mandatory = $true)][string]$PackagedSignature,
    [string]$SignatureToolJar,
    [Parameter(Mandatory = $true)][string]$OutputFile,
    [Parameter(Mandatory = $true)][string]$CatalogFile,
    [int]$TimeoutMs = 3000,
    [string]$ProxyUrl,
    [string]$Language = "en",
    [string]$CoreApiVersion = "1.0.0"
)

$ErrorActionPreference = "Stop"

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    $dir = [System.IO.Path]::GetDirectoryName($Path)
    if (-not [string]::IsNullOrWhiteSpace($dir)) {
        [System.IO.Directory]::CreateDirectory($dir) | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
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

function Invoke-Fetch([string]$Url, [string]$OutFile) {
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
    $request = [System.Net.HttpWebRequest][System.Net.WebRequest]::Create($Url)
    $request.Method = "GET"
    $request.UserAgent = "PixivDownload/setup-plugin-catalog"
    $request.AllowAutoRedirect = $true
    $request.Timeout = $TimeoutMs
    $request.ReadWriteTimeout = $TimeoutMs
    if ([string]::IsNullOrWhiteSpace($ProxyUrl)) {
        $request.Proxy = $null
    } else {
        $proxy = New-Object System.Net.WebProxy($ProxyUrl, $true)
        $proxy.Credentials = [System.Net.CredentialCache]::DefaultNetworkCredentials
        $request.Proxy = $proxy
    }
    $response = $request.GetResponse()
    try {
        $stream = $response.GetResponseStream()
        $file = [System.IO.File]::Open($OutFile, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            $buffer = New-Object byte[] 65536
            while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $file.Write($buffer, 0, $read)
            }
        } finally {
            $file.Dispose()
            $stream.Dispose()
        }
    } finally {
        $response.Close()
    }
}

function Get-JavaCommand {
    $cmd = Get-Command "java.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cmd = Get-Command "java" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return ""
}

function Test-ManifestSignature([string]$Manifest, [string]$Signature, [bool]$AllowPackagedWithoutVerifier) {
    if (-not (Test-Path -LiteralPath $Manifest -PathType Leaf)) { return $false }
    if (-not (Test-Path -LiteralPath $Signature -PathType Leaf)) { return $false }
    if ([string]::IsNullOrWhiteSpace($SignatureToolJar) -or -not (Test-Path -LiteralPath $SignatureToolJar -PathType Leaf)) {
        return $AllowPackagedWithoutVerifier
    }
    $java = Get-JavaCommand
    if ([string]::IsNullOrWhiteSpace($java)) {
        return $AllowPackagedWithoutVerifier
    }
    & $java "-cp" $SignatureToolJar "top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool" `
        "verify-manifest" `
        "--manifest" $Manifest `
        "--signature" $Signature `
        "--repository-id" "official" `
        "--policy" "official" | Out-Null
    return $LASTEXITCODE -eq 0
}

function Get-Prop($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-TextValue($Map, [string]$Fallback) {
    if ($null -eq $Map) { return $Fallback }
    $lang = if ([string]::IsNullOrWhiteSpace($Language)) { "en" } else { $Language }
    $base = $lang.Split("-")[0]
    foreach ($key in @($lang, $base, "zh", "en")) {
        $value = Get-Prop $Map $key
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

function Escape-Field([string]$Value) {
    if ($null -eq $Value) { return "" }
    return $Value.Replace("%", "%25").Replace("|", "%7C").Replace("`r", " ").Replace("`n", " ")
}

function Parse-VersionPair([string]$Version) {
    if ([string]::IsNullOrWhiteSpace($Version)) { return @(0, 0) }
    $parts = $Version.Split(".")
    $major = 0
    $minor = 0
    if ($parts.Length -ge 1) { [void][int]::TryParse($parts[0], [ref]$major) }
    if ($parts.Length -ge 2) { [void][int]::TryParse($parts[1], [ref]$minor) }
    return @($major, $minor)
}

function Test-Compatible([string]$Required) {
    if ([string]::IsNullOrWhiteSpace($Required)) { return $true }
    $core = Parse-VersionPair $CoreApiVersion
    $requiredPair = Parse-VersionPair $Required
    return ($core[0] -eq $requiredPair[0]) -and ($core[1] -ge $requiredPair[1])
}

function Select-Package($Entry) {
    $packages = @(Get-Prop $Entry "packages")
    if ($packages.Count -eq 0) { return $null }
    $market = Get-Prop $Entry "market"
    $latest = Get-Prop $market "latestVersion"
    if (-not [string]::IsNullOrWhiteSpace([string]$latest)) {
        foreach ($pkg in $packages) {
            if ((Get-Prop $pkg "version") -eq $latest) {
                return $pkg
            }
        }
    }
    return $packages[0]
}

function Is-InstallablePackage($Package) {
    if ($null -eq $Package) { return $false }
    if ([string]::IsNullOrWhiteSpace([string](Get-Prop $Package "version"))) { return $false }
    if ([string]::IsNullOrWhiteSpace([string](Get-Prop $Package "packageUrl"))) { return $false }
    if ([string]::IsNullOrWhiteSpace([string](Get-Prop $Package "sha256"))) { return $false }
    $size = [int64](Get-Prop $Package "expectedSizeBytes")
    if ($size -le 0) { return $false }
    if ($null -eq (Get-Prop $Package "signature")) { return $false }
    return Test-Compatible ([string](Get-Prop $Package "requiredCoreApi"))
}

function Write-ProjectedCatalog([string]$Source, [string]$ManifestPath) {
    $json = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8
    $manifest = $json | ConvertFrom-Json
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("CATALOG|$Source|$ManifestPath")
    foreach ($entry in @(Get-Prop $manifest "entries")) {
        $pluginId = [string](Get-Prop $entry "pluginId")
        if ([string]::IsNullOrWhiteSpace($pluginId)) { continue }
        $market = Get-Prop $entry "market"
        if ([bool](Get-Prop $market "officialRequired")) { continue }
        $pkg = Select-Package $entry
        if (-not (Is-InstallablePackage $pkg)) { continue }
        $name = Get-TextValue (Get-Prop $market "displayName") $pluginId
        $summary = Get-TextValue (Get-Prop $market "summary") ""
        $version = [string](Get-Prop $pkg "version")
        $size = [string](Get-Prop $pkg "expectedSizeBytes")
        $category = [string](Get-Prop $market "category")
        $line = "ITEM|{0}|{1}|{2}|{3}|{4}|{5}" -f `
            (Escape-Field $pluginId), (Escape-Field $version), (Escape-Field $name), `
            (Escape-Field $summary), (Escape-Field $size), (Escape-Field $category)
        $lines.Add($line)
    }
    Write-Utf8NoBom $OutputFile (($lines -join "`n") + "`n")
}

try {
    $workDir = [System.IO.Path]::GetDirectoryName($CatalogFile)
    if (-not [string]::IsNullOrWhiteSpace($workDir)) {
        [System.IO.Directory]::CreateDirectory($workDir) | Out-Null
    }
    $onlineManifest = $CatalogFile + ".online"
    $onlineSignature = $CatalogFile + ".online.sig"
    try {
        Invoke-Fetch (Convert-ToRawCatalogUrl $ManifestUrl) $onlineManifest
        Invoke-Fetch (Get-DetachedSignatureUrl $ManifestUrl) $onlineSignature
        if (Test-ManifestSignature $onlineManifest $onlineSignature $false) {
            Copy-Item -LiteralPath $onlineManifest -Destination $CatalogFile -Force
            Write-ProjectedCatalog "ONLINE" $CatalogFile
            exit 0
        }
    } catch {
        # Fall back to the packaged snapshot below.
    }

    if (Test-ManifestSignature $PackagedManifest $PackagedSignature $true) {
        Copy-Item -LiteralPath $PackagedManifest -Destination $CatalogFile -Force
        Write-ProjectedCatalog "PACKAGED" $CatalogFile
        exit 0
    }

    Write-Utf8NoBom $OutputFile "CATALOG|UNAVAILABLE|`n"
    exit 0
} catch {
    Write-Utf8NoBom $OutputFile "CATALOG|UNAVAILABLE|`n"
    exit 0
}
