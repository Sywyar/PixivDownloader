[CmdletBinding()]
param(
    [string]$Version = "0.0.1-local",
    [switch]$RunTests,
    [switch]$SkipPortable,
    [switch]$SkipOfflinePortable,
    [switch]$RedownloadFfmpeg,
    [string[]]$MsiCultures,
    [string[]]$MsiVariants,
    [Alias("SkipMsi")]
    [switch]$SkipInstaller
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path $ProjectRoot "build"
$InputDir = Join-Path $BuildRoot "input"
$RuntimeDir = Join-Path $BuildRoot "runtime"
$OnlineAppImageRoot = Join-Path $BuildRoot "app-image-online"
$OnlineAppDir = Join-Path $OnlineAppImageRoot "PixivDownload"
$OfflineAppImageRoot = Join-Path $BuildRoot "app-image-full"
$OfflineAppDir = Join-Path $OfflineAppImageRoot "PixivDownload"
$InnoToolchainDir = Join-Path $BuildRoot "inno-admin-loader"
$OutDir = Join-Path $BuildRoot "out"
$WixDir = Join-Path $BuildRoot "wix"
$FfmpegDir = Join-Path $BuildRoot "ffmpeg"
$FfmpegUnpackDir = Join-Path $FfmpegDir "unpack"
$AppName = "PixivDownload"
$AppVendor = "sywyar"
$MainClass = "org.springframework.boot.loader.launch.JarLauncher"
$JreModules = "java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.xml,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.management,jdk.unsupported,jdk.zipfs"
$FfmpegZipUrl = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip"
$FfmpegExe = Join-Path $FfmpegDir "ffmpeg.exe"
$FfprobeExe = Join-Path $FfmpegDir "ffprobe.exe"
$FfmpegLicense = Join-Path $FfmpegDir "ffmpeg-LGPL.txt"
$OnlineZipPath = Join-Path $OutDir "$AppName-$Version-win-x64-online-portable.zip"
$OfflineZipPath = Join-Path $OutDir "$AppName-$Version-win-x64-portable.zip"
$SetupPath = Join-Path $OutDir "$AppName-$Version-win-x64-setup.exe"
$InnoScript = Join-Path $ProjectRoot "packaging/windows/inno/PixivDownload.iss"
$SetExeExecutionLevelScript = Join-Path $PSScriptRoot "set-windows-exe-requested-execution-level.ps1"
$PrepareInnoAdminLoaderScript = Join-Path $PSScriptRoot "prepare-inno-admin-loader.ps1"
$InstallerVersion = $null

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Missing required command: $Name"
    }
}

function Get-MavenCommand {
    $wrapper = Join-Path $ProjectRoot "mvnw.cmd"
    if (Test-Path $wrapper) {
        return $wrapper
    }

    $maven = Get-Command "mvn.cmd" -ErrorAction SilentlyContinue
    if ($maven) {
        return $maven.Source
    }

    $maven = Get-Command "mvn" -ErrorAction SilentlyContinue
    if ($maven) {
        return $maven.Source
    }

    throw "Missing Maven command. Install Maven or use the Maven wrapper."
}

function Get-InnoSetupCompiler {
    $command = Get-Command "iscc.exe" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidates = @()
    $programFilesX86 = ${env:ProgramFiles(x86)}
    if (-not [string]::IsNullOrWhiteSpace($programFilesX86)) {
        $candidates += Join-Path $programFilesX86 "Inno Setup 6\ISCC.exe"
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $candidates += Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe"
    }

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "Missing required command: iscc.exe. Install Inno Setup 6 or add ISCC.exe to PATH."
}

function Remove-PathIfExists {
    param([string]$Path)
    if (Test-Path $Path) {
        Remove-Item -Recurse -Force $Path
    }
}

function Ensure-Directory {
    param([string]$Path)
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Invoke-External {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList
    )

    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $FilePath $($ArgumentList -join ' ')"
    }
}

function Get-BuiltJar {
    $jar = Get-ChildItem (Join-Path $ProjectRoot "target\PixivDownload-*.jar") -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $jar) {
        throw "Could not find built JAR under target/."
    }

    return $jar
}

function Get-InstallerVersion {
    param([string]$VersionText)

    $match = [regex]::Match($VersionText, "\d+(?:\.\d+){0,2}")
    if (-not $match.Success) {
        throw "Installer version must contain up to three numeric components. Received: $VersionText"
    }

    return $match.Value
}

