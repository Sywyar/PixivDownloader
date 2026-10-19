[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$CompilerPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path $ProjectRoot "build"
$PatchExecutionLevelScript = Join-Path $PSScriptRoot "set-windows-exe-requested-execution-level.ps1"

if (-not (Test-Path $CompilerPath)) {
    throw "Inno Setup compiler not found: $CompilerPath"
}
if (-not (Test-Path $PatchExecutionLevelScript)) {
    throw "Execution level patch script not found: $PatchExecutionLevelScript"
}

$compilerItem = Get-Item $CompilerPath
$sourceDirectory = Split-Path -Parent $compilerItem.FullName
$targetDirectory = Assert-SafeDistributionOutputDirectory `
    -Path $OutputDirectory -ProjectRoot $ProjectRoot -ProtectedPaths @($sourceDirectory)

if (-not (Test-PathSameOrDescendant $targetDirectory $BuildRoot)) {
    throw "Refusing to prepare Inno Setup outside the build directory: $targetDirectory"
}
if (Test-PathSameOrDescendant $targetDirectory $sourceDirectory) {
    throw "Refusing to copy Inno Setup into its source directory: $targetDirectory"
}

if (Test-Path -LiteralPath $targetDirectory) {
    Remove-Item -Recurse -Force -LiteralPath $targetDirectory
}

New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
Copy-Item -Path (Join-Path $sourceDirectory "*") -Destination $targetDirectory -Recurse -Force

foreach ($loaderName in @("SetupLdr.e32", "SetupLdr.e64")) {
    $loaderPath = Join-Path $targetDirectory $loaderName
    if (Test-Path $loaderPath) {
        & $PatchExecutionLevelScript -Path $loaderPath -Level "requireAdministrator"
    }
}

$preparedCompiler = Join-Path $targetDirectory "ISCC.exe"
if (-not (Test-Path $preparedCompiler)) {
    throw "Prepared Inno Setup compiler was not created: $preparedCompiler"
}

Write-Output $preparedCompiler
