# Stage the complete official plugin set from one immutable, signed catalog commit.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][string]$SignatureToolJar,
    [string]$InputManifest = (Join-Path $PSScriptRoot 'sdk-runtime-input.json')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$inputFile = Get-Item -LiteralPath $InputManifest
if ($inputFile.Length -gt 1MB) { throw 'SDK input metadata is too large.' }
$inputLock = [IO.File]::ReadAllText($inputFile.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json
if ($inputLock.schemaVersion -ne 2 -or
    $inputLock.catalogCommitSha -cnotmatch '^[0-9a-f]{40}$') {
    throw 'Invalid fixed SDK plugin catalog identity.'
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'SDK input output directory must be new.' }
$catalogUrl = "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/$($inputLock.catalogCommitSha)/manifest.json"
& (Join-Path $PSScriptRoot '../stage-official-plugin-inputs-from-catalog.ps1') `
    -ManifestUrl $catalogUrl -OutputDir (Join-Path $OutputDirectory 'plugins') `
    -SignatureToolJar $SignatureToolJar -IncludeOptional -RequireProguard