function Ensure-FfmpegPayload {
    $hasPayload =
        (Test-Path $FfmpegExe) -and
        (Test-Path $FfprobeExe) -and
        (Test-Path $FfmpegLicense)

    if ($hasPayload -and -not $RedownloadFfmpeg) {
        Write-Step "Reusing existing FFmpeg payload from build/ffmpeg"
        return
    }

    Assert-Command "curl.exe"

    Write-Step "Downloading FFmpeg payload"
    Ensure-Directory $FfmpegDir
    Remove-PathIfExists $FfmpegUnpackDir
    Ensure-Directory $FfmpegUnpackDir

    $zipPath = Join-Path $FfmpegDir "ffmpeg.zip"
    Invoke-External "curl.exe" @("-fL", $FfmpegZipUrl, "-o", $zipPath)

    Expand-Archive -Path $zipPath -DestinationPath $FfmpegUnpackDir -Force

    $payloadRoot = Get-ChildItem $FfmpegUnpackDir -Directory |
        Where-Object { $_.Name -like "ffmpeg-*-win64-lgpl" } |
        Select-Object -First 1

    if (-not $payloadRoot) {
        throw "Could not locate unpacked FFmpeg payload."
    }

    Copy-Item (Join-Path $payloadRoot.FullName "bin\ffmpeg.exe") $FfmpegExe -Force
    Copy-Item (Join-Path $payloadRoot.FullName "bin\ffprobe.exe") $FfprobeExe -Force
    @"
FFmpeg is licensed under the LGPL v2.1.
Source code: https://ffmpeg.org
Build: BtbN FFmpeg Builds (https://github.com/BtbN/FFmpeg-Builds)
LGPL License: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
"@ | Set-Content -Path $FfmpegLicense -Encoding UTF8
}

Push-Location $ProjectRoot
try {
    Write-Step "Checking local toolchain"
    $InstallerVersion = Get-InstallerVersion $Version
    $mavenCmd = Get-MavenCommand
    Assert-Command "jlink"
    Assert-Command "jpackage"
    if (-not $SkipInstaller) {
        $innoCompilerSource = Get-InnoSetupCompiler
    }

    Write-Step "Cleaning local packaging directories"
    foreach ($path in @($InputDir, $RuntimeDir, $OnlineAppImageRoot, $OfflineAppImageRoot, $InnoToolchainDir, $OutDir, $WixDir)) {
        Remove-PathIfExists $path
    }
    Ensure-Directory $InputDir
    Ensure-Directory $OutDir

    Write-Step "Building application JAR"
    if ($RunTests) {
        Invoke-External $mavenCmd @("package", "-Dapp.release.version=$Version")
    } else {
        Invoke-External $mavenCmd @("package", "-DskipTests", "-Dapp.release.version=$Version")
    }

    $jar = Get-BuiltJar
    $stagedJar = Join-Path $InputDir "$AppName-$Version.jar"
    Copy-Item $jar.FullName $stagedJar -Force

    Write-Step "Building trimmed runtime image"
    Invoke-External "jlink" @(
        "--add-modules", $JreModules,
        "--strip-debug",
        "--no-man-pages",
        "--no-header-files",
        "--compress=2",
        "--output", $RuntimeDir
    )

    Write-Step "Building app-image"
    Invoke-External "jpackage" @(
        "--type", "app-image",
        "--name", $AppName,
        "--app-version", $InstallerVersion,
        "--vendor", $AppVendor,
        "--input", $InputDir,
        "--main-jar", "$AppName-$Version.jar",
        "--main-class", $MainClass,
        "--runtime-image", $RuntimeDir,
        "--icon", "src/main/resources/static/favicon.ico",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--dest", $OnlineAppImageRoot
    )

    Write-Step "Patching launcher to request administrator rights"
    & $SetExeExecutionLevelScript -Path (Join-Path $OnlineAppDir "$AppName.exe") -Level "requireAdministrator"

    if (-not $SkipPortable) {
        Write-Step "Packaging online portable zip"
        Compress-Archive -Path $OnlineAppDir -DestinationPath $OnlineZipPath -Force
    }

    if (-not $SkipOfflinePortable) {
        Ensure-FfmpegPayload

        Write-Step "Building offline app-image"
        Ensure-Directory $OfflineAppImageRoot
        Copy-Item $OnlineAppDir $OfflineAppImageRoot -Recurse -Force
        Copy-Item $FfmpegExe $OfflineAppDir -Force
        Copy-Item $FfprobeExe $OfflineAppDir -Force
        Ensure-Directory (Join-Path $OfflineAppDir "licenses")
        Copy-Item $FfmpegLicense (Join-Path $OfflineAppDir "licenses") -Force

        Write-Step "Packaging offline portable zip"
        Compress-Archive -Path $OfflineAppDir -DestinationPath $OfflineZipPath -Force
    }

    if (-not $SkipInstaller) {
        Write-Step "Preparing Inno Setup admin loader"
        $innoCompiler = & $PrepareInnoAdminLoaderScript -CompilerPath $innoCompilerSource -OutputDirectory $InnoToolchainDir

        Write-Step "Building Windows setup"
        Invoke-External $innoCompiler @(
            "/DAppVersion=$Version",
            "/DInstallerVersion=$InstallerVersion",
            "/DAppImageDir=$OnlineAppDir",
            "/DOutputDir=$OutDir",
            $InnoScript
        )
    }

    Write-Step "Done"
    if (-not $SkipPortable) {
        Write-Host "Online portable: $OnlineZipPath"
    }
    if (-not $SkipOfflinePortable) {
        Write-Host "Offline portable: $OfflineZipPath"
    }
    if (-not $SkipInstaller) {
        Write-Host "Windows setup : $SetupPath"
    }
    Write-Host "App dir       : $OnlineAppDir"
    if ($MsiCultures -or $MsiVariants) {
        Write-Host "Note: MSI options are retained for compatibility and are ignored by the Inno Setup flow."
    }
} finally {
    Pop-Location
}
