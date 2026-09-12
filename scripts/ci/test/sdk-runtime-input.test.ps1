# Verify the real SDK input entry point with exact bytes and rejected input.
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$TestDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$TestDirectory = [IO.Path]::GetFullPath($TestDirectory)
if (Test-Path -LiteralPath $TestDirectory) { throw 'Test directory must be new.' }
[IO.Directory]::CreateDirectory($TestDirectory) | Out-Null
$script = Join-Path $PSScriptRoot '../sdk-runtime-inputs.ps1'
function Test-Input([string]$Name, [string[]]$Entries, [string]$Mutation, [bool]$Accept, [string]$AssetUrl) {
    $inputLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../sdk-runtime-input.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($AssetUrl) { $inputLock.assetUrl = $AssetUrl }
    $archive = Join-Path $TestDirectory "$Name.zip"
    $zip = [IO.Compression.ZipFile]::Open($archive, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $Entries) {
            $stream = $zip.CreateEntry($entry).Open()
            try {
                $bytes = [Text.Encoding]::UTF8.GetBytes($entry)
                $stream.Write($bytes, 0, $bytes.Length)
            } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
    $inputLock.size = (Get-Item -LiteralPath $archive).Length
    $inputLock.sha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($Mutation -eq 'digest') { $inputLock.sha256 = '0' * 64 }
    if ($Mutation -eq 'truncated') {
        $stream = [IO.File]::OpenWrite($archive)
        try { $stream.SetLength($stream.Length - 1) } finally { $stream.Dispose() }
    }
    $manifest = Join-Path $TestDirectory "$Name.json"
    [IO.File]::WriteAllText($manifest, ($inputLock | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    $output = Join-Path $TestDirectory $Name
    $failure = $null
    try { & $script -OutputDirectory $output -Archive $archive -InputManifest $manifest }
    catch { $failure = $_ }
    if ($Accept) {
        if ($failure) { throw $failure }
        $actual = [IO.File]::ReadAllText((Join-Path $output 'plugins/example.jar'), [Text.Encoding]::UTF8)
        if ($actual -cne 'plugins/example.jar') { throw 'Plugin input bytes changed.' }
        if (Test-Path -LiteralPath (Join-Path $output 'ignored.txt')) { throw 'Unrelated host files were extracted.' }
    } elseif (-not $failure) { throw "Invalid SDK input accepted: $Name" }
}
Test-Input 'valid' @('plugins/example.jar', 'plugins/provenance/example.jar.pixiv-plugin-provenance', 'ignored.txt') '' $true
Test-Input 'main-release' @('plugins/example.jar') '' $true 'https://api.github.com/repos/Sywyar/PixivDownloader/releases/assets/1'
Test-Input 'sdk-release' @('plugins/example.jar') '' $true 'https://api.github.com/repos/Sywyar/PixivDownloader-Plugin-SDK/releases/assets/1'
Test-Input 'foreign-repository' @('plugins/example.jar') '' $false 'https://api.github.com/repos/other/PixivDownloader-Plugin-SDK/releases/assets/1'
Test-Input 'moving-tag' @('plugins/example.jar') '' $false 'https://api.github.com/repos/Sywyar/PixivDownloader-Plugin-SDK/releases/tags/latest'
Test-Input 'http' @('plugins/example.jar') '' $false 'http://api.github.com/repos/Sywyar/PixivDownloader-Plugin-SDK/releases/assets/1'
Test-Input 'digest' @('plugins/example.jar') 'digest' $false
Test-Input 'truncated' @('plugins/example.jar') 'truncated' $false
Test-Input 'traversal' @('plugins/../escape.jar') '' $false
Test-Input 'collision' @('plugins/example.jar', 'plugins/EXAMPLE.jar') '' $false
Test-Input 'reserved' @('plugins/CON.jar') '' $false
Test-Input 'empty' @('ignored.txt') '' $false
if (Test-Path -LiteralPath (Join-Path $TestDirectory 'escape.jar')) { throw 'Input escaped its output directory.' }
Write-Host 'PASS: SDK input preserves exact selected bytes and rejects invalid archives.'
