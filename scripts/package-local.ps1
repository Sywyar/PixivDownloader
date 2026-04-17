[CmdletBinding()]
param(
    [string]$Version = "0.0.1-local",
    [switch]$RunTests,
    [switch]$SkipOfflinePortable,
    [switch]$SkipMsi,
    [switch]$RedownloadFfmpeg,
    [string[]]$MsiCultures = @("zh-CN", "en-US"),
    [string[]]$MsiVariants = @("with-ffmpeg", "no-ffmpeg")
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
$FfmpegDir = Join-Path $BuildRoot "ffmpeg"
$FfmpegUnpackDir = Join-Path $FfmpegDir "unpack"
$OutDir = Join-Path $BuildRoot "out"
$WixDir = Join-Path $BuildRoot "wix"
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
$FixWixKeyPathsScript = Join-Path $PSScriptRoot "fix-wix-per-user-keypaths.ps1"
$InstallerVersion = $null
$MsiLocalization = @{
    "en-US" = @{
        WxlPath = Join-Path $ProjectRoot "packaging/windows/installer.en-US.wxl"
        LicenseRtfPath = Join-Path $ProjectRoot "packaging/windows/license.en-US.rtf"
    }
    "zh-CN" = @{
        WxlPath = Join-Path $ProjectRoot "packaging/windows/installer.zh-CN.wxl"
        LicenseRtfPath = Join-Path $ProjectRoot "packaging/windows/license.zh-CN.rtf"
    }
}
$MsiVariantConfig = @{
    "with-ffmpeg" = @{
        IncludeFfmpeg = "yes"
        RequiresPayload = $true
    }
    "no-ffmpeg" = @{
        IncludeFfmpeg = "no"
        RequiresPayload = $false
    }
}
$BuiltMsiArtifacts = @()

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
        throw "Installer version must start with up to three numeric components. Received: $VersionText"
    }

    return $match.Value
}

function Get-MsiCultureConfig {
    param([string]$Culture)

    if (-not $MsiLocalization.ContainsKey($Culture)) {
        $supported = ($MsiLocalization.Keys | Sort-Object) -join ", "
        throw "Unsupported MSI culture '$Culture'. Supported values: $supported"
    }

    return $MsiLocalization[$Culture]
}

function Get-MsiVariantConfig {
    param([string]$Variant)

    if (-not $MsiVariantConfig.ContainsKey($Variant)) {
        $supported = ($MsiVariantConfig.Keys | Sort-Object) -join ", "
        throw "Unsupported MSI variant '$Variant'. Supported values: $supported"
    }

    return $MsiVariantConfig[$Variant]
}

