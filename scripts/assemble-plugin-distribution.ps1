<#
.SYNOPSIS
    Assemble the plugin distribution: core/default-downloader jar + official optional external plugin
    thin jars (+ per-package sha256 + aggregate manifest), producing a ready-to-run "full offline" layout.

.DESCRIPTION
    One command that consolidates the plugin distribution boundary:

      <OutputDir>/
        PixivDownload-<Version>.jar              # core shell + built-in plugins (incl. required download
                                                 #   workbench) = default downloader jar
        plugins/
          <plugin>-<version>.(jar|zip)           # official optional external plugin
          <plugin>-<version>.(jar|zip).sha256    # per-package sha256 checksum file
        SHA256SUMS                               # aggregate checksum file (sha256sum -c compatible)
        plugins-manifest.json                    # per external plugin: id / version / requires / file / sha256

    The "default downloader jar" alone completes the basic download workflow (the download workbench is a
    built-in required plugin carried in the boot jar). Shipping the whole <OutputDir> together is the
    "full offline" bundle: run `java -jar PixivDownload-<Version>.jar` from that directory and the runtime
    loads these external plugins from the working-directory plugins/ folder.

    The script self-checks official plugins for their declared form (thin jar or exploded-directory zip)
    and the boot jar for the distribution boundary (no external plugin classes / static / i18n; PF4J only
    nested under BOOT-INF/lib). Any broken invariant aborts with an error.

    Note: the download workbench is still a built-in required plugin compiled into the boot jar (not yet
    physically externalized into its own plugin jar), so a "core shell only, missing download workbench
    -> recovery mode" artifact cannot yet be produced as a standalone output. This script produces the
    "default downloader jar" and the "default downloader + official optional plugins" full-offline layout
    against the current state.

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
    [switch]$IncludeSentinel,
    [string]$OfficialKeyId,
    [string]$PrivateKeyFile,
    [string]$SignatureToolJar
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

# Shared official-plugin list + thin-jar / checksum primitives (one source of distribution truth).
. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot "build/dist"
}
$PluginsOutDir = Join-Path $OutputDir "plugins"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
if ([string]::IsNullOrWhiteSpace($OfficialKeyId)) { throw "OfficialKeyId is required." }
if ([string]::IsNullOrWhiteSpace($PrivateKeyFile) -or -not (Test-Path -LiteralPath $PrivateKeyFile -PathType Leaf)) {
    throw "PrivateKeyFile is required and must point to an Ed25519 PKCS#8 PEM file."
}
$SignatureToolJar = Resolve-SignatureToolJar $ProjectRoot $SignatureToolJar

# Official optional external plugins (id / Maven module). recovery-sentinel only when -IncludeSentinel.
# Wrap in @() so a single-element result keeps array shape (the function return unwraps it otherwise),
# preserving $OptionalPlugins.Count for the summary line.
$OptionalPlugins = @(Get-OfficialOptionalPlugins -IncludeSentinel:$IncludeSentinel)

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
        "BOOT-INF/classes/top/sywyar/pixivdownload/stats/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/guitheme/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/recoverysentinel/",
        "BOOT-INF/classes/static/pixiv-stats",
        "BOOT-INF/classes/i18n/web/stats",
        "BOOT-INF/classes/i18n/web/gui-theme",
        "BOOT-INF/classes/org/pf4j/",
        "BOOT-INF/lib/flatlaf-",
        "BOOT-INF/lib/flatlaf-intellij-themes-",
        "BOOT-INF/lib/jna-",
        "BOOT-INF/lib/jna-platform-"
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

Push-Location $ProjectRoot
try {
    if ($Build) {
        Write-Step "Building reactor (mvn package -DskipTests)"
        $mvn = Get-MavenCommand
        & $mvn "package" "-DskipTests" "-Dexec.skip=true" "-Dapp.release.version=$Version"
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }
    }

    Write-Step "Locating built artifacts"
    $bootJar = Get-ChildItem (Join-Path $ProjectRoot "pixivdownload-app/target/PixivDownload-*.jar") -File |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $bootJar) {
        throw "Could not find boot jar under pixivdownload-app/target/ (run with -Build or 'mvn package' first)."
    }

    Write-Step "Verifying boot jar distribution boundary"
    Assert-BootJarBoundary $bootJar.FullName
    Write-Host "    OK: boot jar contains core + built-in plugins, excludes external plugin classes/resources." -ForegroundColor Green

    Write-Step "Staging distribution to $OutputDir"
    [void](Assert-SafeRemovableDir $OutputDir $ProjectRoot)
    if (Test-Path $OutputDir) { Remove-Item -Recurse -Force -LiteralPath $OutputDir }
    New-Item -ItemType Directory -Force -Path $PluginsOutDir | Out-Null

    $coreJarName = "PixivDownload-$Version.jar"
    Copy-Item $bootJar.FullName (Join-Path $OutputDir $coreJarName) -Force

    $manifest = @()
    $sumLines = @()
    foreach ($plugin in $OptionalPlugins) {
        Write-Step "Staging plugin '$($plugin.Id)'"
        $sourceArtifact = Find-ModulePluginArtifact $plugin $ProjectRoot
        $descriptor = Assert-OfficialPluginArtifact $sourceArtifact $plugin
        $pluginVersion = $descriptor["plugin.version"]
        $requires = $descriptor["plugin.requires"]
        $extension = if ($plugin.Format -eq "zip") { "zip" } else { "jar" }

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
            file     = "plugins/$targetName"
            sha256   = $sha
            signature = $signature
        }
        $sumLines += "$sha  plugins/$targetName"
        Write-Host "    OK: official plugin artifact staged ($targetName, sha256 $sha)." -ForegroundColor Green
    }

    # Include the core jar in the aggregate checksum file too.
    $coreSha = Get-Sha256Hex (Join-Path $OutputDir $coreJarName)
    $sumLines = @("$coreSha  $coreJarName") + $sumLines
    [System.IO.File]::WriteAllText((Join-Path $OutputDir "SHA256SUMS"), (($sumLines -join "`n") + "`n"), $Utf8NoBom)

    # ConvertTo-Json with an explicit @() wrapper preserves the array shape for a single
    # element on Windows PowerShell 5.1 (the bare-object quirk only affects pipeline input).
    $manifestJson = ConvertTo-Json @($manifest) -Depth 5
    [System.IO.File]::WriteAllText((Join-Path $OutputDir "plugins-manifest.json"), $manifestJson + "`n", $Utf8NoBom)

    Write-Step "Done"
    Assert-NoPrivateKeyMaterial $OutputDir
    Write-Host "Distribution : $OutputDir"
    Write-Host "Core jar     : $coreJarName  (default downloader - built-in download workbench)"
    Write-Host "Plugins      : $($OptionalPlugins.Count) optional plugin(s) staged under plugins/"
    Write-Host "Checksums    : SHA256SUMS + per-plugin .sha256 + .sig + provenance sidecar + plugins-manifest.json"
    Write-Host ""
    Write-Host "Full offline run: cd `"$OutputDir`" && java -jar $coreJarName"
} finally {
    Pop-Location
}
