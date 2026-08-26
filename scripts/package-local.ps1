[CmdletBinding()]
param(
    [string]$Version = "0.0.1-local",
    [string]$PrebuiltJar,
    [string]$PrebuiltPluginsDir,
    [switch]$SkipPlugins,
    [switch]$AllowUnsignedLocalPlugins,
    [switch]$RunTests,
    [switch]$SkipPortable,
    [switch]$SkipOfflinePortable,
    [switch]$RedownloadFfmpeg,
    [string[]]$MsiCultures,
    [string[]]$MsiVariants,
    [Alias("SkipMsi")]
    [switch]$SkipInstaller,
    [string]$OfficialKeyId,
    [string]$PrivateKeyFile,
    [string]$SignatureToolJar
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

# Shared official-plugin list + plugin-jar / checksum primitives (one source of distribution truth,
# also used by scripts/assemble-plugin-distribution.ps1).
. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")
. (Join-Path $PSScriptRoot "ffmpeg-release-integrity.ps1")
. (Join-Path $PSScriptRoot "package-local-plugin-staging.ps1")
. (Join-Path $PSScriptRoot "package-local-installer-catalog.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BuildRoot = Join-Path $ProjectRoot "build"
$InputDir = Join-Path $BuildRoot "input"
$RuntimeDir = Join-Path $BuildRoot "runtime"
$OnlineAppImageRoot = Join-Path $BuildRoot "app-image-online"
$OnlineAppDir = Join-Path $OnlineAppImageRoot "PixivDownload"
$OfflineAppImageRoot = Join-Path $BuildRoot "app-image-full"
$OfflineAppDir = Join-Path $OfflineAppImageRoot "PixivDownload"
$InnoToolchainDir = Join-Path $BuildRoot "inno-admin-loader"
$OutDir = if ($AllowUnsignedLocalPlugins) {
    Join-Path $BuildRoot "out-local-unsigned"
} else {
    Join-Path $BuildRoot "out"
}
$WixDir = Join-Path $BuildRoot "wix"
$FfmpegDir = Join-Path $BuildRoot "ffmpeg"
$FfmpegUnpackDir = Join-Path $FfmpegDir "unpack"
$FfmpegLicenseDir = Join-Path $FfmpegDir "licenses"
$AppName = "PixivDownload"
$AppVendor = "sywyar"
$MainClass = "org.springframework.boot.loader.launch.JarLauncher"
$JreModules = "java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.xml,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.localedata,jdk.management,jdk.unsupported,jdk.zipfs"
$FfmpegReleaseBaseUrl = "https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/"
$FfmpegAssetName = "ffmpeg-windows-x64.zip"
$OfficialPluginCatalogUrl = "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json"
$InstallerSdkVersion = Get-PixivDownloadSdkVersion -ProjectRoot $ProjectRoot
$EnableInstallerPluginSelection = $false
$InstallerCatalogDirName = "installer-catalog"
$InstallerCatalogIncludePath = Join-Path $BuildRoot "installer-plugin-catalog-items.iss.inc"
$FfmpegExe = Join-Path $FfmpegDir "ffmpeg.exe"
$FfprobeExe = Join-Path $FfmpegDir "ffprobe.exe"
$FfmpegLicenseFiles = @("ffmpeg-LGPLv2.1.txt", "libwebp-COPYING.txt", "libwebp-PATENTS.txt")
$OnlineZipPath = Join-Path $OutDir "$AppName-$Version-win-x64-online-portable.zip"
$OfflineZipPath = Join-Path $OutDir "$AppName-$Version-win-x64-portable.zip"
$SetupPath = Join-Path $OutDir "$AppName-$Version-win-x64-setup.exe"
$LocalUnsignedSetupPath = Join-Path $OutDir "$AppName-$Version-LOCAL-UNSIGNED-win-x64-setup.exe"
$ResultSetupPath = if ($AllowUnsignedLocalPlugins) { $LocalUnsignedSetupPath } else { $SetupPath }
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
    $bootCandidate = Get-ChildItem (Join-Path $ProjectRoot "pixivdownload-app\target\PixivDownload-*-boot.jar") -File
    if ($bootCandidate) {
        return ($bootCandidate | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
    }

    $jar = Get-ChildItem (Join-Path $ProjectRoot "pixivdownload-app\target\PixivDownload-*.jar") -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $jar) {
        throw "Could not find built JAR under pixivdownload-app/target/."
    }

    return $jar
}

function Resolve-PrebuiltJar {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $null
    }

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Write-Warning "Prebuilt JAR not found: $Path"
        return $null
    }

    $item = Get-Item -LiteralPath $Path
    if ($item.Extension -ne ".jar") {
        Write-Warning "Prebuilt JAR is not a .jar file: $Path"
        return $null
    }
    if ($item.Length -le 0) {
        Write-Warning "Prebuilt JAR is empty: $Path"
        return $null
    }

    return $item.FullName
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
    Assert-Command "curl.exe"

    Ensure-Directory $FfmpegDir
    $zipPath = Join-Path $FfmpegDir "ffmpeg.zip"
    $manifestPath = Join-Path $FfmpegDir "ffmpeg-release.json"
    $signaturePath = "$manifestPath.sig"
    $expectedAsset = $null

    if (-not $RedownloadFfmpeg -and
        (Test-Path -LiteralPath $zipPath -PathType Leaf) -and
        (Test-Path -LiteralPath $manifestPath -PathType Leaf) -and
        (Test-Path -LiteralPath $signaturePath -PathType Leaf)) {
        try {
            $expectedAsset = Get-VerifiedFfmpegReleaseAsset `
                -ManifestPath $manifestPath `
                -SignaturePath $signaturePath `
                -AssetName $FfmpegAssetName `
                -SignatureToolJar $SignatureToolJar
            Assert-FfmpegReleaseAsset -ArchivePath $zipPath -ExpectedAsset $expectedAsset
            Write-Step "Reusing verified FFmpeg release archive from build/ffmpeg"
        } catch {
            Write-Host "Cached FFmpeg archive is not trusted; downloading the signed release again." -ForegroundColor Yellow
            $expectedAsset = $null
        }
    }

    if (-not $expectedAsset) {
        Write-Step "Downloading and verifying FFmpeg payload"
        $downloadManifest = "$manifestPath.download"
        $downloadSignature = "$signaturePath.download"
        $downloadZip = "$zipPath.download"
        foreach ($path in @($downloadManifest, $downloadSignature, $downloadZip)) {
            Remove-PathIfExists $path
        }
        Invoke-External "curl.exe" @("--proto", "=https", "--proto-redir", "=https", "--tlsv1.2", "-fL",
            "${FfmpegReleaseBaseUrl}ffmpeg-release.json", "-o", $downloadManifest)
        Invoke-External "curl.exe" @("--proto", "=https", "--proto-redir", "=https", "--tlsv1.2", "-fL",
            "${FfmpegReleaseBaseUrl}ffmpeg-release.json.sig", "-o", $downloadSignature)
        $expectedAsset = Get-VerifiedFfmpegReleaseAsset `
            -ManifestPath $downloadManifest `
            -SignaturePath $downloadSignature `
            -AssetName $FfmpegAssetName `
            -SignatureToolJar $SignatureToolJar
        Invoke-External "curl.exe" @("--proto", "=https", "--proto-redir", "=https", "--tlsv1.2", "-fL",
            "$FfmpegReleaseBaseUrl$FfmpegAssetName", "-o", $downloadZip)
        Assert-FfmpegReleaseAsset -ArchivePath $downloadZip -ExpectedAsset $expectedAsset
        Move-Item -LiteralPath $downloadManifest -Destination $manifestPath -Force
        Move-Item -LiteralPath $downloadSignature -Destination $signaturePath -Force
        Move-Item -LiteralPath $downloadZip -Destination $zipPath -Force
    }

    Remove-PathIfExists $FfmpegUnpackDir
    Ensure-Directory $FfmpegUnpackDir
    Expand-Archive -Path $zipPath -DestinationPath $FfmpegUnpackDir -Force

    $payloadRoot = Get-ChildItem $FfmpegUnpackDir -Directory |
        Where-Object { $_.Name -eq "ffmpeg-windows-x64" } |
        Select-Object -First 1

    if (-not $payloadRoot) {
        throw "Could not locate unpacked FFmpeg payload."
    }

    $sourceFfmpeg = Join-Path $payloadRoot.FullName "bin\ffmpeg.exe"
    $sourceFfprobe = Join-Path $payloadRoot.FullName "bin\ffprobe.exe"
    $sourceLicenseDir = Join-Path $payloadRoot.FullName "licenses"
    foreach ($requiredPath in @($sourceFfmpeg, $sourceFfprobe)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "FFmpeg payload is missing required file: $requiredPath"
        }
    }
    foreach ($licenseName in $FfmpegLicenseFiles) {
        if (-not (Test-Path -LiteralPath (Join-Path $sourceLicenseDir $licenseName) -PathType Leaf)) {
            throw "FFmpeg payload is missing required license file: $licenseName"
        }
    }

    Copy-Item $sourceFfmpeg $FfmpegExe -Force
    Copy-Item $sourceFfprobe $FfprobeExe -Force
    Remove-PathIfExists $FfmpegLicenseDir
    Copy-Item $sourceLicenseDir $FfmpegDir -Recurse -Force
}

