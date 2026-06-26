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
          <plugin>-<version>.jar                 # official optional external plugin (thin jar, root
                                                 #   plugin.properties)
          <plugin>-<version>.jar.sha256          # per-package sha256 checksum file
        SHA256SUMS                               # aggregate checksum file (sha256sum -c compatible)
        plugins-manifest.json                    # per external plugin: id / version / requires / file / sha256

    The "default downloader jar" alone completes the basic download workflow (the download workbench is a
    built-in required plugin carried in the boot jar). Shipping the whole <OutputDir> together is the
    "full offline" bundle: run `java -jar PixivDownload-<Version>.jar` from that directory and the runtime
    loads these external plugins from the working-directory plugins/ folder.

    The script self-checks each plugin jar for the thin form (root plugin.properties; no BOOT-INF/; no
    bundled PF4J / Spring / shared-contract classes) and the boot jar for the distribution boundary (no
    external plugin classes / static / i18n; PF4J only nested under BOOT-INF/lib). Any broken invariant
    aborts with an error.

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
    [switch]$IncludeSentinel
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot "build/dist"
}
$PluginsOutDir = Join-Path $OutputDir "plugins"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# Official optional external plugins (id / Maven module). recovery-sentinel only when -IncludeSentinel.
$OptionalPlugins = @(
    [pscustomobject]@{ Id = "stats"; Module = "pixivdownload-plugin-stats" }
)
if ($IncludeSentinel) {
    $OptionalPlugins += [pscustomobject]@{ Id = "recovery-sentinel"; Module = "pixivdownload-plugin-recovery-sentinel" }
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

function Get-Sha256Hex {
    param([string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Get-ZipEntryNames {
    param([string]$Path)
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        return @($archive.Entries | ForEach-Object { $_.FullName })
    } finally {
        $archive.Dispose()
    }
}

function Read-PluginDescriptor {
    param([string]$JarPath)
    $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $archive.GetEntry("plugin.properties")
        if (-not $entry) { return $null }
        $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally {
        $archive.Dispose()
    }
    $props = @{}
    foreach ($line in ($text -split "`r?`n")) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
        $idx = $trimmed.IndexOf("=")
        if ($idx -lt 1) { continue }
        $props[$trimmed.Substring(0, $idx).Trim()] = $trimmed.Substring($idx + 1).Trim()
    }
    return $props
}

function Find-ModuleJar {
    param([string]$Module)
    $targetDir = Join-Path $ProjectRoot "$Module/target"
    if (-not (Test-Path $targetDir)) {
        throw "Module target not built: $targetDir (run with -Build or 'mvn package' first)."
    }
    $jar = Get-ChildItem (Join-Path $targetDir "$Module-*.jar") -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) {
        throw "Could not find built jar under $targetDir for module $Module."
    }
    return $jar.FullName
}

function Assert-ThinPluginJar {
    param([string]$JarPath, [string]$ExpectedId)
    $entries = Get-ZipEntryNames $JarPath
    if ($entries -notcontains "plugin.properties") {
        throw "Plugin jar is not a valid PF4J package (missing root plugin.properties): $JarPath"
    }
    foreach ($prefix in @("BOOT-INF/", "org/pf4j/", "org/springframework/", "top/sywyar/pixivdownload/plugin/api/")) {
        $leaked = $entries | Where-Object { $_.StartsWith($prefix) }
        if ($leaked) {
            throw "Plugin jar is not thin - leaked '$prefix' entries (deps must be provided): $JarPath"
        }
    }
    $descriptor = Read-PluginDescriptor $JarPath
    if (-not $descriptor -or -not $descriptor["plugin.id"]) {
        throw "Plugin jar plugin.properties missing plugin.id: $JarPath"
    }
    if ($descriptor["plugin.id"] -ne $ExpectedId) {
        throw "Plugin jar declares id '$($descriptor['plugin.id'])' but expected '$ExpectedId': $JarPath"
    }
    return $descriptor
}

function Assert-BootJarBoundary {
    param([string]$JarPath)
    $entries = Get-ZipEntryNames $JarPath
    $forbidden = @(
        "BOOT-INF/classes/top/sywyar/pixivdownload/stats/",
        "BOOT-INF/classes/top/sywyar/pixivdownload/recoverysentinel/",
        "BOOT-INF/classes/static/pixiv-stats",
        "BOOT-INF/classes/i18n/web/stats",
        "BOOT-INF/classes/org/pf4j/"
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
        $sourceJar = Find-ModuleJar $plugin.Module
        $descriptor = Assert-ThinPluginJar $sourceJar $plugin.Id
        $pluginVersion = $descriptor["plugin.version"]
        $requires = $descriptor["plugin.requires"]

        $targetName = "$($plugin.Module)-$pluginVersion.jar"
        $targetJar = Join-Path $PluginsOutDir $targetName
        Copy-Item $sourceJar $targetJar -Force

        $sha = Get-Sha256Hex $targetJar
        [System.IO.File]::WriteAllText("$targetJar.sha256", "$sha  $targetName`n", $Utf8NoBom)

        $manifest += [ordered]@{
            id       = $plugin.Id
            version  = $pluginVersion
            requires = $requires
            file     = "plugins/$targetName"
            sha256   = $sha
        }
        $sumLines += "$sha  plugins/$targetName"
        Write-Host "    OK: thin plugin jar staged ($targetName, sha256 $sha)." -ForegroundColor Green
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
    Write-Host "Distribution : $OutputDir"
    Write-Host "Core jar     : $coreJarName  (default downloader - built-in download workbench)"
    Write-Host "Plugins      : $($OptionalPlugins.Count) optional plugin(s) staged under plugins/"
    Write-Host "Checksums    : SHA256SUMS + per-plugin .sha256 + plugins-manifest.json"
    Write-Host ""
    Write-Host "Full offline run: cd `"$OutputDir`" && java -jar $coreJarName"
} finally {
    Pop-Location
}
