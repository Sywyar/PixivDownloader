<#
.SYNOPSIS
    Assemble plugin distribution layouts: core shell only, default downloader, or full offline.

.DESCRIPTION
    One command that consolidates the plugin distribution boundary:

      Core shell only:
        <OutputDir>/PixivDownload-<Version>.jar

      Default downloader / full offline:
        <OutputDir>/
          PixivDownload-<Version>.jar            # core shell boot jar; no download-workbench implementation
          plugins/
            pixivdownload-plugin-download-workbench-<version>.jar
                                                 # official required plugin
            <plugin>-<version>.jar               # default-installed official plugin
            pixivdownload-plugin-douyin-<version>.jar
                                                 # on-demand plugin (full offline only)
            <plugin>-<version>.jar.sha256        # per-package sha256 checksum file
            provenance/
              <plugin>-<version>.jar.pixiv-plugin-provenance
          run.bat                                # Windows launcher (CRLF, no BOM)
          run.sh                                 # POSIX sh launcher (LF, no BOM)
          SHA256SUMS                             # aggregate checksum file (sha256sum -c compatible)
          plugins-manifest.json                  # per external plugin: id / version / requires / file / sha256

    The boot jar alone is the core-shell package and must enter recovery/repair mode because the required
    download-workbench plugin is missing. The default downloader is the boot jar plus every user-facing official plugin
    except Douyin under plugins/. The full-offline bundle additionally carries Douyin. The launcher scripts
    reference the exact PixivDownload-<Version>.jar staged for this distribution, set
    -Dpixivdownload.plugins-dir to the distribution's plugins/ folder, and forward all user arguments
    (run.bat passes %* and returns the Java exit code; run.sh uses exec with "$@"). CoreShellOnly layouts
    stay a pure core-shell package and do not get launcher scripts.

    The script self-checks official plugins for their declared form (thin jar or jar with private lib/*.jar)
    and the boot jar for the distribution boundary (no external plugin classes / static / i18n; PF4J only
    nested under BOOT-INF/lib). Any broken invariant aborts with an error.

.PARAMETER CoreShellOnly
    Produce only the core-shell boot jar, without staging required or optional plugins.
    The result is a recovery/repair package, not the normal default downloader.

.PARAMETER DefaultDownloader
    Stage the default-installed official plugin set (all user-facing official plugins except Douyin).
    Without this switch, the script additionally stages Douyin as the full-offline distribution.

.PARAMETER Version
    Distribution version, used for the core jar file name. Default 0.0.1-local.

.PARAMETER OutputDir
    Distribution output directory. Default <repo>/build/dist.

.PARAMETER PrebuiltPluginsDir
    Directory containing official plugin artifacts downloaded from the signed plugin catalog. When an artifact has
    an adjacent .sig sidecar, the signature is verified and reused instead of generating a new local signature.

.PARAMETER PrebuiltJar
    Exact path to a prebuilt executable app boot jar to stage as the distribution core shell. Mutually exclusive
    with -Build. The path is resolved and validated before the script changes the current directory: the file must
    exist, be a non-empty *.jar, be readable as a zip, and contain the Spring Boot executable layout (BOOT-INF/);
    the staged jar then still passes the boot jar distribution boundary self-check. When -PrebuiltJar is set the
    script never calls Get-AppBootJar. When omitted the local development behavior is preserved: -Build may run
    Maven first and the app boot jar is located under pixivdownload-app/target.

.PARAMETER Build
    Run Maven `package` (skip tests and userscript generation) before staging; otherwise the reactor jars
    must already be built.

.PARAMETER IncludeSentinel
    Also stage the minimal validation plugin recovery-sentinel (off by default - it is only the recovery
    mode validation fixture, not a user-facing official plugin).
#>
[CmdletBinding()]
param(
    [string]$Version = "0.0.1-local",
    [string]$OutputDir,
    [string]$PrebuiltPluginsDir,
    [string]$PrebuiltJar,
    [switch]$Build,
    [switch]$CoreShellOnly,
    [switch]$DefaultDownloader,
    [switch]$IncludeSentinel,
    [string]$OfficialKeyId,
    [string]$PrivateKeyFile,
    [string]$SignatureToolJar
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

# Shared official-plugin list + plugin-jar / checksum primitives (one source of distribution truth).
. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot "build/dist"
}
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
if ($CoreShellOnly -and $DefaultDownloader) {
    throw "CoreShellOnly and DefaultDownloader cannot be combined."
}
$ResolvedPrebuiltPluginsDir = ""
if (-not $CoreShellOnly) {
    $SignatureToolJar = Resolve-SignatureToolJar $ProjectRoot $SignatureToolJar
    if (-not [string]::IsNullOrWhiteSpace($PrebuiltPluginsDir)) {
        if (-not (Test-Path -LiteralPath $PrebuiltPluginsDir -PathType Container)) {
            throw "PrebuiltPluginsDir not found or not a directory: $PrebuiltPluginsDir"
        }
        $ResolvedPrebuiltPluginsDir = (Resolve-Path -LiteralPath $PrebuiltPluginsDir).Path
    }
}

# Official external plugins (default-installed + optional Douyin). recovery-sentinel only when -IncludeSentinel.
# Wrap in @() so a single-element result keeps array shape (the function return unwraps it otherwise),
# preserving $DistributionPlugins.Count for the summary line.
$DistributionPlugins = @()
if (-not $CoreShellOnly) {
    $DistributionPlugins = @(Get-OfficialDistributionPlugins -IncludeOptional:(!$DefaultDownloader) -IncludeSentinel:$IncludeSentinel)
}

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Get-MavenCommand {
    $wrapper = Join-Path $ProjectRoot "mvnw.cmd"
    if (Test-Path $wrapper) { return $wrapper }
    foreach ($name in @("mvn.cmd", "mvn")) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    throw "Missing Maven command. Install Maven or use the Maven wrapper."
}

function Assert-BootJarBoundary {
    param([string]$JarPath)
    $entries = Get-ZipEntryNames $JarPath
    $forbidden = @(
        "BOOT-INF/classes/top/sywyar/pixivdownload/ai/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/notification/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/push/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/mail/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/stats/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/duplicate/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/gallery/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/novel/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/novelgallery/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/guitheme/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/download/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/schedule/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/download/DownloadWorkbenchPf4jPlugin",
        "BOOT-INF/classes/top/sywyar/pixivdownload/download/DownloadWorkbenchPlugin",
        "BOOT-INF/classes/top/sywyar/pixivdownload/download/controller/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/download/schedule/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/notificationbase/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/recoverysentinel/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/push/channel/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/mail/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/TtsPf4jPlugin",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/EdgeTts",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/TtsRateLimitService",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/controller/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/dto/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/narration/engine/AbstractHttp",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/narration/engine/CosyVoice",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/narration/engine/Doubao",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/narration/engine/ElevenLabs",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/narration/engine/Fish",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/narration/engine/MiMo",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/narration/engine/MiniMax",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/narration/engine/Qwen",
        "BOOT-INF/classes/top/sywyar/pixivdownload/tts/narration/engine/VoxCpm",
        "BOOT-INF/classes/top/sywyar/pixivdownload/ai/controller/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/ai/preset/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/ai/probe/",
        "BOOT-INF/classes/static/pixiv-stats",
        "BOOT-INF/classes/static/pixiv-duplicates",
        "BOOT-INF/classes/static/pixiv-gallery",
        "BOOT-INF/classes/static/pixiv-novel-gallery",
        "BOOT-INF/classes/static/pixiv-novel-download",
        "BOOT-INF/classes/static/pixiv-novel.html",
        "BOOT-INF/classes/static/pixiv-novel/",
        "BOOT-INF/classes/static/pixiv-artwork",
        "BOOT-INF/classes/static/pixiv-showcase",
        "BOOT-INF/classes/static/pixiv-series",
        "BOOT-INF/classes/static/pixiv-batch.html",
        "BOOT-INF/classes/static/pixiv-batch",
        "BOOT-INF/classes/static/userscripts/",
        "BOOT-INF/classes/static/pixiv-tts",
        "BOOT-INF/classes/static/pixiv-ai",
        "BOOT-INF/classes/i18n/web/stats",
        "BOOT-INF/classes/i18n/web/duplicates",
        "BOOT-INF/classes/i18n/web/gallery",
        "BOOT-INF/classes/i18n/web/novel",
        "BOOT-INF/classes/i18n/web/novel-gallery",
        "BOOT-INF/classes/i18n/web/narration",
        "BOOT-INF/classes/i18n/web/artwork",
        "BOOT-INF/classes/i18n/web/showcase",
        "BOOT-INF/classes/i18n/web/series",
        "BOOT-INF/classes/i18n/web/batch",
        "BOOT-INF/classes/i18n/web/userscript",
        "BOOT-INF/classes/i18n/web/gui-swing",
        "BOOT-INF/classes/i18n/web/gui-compose",
        "BOOT-INF/classes/i18n/web/notification",
        "BOOT-INF/classes/i18n/web/tts",
        "BOOT-INF/classes/i18n/web/ai",
        "BOOT-INF/classes/i18n/web/translate",
        "BOOT-INF/classes/i18n/mail/",
        "BOOT-INF/classes/i18n/push/",
        "BOOT-INF/classes/mail/",
        "BOOT-INF/classes/org/pf4j/",
        "BOOT-INF/lib/flatlaf-",
        "BOOT-INF/lib/flatlaf-intellij-themes-",
        "BOOT-INF/lib/jna-",
        "BOOT-INF/lib/jna-platform-",
        "BOOT-INF/lib/desktop-jvm-",
        "BOOT-INF/lib/ui-desktop-",
        "BOOT-INF/lib/skiko-awt-",
        "BOOT-INF/lib/kotlin-stdlib-",
        "BOOT-INF/lib/jakarta.mail-",
        "BOOT-INF/lib/jakarta.activation-api-",
        "BOOT-INF/lib/angus-activation-",
        "BOOT-INF/lib/spring-context-support-"
    )
    foreach ($prefix in $forbidden) {
        $leaked = $entries | Where-Object { $_.StartsWith($prefix) -and -not $_.EndsWith("/") }
        if ($leaked) {
            throw "Boot jar boundary violated - contains '$prefix' entries: $JarPath"
        }
    }
    # The boot jar root must not be a plugin descriptor (the boot jar is not an external plugin package).
    if ($entries -contains "plugin.properties") {
        throw "Boot jar must not contain a root plugin.properties: $JarPath"
    }
    # PF4J must travel as a nested library only (BOOT-INF/lib/pf4j-*.jar), not as loose classes.
    $pf4jLib = $entries | Where-Object { $_ -match "^BOOT-INF/lib/pf4j-.*\.jar$" }
    if (-not $pf4jLib) {
        throw "Boot jar is missing the nested PF4J runtime (BOOT-INF/lib/pf4j-*.jar): $JarPath"
    }
}

function Get-AppBootJar {
    $rootDir = Join-Path $ProjectRoot "pixivdownload-app/target"
    $bootJar = Get-ChildItem (Join-Path $rootDir "PixivDownload-*-boot.jar") -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($bootJar) {
        return $bootJar
    }

    return Get-ChildItem (Join-Path $rootDir "PixivDownload-*.jar") -File |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

# Resolve and strictly validate the exact prebuilt core jar BEFORE Push-Location changes the current
# directory, so a caller-provided relative path resolves in the caller's directory, not under
# $ProjectRoot. With -PrebuiltJar set the script never calls Get-AppBootJar; the final selected jar
# path is $SelectedAppJar, used for boundary verification, Copy-Item and logging alike.
$SelectedAppJar = ""
if ($PrebuiltJar) {
    if ($Build) {
        throw "Build and PrebuiltJar cannot be combined."
    }
    if (-not (Test-Path -LiteralPath $PrebuiltJar -PathType Leaf)) {
        throw "PrebuiltJar not found or not a file: $PrebuiltJar"
    }
    $prebuiltItem = Get-Item -LiteralPath $PrebuiltJar
    if (-not $prebuiltItem.Extension.Equals(".jar", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "PrebuiltJar must be a .jar file: $PrebuiltJar"
    }
    if ($prebuiltItem.Length -le 0) {
        throw "PrebuiltJar is empty: $PrebuiltJar"
    }
    $SelectedAppJar = $prebuiltItem.FullName
    try {
        $prebuiltEntries = @(Get-ZipEntryNames $SelectedAppJar)
    } catch {
        throw "PrebuiltJar cannot be read as a zip/jar: $PrebuiltJar ($($_.Exception.Message))"
    }
    if (-not ($prebuiltEntries | Where-Object { $_.StartsWith("BOOT-INF/") })) {
        throw "PrebuiltJar is not a Spring Boot executable jar (missing BOOT-INF/): $PrebuiltJar"
    }
}

# Stage 1 output-dir safety (shared assertion, before any build work): normalize
# the output dir and reject repository source paths, the build root, the repo
# root and its ancestors. Also protects every input known so far. The local
# target fallback core jar is only known after the Maven build, so a second
# full check runs right before the delete (see stage 2 below) with the final
# SelectedAppJar included. No destructive operation is allowed before that.
$ProtectedInputPaths = @($PSScriptRoot, $ProjectRoot)
if ($PrebuiltJar) {
    $ProtectedInputPaths += $SelectedAppJar
}
if (-not [string]::IsNullOrWhiteSpace($ResolvedPrebuiltPluginsDir)) {
    $ProtectedInputPaths += $ResolvedPrebuiltPluginsDir
}
if (-not [string]::IsNullOrWhiteSpace($SignatureToolJar)) {
    $ProtectedInputPaths += $SignatureToolJar
}
if (-not [string]::IsNullOrWhiteSpace($PrivateKeyFile)) {
    $privateKeyItem = Get-Item -LiteralPath $PrivateKeyFile -ErrorAction SilentlyContinue
    if ($privateKeyItem) {
        $ProtectedInputPaths += $privateKeyItem.FullName
    }
}
$OutputDir = Assert-SafeDistributionOutputDirectory `
    -Path $OutputDir `
    -ProjectRoot $ProjectRoot `
    -ProtectedPaths $ProtectedInputPaths
$PluginsOutDir = Join-Path $OutputDir "plugins"

Push-Location $ProjectRoot
try {
    if ($Build) {
        Write-Step "Building reactor (mvn package -DskipTests)"
        $mvn = Get-MavenCommand
        & $mvn "package" "-DskipTests" "-Dexec.skip=true" "-Dapp.release.version=$Version"
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }
    }

    Write-Step "Locating built artifacts"
    if (-not $SelectedAppJar) {
        $appJarCandidate = Get-AppBootJar
        if (-not $appJarCandidate) {
            throw "Could not find boot jar under pixivdownload-app/target/ (run with -Build, pass -PrebuiltJar, or 'mvn package' first)."
        }
        $SelectedAppJar = $appJarCandidate.FullName
        Write-Host "    Local target fallback: $SelectedAppJar" -ForegroundColor DarkGray
    } else {
        Write-Host "    Exact prebuilt jar: $SelectedAppJar"
    }

    Write-Step "Verifying boot jar distribution boundary"
    Assert-BootJarBoundary $SelectedAppJar
    Write-Host "    OK: boot jar contains core + built-in plugins, excludes external plugin classes/resources." -ForegroundColor Green

    Write-Step "Staging distribution to $OutputDir"
    # Stage 2 full safety check right before the only destructive operation:
    # every protected input, including the final SelectedAppJar (which may be
    # the local target fallback discovered above), must be disjoint from the
    # output tree. No destructive operation is allowed before this point.
    $ProtectedInputPaths += $SelectedAppJar
    [void](Assert-SafeDistributionOutputDirectory `
        -Path $OutputDir `
        -ProjectRoot $ProjectRoot `
        -ProtectedPaths $ProtectedInputPaths)
    if (Test-Path -LiteralPath $OutputDir) { Remove-Item -Recurse -Force -LiteralPath $OutputDir }
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    if (-not $CoreShellOnly) {
        New-Item -ItemType Directory -Force -Path $PluginsOutDir | Out-Null
    }

    $coreJarName = "PixivDownload-$Version.jar"
    Copy-Item $SelectedAppJar (Join-Path $OutputDir $coreJarName) -Force

    $manifest = @()
    $sumLines = @()
    $requiredPluginIds = @(Get-OfficialRequiredPlugins | ForEach-Object { $_.Id })
    foreach ($plugin in $DistributionPlugins) {
        Write-Step "Staging plugin '$($plugin.Id)'"
        if ($ResolvedPrebuiltPluginsDir) {
            $sourceArtifact = Find-PrebuiltOfficialPluginArtifact $plugin $ResolvedPrebuiltPluginsDir
        } else {
            $sourceArtifact = Find-ModulePluginArtifact $plugin $ProjectRoot
        }
        $sourceSignaturePath = Find-PluginArtifactSignatureSidecar $sourceArtifact
        $descriptor = Assert-OfficialPluginArtifact $sourceArtifact $plugin
        $pluginVersion = $descriptor["plugin.version"]
        $requires = $descriptor["plugin.requires"]
        $extension = Get-OfficialPluginArtifactExtension $plugin
        $isRequired = $requiredPluginIds -contains $plugin.Id

        $targetName = "$($plugin.Module)-$pluginVersion.$extension"
        $targetArtifact = Join-Path $PluginsOutDir $targetName
        Copy-Item $sourceArtifact $targetArtifact -Force

        $sha = Get-Sha256Hex $targetArtifact
        [System.IO.File]::WriteAllText("$targetArtifact.sha256", "$sha  $targetName`n", $Utf8NoBom)
        $signature = Get-PluginArtifactSignatureForDistribution `
            -ToolJar $SignatureToolJar `
            -ArtifactPath $targetArtifact `
            -PluginId $plugin.Id `
            -Version $pluginVersion `
            -ExistingSignaturePath $sourceSignaturePath `
            -OfficialKeyId $OfficialKeyId `
            -PrivateKeyFile $PrivateKeyFile `
            -OutputPath "$targetArtifact.sig"
        $verifiedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
        [void](Write-PluginProvenanceSidecar $targetArtifact (Get-Item -LiteralPath $targetArtifact).Length `
            $sha $signature $verifiedAt)

        $manifest += [ordered]@{
            id       = $plugin.Id
            version  = $pluginVersion
            requires = $requires
            required = $isRequired
            file     = "plugins/$targetName"
            sha256   = $sha
            signature = $signature
        }
        $sumLines += "$sha  plugins/$targetName"
        Write-Host "    OK: official plugin artifact staged ($targetName, sha256 $sha)." -ForegroundColor Green
    }

    if (-not $CoreShellOnly) {
        # Include the core jar in the aggregate checksum file too.
        $coreSha = Get-Sha256Hex (Join-Path $OutputDir $coreJarName)
        $sumLines = @("$coreSha  $coreJarName") + $sumLines
        [System.IO.File]::WriteAllText((Join-Path $OutputDir "SHA256SUMS"), (($sumLines -join "`n") + "`n"), $Utf8NoBom)

        # ConvertTo-Json with an explicit @() wrapper preserves the array shape for a single
        # element on Windows PowerShell 5.1 (the bare-object quirk only affects pipeline input).
        $manifestJson = ConvertTo-Json @($manifest) -Depth 5
        [System.IO.File]::WriteAllText((Join-Path $OutputDir "plugins-manifest.json"), $manifestJson + "`n", $Utf8NoBom)
    }

    if (-not $CoreShellOnly) {
        Write-Step "Writing launch scripts"
        $runBat = @'
@echo off
setlocal
set "APP_HOME=%~dp0"
cd /d "%APP_HOME%" || exit /b 1
java -Dfile.encoding=UTF-8 "-Dpixivdownload.plugins-dir=%APP_HOME%plugins" -jar "%APP_HOME%PixivDownload-VERSION.jar" %*
exit /b %ERRORLEVEL%
'@
        $runBat = $runBat.Replace("PixivDownload-VERSION.jar", $coreJarName)
        $runBat = ($runBat -replace "`r?`n", "`r`n")
        [System.IO.File]::WriteAllText((Join-Path $OutputDir "run.bat"), $runBat, $Utf8NoBom)

        $runSh = @'
#!/usr/bin/env sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$APP_HOME"

exec java \
  -Dfile.encoding=UTF-8 \
  "-Dpixivdownload.plugins-dir=$APP_HOME/plugins" \
  -jar "$APP_HOME/PixivDownload-VERSION.jar" \
  "$@"
'@
        $runSh = $runSh.Replace("PixivDownload-VERSION.jar", $coreJarName)
        $runSh = ($runSh -replace "`r?`n", "`n")
        [System.IO.File]::WriteAllText((Join-Path $OutputDir "run.sh"), $runSh, $Utf8NoBom)
        Write-Host "    OK: run.bat (CRLF) and run.sh (LF) reference the exact $coreJarName." -ForegroundColor Green
    }

    Write-Step "Done"
    Assert-NoPrivateKeyMaterial $OutputDir
    Write-Host "Distribution : $OutputDir"
    if ($CoreShellOnly) {
        Write-Host "Core jar     : $coreJarName  (core shell only - missing required plugin enters recovery mode)"
        Write-Host "Plugins      : none staged"
    } else {
        $requiredCount = @($DistributionPlugins | Where-Object { $requiredPluginIds -contains $_.Id }).Count
        $optionalCount = $DistributionPlugins.Count - $requiredCount
        if ($DefaultDownloader) {
            Write-Host "Core jar     : $coreJarName  (default downloader; plugins/ carries all user-facing official plugins except Douyin)"
        } else {
            Write-Host "Core jar     : $coreJarName  (full offline; plugins/ carries required and optional plugins)"
        }
        Write-Host "Plugins      : $requiredCount required + $optionalCount optional plugin(s) staged under plugins/"
    }
    if (-not $CoreShellOnly) {
        Write-Host "Checksums    : SHA256SUMS + per-plugin .sha256 + .sig + provenance sidecar + plugins-manifest.json"
        Write-Host "Launch       : run.bat (Windows) / sh run.sh (Linux/macOS) from the distribution directory"
    }
    Write-Host ""
    Write-Host "Run: cd `"$OutputDir`" && java -jar $coreJarName"
} finally {
    Pop-Location
}
