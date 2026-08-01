[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"

$secretName = "PIXIVDOWNLOAD_PLUGIN_CREDENTIAL_MASTER_KEY_BASE64"
$openSourceFallback = "ieiuDoVetH5W1984VOMLG2rBJT53zzBQHK/HrG+0Moc="
$masterKeyBase64 = [Environment]::GetEnvironmentVariable($secretName)
if ([string]::IsNullOrWhiteSpace($masterKeyBase64)) {
    throw "$secretName must be set."
}

try {
    $masterKey = [Convert]::FromBase64String($masterKeyBase64)
} catch {
    throw "$secretName must be canonical standard Base64."
}

try {
    if ($masterKey.Length -ne 32) {
        throw "$secretName must decode to exactly 32 bytes."
    }

    $canonical = [Convert]::ToBase64String($masterKey)
    if (-not [string]::Equals($canonical, $masterKeyBase64, [StringComparison]::Ordinal)) {
        throw "$secretName must be canonical standard Base64."
    }
    if ([string]::Equals($canonical, $openSourceFallback, [StringComparison]::Ordinal)) {
        throw "$secretName must differ from the committed open-source fallback."
    }

    $fullOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
    $outputDirectory = [System.IO.Path]::GetDirectoryName($fullOutputPath)
    if ([string]::IsNullOrWhiteSpace($outputDirectory)) {
        throw "OutputPath must include a parent directory."
    }
    [System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null

    $content = @(
        "plugin.credential.key.profile=production"
        "plugin.credential.key.current-base64=$canonical"
        "plugin.credential.key.open-source-fallback-base64=$openSourceFallback"
    ) -join "`n"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($fullOutputPath, $content + "`n", $utf8NoBom)
} finally {
    if ($null -ne $masterKey) {
        [Array]::Clear($masterKey, 0, $masterKey.Length)
    }
}
