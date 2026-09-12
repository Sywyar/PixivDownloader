# Stage the exact signed plugin input selected for this SDK source. Never select latest/nightly.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$Archive,
    [string]$InputManifest = (Join-Path $PSScriptRoot 'sdk-runtime-input.json')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
$inputFile = Get-Item -LiteralPath $InputManifest
if ($inputFile.Length -gt 1MB) { throw 'SDK input metadata is too large.' }
$inputLock = [IO.File]::ReadAllText($inputFile.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json
if ($inputLock.schemaVersion -ne 1 -or
    $inputLock.assetUrl -cnotmatch '^https://api\.github\.com/repos/Sywyar/PixivDownloader(?:-Plugin-SDK)?/releases/assets/[1-9][0-9]*$' -or
    $inputLock.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
    $inputLock.size -le 0 -or $inputLock.size -gt 512MB) { throw 'Invalid fixed SDK plugin input.' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'SDK input output directory must be new.' }
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
if (-not $Archive) {
    $Archive = Join-Path $OutputDirectory 'input.zip'
    $curl = if ($env:OS -eq 'Windows_NT') { 'curl.exe' } else { 'curl' }
    & $curl --fail --show-error --silent --location --proto '=https' --proto-redir '=https' `
        --max-redirs 5 --connect-timeout 15 --max-time 300 --max-filesize $inputLock.size `
        --header 'Accept: application/octet-stream' --output $Archive $inputLock.assetUrl
    if ($LASTEXITCODE -ne 0) { throw 'Cannot download the fixed SDK plugin input.' }
}
$Archive = [IO.Path]::GetFullPath($Archive)
if ((Get-Item -LiteralPath $Archive).Length -ne $inputLock.size -or
    (Get-FileHash -LiteralPath $Archive -Algorithm SHA256).Hash -ine $inputLock.sha256) {
    throw 'SDK plugin input bytes differ from the fixed identity.'
}
$zip = [IO.Compression.ZipFile]::OpenRead($Archive)
try {
    if ($zip.Entries.Count -gt 48000) { throw 'SDK input has too many ZIP entries.' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $buffer = New-Object byte[] 131072
    $total = [long]0
    foreach ($entry in $zip.Entries) {
        # Only immediate plugin artifacts and their original provenance are needed by the shared packer.
        $name = $entry.FullName
        if (-not $name.StartsWith('plugins/', [StringComparison]::Ordinal) -or $name.EndsWith('/')) { continue }
        if ($name.Length -gt 1024 -or
            $name -cnotmatch '^plugins/(provenance/)?[A-Za-z0-9][A-Za-z0-9._-]*$' -or
            $name.EndsWith('.') -or
            [IO.Path]::GetFileName($name) -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)' -or
            -not $seen.Add($name) -or $entry.Length -gt 256MB) { throw "Invalid SDK input entry: $name" }
        $destination = Join-Path $OutputDirectory $name
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
        $source = $entry.Open()
        try {
            $target = [IO.File]::Open($destination, [IO.FileMode]::CreateNew)
            try {
                $entryBytes = [long]0
                while (($count = $source.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $entryBytes += $count
                    $total += $count
                    if ($entryBytes -gt 256MB -or $total -gt 1536MB) { throw 'SDK input exceeds extraction budget.' }
                    $target.Write($buffer, 0, $count)
                }
                if ($entryBytes -ne $entry.Length) { throw 'SDK input entry is truncated.' }
            } finally { $target.Dispose() }
        } finally { $source.Dispose() }
    }
    if ($seen.Count -eq 0) { throw 'SDK input contains no plugin files.' }
} finally { $zip.Dispose() }
Write-Host "Staged fixed SDK plugin input ($($seen.Count) files, $total bytes)."
