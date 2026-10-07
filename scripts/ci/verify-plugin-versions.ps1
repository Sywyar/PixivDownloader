[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BaseRef,
    [string]$ProjectRoot,
    [string]$ReleasesFile
)
$ErrorActionPreference = 'Stop'
if (-not $ProjectRoot) { $ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
. (Join-Path $ProjectRoot 'scripts/plugin-distribution-common.ps1')
$output = Join-Path $ProjectRoot 'target/plugin-version-inputs.json'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
[IO.File]::WriteAllText($output, (ConvertTo-Json -Depth 10 -InputObject @(Get-OfficialDistributionPlugins -IncludeOptional)), (New-Object Text.UTF8Encoding($false)))
$arguments = @((Join-Path $PSScriptRoot 'plugin-version-policy.mjs'), $ProjectRoot, $BaseRef, $output)
if ($ReleasesFile) { $arguments += $ReleasesFile }
& node @arguments
if ($LASTEXITCODE -ne 0) { throw 'Plugin version policy failed.' }
