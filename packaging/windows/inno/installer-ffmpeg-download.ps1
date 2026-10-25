[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ReleaseBaseUrl,
    [Parameter(Mandatory = $true)][string]$AssetName,
    [Parameter(Mandatory = $true)][string]$OutFile,
    [Parameter(Mandatory = $true)][string]$ProgressFile,
    [Parameter(Mandatory = $true)][string]$SignatureToolJar,
    [Parameter(Mandatory = $true)][string]$JavaPath,
    [Parameter(Mandatory = $true)][string]$IntegrityScript,
    [string]$ProxyUrl
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0
. $IntegrityScript
. (Join-Path $PSScriptRoot 'installer-progress.ps1')

function Invoke-Download([string]$Url, [string]$Target, [bool]$ReportProgress) {
    $request = [System.Net.HttpWebRequest][System.Net.WebRequest]::Create($Url)
    $request.Method = "GET"
    $request.UserAgent = "PixivDownload/setup-ffmpeg"
    $request.AllowAutoRedirect = $true
    $request.Timeout = 600000
    $request.ReadWriteTimeout = 600000
    if ([string]::IsNullOrWhiteSpace($ProxyUrl)) {
        $request.Proxy = $null
        if ($ReportProgress) { Write-State "PROXY|DIRECT" }
    } else {
        $proxy = New-Object System.Net.WebProxy($ProxyUrl, $true)
        $proxy.Credentials = [System.Net.CredentialCache]::DefaultNetworkCredentials
        $request.Proxy = $proxy
        if ($ReportProgress) { Write-State ("PROXY|" + $ProxyUrl) }
    }

    $response = $request.GetResponse()
    try {
        if (-not [string]::Equals($response.ResponseUri.Scheme, "https",
                [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "FFmpeg release download redirected outside HTTPS."
        }
        [int64]$downloaded = 0
        [int64]$total = $response.ContentLength
        $inputStream = $response.GetResponseStream()
        $outputStream = [System.IO.File]::Open($Target, [System.IO.FileMode]::Create,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            $buffer = New-Object byte[] 65536
            while (($read = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $outputStream.Write($buffer, 0, $read)
                $downloaded += $read
                if ($ReportProgress) {
                    $percent = if ($total -gt 0) {
                        [Math]::Min(100, [Math]::Max(0, [int][Math]::Floor(($downloaded / [double]$total) * 100)))
                    } else { 0 }
                    Write-State ("PROGRESS|{0}|{1}|{2}" -f $downloaded, $total, $percent)
                }
            }
        } finally {
            $outputStream.Dispose()
            $inputStream.Dispose()
        }
    } finally {
        $response.Close()
    }
}

$manifestPath = "$OutFile.manifest.json"
$signaturePath = "$manifestPath.sig"
$downloadPath = "$OutFile.download"
try {
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
    Invoke-Download ($ReleaseBaseUrl + "ffmpeg-release.json") $manifestPath $false
    Invoke-Download ($ReleaseBaseUrl + "ffmpeg-release.json.sig") $signaturePath $false
    $expectedAsset = Get-VerifiedFfmpegReleaseAsset `
        -ManifestPath $manifestPath `
        -SignaturePath $signaturePath `
        -AssetName $AssetName `
        -SignatureToolJar $SignatureToolJar `
        -JavaPath $JavaPath
    Invoke-Download ($ReleaseBaseUrl + $AssetName) $downloadPath $true
    Assert-FfmpegReleaseAsset -ArchivePath $downloadPath -ExpectedAsset $expectedAsset
    Move-Item -LiteralPath $downloadPath -Destination $OutFile -Force
    Write-State ("DONE|{0}|{0}|100" -f (Get-Item -LiteralPath $OutFile).Length)
    exit 0
} catch {
    Remove-Item -LiteralPath $downloadPath -Force -ErrorAction SilentlyContinue
    Write-State ("ERROR|" + (Escape-StateField ($_.Exception.GetType().FullName + ": " + $_.Exception.Message)))
    exit 1
} finally {
    Remove-Item -LiteralPath $manifestPath, $signaturePath -Force -ErrorAction SilentlyContinue
}
