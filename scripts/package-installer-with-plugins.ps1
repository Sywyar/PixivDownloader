<#
.SYNOPSIS
    Build the Windows installer with signed catalog inputs or current-source local test plugins.

.DESCRIPTION
    One-command wrapper for local installer verification:

      1. Build the app boot jar and plugin signature CLI.
      2. Use the signed catalog (default), or build current-source plugins for an explicit unsigned local test.
      3. Build only the Windows setup package through package-local.ps1.

    Local mode requires -AllowUnsignedLocalPlugins. It stages every current-source user plugin except Douyin
    with explicit LOCAL_UPLOAD provenance and never changes the catalog or release verification policy.
#>
[CmdletBinding()]
param(
    [string]$Version = "0.0.1-local",
    [ValidateSet("Catalog", "Local")]
    [string]$PluginSource = "Catalog",
    [string]$ManifestUrl = "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json",
    [string]$PluginInputsDir,
    [string]$SignatureToolJar,
    [string]$CoreApiVersion = "1.3.0",
    [switch]$AllowUnsignedLocalPlugins,
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
if ($AllowUnsignedLocalPlugins -and $PluginSource -ne "Local") {
    throw "AllowUnsignedLocalPlugins can only be used with -PluginSource Local."
}
if ($PluginSource -eq "Local" -and -not $AllowUnsignedLocalPlugins) {
    throw "PluginSource Local requires -AllowUnsignedLocalPlugins and is only for local testing."
}
if ($AllowUnsignedLocalPlugins -and -not [string]::IsNullOrWhiteSpace($SignatureToolJar)) {
    throw "AllowUnsignedLocalPlugins cannot be combined with SignatureToolJar."
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

    $mavenProjects = @("pixivdownload-plugin-signature", "pixivdownload-app")
    if ($PluginSource -eq "Local") {
        $localPluginModules = @(Get-OfficialDistributionPlugins -IncludeOptional |
            Where-Object { $_.Id -ne "douyin" } |
            ForEach-Object { $_.Module })
        $mavenProjects += $localPluginModules
    }
    $mavenProjects = @($mavenProjects | Select-Object -Unique)

    Write-Step "Building application jar and selected plugin inputs"
    $mavenArgs = @(
        "-pl", ($mavenProjects -join ","),
        "-am",
        "package",
        "-Dexec.skip=true",
        "-Dapp.release.version=$Version"
    )
    if (-not $RunTests) {
        $mavenArgs += "-DskipTests"
    }
    Invoke-External $maven $mavenArgs

    $resolvedSignatureToolJar = ""
    if ($PluginSource -eq "Catalog") {
        Write-Step "Resolving signature tool and boot jar"
        $resolvedSignatureToolJar = Resolve-SignatureToolJar $ProjectRoot $SignatureToolJar
        if ([string]::IsNullOrWhiteSpace($resolvedSignatureToolJar)) {
            throw "SignatureToolJar must not be empty."
        }
    } else {
        Write-Step "Resolving boot jar for local unsigned-plugin test installer"
    }
    $bootJar = Get-BuiltBootJar
    if ($resolvedSignatureToolJar) {
        Write-Host "    Signature tool: $resolvedSignatureToolJar"
    }
    Write-Host "    Boot jar      : $bootJar"

    $packageArgs = @{
        Version = $Version
        PrebuiltJar = $bootJar
        SkipPortable = $true
        SkipOfflinePortable = $true
    }
    if ($PluginSource -eq "Catalog") {
        Write-Step "Staging official plugin inputs from signed catalog"
        & $StagePluginsScript `
            -ManifestUrl $ManifestUrl `
            -OutputDir $PluginInputsDir `
            -SignatureToolJar $resolvedSignatureToolJar `
            -CoreApiVersion $CoreApiVersion `
            -IncludeOptional
        $packageArgs.PrebuiltPluginsDir = $PluginInputsDir
        $packageArgs.SignatureToolJar = $resolvedSignatureToolJar
    } else {
        Write-Step "Using current-source plugins without signatures (LOCAL TEST ONLY)"
        Write-Host "Do not distribute the resulting installer or app image." -ForegroundColor Red
        $packageArgs.AllowUnsignedLocalPlugins = $true
    }

    Write-Step "Building Windows installer"
    & $PackageLocalScript @packageArgs

    $setupPath = if ($AllowUnsignedLocalPlugins) {
        Join-Path $ProjectRoot "build/out-local-unsigned/PixivDownload-$Version-LOCAL-UNSIGNED-win-x64-setup.exe"
    } else {
        Join-Path $ProjectRoot "build/out/PixivDownload-$Version-win-x64-setup.exe"
    }
    if (-not (Test-Path -LiteralPath $setupPath -PathType Leaf)) {
        throw "Installer was not produced at expected path: $setupPath"
    }

    Write-Step "Done"
    Write-Host "Windows setup: $setupPath"
} finally {
    Pop-Location
}
