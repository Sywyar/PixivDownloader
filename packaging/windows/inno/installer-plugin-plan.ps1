[CmdletBinding()]
param(
    [ValidateSet('Scan', 'Validate', 'Finalize')][string]$Action = 'Scan',
    [string]$ManifestFile, [string]$InstallDir, [string]$PlanFile,
    [string]$ProjectionFile, [string]$ChoicesFile, [string]$SdkVersion,
    [string]$AppVersion, [string]$Language = 'en', [switch]$Library
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'plugin-package-metadata.ps1')

function Get-PreviewProperty($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    if ($Object -is [Collections.IDictionary]) { return $Object[$Name] }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -ne $property) { return $property.Value }
    return $null
}

function Get-PlanFileHash([string]$Path, [long]$Maximum = 192MB) {
    $stream = [IO.File]::OpenRead($Path)
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        if ($stream.Length -gt $Maximum) { throw "Plugin snapshot input exceeds $Maximum bytes." }
        return [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-', '')
    }
    finally { $stream.Dispose(); $hash.Dispose() }
}

function Assert-PlanPath([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -Force -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'Plugin preview does not follow reparse points.'
            }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
}

function Get-PlanCompanions([string]$Path) {
    return @($Path, "$Path.sha256", "$Path.sig", "$Path.sig.json", "$Path.pixiv-plugin-provenance",
        (Join-Path (Join-Path ([IO.Path]::GetDirectoryName($Path)) 'provenance') ([IO.Path]::GetFileName($Path) + '.pixiv-plugin-provenance')))
}

function Get-PlanCopyFiles($Entry) {
    $allowed = @($Entry.file, "$($Entry.file).sha256", "$($Entry.file).sig", "$($Entry.file).sig.json",
        "$($Entry.file).pixiv-plugin-provenance", "provenance/$($Entry.file).pixiv-plugin-provenance")
    $declared = Get-PreviewProperty $Entry 'files'
    if ($null -eq $declared) { return $allowed }
    $files = @($declared)
    if ($files -notcontains $Entry.file -or @($files | Where-Object { $_ -notin $allowed }).Count) { throw 'Invalid bundled companion list.' }
    return $files
}

function Get-PlanSnapshot([string]$Directory) {
    Assert-PlanPath $Directory
    $files = New-Object 'System.Collections.Generic.List[string]'
    $rootEntries = 0; $archiveBytes = 0L
    $plugins = Join-Path $Directory 'plugins'
    Assert-PlanPath $plugins
    Assert-PlanPath (Join-Path $plugins 'provenance')
    if (Test-Path -LiteralPath $plugins) {
        foreach ($file in [IO.Directory]::EnumerateFiles($plugins)) {
            $rootEntries++
            if ($rootEntries -gt 2048) { throw 'Plugin root exceeds 2048 files.' }
            if ([IO.Path]::GetExtension($file) -in @('.jar', '.zip')) {
                $archiveBytes += (Get-Item -LiteralPath $file).Length
                if ($archiveBytes -gt 2GB) { throw 'Plugin inventory exceeds 2 GiB.' }
                foreach ($path in (Get-PlanCompanions $file)) { $files.Add($path) }
            }
            if ($files.Count -gt 3072) { throw 'Plugin inventory exceeds 512 packages.' }
        }
    }
    $files.Add((Join-Path $Directory 'config/config.yaml'))
    $files.Add((Join-Path $plugins 'plugins-manifest.json'))
    $snapshot = [ordered]@{}; $metadataBytes = 0L
    foreach ($path in ($files | Sort-Object -Unique)) {
        Assert-PlanPath $path
        if (Test-Path -LiteralPath $path -PathType Container) { throw 'Plugin artifact or companion is a directory.' }
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $maximum = 192MB
            if ([IO.Path]::GetExtension($path) -notin @('.jar', '.zip')) {
                $maximum = 1MB
                $metadataBytes += (Get-Item -LiteralPath $path).Length
                if ($metadataBytes -gt 64MB) { throw 'Plugin snapshot metadata exceeds 64 MiB.' }
            }
            $snapshot[$path] = Get-PlanFileHash $path $maximum
        }
    }
    return $snapshot
}

function Get-PlanDatabaseOwner([string]$Directory) {
    $path = Join-Path $Directory 'config/config.yaml'
    if (-not (Test-Path -LiteralPath $path)) { return '' }
    $text = Read-PluginMetadataFile $path
    $lines = @($text -split "`r?`n" | Where-Object { $_ -match '^\s*["'']?database\.provider["'']?\s*:' })
    if ($lines.Count -gt 1) { throw 'Ambiguous database provider setting.' }
    if ($lines.Count -eq 0) { return '' }
    if ($lines[0] -notmatch '^\s*["'']?database\.provider["'']?\s*:\s*["'']?([a-zA-Z0-9._-]*)["'']?\s*(?:#.*)?$') {
        throw 'Unreadable database provider setting.'
    }
    return $Matches[1]
}