if ($AllowUnsignedLocalPlugins) {
    if ($SkipPlugins) {
        throw "AllowUnsignedLocalPlugins cannot be combined with SkipPlugins."
    }
    if (-not [string]::IsNullOrWhiteSpace($PrebuiltPluginsDir)) {
        throw "AllowUnsignedLocalPlugins only accepts plugin artifacts built from the current source tree; PrebuiltPluginsDir is not allowed."
    }
    if (-not [string]::IsNullOrWhiteSpace($OfficialKeyId) -or
        -not [string]::IsNullOrWhiteSpace($PrivateKeyFile)) {
        throw "AllowUnsignedLocalPlugins cannot be combined with OfficialKeyId or PrivateKeyFile."
    }
    if (-not $SkipPortable -or -not $SkipOfflinePortable) {
        throw "AllowUnsignedLocalPlugins requires SkipPortable and SkipOfflinePortable so no unsigned portable archive is produced."
    }
    if ($SkipInstaller) {
        throw "AllowUnsignedLocalPlugins is only for building a local test installer; SkipInstaller is not allowed."
    }
}
Push-Location $ProjectRoot
try {
    Write-Step "Checking local toolchain"
    if ($AllowUnsignedLocalPlugins) {
        Write-Host "LOCAL TEST ONLY: staging current-source plugins as unsigned local uploads." -ForegroundColor Red
        Write-Host "Do not distribute the resulting installer or app image." -ForegroundColor Red
    }
    $InstallerVersion = Get-InstallerVersion $Version
    $resolvedPrebuiltPluginsDir = ""
    if (-not $SkipPlugins) {
        $resolvedPrebuiltPluginsDir = Resolve-PrebuiltPluginsDir $PrebuiltPluginsDir
    }
    $mavenCmd = $null
    if (-not $PrebuiltJar) {
        $mavenCmd = Get-MavenCommand
    }
    Assert-Command "jlink"
    Assert-Command "jpackage"
    if (-not $SkipInstaller) {
        $innoCompilerSource = Get-InnoSetupCompiler
    }

    Write-Step "Cleaning local packaging directories"
    foreach ($path in @($InputDir, $RuntimeDir, $OnlineAppImageRoot, $OfflineAppImageRoot, $InnoToolchainDir, $OutDir, $WixDir, $InstallerCatalogIncludePath)) {
        Remove-PathIfExists $path
    }
    Ensure-Directory $InputDir
    Ensure-Directory $OutDir

    $stagedJar = Join-Path $InputDir "$AppName-$Version.jar"
    $resolvedPrebuiltJar = Resolve-PrebuiltJar $PrebuiltJar
    if ($resolvedPrebuiltJar) {
        Write-Step "Staging prebuilt application JAR"
        Copy-Item $resolvedPrebuiltJar $stagedJar -Force
    } else {
        if ($PrebuiltJar) {
            Write-Step "Prebuilt JAR invalid; falling back to Maven build"
        } else {
            Write-Step "Building application JAR"
        }
        if (-not $mavenCmd) {
            $mavenCmd = Get-MavenCommand
        }
        if ($RunTests) {
            Invoke-External $mavenCmd @("package", "-Dapp.release.version=$Version")
        } else {
            Invoke-External $mavenCmd @("package", "-DskipTests", "-Dapp.release.version=$Version")
        }

        $jar = Get-BuiltJar
        Copy-Item $jar.FullName $stagedJar -Force
    }

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
        "--icon", "pixivdownload-app/src/main/resources/static/favicon.ico",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Dstdout.encoding=UTF-8",
        "--java-options", "-Dstderr.encoding=UTF-8",
        "--dest", $OnlineAppImageRoot
    )

    Write-Step "Patching launcher to request administrator rights"
    & $SetExeExecutionLevelScript -Path (Join-Path $OnlineAppDir "$AppName.exe") -Level "requireAdministrator"

    if ((-not $SkipPlugins -and -not $AllowUnsignedLocalPlugins) -or
        -not $SkipOfflinePortable -or -not $SkipInstaller) {
        $SignatureToolJar = Resolve-SignatureToolJar $ProjectRoot $SignatureToolJar
    }

    # Stage all default-installed external plugins into the online app-image before packaging. They remain
    # separate PF4J artifacts; only Douyin is left for on-demand installation / the full-offline image.
    if (-not $SkipPlugins) {
        Write-Step "Staging official default-installed external plugins into app-image plugins/"
        $defaultInstalledPlugins = @(Get-OfficialDefaultInstalledPlugins)
        $defaultInstalledCount = Stage-OfficialPlugins -AppDir $OnlineAppDir -Plugins $defaultInstalledPlugins `
            -PrebuiltPluginsDir $resolvedPrebuiltPluginsDir `
            -ProjectRoot $ProjectRoot -OfficialKeyId $OfficialKeyId -PrivateKeyFile $PrivateKeyFile `
            -SignatureToolJar $SignatureToolJar -AllowUnsignedLocalPlugins:$AllowUnsignedLocalPlugins
        Write-Host ("    {0} official default-installed plugin(s) staged under plugins/." -f $defaultInstalledCount) -ForegroundColor Green

        if ($EnableInstallerPluginSelection) {
            Write-Step "Staging signed installer plugin catalog snapshot"
            Stage-InstallerPluginCatalogSnapshot `
                -AppDir $OnlineAppDir `
                -SignatureToolJar $SignatureToolJar `
                -CatalogUrl $OfficialPluginCatalogUrl `
                -SdkVersion $InstallerSdkVersion `
                -BuildRoot $BuildRoot `
                -CatalogDirName $InstallerCatalogDirName `
                -CatalogIncludePath $InstallerCatalogIncludePath
        } else {
            Write-Step "Skipping installer plugin selection (temporarily disabled; implementation retained)"
        }
    } else {
        Write-Step "Skipping plugin staging (-SkipPlugins): core shell only; required plugin missing triggers recovery"
    }

    if (-not $SkipPortable) {
        Write-Step "Packaging online portable zip"
        Compress-Archive -Path $OnlineAppDir -DestinationPath $OnlineZipPath -Force
    }

    if (-not $SkipOfflinePortable) {
        Ensure-FfmpegPayload

        Write-Step "Building offline app-image"
        Ensure-Directory $OfflineAppImageRoot
        Copy-Item $OnlineAppDir $OfflineAppImageRoot -Recurse -Force
        if (-not $SkipPlugins) {
            Write-Step "Staging official required + optional external plugins into offline app-image plugins/"
            $fullOfflinePlugins = @(Get-OfficialDistributionPlugins -IncludeOptional)
            $fullOfflineCount = Stage-OfficialPlugins -AppDir $OfflineAppDir -Plugins $fullOfflinePlugins `
                -PrebuiltPluginsDir $resolvedPrebuiltPluginsDir `
                -ProjectRoot $ProjectRoot -OfficialKeyId $OfficialKeyId -PrivateKeyFile $PrivateKeyFile `
                -SignatureToolJar $SignatureToolJar -AllowUnsignedLocalPlugins:$AllowUnsignedLocalPlugins
            Write-Host ("    {0} official plugin(s) staged under plugins/ (full-offline)." -f $fullOfflineCount) -ForegroundColor Green
        }
        $offlineFfmpegDir = Join-Path $OfflineAppDir "tools\ffmpeg"
        Copy-Item $FfmpegExe $offlineFfmpegDir -Force
        Copy-Item $FfprobeExe $offlineFfmpegDir -Force
        Copy-Item $FfmpegLicenseDir $offlineFfmpegDir -Recurse -Force

        Write-Step "Packaging offline portable zip"
        Compress-Archive -Path $OfflineAppDir -DestinationPath $OfflineZipPath -Force
    }

    if (-not $SkipInstaller) {
        Write-Step "Preparing Inno Setup admin loader"
        $innoCompiler = & $PrepareInnoAdminLoaderScript -CompilerPath $innoCompilerSource -OutputDirectory $InnoToolchainDir

        Write-Step "Building Windows setup"
        $installerPluginCatalogEnabled = if ((-not $SkipPlugins) -and $EnableInstallerPluginSelection) { "1" } else { "0" }
        Invoke-External $innoCompiler @(
            "/DAppVersion=$Version",
            "/DInstallerVersion=$InstallerVersion",
            "/DAppImageDir=$OnlineAppDir",
            "/DOutputDir=$OutDir",
            "/DSdkVersion=$InstallerSdkVersion",
            "/DInstallerPluginCatalogEnabled=$installerPluginCatalogEnabled",
            "/DSignatureToolJar=$SignatureToolJar",
            $InnoScript
        )
        if ($AllowUnsignedLocalPlugins) {
            if (-not (Test-Path -LiteralPath $SetupPath -PathType Leaf)) {
                throw "Inno Setup did not produce the expected intermediate installer: $SetupPath"
            }
            Move-Item -LiteralPath $SetupPath -Destination $LocalUnsignedSetupPath -Force
        }
    }

    Write-Step "Done"
    Assert-NoPrivateKeyMaterial $OutDir
    Assert-NoPrivateKeyMaterial $OnlineAppDir
    if (Test-Path -LiteralPath $OfflineAppDir) { Assert-NoPrivateKeyMaterial $OfflineAppDir }
    if (-not $SkipPortable) {
        Write-Host "Online portable: $OnlineZipPath"
    }
    if (-not $SkipOfflinePortable) {
        Write-Host "Offline portable: $OfflineZipPath"
    }
    if (-not $SkipInstaller) {
        Write-Host "Windows setup : $ResultSetupPath"
    }
    Write-Host "App dir       : $OnlineAppDir"
    if ($AllowUnsignedLocalPlugins) {
        Write-Host "Plugins       : LOCAL TEST ONLY; current-source defaults, unsigned LOCAL_UPLOAD provenance" -ForegroundColor Red
    } elseif (-not $SkipPlugins) {
        Write-Host "Plugins       : all user-facing official plugins except Douyin in default package; Douyin added by full-offline"
    } else {
        Write-Host "Plugins       : none bundled (-SkipPlugins; core shell recovery package)"
    }
    if ($MsiCultures -or $MsiVariants) {
        Write-Host "Note: MSI options are retained for compatibility and are ignored by the Inno Setup flow."
    }
} finally {
    Pop-Location
}
