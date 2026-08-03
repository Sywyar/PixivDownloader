<#
.SYNOPSIS
    Build the Windows installer with official plugin inputs.

.DESCRIPTION
    One-command wrapper for local installer verification:

      1. Build the app boot jar and plugin signature CLI.
      2. Use either the signed plugin repository catalog (default) or locally built official plugins.
      3. Build only the Windows setup package through package-local.ps1.

    Catalog mode requires the signed catalog to contain the canonical default-installed plugin set.
    Local mode builds that set from the current source tree and normally requires the official signing key.
    -AllowUnsignedLocalPlugins creates a local-test-only installer using explicit LOCAL_UPLOAD provenance;
    it never changes the catalog or release verification policy.

    Layout survey packaging (LOCAL ONLY):
      -EnableLayoutSurvey must be combined with -PluginSource Local. It reads the four public
      pixiv.layout-survey.* values from scripts/properties/posthog.properties (or the file passed via
      -LayoutSurveyPropertiesFile), generates a public-config.js, and bakes it into the staged
      download-workbench plugin copy so the local test installer shows the survey.
      Without -EnableLayoutSurvey, Local mode still generates an explicit disabled public-config.js so a
      previously enabled local build is overwritten; the real properties file is never read in that mode.
      Catalog mode never bakes a local configuration: -EnableLayoutSurvey with -PluginSource Catalog
      fails immediately, and catalog-downloaded plugins are never modified.
#>
[CmdletBinding()]
param(
    [string]$Version = "0.0.1-local",
    [ValidateSet("Catalog", "Local")]
    [string]$PluginSource = "Catalog",
    [string]$ManifestUrl = "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json",
    [string]$PluginInputsDir,
    [string]$SignatureToolJar,
    [string]$CoreApiVersion = "1.0.0",
    [string]$OfficialKeyId,
    [string]$PrivateKeyFile,
    [switch]$AllowUnsignedLocalPlugins,
    [switch]$EnableLayoutSurvey,
    [string]$LayoutSurveyPropertiesFile,
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
$LayoutSurveyGenerator = Join-Path $PSScriptRoot "generate-layout-survey-public-config.ps1"

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
if ($EnableLayoutSurvey -and $PluginSource -ne "Local") {
    throw "EnableLayoutSurvey requires -PluginSource Local; catalog-downloaded signed plugins must never be modified locally."
}
if (-not $EnableLayoutSurvey -and $PSBoundParameters.ContainsKey("LayoutSurveyPropertiesFile")) {
    throw "LayoutSurveyPropertiesFile was provided without -EnableLayoutSurvey; pass -EnableLayoutSurvey to use it."
}
if ($PluginSource -eq "Local" -and $AllowUnsignedLocalPlugins) {
    if (-not [string]::IsNullOrWhiteSpace($OfficialKeyId) -or
        -not [string]::IsNullOrWhiteSpace($PrivateKeyFile) -or
        -not [string]::IsNullOrWhiteSpace($SignatureToolJar)) {
        throw "AllowUnsignedLocalPlugins cannot be combined with OfficialKeyId, PrivateKeyFile, or SignatureToolJar."
    }
} elseif ($PluginSource -eq "Local") {
    if ([string]::IsNullOrWhiteSpace($OfficialKeyId)) {
        throw "OfficialKeyId is required when -PluginSource Local is used."
    }
    if ([string]::IsNullOrWhiteSpace($PrivateKeyFile) -or
        -not (Test-Path -LiteralPath $PrivateKeyFile -PathType Leaf)) {
        throw "PrivateKeyFile is required when -PluginSource Local is used and must point to an Ed25519 PKCS#8 PEM file."
    }
    $PrivateKeyFile = (Resolve-Path -LiteralPath $PrivateKeyFile).Path
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
        $defaultPluginModules = @(Get-OfficialDefaultInstalledPlugins | ForEach-Object { $_.Module })
        $mavenProjects += $defaultPluginModules
    }
    $mavenProjects = @($mavenProjects | Select-Object -Unique)

    Write-Step "Building application jar, signature tool, and selected plugin inputs"
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
    if (-not $AllowUnsignedLocalPlugins) {
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

    # Local layout-survey packaging configuration. Enabled only when
    # -EnableLayoutSurvey is passed: the file's existence alone never enables
    # the survey. Local mode without the switch still generates an explicit
    # disabled config so a residual enabled config in the staged plugin copy is
    # overwritten. Catalog mode generates nothing and never modifies catalog
    # artifacts. The generated file is written into the build directory and
    # only its path is passed on; the four values are never printed.
    $layoutSurveyConfigFile = ""
    if ($PluginSource -eq "Local") {
        $layoutSurveyDir = Join-Path $ProjectRoot "build/local-layout-survey"
        $layoutSurveyConfigFile = Join-Path $layoutSurveyDir "public-config.js"
        Remove-Item -LiteralPath $layoutSurveyConfigFile -Force -ErrorAction SilentlyContinue
        $generatorParams = @{ OutputPath = $layoutSurveyConfigFile }
        if ($EnableLayoutSurvey) {
            if (-not $LayoutSurveyPropertiesFile) {
                $LayoutSurveyPropertiesFile = Join-Path $PSScriptRoot "properties/posthog.properties"
            }
            $generatorParams.PropertiesFile = $LayoutSurveyPropertiesFile
        }
        & $LayoutSurveyGenerator @generatorParams |
            ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) {
            throw "Layout survey public config generation failed."
        }
        if (-not (Test-Path -LiteralPath $layoutSurveyConfigFile -PathType Leaf)) {
            throw "Layout survey public config file was not generated: $layoutSurveyConfigFile"
        }
        if ($EnableLayoutSurvey) {
            Write-Host "Layout survey packaging: enabled (properties file: $LayoutSurveyPropertiesFile)"
        } else {
            Write-Host "Layout survey packaging: disabled"
        }
    } else {
        Write-Host "Layout survey packaging: disabled (catalog plugins kept untouched)"
    }

    $packageArgs = @{
        Version = $Version
        PrebuiltJar = $bootJar
        SkipPortable = $true
        SkipOfflinePortable = $true
    }
    if ($AllowUnsignedLocalPlugins) {
        $packageArgs.AllowUnsignedLocalPlugins = $true
    } else {
        $packageArgs.SignatureToolJar = $resolvedSignatureToolJar
    }
    if ($layoutSurveyConfigFile) {
        $packageArgs.LayoutSurveyPublicConfigFile = $layoutSurveyConfigFile
    }

    if ($PluginSource -eq "Catalog") {
        Write-Step "Staging official plugin inputs from signed catalog"
        & $StagePluginsScript `
            -ManifestUrl $ManifestUrl `
            -OutputDir $PluginInputsDir `
            -SignatureToolJar $resolvedSignatureToolJar `
            -CoreApiVersion $CoreApiVersion
        $packageArgs.PrebuiltPluginsDir = $PluginInputsDir
    } elseif ($AllowUnsignedLocalPlugins) {
        Write-Step "Using current-source default-installed plugins without signatures (LOCAL TEST ONLY)"
        Write-Host "Do not distribute the resulting installer or app image." -ForegroundColor Red
    } else {
        Write-Step "Using locally built default-installed plugins with official signing"
        $packageArgs.OfficialKeyId = $OfficialKeyId
        $packageArgs.PrivateKeyFile = $PrivateKeyFile
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
