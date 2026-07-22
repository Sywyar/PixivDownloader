<#
.SYNOPSIS
    Shared plugin-distribution primitives reused by the distribution assembler and the Windows
    portable / installer packager, so both consume one source of "official optional plugin" truth
    and one set of plugin-jar / checksum helpers (no duplicated release semantics).

.DESCRIPTION
    Dot-source this file from a packaging script:

        . (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

    It defines the canonical official external plugin sets plus the plugin-jar inspection and
    checksum helpers. It performs no work on its own and writes nothing to disk.

    ASCII-only (no BOM, English comments): these scripts run under Windows powershell(5.1); non-ASCII
    without a BOM is decoded with the system ANSI code page and fails to parse (same constraint as the
    other scripts/*.ps1).
#>

# Official required external plugins (id / Maven module / artifact format). Required is a runtime/recovery
# policy: only these plugins may force the core shell into recovery mode when missing.
function Get-OfficialRequiredPlugins {
    [CmdletBinding()]
    param()
    return @(
        [pscustomobject]@{ Id = "download-workbench"; Module = "pixivdownload-plugin-download-workbench"; Format = "jar"; PrivateLibs = $false }
    )
}

# Official plugins installed into the default build artifacts. They remain separate PF4J artifacts and retain
# their runtime required/optional semantics; this list only controls build-time staging. Douyin is intentionally
# excluded and remains available on demand from the plugin market / full-offline distribution.
function Get-OfficialDefaultInstalledPlugins {
    [CmdletBinding()]
    param()
    $plugins = @(Get-OfficialRequiredPlugins)
    $plugins += @(
        [pscustomobject]@{
            Id = "gui-theme"; Module = "pixivdownload-plugin-gui-theme"; Format = "jar"; PrivateLibs = $true;
            ClassPrefix = "top/sywyar/pixivdownload/guitheme/";
            RequiredLibPatterns = @(
                "^flatlaf-[0-9].*\.jar$",
                "^flatlaf-intellij-themes-[0-9].*\.jar$",
                "^jna-[0-9].*\.jar$",
                "^jna-platform-[0-9].*\.jar$"
            )
        },
        [pscustomobject]@{ Id = "stats"; Module = "pixivdownload-plugin-stats"; Format = "jar"; PrivateLibs = $false },
        [pscustomobject]@{ Id = "duplicate"; Module = "pixivdownload-plugin-duplicate"; Format = "jar"; PrivateLibs = $false },
        [pscustomobject]@{ Id = "gallery"; Module = "pixivdownload-plugin-gallery"; Format = "jar"; PrivateLibs = $false },
        [pscustomobject]@{ Id = "novel"; Module = "pixivdownload-plugin-novel"; Format = "jar"; PrivateLibs = $false },
        [pscustomobject]@{ Id = "notification"; Module = "pixivdownload-plugin-notification"; Format = "jar"; PrivateLibs = $false },
        [pscustomobject]@{ Id = "push"; Module = "pixivdownload-plugin-push"; Format = "jar"; PrivateLibs = $false },
        [pscustomobject]@{
            Id = "mail"; Module = "pixivdownload-plugin-mail"; Format = "jar"; PrivateLibs = $true;
            ClassPrefix = "top/sywyar/pixivdownload/mail/";
            RequiredLibPatterns = @(
                "^spring-context-support-[0-9].*\.jar$",
                "^jakarta\.mail-[0-9].*\.jar$",
                "^jakarta\.activation-api-[0-9].*\.jar$",
                "^angus-activation-[0-9].*\.jar$"
            )
        },
        [pscustomobject]@{ Id = "tts"; Module = "pixivdownload-plugin-tts"; Format = "jar"; PrivateLibs = $false },
        [pscustomobject]@{ Id = "ai"; Module = "pixivdownload-plugin-ai"; Format = "jar"; PrivateLibs = $false }
    )
    return $plugins
}

# Official plugins not installed by the default build. recovery-sentinel is a recovery-mode validation fixture,
# not a user-facing official plugin, so it is only ever included on demand (assembler -IncludeSentinel).
function Get-OfficialOptionalPlugins {
    [CmdletBinding()]
    param([switch]$IncludeSentinel)
    $plugins = @(
        [pscustomobject]@{ Id = "douyin"; Module = "pixivdownload-plugin-douyin"; Format = "jar"; PrivateLibs = $false }
    )
    if ($IncludeSentinel) {
        $plugins += [pscustomobject]@{ Id = "recovery-sentinel"; Module = "pixivdownload-plugin-recovery-sentinel"; Format = "jar"; PrivateLibs = $false }
    }
    return $plugins
}

function Get-OfficialDistributionPlugins {
    [CmdletBinding()]
    param(
        [switch]$IncludeOptional,
        [switch]$IncludeSentinel
    )
    $plugins = @(Get-OfficialDefaultInstalledPlugins)
    if ($IncludeOptional) {
        $plugins += @(Get-OfficialOptionalPlugins -IncludeSentinel:$IncludeSentinel)
    } elseif ($IncludeSentinel) {
        $plugins += @(Get-OfficialOptionalPlugins -IncludeSentinel:$IncludeSentinel |
            Where-Object { $_.Id -eq "recovery-sentinel" })
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

function Find-PluginArtifactSignatureSidecar {
    param([Parameter(Mandatory = $true)][string]$ArtifactPath)
    foreach ($candidate in @("$ArtifactPath.sig", "$ArtifactPath.sig.json")) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return ""
}

function Read-PluginSignatureMetadata {
    param([Parameter(Mandatory = $true)][string]$SignaturePath)
    if (-not (Test-Path -LiteralPath $SignaturePath -PathType Leaf)) {
        throw "Plugin signature sidecar not found: $SignaturePath"
    }
    return (Get-Content -LiteralPath $SignaturePath -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Assert-PluginArtifactSignature {
    param(
        [Parameter(Mandatory = $true)][string]$ToolJar,
        [Parameter(Mandatory = $true)][string]$ArtifactPath,
        [Parameter(Mandatory = $true)][string]$SignaturePath,
        [Parameter(Mandatory = $true)][string]$PluginId,
        [Parameter(Mandatory = $true)][string]$Version,
        [long]$ExpectedSizeBytes,
        [string]$Sha256
    )
    if ($ExpectedSizeBytes -le 0) {
        $ExpectedSizeBytes = (Get-Item -LiteralPath $ArtifactPath).Length
    }
    if ([string]::IsNullOrWhiteSpace($Sha256)) {
        $Sha256 = Get-Sha256Hex $ArtifactPath
    }
    Invoke-PluginSignatureTool $ToolJar @(
        "verify-artifact",
        "--artifact", $ArtifactPath,
        "--signature", $SignaturePath,
        "--plugin-id", $PluginId,
        "--version", $Version,
        "--expected-size", ([string]$ExpectedSizeBytes),
        "--sha256", $Sha256,
        "--policy", "official"
    )
    return Read-PluginSignatureMetadata $SignaturePath
}

function Get-PluginArtifactSignatureForDistribution {
    param(
        [Parameter(Mandatory = $true)][string]$ToolJar,
        [Parameter(Mandatory = $true)][string]$ArtifactPath,
        [Parameter(Mandatory = $true)][string]$PluginId,
        [Parameter(Mandatory = $true)][string]$Version,
        [string]$ExistingSignaturePath,
        [string]$OfficialKeyId,
        [string]$PrivateKeyFile,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )
    if (-not [string]::IsNullOrWhiteSpace($ExistingSignaturePath)) {
        $signature = Assert-PluginArtifactSignature $ToolJar $ArtifactPath $ExistingSignaturePath `
            $PluginId $Version (Get-Item -LiteralPath $ArtifactPath).Length (Get-Sha256Hex $ArtifactPath)
        Copy-Item -LiteralPath $ExistingSignaturePath -Destination $OutputPath -Force
        return $signature
    }

    if ([string]::IsNullOrWhiteSpace($OfficialKeyId)) {
        throw "OfficialKeyId is required when a plugin artifact has no .sig sidecar."
    }
    if ([string]::IsNullOrWhiteSpace($PrivateKeyFile) -or -not (Test-Path -LiteralPath $PrivateKeyFile -PathType Leaf)) {
        throw "PrivateKeyFile is required when a plugin artifact has no .sig sidecar."
    }

    return New-PluginArtifactSignature $ToolJar $ArtifactPath $PluginId $Version `
        $OfficialKeyId $PrivateKeyFile $OutputPath
}

function Write-PluginProvenanceSidecar {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactPath,
        [Parameter(Mandatory = $true)][long]$ExpectedSizeBytes,
        [Parameter(Mandatory = $true)][string]$Sha256,
        [Parameter(Mandatory = $true)]$Signature,
        [Parameter(Mandatory = $true)][string]$VerifiedAt
    )
    $artifact = Get-Item -LiteralPath $ArtifactPath
    $artifactSizeBytes = [int64]$artifact.Length
    $artifactSha256 = Get-Sha256Hex $ArtifactPath
    if ($artifactSizeBytes -ne $ExpectedSizeBytes) {
        throw "Artifact size mismatch for provenance: $ArtifactPath"
    }
    if (-not [string]::Equals($artifactSha256, $Sha256, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Artifact SHA-256 mismatch for provenance: $ArtifactPath"
    }
    $provenanceDir = Join-Path $artifact.Directory.FullName "provenance"
    New-Item -ItemType Directory -Force -Path $provenanceDir | Out-Null
    $sidecar = Join-Path $provenanceDir "$($artifact.Name).pixiv-plugin-provenance"
    $lines = @(
        "formatVersion=1",
        "source=MARKET_CATALOG",
        "repositoryId=official",
        "officialRepository=true",
        "expectedSizeBytes=$ExpectedSizeBytes",
        "expectedSha256=$Sha256",
        "artifactSizeBytes=$artifactSizeBytes",
        "artifactSha256=$artifactSha256",
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

function Write-UnsignedLocalPluginProvenanceSidecar {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactPath,
        [Parameter(Mandatory = $true)][string]$VerifiedAt
    )
    $artifact = Get-Item -LiteralPath $ArtifactPath
    $provenanceDir = Join-Path $artifact.Directory.FullName "provenance"
    New-Item -ItemType Directory -Force -Path $provenanceDir | Out-Null
    $sidecar = Join-Path $provenanceDir "$($artifact.Name).pixiv-plugin-provenance"
    $artifactSha256 = Get-Sha256Hex $ArtifactPath
    $lines = @(
        "formatVersion=1",
        "source=LOCAL_UPLOAD",
        "officialRepository=false",
        "artifactSizeBytes=$($artifact.Length)",
        "artifactSha256=$artifactSha256",
        "status=UNSIGNED_ALLOWED",
        "verifiedAt=$VerifiedAt",
        "diagnosticCode=UNSIGNED_ALLOWED"
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
    $privateLibs = $entries | Where-Object { $_ -match "^lib/[^/]+\.jar$" }
    if ($privateLibs) {
        throw "Plugin jar is not thin - found private lib/*.jar entries: $JarPath"
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

function Assert-JarWithPrivatePluginLibs {
    # Verify a PF4J plugin jar with private dependencies: root plugin.properties, plugin classes/resources at
    # normal jar paths, and declared private runtime dependencies present under lib/.
    param(
        [Parameter(Mandatory = $true)][string]$JarPath,
        [Parameter(Mandatory = $true)]$Plugin
    )
    $ExpectedId = $Plugin.Id
    $entries = Get-ZipEntryNames $JarPath
    if ($entries -notcontains "plugin.properties") {
        throw "Plugin jar is not a PF4J package (missing root plugin.properties): $JarPath"
    }
    $classPrefix = if ($Plugin.ClassPrefix) { $Plugin.ClassPrefix } else { "top/sywyar/pixivdownload/" }
    if (-not ($entries | Where-Object { $_.StartsWith($classPrefix) })) {
        throw "Plugin jar missing expected plugin classes '$classPrefix': $JarPath"
    }
    $libJars = @($entries | Where-Object { $_ -match "^lib/[^/]+\.jar$" })
    if (-not $libJars) {
        throw "Plugin jar missing lib/*.jar payload: $JarPath"
    }
    $requiredLibPatterns = @($Plugin.RequiredLibPatterns)
    foreach ($required in $requiredLibPatterns) {
        $match = $libJars | Where-Object { (Split-Path $_ -Leaf) -match $required }
        if (-not $match) {
            throw "Plugin jar missing required dependency in lib/: $required ($JarPath)"
        }
    }
    foreach ($prefix in @("BOOT-INF/", "top/sywyar/pixivdownload/plugin/api/", "org/pf4j/")) {
        $leaked = $entries | Where-Object { $_.StartsWith($prefix) }
        if ($leaked) {
            throw "Plugin jar leaked forbidden '$prefix' entries: $JarPath"
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

function Assert-OfficialPluginArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$ArtifactPath,
        [Parameter(Mandatory = $true)]$Plugin
    )
    $format = if ($Plugin.Format) { $Plugin.Format } else { "jar" }
    if ($format -eq "jar") {
        if ($Plugin.PrivateLibs) {
            return Assert-JarWithPrivatePluginLibs $ArtifactPath $Plugin
        }
        return Assert-ThinPluginJar $ArtifactPath $Plugin.Id
    }
    if ($format -eq "zip") {
        throw "Official plugin '$($Plugin.Id)' is configured as zip; official plugin artifacts must be jar."
    }
    throw "Unsupported official plugin artifact format '$format' for $($Plugin.Id)."
}
