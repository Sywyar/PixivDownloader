<#
.SYNOPSIS
    Generate the stable or Nightly plugin-market manifest (consumer-facing strict catalog) from the
    ALREADY-PUBLISHED GitHub Releases of the distribution repo + a curation source.

.DESCRIPTION
    The manifest is derived from the published release assets, NOT from a local build. Stable publication may
    reuse complete versioned releases; Nightly publication refreshes every plugin's fixed rolling release from
    current source. For each official required or optional plugin:

      - id / requires / dependencies : read from the published artifact's plugin.properties.
      - version                      : source plugin.version for stable, or that version plus the Nightly build suffix.
      - sha256 / expectedSizeBytes   : computed from the DOWNLOADED published plugin artifact (the real bytes).
      - downloadCount / releasedTime : read from the GitHub Releases API (asset download_count / publishedAt).
      - packageUrl                   : the GitHub Release asset link (github.com/.../releases/download/...).
      - identity display fields      : read from the module's source pixiv.* descriptor keys.
      - displayName / summary        : resolved from the module's i18n bundle using those descriptor keys.
      - market-only fields           : from the curation file, keyed by pluginId.

    The matching release MUST already exist (publish it first); a missing release is a hard error. Output is
    STRICT JSON (no comments), UTF-8 (no BOM), camelCase, asserted <= 1MB; `rating`/`ratingCount`
    are omitted. Cross-shell (Windows PowerShell 5.1 + pwsh): ASCII source, no ternary / -AsHashtable. Needs gh + GH_TOKEN.

.PARAMETER Repo
    owner/repo of the plugin distribution repository. Default Sywyar/PixivDownloader-plugins.

.PARAMETER CurationFile
    Market-only curation source, keyed by pluginId. Default scripts/market-curation.json.

.PARAMETER OutputFile
    Where to write the generated manifest.json. Default build/manifest.json.

