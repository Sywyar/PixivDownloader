# Reject moving or malformed SDK catalog identities before any download or output write.
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$TestDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$TestDirectory = [IO.Path]::GetFullPath($TestDirectory)
if (Test-Path -LiteralPath $TestDirectory) { throw 'Test directory must be new.' }
[IO.Directory]::CreateDirectory($TestDirectory) | Out-Null
$script = Join-Path $PSScriptRoot '../sdk-runtime-inputs.ps1'
foreach ($identity in @(
    @{ name = 'moving-branch'; sha = 'master'; schema = 2 },
    @{ name = 'foreign-url'; sha = 'https://example.com/manifest.json'; schema = 2 },
    @{ name = 'wrong-schema'; sha = 'a' * 40; schema = 1 }
)) {
    $manifest = Join-Path $TestDirectory "$($identity.name).json"
    [IO.File]::WriteAllText($manifest, (@{
        schemaVersion = $identity.schema
        catalogCommitSha = $identity.sha
    } | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    $output = Join-Path $TestDirectory $identity.name
    $rejected = $false
    try {
        & $script -OutputDirectory $output -SignatureToolJar 'unused.jar' -InputManifest $manifest
    } catch {
        $rejected = $_.Exception.Message -eq 'Invalid fixed SDK plugin catalog identity.'
    }
    if (-not $rejected -or (Test-Path -LiteralPath $output)) {
        throw "Invalid SDK plugin catalog identity accepted: $($identity.name)"
    }
}
Write-Host 'PASS: SDK input rejects moving and malformed catalog identities.'
