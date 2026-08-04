<#
.SYNOPSIS
    Package the setup-aligned Java standard distribution and the full-offline distribution.

.DESCRIPTION
    One shared orchestrator consumed by both release.yml and nightly.yml so the two workflows
    never duplicate the assemble / verify / zip logic:

      1. Validate all inputs strictly and resolve absolute paths.
      2. Call scripts/assemble-plugin-distribution.ps1 twice with the SAME prebuilt core jar,
         prebuilt plugin inputs and signature tool:
         - <OutputDir>/java-standard with -DefaultDownloader (setup-aligned default plugin set,
           no Douyin);
         - <OutputDir>/full-offline without -DefaultDownloader (default set plus Douyin).
      3. Perform a real layout acceptance on BOTH assembled directories (fail-fast, before any
         zip is produced): exact core jar identity, launcher scripts, manifest id set vs the
         shared official-plugin sources, per-artifact .sha256 / .sig / provenance / SHA-256
         consistency, verified provenance status, no Douyin in the standard layout, Douyin
         required in full-offline, and identical core jar SHA-256 across both layouts and the
         input jar.
      4. Create PixivDownload-<Version>-java.zip and PixivDownload-<Version>-full-offline.zip
         with the layout contents at the zip root (no extra wrapper directory).

    The script never receives, reads or generates private key material and does not support
    unsigned local plugin staging; it only consumes signed catalog plugin inputs.

    ASCII-only (no BOM, English comments): these scripts run under Windows powershell(5.1);
    non-ASCII without a BOM is decoded with the system ANSI code page and fails to parse.

.PARAMETER Version
    Distribution version. Used for the core jar name, the manifest comparisons and both zip
    file names. Must match the version the prebuilt jar was built with.

.PARAMETER PrebuiltJar
    Exact path to the staged executable app-shell boot jar shared by both distributions.

.PARAMETER PrebuiltPluginsDir
    Directory with signed official plugin inputs staged from the plugin catalog
    (scripts/stage-official-plugin-inputs-from-catalog.ps1 -IncludeOptional).

.PARAMETER SignatureToolJar
    Exact path to the plugin signature tool jar used to verify the catalog inputs.

.PARAMETER OutputDir
    Directory for the two assembled layouts and the two zips. Default <repo>/build/plugin-distributions.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$PrebuiltJar,
    [Parameter(Mandatory = $true)][string]$PrebuiltPluginsDir,
    [Parameter(Mandatory = $true)][string]$SignatureToolJar,
    [string]$OutputDir
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

# Shared official-plugin list + checksum primitives (one source of distribution truth).
. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")

$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDir) {
    $OutputDir = Join-Path $ProjectRoot "build/plugin-distributions"
}
$AssemblerScript = Join-Path $PSScriptRoot "assemble-plugin-distribution.ps1"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-Inputs {
    if ([string]::IsNullOrWhiteSpace($Version)) {
        throw "Version must not be empty."
    }
    if (-not (Test-Path -LiteralPath $PrebuiltJar -PathType Leaf)) {
        throw "PrebuiltJar not found or not a file: $PrebuiltJar"
    }
    $jarItem = Get-Item -LiteralPath $PrebuiltJar
    if (-not $jarItem.Extension.Equals(".jar", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "PrebuiltJar must be a .jar file: $PrebuiltJar"
    }
    if ($jarItem.Length -le 0) {
        throw "PrebuiltJar is empty: $PrebuiltJar"
    }
    if (-not (Test-Path -LiteralPath $PrebuiltPluginsDir -PathType Container)) {
        throw "PrebuiltPluginsDir not found or not a directory: $PrebuiltPluginsDir"
    }
    if (-not (Test-Path -LiteralPath $SignatureToolJar -PathType Leaf)) {
        throw "SignatureToolJar not found or not a file: $SignatureToolJar"
    }
    if (-not (Test-Path -LiteralPath $AssemblerScript -PathType Leaf)) {
        throw "Missing script: $AssemblerScript"
    }
}

function Read-Manifest {
    param([Parameter(Mandatory = $true)][string]$Directory)
    $manifestPath = Join-Path $Directory "plugins-manifest.json"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Missing plugins-manifest.json: $manifestPath"
    }
    $manifestItem = Get-Item -LiteralPath $manifestPath
    if ($manifestItem.Length -le 0) {
        throw "plugins-manifest.json is empty: $manifestPath"
    }
    $parsed = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -eq $parsed -or $parsed -isnot [System.Array]) {
        throw "plugins-manifest.json is not a non-empty JSON array: $manifestPath"
    }
    if ($parsed.Count -eq 0) {
        throw "plugins-manifest.json contains no plugin entries: $manifestPath"
    }
    return $parsed
}

