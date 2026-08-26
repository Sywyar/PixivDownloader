<#
.SYNOPSIS
    Verifies the signed FFmpeg release manifest and the selected archive before extraction.

.DESCRIPTION
    Dot-source this file from packaging and installer scripts. The manifest signature uses the
    built-in FFmpeg trust root in pixivdownload-plugin-signature and repository id ffmpeg-stable.
    This file is ASCII-only for Windows PowerShell 5.1.
#>

function Get-VerifiedFfmpegReleaseAsset {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][string]$SignaturePath,
        [Parameter(Mandatory = $true)][string]$AssetName,
        [Parameter(Mandatory = $true)][string]$SignatureToolJar,
        [string]$JavaPath = "java"
    )

    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        throw "FFmpeg release manifest is missing: $ManifestPath"
    }
    if (-not (Test-Path -LiteralPath $SignaturePath -PathType Leaf)) {
        throw "FFmpeg release manifest signature is missing: $SignaturePath"
    }
    if ((Get-Item -LiteralPath $ManifestPath).Length -gt 1MB -or
        (Get-Item -LiteralPath $SignaturePath).Length -gt 1MB) {
        throw "FFmpeg release metadata exceeds the 1 MiB limit."
    }

    $verifyOutput = & $JavaPath "-cp" $SignatureToolJar `
        "top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool" `
        "verify-manifest" `
        "--manifest" $ManifestPath `
        "--signature" $SignaturePath `
        "--repository-id" "ffmpeg-stable" `
        "--official-purpose" "ffmpeg" `
        "--policy" "official" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "FFmpeg release manifest signature verification failed: $($verifyOutput -join ' ')"
    }

    try {
        $manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        throw "FFmpeg release manifest is not valid JSON: $($_.Exception.Message)"
    }
    if ([int]$manifest.schemaVersion -ne 1) {
        throw "Unsupported FFmpeg release manifest schema: $($manifest.schemaVersion)"
    }
    if (-not $manifest.assets) {
        throw "FFmpeg release manifest has no assets object."
    }
    $assetProperties = @($manifest.assets.PSObject.Properties | Where-Object { $_.Name -ceq $AssetName })
    if ($assetProperties.Count -ne 1) {
        throw "FFmpeg release manifest does not contain the exact asset name: $AssetName"
    }
    $asset = $assetProperties[0].Value
    [long]$expectedSizeBytes = 0
    if (-not [long]::TryParse([string]$asset.expectedSizeBytes, [ref]$expectedSizeBytes) -or
        $expectedSizeBytes -le 0) {
        throw "FFmpeg release asset has an invalid expectedSizeBytes value: $AssetName"
    }
    $sha256 = ([string]$asset.sha256).Trim().ToLowerInvariant()
    if ($sha256 -notmatch '^[0-9a-f]{64}$') {
        throw "FFmpeg release asset has an invalid SHA-256 value: $AssetName"
    }
    return [pscustomobject]@{
        AssetName = $AssetName
        ExpectedSizeBytes = $expectedSizeBytes
        Sha256 = $sha256
    }
}

function Assert-FfmpegReleaseAsset {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$ArchivePath,
        [Parameter(Mandatory = $true)]$ExpectedAsset
    )

    if (-not (Test-Path -LiteralPath $ArchivePath -PathType Leaf)) {
        throw "FFmpeg release archive is missing: $ArchivePath"
    }
    $archive = Get-Item -LiteralPath $ArchivePath
    if ($archive.Length -ne [long]$ExpectedAsset.ExpectedSizeBytes) {
        throw "FFmpeg release archive length mismatch for $($ExpectedAsset.AssetName)."
    }
    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $ArchivePath).Hash.ToLowerInvariant()
    if (-not [string]::Equals($actualSha256, [string]$ExpectedAsset.Sha256,
            [System.StringComparison]::Ordinal)) {
        throw "FFmpeg release archive SHA-256 mismatch for $($ExpectedAsset.AssetName)."
    }
}
