<#
.SYNOPSIS
    Shared plugin-distribution primitives reused by the distribution assembler and the Windows
    portable / installer packager, so both consume one source of "official optional plugin" truth
    and one set of thin-jar / checksum helpers (no duplicated release semantics).

.DESCRIPTION
    Dot-source this file from a packaging script:

        . (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

    It defines the canonical official optional external plugin list plus the thin-jar inspection and
    checksum helpers. It performs no work on its own and writes nothing to disk.

    ASCII-only (no BOM, English comments): these scripts run under Windows powershell(5.1); non-ASCII
    without a BOM is decoded with the system ANSI code page and fails to parse (same constraint as the
    other scripts/*.ps1).
#>

# Official optional external plugins (id / Maven module / artifact format). recovery-sentinel is a recovery-mode
# validation fixture, not a user-facing official plugin, so it is only ever included on demand
# (assembler -IncludeSentinel) and never bundled into user-facing portable / installer packages.
function Get-OfficialOptionalPlugins {
    [CmdletBinding()]
    param([switch]$IncludeSentinel)
    $plugins = @(
        [pscustomobject]@{ Id = "gui-theme"; Module = "pixivdownload-plugin-gui-theme"; Format = "zip" },
        [pscustomobject]@{ Id = "stats"; Module = "pixivdownload-plugin-stats"; Format = "jar" }
    )
    if ($IncludeSentinel) {
        $plugins += [pscustomobject]@{ Id = "recovery-sentinel"; Module = "pixivdownload-plugin-recovery-sentinel"; Format = "jar" }
    }
    return $plugins
}

function Get-OfficialPluginArtifactExtension {
    param([Parameter(Mandatory = $true)]$Plugin)
    $format = if ($Plugin.Format) { $Plugin.Format } else { "jar" }
    if ($format -eq "jar") { return "jar" }
    if ($format -eq "zip") { return "zip" }
    throw "Unsupported official plugin artifact format '$format' for $($Plugin.Id)."
}

function Get-OfficialPluginArtifactName {
    param(
        [Parameter(Mandatory = $true)]$Plugin,
        [Parameter(Mandatory = $true)][string]$Version
    )
    $extension = Get-OfficialPluginArtifactExtension $Plugin
    return "$($Plugin.Module)-$Version.$extension"
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Resolve-SignatureToolJar {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [string]$SignatureToolJar
    )
    if ($SignatureToolJar) {
        if (-not (Test-Path -LiteralPath $SignatureToolJar -PathType Leaf)) {
            throw "Signature tool jar not found: $SignatureToolJar"
        }
        return (Resolve-Path -LiteralPath $SignatureToolJar).Path
    }
    $targetDir = Join-Path $ProjectRoot "pixivdownload-plugin-signature/target"
    $jar = Get-ChildItem (Join-Path $targetDir "pixivdownload-plugin-signature-*.jar") -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) {
        throw "Could not find signature tool jar under $targetDir. Build pixivdownload-plugin-signature first or pass -SignatureToolJar."
    }
    return $jar.FullName
}

function Invoke-PluginSignatureTool {
    param(
        [Parameter(Mandatory = $true)][string]$ToolJar,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    & java "-cp" $ToolJar "top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool" @Arguments |
        ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        throw "Plugin signature tool failed."
    }
}

function New-PluginArtifactSignature {
    param(
        [Parameter(Mandatory = $true)][string]$ToolJar,
        [Parameter(Mandatory = $true)][string]$ArtifactPath,
        [Parameter(Mandatory = $true)][string]$PluginId,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$KeyId,
        [Parameter(Mandatory = $true)][string]$PrivateKeyFile,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    Invoke-PluginSignatureTool $ToolJar @(
        "artifact",
        "--artifact", $ArtifactPath,
        "--plugin-id", $PluginId,
        "--version", $Version,
        "--key-id", $KeyId,
        "--private-key", $PrivateKeyFile,
        "--out", $OutputPath
    )
    return (Get-Content -LiteralPath $OutputPath -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Write-PluginProvenanceSidecar {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactPath,
        [Parameter(Mandatory = $true)][long]$ExpectedSizeBytes,
        [Parameter(Mandatory = $true)][string]$Sha256,
        [Parameter(Mandatory = $true)]$Signature,
        [Parameter(Mandatory = $true)][string]$VerifiedAt
    )
    $sidecar = "$ArtifactPath.pixiv-plugin-provenance"
    $lines = @(
        "formatVersion=1",
        "source=MARKET_CATALOG",
        "repositoryId=official",
        "officialRepository=true",
        "expectedSizeBytes=$ExpectedSizeBytes",
        "expectedSha256=$Sha256",
        "signature.formatVersion=$($Signature.formatVersion)",
        "signature.algorithm=$($Signature.algorithm)",
        "signature.keyId=$($Signature.keyId)",
        "signature.value=$($Signature.value)",
        "status=VERIFIED",
        "keyId=$($Signature.keyId)",
        "publisher=PixivDownloader",
        "trustLabel=PixivDownloader official plugin root",
        "verifiedAt=$VerifiedAt",
        "diagnosticCode=VERIFIED"
    )
    [System.IO.File]::WriteAllText($sidecar, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
    return $sidecar
}

function Assert-NoPrivateKeyMaterial {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $hits = Get-ChildItem -LiteralPath $Path -Recurse -File -ErrorAction SilentlyContinue |
        Select-String -SimpleMatch "BEGIN PRIVATE KEY" -List -ErrorAction SilentlyContinue
    if ($hits) {
        throw "Private key material found under output directory: $($hits[0].Path)"
    }
}

function Import-ZipFileAssembly {
    if (([System.Management.Automation.PSTypeName]"System.IO.Compression.ZipFile").Type) {
        return
    }

    foreach ($assemblyName in @(
        "System.IO.Compression.FileSystem",
        "System.IO.Compression.ZipFile",
        "System.IO.Compression"
    )) {
        try {
            Add-Type -AssemblyName $assemblyName -ErrorAction Stop
            if (([System.Management.Automation.PSTypeName]"System.IO.Compression.ZipFile").Type) {
                return
            }
        } catch {
            # Try the next assembly name.
        }
    }

    throw "Unable to load System.IO.Compression.ZipFile assembly."
}

function Get-ZipEntryNames {
    param([Parameter(Mandatory = $true)][string]$Path)
    Import-ZipFileAssembly
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        return @($archive.Entries | ForEach-Object { $_.FullName })
    } finally {
        $archive.Dispose()
    }
}

function Read-PluginDescriptor {
    param([Parameter(Mandatory = $true)][string]$JarPath)
    Import-ZipFileAssembly
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
    # Locate a reactor module's built jar under <ProjectRoot>/<Module>/target (excludes sources/javadoc).
    param(
        [Parameter(Mandatory = $true)][string]$Module,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
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

function Find-ModulePluginArtifact {
    param(
        [Parameter(Mandatory = $true)]$Plugin,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $format = if ($Plugin.Format) { $Plugin.Format } else { "jar" }
    if ($format -eq "jar") {
        return Find-ModuleJar $Plugin.Module $ProjectRoot
    }
    if ($format -ne "zip") {
        throw "Unsupported official plugin artifact format '$format' for $($Plugin.Id)."
    }
    $targetDir = Join-Path $ProjectRoot "$($Plugin.Module)/target"
    if (-not (Test-Path $targetDir)) {
        throw "Module target not built: $targetDir (run with -Build or 'mvn package' first)."
    }
    $zip = Get-ChildItem (Join-Path $targetDir "$($Plugin.Module)-*.zip") -File -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    if (-not $zip) {
        throw "Could not find built zip under $targetDir for module $($Plugin.Module)."
    }
    return $zip.FullName
}

function Assert-ThinPluginJar {
    # Verify a PF4J plugin jar is "thin": root plugin.properties, no BOOT-INF/, no bundled PF4J /
    # Spring / shared-contract classes (deps must be provided by the host), and the declared
    # plugin.id matches what we expect. Returns the parsed plugin.properties map.
    param(
        [Parameter(Mandatory = $true)][string]$JarPath,
        [Parameter(Mandatory = $true)][string]$ExpectedId
    )
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

function Assert-ExplodedPluginZip {
    # Verify a PF4J exploded-directory zip: root plugin.properties, classes/, lib/*.jar, no root
    # plugin jar, and runtime-only GUI theme dependencies present under lib/.
    param(
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$ExpectedId
    )
    $entries = Get-ZipEntryNames $ZipPath
    if ($entries -notcontains "plugin.properties") {
        throw "Plugin zip is not a PF4J exploded-directory package (missing root plugin.properties): $ZipPath"
    }
    $rootJars = $entries | Where-Object { $_ -match "^[^/]+\.jar$" }
    if ($rootJars) {
        throw "Plugin zip must not contain root plugin jars in exploded-directory layout: $ZipPath"
    }
    if (-not ($entries | Where-Object { $_.StartsWith("classes/") })) {
        throw "Plugin zip missing classes/ payload: $ZipPath"
    }
    $libJars = @($entries | Where-Object { $_ -match "^lib/[^/]+\.jar$" })
    if (-not $libJars) {
        throw "Plugin zip missing lib/*.jar payload: $ZipPath"
    }
    foreach ($required in @("flatlaf-", "flatlaf-intellij-themes-", "jna-", "jna-platform-")) {
        $match = $libJars | Where-Object { (Split-Path $_ -Leaf).StartsWith($required) }
        if (-not $match) {
            throw "Plugin zip missing required dependency in lib/: $required ($ZipPath)"
        }
    }
    foreach ($prefix in @("BOOT-INF/", "classes/top/sywyar/pixivdownload/plugin/api/")) {
        $leaked = $entries | Where-Object { $_.StartsWith($prefix) }
        if ($leaked) {
            throw "Plugin zip leaked forbidden '$prefix' entries: $ZipPath"
        }
    }
    $descriptor = Read-PluginDescriptor $ZipPath
    if (-not $descriptor -or -not $descriptor["plugin.id"]) {
        throw "Plugin zip plugin.properties missing plugin.id: $ZipPath"
    }
    if ($descriptor["plugin.id"] -ne $ExpectedId) {
        throw "Plugin zip declares id '$($descriptor['plugin.id'])' but expected '$ExpectedId': $ZipPath"
    }
    return $descriptor
}

function Assert-OfficialPluginArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactPath,
        [Parameter(Mandatory = $true)]$Plugin
    )
    $format = if ($Plugin.Format) { $Plugin.Format } else { "jar" }
    if ($format -eq "jar") {
        return Assert-ThinPluginJar $ArtifactPath $Plugin.Id
    }
    if ($format -eq "zip") {
        return Assert-ExplodedPluginZip $ArtifactPath $Plugin.Id
    }
    throw "Unsupported official plugin artifact format '$format' for $($Plugin.Id)."
}