.PARAMETER ProjectRoot
    Repo root (to locate plugin modules' source plugin.properties). Default = parent of this script's dir.

.PARAMETER NightlyBuildVersion
    Nightly application build version. Its nightly.date.run.attempt suffix is appended to each source plugin.version.
#>
[CmdletBinding()]
param(
    [string]$Repo = "Sywyar/PixivDownloader-plugins",
    [string]$CurationFile = "scripts/market-curation.json",
    [string]$OutputFile = "build/manifest.json",
    [string]$ProjectRoot,
    [string]$OfficialKeyId,
    [string]$PrivateKeyFile,
    [string]$SignatureToolJar,
    [string]$NightlyBuildVersion
)

$ErrorActionPreference = "Stop"
if (-not $ProjectRoot) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$nowUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")

# Shared official-plugin list (id / module / artifact format).
. (Join-Path $PSScriptRoot "plugin-distribution-common.ps1")
. (Join-Path $PSScriptRoot "market-content-publication.ps1")

if ([string]::IsNullOrWhiteSpace($OfficialKeyId)) { throw "OfficialKeyId is required." }
if ([string]::IsNullOrWhiteSpace($PrivateKeyFile) -or -not (Test-Path -LiteralPath $PrivateKeyFile -PathType Leaf)) {
    throw "PrivateKeyFile is required and must point to an Ed25519 PKCS#8 PEM file."
}
if (-not [string]::IsNullOrWhiteSpace($NightlyBuildVersion) -and
    $NightlyBuildVersion -notmatch '^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})-nightly\.[0-9]{8}\.[1-9][0-9]{0,8}\.[1-9][0-9]{0,8}$') {
    throw "NightlyBuildVersion must match major.minor.patch-nightly.yyyymmdd.run.attempt."
}
$isNightly = -not [string]::IsNullOrWhiteSpace($NightlyBuildVersion)
$nightlySuffix = if ($isNightly) { ($NightlyBuildVersion -split '-', 2)[1] } else { $null }
$nightlySdkVersion = if ($isNightly) { (Get-PixivDownloadSdkVersion -ProjectRoot $ProjectRoot) + "-$nightlySuffix" } else { $null }
$manifestName = if ($isNightly) { "nightly-manifest.json" } else { "manifest.json" }
$SignatureToolJar = Resolve-SignatureToolJar $ProjectRoot $SignatureToolJar

function Read-Json([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing file: $path" }
    return (Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Has-Property($obj, [string]$name) {
    if ($obj -is [System.Collections.IDictionary]) { return $obj.Contains($name) }
    return ($null -ne $obj) -and ($obj.PSObject.Properties.Name -contains $name)
}

function Get-PluginDependencies([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { return @() }
    $dependencies = @()
    foreach ($token in ($value -split ",")) {
        $dependency = $token.Trim()
        if (-not [string]::IsNullOrWhiteSpace($dependency)) {
            $dependencies += $dependency
        }
    }
    return @($dependencies)
}

# Parse a module's source plugin.properties (root descriptor) into a hashtable.
function Read-SourceDescriptor([string]$module) {
    $path = Join-Path $ProjectRoot "$module/src/main/resources/plugin.properties"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing source descriptor: $path" }
    $props = @{}
    foreach ($line in (Get-Content -LiteralPath $path -Encoding UTF8)) {
        $trimmed = $line.Trim()
        $trimmed = $trimmed.TrimStart([char]0xFEFF)
        if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
        $idx = $trimmed.IndexOf("=")
        if ($idx -lt 1) { continue }
        $props[$trimmed.Substring(0, $idx).Trim()] = $trimmed.Substring($idx + 1).Trim()
    }
    return $props
}

function Require-DescriptorValue($descriptor, [string]$key, [string]$module) {
    if (-not $descriptor.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$descriptor[$key])) {
        throw "Missing required descriptor key '$key' in module $module."
    }
    return [string]$descriptor[$key]
}

function Read-PropertiesFile([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing i18n bundle: $path" }
    $props = @{}
    foreach ($line in (Get-Content -LiteralPath $path -Encoding UTF8)) {
        $trimmed = $line.Trim()
        $trimmed = $trimmed.TrimStart([char]0xFEFF)
        if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
        $idx = $trimmed.IndexOf("=")
        if ($idx -lt 1) { continue }
        $props[$trimmed.Substring(0, $idx).Trim()] = $trimmed.Substring($idx + 1).Trim()
    }
    return $props
}

function Resolve-I18nText([string]$module, [string]$namespace, [string]$key, [string]$locale) {
    $suffix = ""
    if ($locale -ne "zh") { $suffix = "_$locale" }
    $path = Join-Path $ProjectRoot "$module/src/main/resources/i18n/web/$namespace$suffix.properties"
    $props = Read-PropertiesFile $path
    if (-not $props.ContainsKey($key) -or [string]::IsNullOrWhiteSpace([string]$props[$key])) {
        throw "Missing i18n key '$key' in $path."
    }
    return [string]$props[$key]
}

function Resolve-LocalizedTextMap([string]$module, [string]$namespace, [string]$key) {
    return [ordered]@{
        zh = Resolve-I18nText $module $namespace $key "zh"
        en = Resolve-I18nText $module $namespace $key "en"
    }
}

$curation = Read-Json $CurationFile
$legacySdkVersions = @($curation._legacySdkVersions)
if ($legacySdkVersions.Count -eq 0 -or @($legacySdkVersions | Where-Object { $_ -notmatch '^\d+\.\d+\.\d+$' }).Count -gt 0) {
    throw 'Curation must declare the fixed legacy SDK versions.'
}
$plugins = @(Get-OfficialDistributionPlugins -IncludeOptional)
$defaultInstalledPluginIds = @(Get-OfficialDefaultInstalledPlugins | ForEach-Object { $_.Id })

# Preserve signed history and cumulative download counts.
$prevByPlugin = @{}
$prevEntries = @{}

function Read-PublishedCatalogFile([string]$path, [switch]$AllowMissing) {
    $response = & gh api "repos/$Repo/contents/$path" --jq '.content' 2>&1
    if ($LASTEXITCODE -ne 0) {
        if ($AllowMissing -and "$response" -match 'HTTP 404') { return $null }
        throw "Failed to read existing catalog file $path."
    }
    $bytes = [Convert]::FromBase64String((($response -join '') -replace '\s', ''))
    if ($bytes.Length -eq 0 -or $bytes.Length -gt 1MB) { throw "Invalid existing catalog size: $path" }
    return ,$bytes
}

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("market-manifest-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$entries = @()
try {
    $existingBytes = Read-PublishedCatalogFile $manifestName -AllowMissing
    if ($null -ne $existingBytes) {
        $existingPath = Join-Path $tmp 'previous-manifest.json'
        [IO.File]::WriteAllBytes($existingPath, $existingBytes)
        [IO.File]::WriteAllBytes("$existingPath.sig", (Read-PublishedCatalogFile "$manifestName.sig"))
        Invoke-PluginSignatureTool $SignatureToolJar @('verify-manifest', '--manifest', $existingPath,
            '--signature', "$existingPath.sig", '--repository-id', 'official', '--policy', 'official')
        $existingManifest = [Text.Encoding]::UTF8.GetString($existingBytes) | ConvertFrom-Json
        foreach ($entry in $existingManifest.entries) {
            $prevEntries[$entry.pluginId] = $entry
            $m = $entry.market
            $prevByPlugin[$entry.pluginId] = @{
                version = if ($m.latestVersion) { "$($entry.pluginId)-v$($m.latestVersion)" } else { "" }
                downloadCount = if ($m.PSObject.Properties.Name -contains "downloadCount") { [long]$m.downloadCount } else { 0 }
                previousDownloadCount = if ($m.PSObject.Properties.Name -contains "previousDownloadCount") { [long]$m.previousDownloadCount } else { 0 }
            }
        }
        Write-Host "  Loaded $($prevByPlugin.Count) plugin(s) from existing manifest."
    } else {
        Write-Host "  No existing manifest found (first run), all previousDownloadCount start at 0."
    }
    foreach ($plugin in $plugins) {
        $d = Read-SourceDescriptor $plugin.Module
        $id = $d["plugin.id"]
        $sourceVersion = $d["plugin.version"]
        $version = if ($isNightly) {
            Get-NightlyPluginVersion $sourceVersion $nightlySuffix
        } else {
            $sourceVersion
        }
        $requires = if ($isNightly) { $nightlySdkVersion } else { $d["plugin.requires"] }
        $manifestRequiredSdk = $requires
        $dependencies = @(Get-PluginDependencies $d["plugin.dependencies"])
        if ($id -ne $plugin.Id) {
            throw "plugin.id '$id' in module $($plugin.Module) does not match expected '$($plugin.Id)'."
        }
        if (-not (Has-Property $curation $id)) {
            throw "No curation entry for plugin '$id' in $CurationFile (market metadata is required)."
        }
        $c = $curation.$id
        $displayNamespace = Require-DescriptorValue $d "pixiv.display-namespace" $plugin.Module
        $displayNameKey = Require-DescriptorValue $d "pixiv.display-name-key" $plugin.Module
        $descriptionKey = Require-DescriptorValue $d "pixiv.description-key" $plugin.Module
        $iconToken = Require-DescriptorValue $d "pixiv.icon-key" $plugin.Module
        $colorToken = Require-DescriptorValue $d "pixiv.color-token" $plugin.Module
        $displayName = Resolve-LocalizedTextMap $plugin.Module $displayNamespace $displayNameKey
        $summary = Resolve-LocalizedTextMap $plugin.Module $displayNamespace $descriptionKey
        $tag = if ($isNightly) { "$id-nightly" } else { "$id-v$version" }
        $assetName = Get-OfficialPluginArtifactName $plugin $version

        # Release metadata (must already exist): asset download_count + stable release publishedAt.
        # A rolling Nightly Release keeps its original publishedAt, so use this manifest generation time instead.
        $relRaw = & gh release view $tag --repo $Repo --json assets,publishedAt,body
        if ($LASTEXITCODE -ne 0 -or -not $relRaw) {
            throw "Release $tag not found in $Repo. Publish it before generating the manifest."
        }
        $rel = $relRaw | ConvertFrom-Json
        $asset = $rel.assets | Where-Object { $_.name -eq $assetName } | Select-Object -First 1
        if (-not $asset) { throw "Asset $assetName not found on release $tag." }
        $downloadCount = [int]$asset.downloadCount
        $releasedTime = if ($isNightly) { $nowUtc } else { $rel.publishedAt }
        if (-not $releasedTime) { $releasedTime = $nowUtc }

        # Cumulative download count: when the plugin version changes, fold the previous version's
        # download count into previousDownloadCount; unchanged versions keep the previous value.
        # New plugins start at 0.
        $prev = $prevByPlugin[$id]
        if ($prev) {
            if ($prev.version -and $prev.version -ne $tag) {
                # Version changed: accumulate the previous version's downloadCount + previousDownloadCount.
                $previousDownloadCount = $prev.downloadCount + $prev.previousDownloadCount
            } else {
                # Version unchanged: keep previousDownloadCount.
                $previousDownloadCount = $prev.previousDownloadCount
            }
        } else {
            $previousDownloadCount = 0
        }
        $totalDownloadCount = $downloadCount + $previousDownloadCount

        # sha256 / size from the ACTUAL published bytes (download the artifact, compute locally).
        & gh release download $tag --repo $Repo --pattern $assetName --dir $tmp --clobber |
            ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) { throw "Failed to download $assetName from release $tag." }
        $artifactPath = Join-Path $tmp $assetName
        $artifactDescriptor = Read-PluginDescriptor $artifactPath
        if ($null -eq $artifactDescriptor -or $artifactDescriptor['plugin.id'] -ne $id -or $artifactDescriptor['plugin.version'] -ne $version) {
            throw "Published artifact descriptor does not match $id $version."
        }
        $manifestRequiredSdk = [string]$artifactDescriptor['plugin.requires']
        if ([string]::IsNullOrWhiteSpace($manifestRequiredSdk)) { throw "Published artifact lacks plugin.requires: $id" }
        $dependencies = @(Get-PluginDependencies $artifactDescriptor['plugin.dependencies'])
        $sizeBytes = (Get-Item -LiteralPath $artifactPath).Length
        $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifactPath).Hash.ToLowerInvariant()

        $packageUrl = "https://github.com/$Repo/releases/download/$tag/$assetName"
        $signaturePath = Join-Path $tmp "$assetName.sig.json"
        Invoke-PluginSignatureTool $SignatureToolJar @(
            "artifact",
            "--artifact", $artifactPath,
            "--plugin-id", $id,
            "--version", $version,
            "--key-id", $OfficialKeyId,
            "--private-key", $PrivateKeyFile,
            "--out", $signaturePath
        )
        $signature = Read-Json $signaturePath

        $changeNotes = @()
        if (Has-Property $c "changeNotes") { $changeNotes = @($c.changeNotes) }
        $contentHash = $null
        if ((Has-Property $rel 'body') -and $rel.body -match 'market-content-sha256=([a-f0-9]{64})') { $contentHash = $Matches[1] }
        $publication = Read-OfficialMarketContent $ProjectRoot $Repo $tag @($rel.assets | ForEach-Object { $_.name }) $tmp $contentHash

        $market = [ordered]@{
            displayName      = $displayName
            summary          = $summary
            description      = $c.description
            author           = $c.author
            sourceType       = $c.sourceType
            category         = $c.category
            tags             = @($c.tags)
            homepageUrl      = $c.homepageUrl
            license          = $c.license
            downloadCount     = $downloadCount
            previousDownloadCount = $previousDownloadCount
            totalDownloadCount = $totalDownloadCount
            latestVersion     = $version
            updatedTime       = $releasedTime
            iconToken        = $iconToken
            colorToken       = $colorToken
            recommended      = [bool]$c.recommended
            officialRequired = [bool]$c.officialRequired
            defaultInstalled = ($defaultInstalledPluginIds -contains $id)
        }
        foreach ($field in @('defaultLocale', 'links', 'icon', 'screenshots')) {
            if (Has-Property $publication $field) { $market[$field] = $publication.$field }
            elseif (Has-Property $c $field) { $market[$field] = $c.$field }
        }

        $package = [ordered]@{
            version           = $version
            packageUrl        = $packageUrl
            expectedSizeBytes = $sizeBytes
            sha256            = $sha256
            signature         = $signature
            signatureUrl      = "$packageUrl.sig"
            requiredSdk       = $manifestRequiredSdk
            # Legacy wire alias retained so existing released clients can still read new manifests.
            requiredCoreApi   = $manifestRequiredSdk
            dependencies      = @($dependencies)
            releasedTime      = $releasedTime
            changeNotes       = $changeNotes
            channel           = if ($isNightly) { 'nightly' } elseif ($version -match '-([a-z]+)') { $Matches[1] } else { 'stable' }
            deprecated        = $false
        }
        if (Has-Property $publication 'content') { $package['content'] = $publication.content }

        $entry = [ordered]@{
            pluginId         = $id
            displayNamespace = $displayNamespace
            displayNameKey   = $displayNameKey
            descriptionKey   = $descriptionKey
            market           = $market
            packages         = @($package)
        }
        if (-not $isNightly -and $prevEntries.ContainsKey($id)) {
            $previous = $prevEntries[$id]
            $historyPackages = @($previous.packages)
            if ((Has-Property $previous 'history') -and $null -ne $previous.history) {
                $reference = $previous.history
                if ($reference.path -cne "history/$id-$($reference.sha256).json" -or $reference.sha256 -notmatch '^[a-f0-9]{64}$' -or
                    $reference.sizeBytes -le 0 -or $reference.sizeBytes -gt 1MB -or $reference.versions -lt 1 -or $reference.versions -gt 900) {
                    throw "Invalid published history reference for $id."
                }
                $historyBytes = Read-PublishedCatalogFile $reference.path
                $digest = [Security.Cryptography.SHA256]::Create()
                try { $hash = ([BitConverter]::ToString($digest.ComputeHash($historyBytes))).Replace('-', '').ToLowerInvariant() }
                finally { $digest.Dispose() }
                if ($historyBytes.Length -ne $reference.sizeBytes -or $hash -ne $reference.sha256) { throw "History digest mismatch for $id." }
                $history = [Text.Encoding]::UTF8.GetString($historyBytes) | ConvertFrom-Json
                if ($history.pluginId -ne $id -or @($history.packages).Count -ne $reference.versions -or
                    ((Has-Property $history 'history') -and $null -ne $history.history)) { throw "History identity mismatch for $id." }
                $historyPackages += @($history.packages)
            }
            $historyPackages = @($historyPackages | Where-Object { $_.version -ne $version })
            if (@($historyPackages.version | Select-Object -Unique).Count -ne $historyPackages.Count) {
                throw "Duplicate published version for $id."
            }
            $stablePackages = @(@($package) + $historyPackages | Where-Object {
                $_.version -match '^\d+\.\d+\.\d+$' -and
                (-not (Has-Property $_ 'deprecated') -or -not $_.deprecated) -and
                (-not (Has-Property $_ 'channel') -or -not $_.channel -or $_.channel -eq 'stable')
            } | Sort-Object { [version]$_.version } -Descending)
            $retained = @($package)
            if ($stablePackages.Count -gt 0 -and $stablePackages[0].version -ne $version) { $retained += $stablePackages[0] }
            foreach ($sdk in $legacySdkVersions) {
                $compatible = @($stablePackages | Where-Object {
                    $required = if ((Has-Property $_ 'requiredSdk') -and $_.requiredSdk) { $_.requiredSdk }
                        elseif (Has-Property $_ 'requiredCoreApi') { $_.requiredCoreApi } else { $null }
                    Test-PluginSdkCompatible $required $sdk
                } | Select-Object -First 1)
                if ($compatible.Count -gt 0 -and $retained.version -notcontains $compatible[0].version) { $retained += $compatible[0] }
            }
            $entry.packages = @($package) + @($retained | Where-Object { $_.version -ne $version } | Sort-Object { [version]$_.version } -Descending)
            $historyPackages = @($historyPackages | Where-Object { $retained.version -notcontains $_.version })
            if ($historyPackages.Count -gt 0) {
                if ($historyPackages.Count -gt 900 -or @($historyPackages.version | Select-Object -Unique).Count -ne $historyPackages.Count) {
                    throw "History version count is invalid for $id."
                }
                $historyJson = ([ordered]@{ pluginId = $id; packages = $historyPackages } | ConvertTo-Json -Depth 12) -replace "`r`n", "`n" -replace "`r", "`n"
                $historyBytes = $Utf8NoBom.GetBytes($historyJson)
                if ($historyBytes.Length -gt 1MB) { throw "Plugin history exceeds 1 MiB: $id" }
                $digest = [Security.Cryptography.SHA256]::Create()
                try { $hash = ([BitConverter]::ToString($digest.ComputeHash($historyBytes))).Replace('-', '').ToLowerInvariant() }
                finally { $digest.Dispose() }
                $relative = "history/$id-$hash.json"
                $historyPath = Join-Path (Split-Path -Parent $OutputFile) $relative
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $historyPath) | Out-Null
                [IO.File]::WriteAllBytes($historyPath, $historyBytes)
                $entry['history'] = [ordered]@{
                    path = $relative; sha256 = $hash
                    sizeBytes = $historyBytes.Length; versions = $historyPackages.Count
                }
            }
        }
        $entries += $entry
        Write-Host "  + $id $version  ($sizeBytes bytes, sha256 $($sha256.Substring(0,12))..., downloads $downloadCount + $previousDownloadCount = $totalDownloadCount)"
    }
} finally {
    Remove-Item -Recurse -Force -LiteralPath $tmp -ErrorAction SilentlyContinue
}

$manifest = [ordered]@{
    schemaVersion = "1"
    generatedTime = $nowUtc
    entries       = @($entries)
}

$json = ($manifest | ConvertTo-Json -Depth 12) -replace "`r`n", "`n" -replace "`r", "`n"
$bytes = $Utf8NoBom.GetBytes($json)
if ($bytes.Length -gt 1MB) {
    throw "Generated manifest is $($bytes.Length) bytes (> 1MB limit). Trim display fields."
}

$outDir = Split-Path -Parent $OutputFile
if ($outDir -and -not (Test-Path -LiteralPath $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
[System.IO.File]::WriteAllText($OutputFile, $json, $Utf8NoBom)
$manifestSignatureFile = "$OutputFile.sig"
Invoke-PluginSignatureTool $SignatureToolJar @(
    "manifest",
    "--manifest", $OutputFile,
    "--repository-id", "official",
    "--key-id", $OfficialKeyId,
    "--private-key", $PrivateKeyFile,
    "--out", $manifestSignatureFile
)
Write-Host "Wrote $OutputFile and $manifestSignatureFile ($($bytes.Length) bytes, $($entries.Count) plugin(s)) from published releases of $Repo."