function Assert-IdSetMatches {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string[]]$ActualIds,
        [Parameter(Mandatory = $true)][string[]]$ExpectedIds
    )
    $uniqueActual = @($ActualIds | Sort-Object -Unique)
    $uniqueExpected = @($ExpectedIds | Sort-Object -Unique)
    if ($uniqueActual.Count -ne $uniqueExpected.Count) {
        throw ("{0}: manifest plugin id set does not match the official set " +
            "(manifest {1} vs official {2}).") -f `
            $Label, ($uniqueActual -join ", "), ($uniqueExpected -join ", ")
    }
    foreach ($id in $uniqueExpected) {
        if ($uniqueActual -notcontains $id) {
            throw "{0}: manifest plugin id set is missing '{1}' (official {2})." -f `
                $Label, $id, ($uniqueExpected -join ", ")
        }
    }
}

function Read-ProvenanceProps {
    param([Parameter(Mandatory = $true)][string]$Path)
    $props = @{}
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        $idx = $trimmed.IndexOf("=")
        if ($idx -lt 1) { continue }
        $props[$trimmed.Substring(0, $idx).Trim()] = $trimmed.Substring($idx + 1).Trim()
    }
    return $props
}

function Assert-PluginArtifactVerification {
    # One artifact must satisfy the signed-distribution contract: adjacent .sha256 and .sig
    # sidecars, artifact SHA-256 identical to both the manifest value and the .sha256 sidecar,
    # and a provenance sidecar in VERIFIED / MARKET_CATALOG state (never LOCAL_UPLOAD or
    # UNSIGNED_ALLOWED).
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$PluginId,
        [Parameter(Mandatory = $true)]$ManifestEntry
    )
    $artifactPath = Join-Path $Directory $ManifestEntry.file
    if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
        throw "Artifact for plugin '$PluginId' not found at $artifactPath."
    }
    $shaFile = "$artifactPath.sha256"
    $sigFile = "$artifactPath.sig"
    if (-not (Test-Path -LiteralPath $shaFile -PathType Leaf)) {
        throw "Missing .sha256 sidecar for plugin '$PluginId': $shaFile"
    }
    if (-not (Test-Path -LiteralPath $sigFile -PathType Leaf)) {
        throw "Missing .sig sidecar for plugin '$PluginId': $sigFile"
    }
    $sidecarSha = (Get-Content -LiteralPath $shaFile -TotalCount 1).Trim().Split(" ")[0]
    if ([string]::IsNullOrWhiteSpace($sidecarSha)) {
        throw "Empty .sha256 sidecar for plugin '$PluginId': $shaFile"
    }
    if (-not [string]::Equals($sidecarSha, $ManifestEntry.sha256, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Plugin '$PluginId': .sha256 sidecar differs from manifest sha256."
    }
    $actualSha = Get-Sha256Hex $artifactPath
    if (-not [string]::Equals($actualSha, $ManifestEntry.sha256, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Plugin '$PluginId': actual artifact SHA-256 differs from manifest sha256."
    }
    $artifactName = [System.IO.Path]::GetFileName($artifactPath)
    $provenance = Join-Path $Directory "plugins/provenance/$artifactName.pixiv-plugin-provenance"
    if (-not (Test-Path -LiteralPath $provenance -PathType Leaf)) {
        throw "Missing provenance sidecar for plugin '$PluginId': $provenance"
    }
    $prov = Read-ProvenanceProps $provenance
    if ($prov["status"] -ne "VERIFIED") {
        throw "Plugin '$PluginId': provenance status is '$($prov['status'])', expected VERIFIED."
    }
    if ($prov["source"] -ne "MARKET_CATALOG") {
        throw "Plugin '$PluginId': provenance source is '$($prov['source'])', expected MARKET_CATALOG."
    }
}

function Assert-DistributionLayout {
    # Real layout acceptance for one assembled distribution directory. Every failure throws
    # (fail-fast) so a broken layout is never zipped or uploaded.
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string[]]$ExpectedPluginIds,
        [Parameter(Mandatory = $true)][bool]$ExpectDouyin,
        [Parameter(Mandatory = $true)][string]$InputJarSha256
    )
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        throw "$Label layout directory not found: $Directory"
    }
    $rootJars = @(Get-ChildItem -LiteralPath $Directory -Filter "PixivDownload-*.jar" -File)
    if ($rootJars.Count -ne 1) {
        throw "${Label}: expected exactly one PixivDownload-*.jar at the layout root, found $($rootJars.Count)."
    }
    $coreJarName = "PixivDownload-$Version.jar"
    if ($rootJars[0].Name -ne $coreJarName) {
        throw "${Label}: core jar is '$($rootJars[0].Name)' but the exact expected name is '$coreJarName'."
    }
    $coreSha = Get-Sha256Hex $rootJars[0].FullName
    if (-not [string]::Equals($coreSha, $InputJarSha256, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "${Label}: core jar SHA-256 differs from the input PrebuiltJar."
    }

    $pluginsDir = Join-Path $Directory "plugins"
    if (-not (Test-Path -LiteralPath $pluginsDir -PathType Container)) {
        throw "${Label}: plugins/ directory missing."
    }
    $runBat = Join-Path $Directory "run.bat"
    $runSh = Join-Path $Directory "run.sh"
    foreach ($script in @($runBat, $runSh)) {
        if (-not (Test-Path -LiteralPath $script -PathType Leaf)) {
            throw "${Label}: missing launch script: $script"
        }
        if ((Get-Item -LiteralPath $script).Length -le 0) {
            throw "${Label}: launch script is empty: $script"
        }
    }

    $shaSums = Join-Path $Directory "SHA256SUMS"
    if (-not (Test-Path -LiteralPath $shaSums -PathType Leaf) -or (Get-Item -LiteralPath $shaSums).Length -le 0) {
        throw "${Label}: SHA256SUMS missing or empty."
    }

    $manifest = @(Read-Manifest $Directory)
    $ids = @($manifest | ForEach-Object { $_.id })
    $files = @($manifest | ForEach-Object { $_.file })
    if ((@($ids | Sort-Object -Unique)).Count -ne $ids.Count) {
        throw "${Label}: plugins-manifest.json contains duplicate plugin ids."
    }
    if ((@($files | Sort-Object -Unique)).Count -ne $files.Count) {
        throw "${Label}: plugins-manifest.json contains duplicate artifact files."
    }
    Assert-IdSetMatches -Label $Label -ActualIds $ids -ExpectedIds $ExpectedPluginIds

    $hasDouyin = $ids -contains "douyin"
    if ($ExpectDouyin -and -not $hasDouyin) {
        throw "${Label}: full-offline layout must include the douyin plugin."
    }
    if (-not $ExpectDouyin -and $hasDouyin) {
        throw "${Label}: java-standard layout must not include the douyin plugin."
    }
    $douyinArtifacts = @(Get-ChildItem -LiteralPath $pluginsDir -Filter "pixivdownload-plugin-douyin-*" -File)
    if ($ExpectDouyin -and $douyinArtifacts.Count -lt 1) {
        throw "${Label}: douyin artifact missing under plugins/."
    }
    if (-not $ExpectDouyin -and $douyinArtifacts.Count -gt 0) {
        throw "${Label}: douyin artifact must not be staged under plugins/."
    }

    foreach ($entry in $manifest) {
        Assert-PluginArtifactVerification -Directory $Directory -PluginId $entry.id -ManifestEntry $entry
    }

    if (Test-Path -LiteralPath (Join-Path $pluginsDir "LOCAL-UNSIGNED-BUILD.txt")) {
        throw "${Label}: LOCAL-UNSIGNED-BUILD.txt must not exist in a formal distribution layout."
    }

    Write-Host ("    OK: {0} layout verified ({1} plugin(s), core jar {2})." -f `
        $Label, $manifest.Count, $coreJarName) -ForegroundColor Green
}

# --- validation and resolution ----------------------------------------------------------------

Assert-Inputs
$ResolvedPrebuiltJar = (Resolve-Path -LiteralPath $PrebuiltJar).Path
$ResolvedPrebuiltPluginsDir = (Resolve-Path -LiteralPath $PrebuiltPluginsDir).Path
$ResolvedSignatureToolJar = (Resolve-Path -LiteralPath $SignatureToolJar).Path

# Shared safety assertion before any destructive operation: the output dir must
# be a strict <repo>/build/<subdir> (or an external temp dir) and must not
# overlap with any input (core jar, plugin inputs, signature tool, this
# script's own file / directory, repository root). No Remove-Item may run
# before this point.
$OutputDir = Assert-SafeDistributionOutputDirectory `
    -Path $OutputDir `
    -ProjectRoot $ProjectRoot `
    -ProtectedPaths @(
        $ResolvedPrebuiltJar,
        $ResolvedPrebuiltPluginsDir,
        $ResolvedSignatureToolJar,
        $AssemblerScript,
        $PSScriptRoot,
        $ProjectRoot
    )

$DefaultInstalledIds = @(Get-OfficialDefaultInstalledPlugins | ForEach-Object { $_.Id })
$FullOfflineIds = @(Get-OfficialDistributionPlugins -IncludeOptional | ForEach-Object { $_.Id })

$JavaStandardDir = Join-Path $OutputDir "java-standard"
$FullOfflineDir = Join-Path $OutputDir "full-offline"
$JavaZip = Join-Path $OutputDir "PixivDownload-$Version-java.zip"
$FullOfflineZip = Join-Path $OutputDir "PixivDownload-$Version-full-offline.zip"

Write-Step "Package Java distributions $Version"
Write-Host "    Prebuilt jar    : $ResolvedPrebuiltJar"
Write-Host "    Plugin inputs   : $ResolvedPrebuiltPluginsDir"
Write-Host "    Signature tool  : $ResolvedSignatureToolJar"
Write-Host "    Output dir      : $OutputDir"

if (Test-Path -LiteralPath $OutputDir) {
    Remove-Item -LiteralPath $OutputDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$InputJarSha256 = Get-Sha256Hex $ResolvedPrebuiltJar

Write-Step "Assembling java-standard distribution (DefaultDownloader)"
& $AssemblerScript `
    -Version $Version `
    -OutputDir $JavaStandardDir `
    -PrebuiltJar $ResolvedPrebuiltJar `
    -PrebuiltPluginsDir $ResolvedPrebuiltPluginsDir `
    -SignatureToolJar $ResolvedSignatureToolJar `
    -DefaultDownloader
Assert-DistributionLayout -Directory $JavaStandardDir -Version $Version -Label "java-standard" `
    -ExpectedPluginIds $DefaultInstalledIds -ExpectDouyin $false -InputJarSha256 $InputJarSha256

Write-Step "Assembling full-offline distribution"
& $AssemblerScript `
    -Version $Version `
    -OutputDir $FullOfflineDir `
    -PrebuiltJar $ResolvedPrebuiltJar `
    -PrebuiltPluginsDir $ResolvedPrebuiltPluginsDir `
    -SignatureToolJar $ResolvedSignatureToolJar
Assert-DistributionLayout -Directory $FullOfflineDir -Version $Version -Label "full-offline" `
    -ExpectedPluginIds $FullOfflineIds -ExpectDouyin $true -InputJarSha256 $InputJarSha256

Write-Step "Creating distribution zips"
Compress-Archive -Path (Join-Path $JavaStandardDir "*") -DestinationPath $JavaZip -Force
Compress-Archive -Path (Join-Path $FullOfflineDir "*") -DestinationPath $FullOfflineZip -Force
foreach ($zip in @($JavaZip, $FullOfflineZip)) {
    $zipItem = Get-Item -LiteralPath $zip
    if ($zipItem.Length -le 0) {
        throw "Produced an empty zip: $zip"
    }
    Write-Host "    OK: $($zipItem.Name) ($($zipItem.Length) bytes)." -ForegroundColor Green
}

Write-Step "Done"
Assert-NoPrivateKeyMaterial $OutputDir
Write-Host "Java standard  : $JavaZip"
Write-Host "Full offline   : $FullOfflineZip"
