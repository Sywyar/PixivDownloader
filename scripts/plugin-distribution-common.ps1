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

# Official optional external plugins (id / Maven module). recovery-sentinel is a recovery-mode
# validation fixture, not a user-facing official plugin, so it is only ever included on demand
# (assembler -IncludeSentinel) and never bundled into user-facing portable / installer packages.
function Get-OfficialOptionalPlugins {
    [CmdletBinding()]
    param([switch]$IncludeSentinel)
    $plugins = @(
        [pscustomobject]@{ Id = "stats"; Module = "pixivdownload-plugin-stats" }
    )
    if ($IncludeSentinel) {
        $plugins += [pscustomobject]@{ Id = "recovery-sentinel"; Module = "pixivdownload-plugin-recovery-sentinel" }
    }
    return $plugins
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
    & java "-cp" $ToolJar "top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool" @Arguments
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