function Get-PlanDisplayName($Metadata, [string]$Locale) {
    $names = Get-PreviewProperty $Metadata 'displayNames'
    if ($null -ne $names) {
        foreach ($key in @($Locale, 'en')) {
            $value = Get-PreviewProperty $names $key
            if ($value) { return [string]$value }
        }
    }
    $description = Get-PreviewProperty $Metadata 'description'
    if ($description) { return [string]$description }
    return [string]$Metadata.id
}

function New-InstallerPluginPlan([string]$Manifest, [string]$Directory, [string]$Sdk, [string]$App, [string]$Locale) {
    $Directory = [IO.Path]::GetFullPath($Directory).TrimEnd('\')
    $snapshot = Get-PlanSnapshot $Directory
    $previousManifest = Join-Path $Directory 'plugins/plugins-manifest.json'
    if (Test-Path -LiteralPath $previousManifest) { $null = (Read-PluginMetadataFile $previousManifest) | ConvertFrom-Json }
    $parsed = (Read-PluginMetadataFile $Manifest) | ConvertFrom-Json
    $bundled = @($parsed)
    if ($bundled.Count -gt 512) { throw 'Bundled manifest exceeds 512 packages.' }
    $installed = @{}; $files = @{}; $seen = @{}; $bundleFiles = @{}
    $metadataBudget = @{ Remaining = 64MB }
    $plugins = Join-Path $Directory 'plugins'
    foreach ($path in $snapshot.Keys) {
        if ([IO.Path]::GetDirectoryName($path) -ne $plugins -or [IO.Path]::GetExtension($path) -notin @('.jar', '.zip')) { continue }
        $metadata = Get-PluginPackageMetadata $path -Budget $metadataBudget
        if ($installed.ContainsKey($metadata.id)) { throw 'Duplicate installed plugin id.' }
        $source = 'unknown'
        foreach ($sidecar in @((Get-PlanCompanions $path)[5], "$path.pixiv-plugin-provenance")) {
            if (Test-Path -LiteralPath $sidecar) {
                $provenance = ConvertFrom-PluginProperties (Read-PluginMetadataFile $sidecar)
                # 来源只用于展示，验签与信任决策仍由运行时负责。
                if ($provenance['artifactSha256'] -eq $snapshot[$path]) {
                    $source = $provenance['source']
                    if ($source -eq 'MARKET_CATALOG' -and $provenance['officialRepository'] -ne 'true') { $source = 'COMMUNITY_CATALOG' }
                }
                break
            }
        }
        $installed[$metadata.id] = [pscustomobject]@{ metadata = $metadata; path = $path; source = $source }
        $files[[IO.Path]::GetFileName($path)] = $metadata.id
    }
    $owner = Get-PlanDatabaseOwner $Directory
    $rows = New-Object 'System.Collections.Generic.List[object]'
    foreach ($bundle in $bundled) {
        if (-not $bundle.id -or $seen.ContainsKey($bundle.id) -or $bundleFiles.ContainsKey($bundle.file) -or
            $bundle.file -notmatch '^[a-zA-Z0-9._-]+\.(jar|zip)$' -or $bundle.sha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid bundled plugin manifest.' }
        $seen[$bundle.id] = $true
        $bundleFiles[$bundle.file] = $true
        $null = Get-PlanCopyFiles $bundle
        if ($files.ContainsKey($bundle.file) -and $files[$bundle.file] -ne $bundle.id) { throw 'Bundled plugin filename belongs to another installed plugin.' }
        $current = $installed[$bundle.id]
        $compatible = Test-InstallerSdkCompatibility (Get-PreviewProperty $bundle 'requires') $Sdk $App $bundle.version
        $currentCompatible = $false; $version = ''; $source = ''; $reason = ''; $select = $compatible
        $operation = 'install'
        if ($null -ne $current) {
            $version = $current.metadata.version; $source = $current.source
            $currentCompatible = Test-InstallerSdkCompatibility $current.metadata.requires $Sdk $App $version
            $cmp = Compare-InstallerPluginVersion $version $bundle.version
            $operation = if ($cmp -gt 0) { 'downgrade' } elseif ($cmp -eq 0) { 'replace' } else { 'upgrade' }
            if (-not $currentCompatible) { $reason = 'incompatible' }
            elseif ($cmp -gt 0) { $reason = 'newer' }
            elseif ($source -ne 'MARKET_CATALOG') { $reason = 'source' }
            elseif ($cmp -eq 0 -and $snapshot[$current.path] -ne $bundle.sha256) { $reason = 'different' }
            $select = $compatible -and ($reason -eq '') -and ($cmp -lt 0)
            if (-not $currentCompatible) { $select = $false }
            if ($compatible -and $cmp -lt 0 -and $source -eq 'MARKET_CATALOG') {
                $reason = ''; $select = $true
            }
        }
        if (-not $compatible) { $reason = 'bundleIncompatible'; $select = $false }
        $rows.Add([pscustomobject]@{
            id = $bundle.id; name = (Get-PlanDisplayName $bundle $Locale); current = $version; bundled = $bundle.version;
            source = $source; reason = $reason; operation = $operation; selected = $select; default = $select;
            selectable = ($reason -ne '' -and $compatible); compatible = $currentCompatible;
            required = ([bool](Get-PreviewProperty $bundle 'required') -or $bundle.id -eq $owner); file = $bundle.file
        })
    }
    foreach ($id in $installed.Keys) {
        if ($seen.ContainsKey($id)) { continue }
        $current = $installed[$id]
        $compatible = Test-InstallerSdkCompatibility $current.metadata.requires $Sdk $App $current.metadata.version
        $rows.Add([pscustomobject]@{ id = $id; name = (Get-PlanDisplayName $current.metadata $Locale);
            current = $current.metadata.version; bundled = ''; source = $current.source;
            reason = $(if ($compatible) { '' } else { 'incompatible' }); operation = 'keep'; selected = $false; default = $false;
            selectable = $false; compatible = $compatible; required = ($id -eq $owner); file = '' })
    }
    if ($owner -and -not $installed.ContainsKey($owner) -and -not $seen.ContainsKey($owner)) {
        $rows.Add([pscustomobject]@{ id = $owner; name = $owner; current = ''; bundled = ''; source = ''; reason = 'missing';
            operation = 'keep'; selected = $false; default = $false; selectable = $false; compatible = $false; required = $true; file = '' })
    }
    $sorted = @($rows | Sort-Object @{ Expression = { if ($_.required -and -not $_.compatible) { 0 } elseif ($_.reason -in @('incompatible','bundleIncompatible')) { 1 } elseif ($_.reason) { 2 } else { 3 } } }, name)
    return [pscustomobject]@{ directory = $Directory; sdk = $Sdk; app = $App; language = $Locale;
        manifest = (Get-PlanFileHash $Manifest); snapshot = $snapshot; rows = $sorted }
}

function Write-PlanProjection($Plan, [string]$Path) {
    $lines = New-Object 'System.Collections.Generic.List[string]'
    $lines.Add('[plan]'); $lines.Add('count=' + $Plan.rows.Count)
    for ($i = 0; $i -lt $Plan.rows.Count; $i++) {
        $lines.Add("[row$i]")
        foreach ($property in $Plan.rows[$i].PSObject.Properties) {
            $value = [string]$property.Value
            if ($property.Value -is [bool]) { $value = [string][int]$property.Value }
            $lines.Add($property.Name + '=' + ($value -replace '[\r\n\x00-\x1f]', ' '))
        }
    }
    [IO.File]::WriteAllLines($Path, $lines, [Text.Encoding]::Unicode)
}

function Set-PlanChoices($Plan, [string]$Path) {
    $choices = ConvertFrom-PluginProperties ([IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8))
    for ($i = 0; $i -lt $Plan.rows.Count; $i++) {
        $row = $Plan.rows[$i]
        if ($row.selectable) {
            if ($choices["row$i"] -notin @('0', '1')) { throw 'Incomplete plugin selection.' }
            $row.selected = $choices["row$i"] -eq '1'
        }
        if ($row.required -and -not $row.selected -and -not $row.compatible) { throw 'Required plugin is incompatible or missing.' }
    }
}

function Save-PlanJson($Value, [string]$Path) {
    $json = ConvertTo-Json -InputObject $Value -Depth 12
    if ([Text.Encoding]::UTF8.GetByteCount($json) -gt 16MB) { throw 'Plugin plan exceeds 16 MiB.' }
    [IO.File]::WriteAllText($Path, $json, (New-Object Text.UTF8Encoding($false)))
}

function Invoke-PluginPlan {
    if ($Action -eq 'Scan') {
        $plan = New-InstallerPluginPlan $ManifestFile $InstallDir $SdkVersion $AppVersion $Language
        Save-PlanJson $plan $PlanFile
        Write-PlanProjection $plan $ProjectionFile
        Write-Host ('Plugin preview completed: packages={0}.' -f $plan.rows.Count)
        return
    }
    $plan = (Read-PluginMetadataFile $PlanFile 16MB) | ConvertFrom-Json
    if ($plan.directory -ne [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')) { throw 'Plugin plan directory changed.' }
    $parsed = (Read-PluginMetadataFile $ManifestFile) | ConvertFrom-Json
    $bundled = @($parsed)
    if ($plan.manifest -ne (Get-PlanFileHash $ManifestFile)) { throw 'Bundled manifest changed.' }
    if ($Action -eq 'Validate') {
        $snapshot = Get-PlanSnapshot $InstallDir
        $previous = @($plan.snapshot.PSObject.Properties)
        if ($snapshot.Count -ne $previous.Count) { throw 'Installed plugin inventory changed; refresh preview.' }
        foreach ($item in $previous) { if ($snapshot[$item.Name] -ne $item.Value) { throw 'Installed plugin inventory changed; refresh preview.' } }
        Set-PlanChoices $plan $ChoicesFile
        if (@($plan.rows | Where-Object { $_.current -or $_.selected }).Count -gt 512) { throw 'Planned plugin inventory exceeds 512 packages.' }
        $selected = @($plan.rows | Where-Object selected | ForEach-Object id)
        $entries = @($bundled | Where-Object { $_.id -in $selected })
        Save-PlanJson $entries "$PlanFile.selected.json"
        $allow = @('[files]')
        foreach ($entry in $entries) {
            $target = Join-Path (Join-Path $InstallDir 'plugins') $entry.file
            $copyFiles = @(Get-PlanCopyFiles $entry | ForEach-Object { $_.Replace('/', '\') })
            foreach ($path in (Get-PlanCompanions $target)) {
                Assert-PlanPath $path
                if (Test-Path -LiteralPath $path -PathType Container) { throw 'Plugin destination is a directory.' }
                $relative = $path.Substring((Join-Path $InstallDir 'plugins').Length + 1)
                if ($relative -in $copyFiles) { $allow += ('plugins\' + $relative + '=1') }
            }
        }
        [IO.File]::WriteAllLines("$PlanFile.files.ini", $allow, [Text.Encoding]::Unicode)
        Save-PlanJson $plan $PlanFile
        Write-Host ('Plugin selection validated: install={0}, keep={1}.' -f $selected.Count, ($plan.rows.Count - $selected.Count))
        return
    }
    # 只对已选 ID 对账，保留项及其附属文件不参与清理。
    $selected = @($plan.rows | Where-Object selected | ForEach-Object id)
    $entries = @($bundled | Where-Object { $_.id -in $selected })
    if ($entries.Count) {
        & (Join-Path $PSScriptRoot 'installer-plugin-install.ps1') -ReconcileBundledDefaults -ManifestFile "$PlanFile.selected.json" -InstallDir $InstallDir
        if ($LASTEXITCODE -ne 0) { throw 'Selected plugin reconciliation failed.' }
    }
    $plugins = Join-Path $InstallDir 'plugins'
    foreach ($entry in $entries) {
        $copyFiles = @(Get-PlanCopyFiles $entry | ForEach-Object { $_.Replace('/', '\') })
        foreach ($path in (Get-PlanCompanions (Join-Path $plugins $entry.file))) {
            $relative = $path.Substring($plugins.Length + 1)
            if ($relative -notin $copyFiles -and (Test-Path -LiteralPath $path)) {
                Assert-PlanPath $path
                Remove-Item -LiteralPath $path -Force
            }
        }
    }
    $manifest = Join-Path $plugins 'plugins-manifest.json'
    $merged = @($entries)
    if (Test-Path -LiteralPath $manifest) {
        $old = (Read-PluginMetadataFile $manifest) | ConvertFrom-Json
        foreach ($entry in @($old)) {
            if ($entry.id -in $selected -or $entry.file -notmatch '^[a-zA-Z0-9._-]+\.(jar|zip)$') { continue }
            $path = Join-Path $plugins $entry.file
            if ((Test-Path -LiteralPath $path -PathType Leaf) -and (Get-PlanFileHash $path) -eq $entry.sha256) { $merged += $entry }
        }
    }
    if (-not (Test-Path -LiteralPath $plugins)) { [IO.Directory]::CreateDirectory($plugins) | Out-Null }
    Save-PlanJson $merged $manifest
    $sums = @($merged | ForEach-Object { $_.sha256 + '  ' + $_.file })
    [IO.File]::WriteAllLines((Join-Path $plugins 'SHA256SUMS'), [string[]]$sums, (New-Object Text.UTF8Encoding($false)))
    Write-Host ('Plugin installation completed: install={0}, keep={1}.' -f $selected.Count, ($plan.rows.Count - $selected.Count))
}

if (-not $Library) {
    $ProgressFile = "$PlanFile.progress"
    . (Join-Path $PSScriptRoot 'installer-progress.ps1')
    try { Invoke-PluginPlan; Write-State 'DONE|0'; exit 0 }
    catch {
        Write-State ('ERROR|' + (Escape-StateField $_.Exception.Message))
        Write-Error ('Plugin plan failed: ' + $_.Exception.Message) -ErrorAction Continue
        exit 1
    }
}