function Ensure-FfmpegPayload {
    if ($SkipOfflinePortable -and $SkipMsi) {
        return
    }

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
    $MsiCultures = @($MsiCultures | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    $MsiVariants = @($MsiVariants | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    $mavenCmd = Get-MavenCommand
    Assert-Command "jlink"
    Assert-Command "jpackage"
    if (-not $SkipMsi) {
        if ($MsiCultures.Count -eq 0) {
            throw "At least one MSI culture must be specified unless -SkipMsi is used."
        }
        if ($MsiVariants.Count -eq 0) {
            throw "At least one MSI variant must be specified unless -SkipMsi is used."
        }
        foreach ($culture in $MsiCultures) {
            [void](Get-MsiCultureConfig $culture)
        }
        foreach ($variant in $MsiVariants) {
            [void](Get-MsiVariantConfig $variant)
        }
        Assert-Command "heat.exe"
        Assert-Command "candle.exe"
        Assert-Command "light.exe"
    }

    Write-Step "Cleaning local packaging directories"
    foreach ($path in @($InputDir, $RuntimeDir, $OnlineAppImageRoot, $OfflineAppImageRoot, $OutDir, $WixDir)) {
        Remove-PathIfExists $path
    }
    Ensure-Directory $InputDir
    Ensure-Directory $OutDir

    Write-Step "Building application JAR"
    if ($RunTests) {
        Invoke-External $mavenCmd @("package")
    } else {
        Invoke-External $mavenCmd @("package", "-DskipTests")
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

    Write-Step "Building online app-image"
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

    Write-Step "Packaging online portable zip"
    Compress-Archive -Path $OnlineAppDir -DestinationPath $OnlineZipPath -Force

    $requiresFfmpegPayload = (-not $SkipOfflinePortable)
    if (-not $SkipMsi) {
        foreach ($variant in $MsiVariants) {
            if ((Get-MsiVariantConfig $variant).RequiresPayload) {
                $requiresFfmpegPayload = $true
                break
            }
        }
    }
    if ($requiresFfmpegPayload) {
        Ensure-FfmpegPayload
    }

    if (-not $SkipOfflinePortable) {
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

    if (-not $SkipMsi) {
        Write-Step "Harvesting app-image for WiX"
        Ensure-Directory $WixDir
        Invoke-External "heat.exe" @(
            "dir", $OnlineAppDir,
            "-cg", "AppFiles",
            "-dr", "INSTALLFOLDER",
            "-platform", "x64",
            "-srd",
            "-sfrag",
            "-gg",
            "-var", "var.AppImageDir",
            "-out", (Join-Path $WixDir "AppFiles.wxs")
        )
        & $FixWixKeyPathsScript -Path (Join-Path $WixDir "AppFiles.wxs")

        foreach ($variant in $MsiVariants) {
            $variantConfig = Get-MsiVariantConfig $variant

            Write-Step "Compiling WiX sources ($variant)"
            $candleArgs = @(
                "-nologo",
                "-arch", "x64",
                "-ext", "WixUIExtension",
                "-dVersion=$InstallerVersion",
                "-dAppImageDir=$OnlineAppDir",
                "-dIncludeFfmpeg=$($variantConfig.IncludeFfmpeg)",
                "-out", "$WixDir\",
                "packaging/windows/installer.wxs",
                (Join-Path $WixDir "AppFiles.wxs")
            )
            if ($variantConfig.RequiresPayload) {
                $candleArgs += @(
                    "-dFfmpegExe=$FfmpegExe",
                    "-dFfprobeExe=$FfprobeExe",
                    "-dFfmpegLicense=$FfmpegLicense"
                )
            }
            Invoke-External "candle.exe" $candleArgs

            foreach ($culture in $MsiCultures) {
                $cultureConfig = Get-MsiCultureConfig $culture
                $msiPath = Join-Path $OutDir "$AppName-$Version-win-x64-$culture-$variant.msi"

                Write-Step "Linking MSI ($culture, $variant)"
                Invoke-External "light.exe" @(
                    "-nologo",
                    "-ext", "WixUIExtension",
                    "-cultures:$culture",
                    "-loc", $cultureConfig.WxlPath,
                    "-dWixUILicenseRtf=$($cultureConfig.LicenseRtfPath)",
                    "-sice:ICE64",
                    "-sice:ICE91",
                    "-out", $msiPath,
                    (Join-Path $WixDir "installer.wixobj"),
                    (Join-Path $WixDir "AppFiles.wixobj")
                )

                $BuiltMsiArtifacts += [pscustomobject]@{
                    Culture = $culture
                    Variant = $variant
                    Path = $msiPath
                }
            }
        }
    }

    Write-Step "Done"
    Write-Host "Online portable : $OnlineZipPath"
    if (-not $SkipOfflinePortable) {
        Write-Host "Offline portable: $OfflineZipPath"
    }
    if (-not $SkipMsi) {
        foreach ($artifact in $BuiltMsiArtifacts) {
            Write-Host ("MSI ({0}, {1}) : {2}" -f $artifact.Culture, $artifact.Variant, $artifact.Path)
        }
    }
    Write-Host "Online app dir : $OnlineAppDir"
} finally {
    Pop-Location
}
