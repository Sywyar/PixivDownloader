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
                                                 # official required plugin; default downloader needs it
            <plugin>-<version>.jar               # official optional external plugin (full offline)
            <plugin>-<version>.jar.sha256        # per-package sha256 checksum file
            provenance/
              <plugin>-<version>.jar.pixiv-plugin-provenance
          SHA256SUMS                             # aggregate checksum file (sha256sum -c compatible)
          plugins-manifest.json                  # per external plugin: id / version / requires / file / sha256

    The boot jar alone is the core-shell package and must enter recovery/repair mode because the required
    download-workbench plugin is missing. The default downloader is the boot jar plus only the required
    download-workbench artifact under plugins/. The full-offline bundle adds all official optional plugins:
    run `java -jar PixivDownload-<Version>.jar` from that directory and the runtime loads external plugins
    from the working-directory plugins/ folder.

    The script self-checks official plugins for their declared form (thin jar or jar with private lib/*.jar)
    and the boot jar for the distribution boundary (no external plugin classes / static / i18n; PF4J only
    nested under BOOT-INF/lib). Any broken invariant aborts with an error.

.PARAMETER CoreShellOnly
    Produce only the core-shell boot jar, without staging required or optional plugins.
    The result is a recovery/repair package, not the normal default downloader.

.PARAMETER DefaultDownloader
    Stage only official required plugins. Without this switch, the script stages required and optional
    official plugins as the full-offline distribution.

.PARAMETER Version
    Distribution version, used for the core jar file name. Default 0.0.1-local.

.PARAMETER OutputDir
    Distribution output directory. Default <repo>/build/dist.

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
$PluginsOutDir = Join-Path $OutputDir "plugins"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
if (-not $CoreShellOnly -and [string]::IsNullOrWhiteSpace($OfficialKeyId)) { throw "OfficialKeyId is required." }
if (-not $CoreShellOnly -and ([string]::IsNullOrWhiteSpace($PrivateKeyFile) -or -not (Test-Path -LiteralPath $PrivateKeyFile -PathType Leaf))) {
    throw "PrivateKeyFile is required and must point to an Ed25519 PKCS#8 PEM file."
}
if ($CoreShellOnly -and $DefaultDownloader) {
    throw "CoreShellOnly and DefaultDownloader cannot be combined."
}
if (-not $CoreShellOnly) {
    $SignatureToolJar = Resolve-SignatureToolJar $ProjectRoot $SignatureToolJar
}

# Official external plugins (required + optional). recovery-sentinel only when -IncludeSentinel.
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

function Assert-SafeRemovableDir {
    # Guard before recursively deleting the output dir: refuse a drive/filesystem root, the
    # repository root, and any ancestor of it, so a mistyped -OutputDir can never wipe an
    # unrelated tree. Returns the resolved absolute path.
    param([string]$Path, [string]$RepoRoot)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "Output dir path is empty; refusing to delete."
    }
    $full = [System.IO.Path]::GetFullPath($Path)
    $parent = [System.IO.Path]::GetDirectoryName($full)
    if ([string]::IsNullOrEmpty($parent)) {
        throw "Refusing to use a drive/filesystem root as the output dir: $full"
    }
    $sep = [System.IO.Path]::DirectorySeparatorChar
    $fullTrimmed = $full.TrimEnd($sep, '/')
    $repoTrimmed = ([System.IO.Path]::GetFullPath($RepoRoot)).TrimEnd($sep, '/')
    if ($fullTrimmed.Equals($repoTrimmed, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to use the repository root as the output dir: $full"
    }
    if ($repoTrimmed.StartsWith($fullTrimmed + $sep, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to use an ancestor of the repository root as the output dir: $full"
    }
    return $full
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
        "BOOT-INF/classes/static/pixiv-batch.html",
        "BOOT-INF/classes/static/pixiv-batch",
        "BOOT-INF/classes/static/userscripts/",
        "BOOT-INF/classes/static/pixiv-tts",
        "BOOT-INF/classes/static/pixiv-ai",
        "BOOT-INF/classes/i18n/web/stats",
        "BOOT-INF/classes/i18n/web/batch",
        "BOOT-INF/classes/i18n/web/userscript",
        "BOOT-INF/classes/i18n/web/gui-theme",
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
        "BOOT-INF/lib/jakarta.mail-",
        "BOOT-INF/lib/jakarta.activation-api-",
        "BOOT-INF/lib/angus-activation-",
        "BOOT-INF/lib/spring-context-support-"
    )
    foreach ($prefix in $forbidden) {
        $leaked = $entries | Where-Object { $_.StartsWith($prefix) }
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

Push-Location $ProjectRoot
try {
    if ($Build) {
        Write-Step "Building reactor (mvn package -DskipTests)"
        $mvn = Get-MavenCommand
        & $mvn "package" "-DskipTests" "-Dexec.skip=true" "-Dapp.release.version=$Version"
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }
    }

    Write-Step "Locating built artifacts"
    $bootJar = Get-AppBootJar
    if (-not $bootJar) {
        throw "Could not find boot jar under pixivdownload-app/target/ (run with -Build or 'mvn package' first)."
    }

    Write-Step "Verifying boot jar distribution boundary"
    Assert-BootJarBoundary $bootJar.FullName
    Write-Host "    OK: boot jar contains core + built-in plugins, excludes external plugin classes/resources." -ForegroundColor Green

    Write-Step "Staging distribution to $OutputDir"
    [void](Assert-SafeRemovableDir $OutputDir $ProjectRoot)
    if (Test-Path $OutputDir) { Remove-Item -Recurse -Force -LiteralPath $OutputDir }
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
    if (-not $CoreShellOnly) {
        New-Item -ItemType Directory -Force -Path $PluginsOutDir | Out-Null
    }

    $coreJarName = "PixivDownload-$Version.jar"
    Copy-Item $bootJar.FullName (Join-Path $OutputDir $coreJarName) -Force

    $manifest = @()
    $sumLines = @()
    $requiredPluginIds = @(Get-OfficialRequiredPlugins | ForEach-Object { $_.Id })
    foreach ($plugin in $DistributionPlugins) {
        Write-Step "Staging plugin '$($plugin.Id)'"
        $sourceArtifact = Find-ModulePluginArtifact $plugin $ProjectRoot
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
        $signature = New-PluginArtifactSignature $SignatureToolJar $targetArtifact $plugin.Id $pluginVersion `
            $OfficialKeyId $PrivateKeyFile "$targetArtifact.sig"
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
            Write-Host "Core jar     : $coreJarName  (default downloader; plugins/ carries required plugins)"
        } else {
            Write-Host "Core jar     : $coreJarName  (full offline; plugins/ carries required and optional plugins)"
        }
        Write-Host "Plugins      : $requiredCount required + $optionalCount optional plugin(s) staged under plugins/"
    }
    if (-not $CoreShellOnly) {
        Write-Host "Checksums    : SHA256SUMS + per-plugin .sha256 + .sig + provenance sidecar + plugins-manifest.json"
    }
    Write-Host ""
    Write-Host "Run: cd `"$OutputDir`" && java -jar $coreJarName"
} finally {
    Pop-Location
}
