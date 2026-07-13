<#
.SYNOPSIS
    Build the Windows installer with official plugin inputs from the signed plugin catalog.

.DESCRIPTION
    One-command wrapper for local installer verification:

      1. Build the app boot jar and plugin signature CLI.
      2. Download and verify official plugin inputs from the signed plugin repository catalog.
      3. Build only the Windows setup package through package-local.ps1.

    The script always keeps plugin staging enabled. SignatureToolJar is resolved after the signature
    module build and is passed to both catalog staging and installer packaging.
#>
[CmdletBinding()]
param(
    [string]$Version = "0.0.1-local",
    [string]$ManifestUrl = "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json",
    [string]$PluginInputsDir,
    [string]$SignatureToolJar,
    [string]$CoreApiVersion = "1.2.0",
    [switch]$RunTests
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $PluginInputsDir) {
    $PluginInputsDir = Join-Path $ProjectRoot "build/plugin-inputs"
}
$StagePluginsScript = Join-Path $PSScriptRoot "stage-official-plugin-inputs-from-catalog.ps1"
$PackageLocalScript = Join-Path $PSScriptRoot "package-local.ps1"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Get-MavenCommand {
    $wrapper = Join-Path $ProjectRoot "mvnw.cmd"
    if (Test-Path -LiteralPath $wrapper) {
        return $wrapper
    }

    foreach ($name in @("mvn.cmd", "mvn")) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }

    throw "Missing Maven command. Install Maven or use the Maven wrapper."
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList
    )

    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($ArgumentList -join ' ')"
    }
}

function Get-BuiltBootJar {
    $targetDir = Join-Path $ProjectRoot "pixivdownload-app/target"
    $jar = Get-ChildItem (Join-Path $targetDir "PixivDownload-*-boot.jar") -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) {
        throw "Could not find executable boot jar under $targetDir."
    }
    return $jar.FullName
}

if ([string]::IsNullOrWhiteSpace($Version)) {
    throw "Version must not be empty."
}
if (-not (Test-Path -LiteralPath $StagePluginsScript -PathType Leaf)) {
    throw "Missing script: $StagePluginsScript"
}
if (-not (Test-Path -LiteralPath $PackageLocalScript -PathType Leaf)) {
    throw "Missing script: $PackageLocalScript"
}

Push-Location $ProjectRoot
try {
    $maven = Get-MavenCommand

    Write-Step "Building application jar and signature tool"
    $mavenArgs = @(
        "-pl", "pixivdownload-plugin-signature,pixivdownload-app",
        "-am",
        "package",
        "-Dexec.skip=true",
        "-Dapp.release.version=$Version"
    )
    if (-not $RunTests) {
        $mavenArgs += "-DskipTests"
    }
    Invoke-External $maven $mavenArgs

    Write-Step "Resolving signature tool and boot jar"
    $resolvedSignatureToolJar = Resolve-SignatureToolJar $ProjectRoot $SignatureToolJar
    if ([string]::IsNullOrWhiteSpace($resolvedSignatureToolJar)) {
        throw "SignatureToolJar must not be empty."
    }
    $bootJar = Get-BuiltBootJar
    Write-Host "    Signature tool: $resolvedSignatureToolJar"
    Write-Host "    Boot jar      : $bootJar"

    Write-Step "Staging official plugin inputs from signed catalog"
    & $StagePluginsScript `
        -ManifestUrl $ManifestUrl `
        -OutputDir $PluginInputsDir `
        -SignatureToolJar $resolvedSignatureToolJar `
        -CoreApiVersion $CoreApiVersion `
        -IncludeOptional

    Write-Step "Building Windows installer"
    & $PackageLocalScript `
        -Version $Version `
        -PrebuiltJar $bootJar `
        -PrebuiltPluginsDir $PluginInputsDir `
        -SignatureToolJar $resolvedSignatureToolJar `
        -SkipPortable `
        -SkipOfflinePortable

    $setupPath = Join-Path $ProjectRoot "build/out/PixivDownload-$Version-win-x64-setup.exe"
    if (-not (Test-Path -LiteralPath $setupPath -PathType Leaf)) {
        throw "Installer was not produced at expected path: $setupPath"
    }

    Write-Step "Done"
    Write-Host "Windows setup: $setupPath"
} finally {
    Pop-Location
}
