# 分发暂存与 Windows 安装器共用的只读插件元数据解析。
$PluginMetadataMaxBytes = 1MB
$PluginMetadataLocales = $null

function ConvertFrom-PluginProperties([string]$Text) {
    $result = @{}
    $logical = ''
    foreach ($line in ($Text -split "`r?`n")) {
        $logical += $line.TrimStart()
        if (($logical.Length - $logical.TrimEnd('\').Length) % 2 -eq 1) {
            $logical = $logical.Substring(0, $logical.Length - 1)
            continue
        }
        if ($logical -match '^\s*([^#!\s:=]+)\s*[=:]?\s*(.*)$') {
            $value = [regex]::Replace($Matches[2], '\\(u[0-9a-fA-F]{4}|.)', {
                param($m)
                $s = $m.Groups[1].Value
                if ($s.Length -eq 5 -and $s[0] -eq 'u') { return [string][char][Convert]::ToInt32($s.Substring(1), 16) }
                switch -CaseSensitive ($s) { 't' { "`t" }; 'r' { "`r" }; 'n' { "`n" }; 'f' { "`f" }; default { $s } }
            })
            $result[$Matches[1]] = $value
        }
        $logical = ''
    }
    return $result
}

function Read-PluginMetadataText([IO.Stream]$Stream, [int]$Maximum = $PluginMetadataMaxBytes, [hashtable]$Budget) {
    $output = New-Object IO.MemoryStream
    try {
        $buffer = New-Object byte[] 8192
        while (($count = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            if ($output.Length + $count -gt $Maximum) { throw "Plugin metadata exceeds $Maximum bytes." }
            if ($null -ne $Budget) {
                if ($count -gt $Budget.Remaining) { throw 'Plugin inventory metadata budget exceeded.' }
                $Budget.Remaining -= $count
            }
            $output.Write($buffer, 0, $count)
        }
        $encoding = New-Object Text.UTF8Encoding($false, $true)
        return $encoding.GetString($output.ToArray()).TrimStart([char]0xfeff)
    } finally { $Stream.Dispose(); $output.Dispose() }
}

function Read-PluginMetadataFile([string]$Path, [int]$Maximum = $PluginMetadataMaxBytes) {
    if ((Get-Item -LiteralPath $Path).Length -gt $Maximum) { throw "Plugin metadata exceeds $Maximum bytes." }
    return Read-PluginMetadataText ([IO.File]::OpenRead($Path)) $Maximum
}

function Get-PluginPackageMetadata([string]$Path, [switch]$IdentityOnly, [hashtable]$Budget) {
    if ((Get-Item -LiteralPath $Path).Length -gt 192MB) { throw 'Plugin package exceeds 192 MiB.' }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        if ($archive.Entries.Count -gt 48000) { throw 'Plugin archive exceeds 48000 entries.' }
        $rootJars = @($archive.Entries | Where-Object { $_.FullName -match '^[^/\\]+\.jar$' })
        if ([IO.Path]::GetExtension($Path) -eq '.zip') {
            if ($archive.GetEntry('plugin.properties') -and $rootJars.Count) { throw 'Ambiguous ZIP plugin layout.' }
            if (-not $archive.GetEntry('plugin.properties')) {
                if ($rootJars.Count -ne 1 -or $rootJars[0].Length -gt 64MB) { throw 'Invalid ZIP plugin layout.' }
                $stream = $rootJars[0].Open(); $memory = New-Object IO.MemoryStream
                $nested = $null
                try {
                    $buffer = New-Object byte[] 8192
                    while (($count = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                        if ($memory.Length + $count -gt 64MB) { throw 'Nested plugin JAR exceeds 64 MiB.' }
                        $memory.Write($buffer, 0, $count)
                    }
                    $memory.Position = 0
                    $nested = New-Object IO.Compression.ZipArchive($memory, [IO.Compression.ZipArchiveMode]::Read)
                    if ($archive.Entries.Count + $nested.Entries.Count -gt 48000) { throw 'Plugin archive exceeds 48000 entries.' }
                    return Get-PluginArchiveMetadata $nested -IdentityOnly:$IdentityOnly -Budget $Budget
                } finally { if ($nested) { $nested.Dispose() }; $stream.Dispose(); $memory.Dispose() }
            }
        }
        return Get-PluginArchiveMetadata $archive -IdentityOnly:$IdentityOnly -Budget $Budget
    } finally { $archive.Dispose() }
}

function Get-PluginArchiveMetadata($Archive, [switch]$IdentityOnly, [hashtable]$Budget) {
    if ($archive.Entries.Count -gt 48000) { throw 'Plugin archive exceeds 48000 entries.' }
    if (@($archive.Entries | Where-Object FullName -CEQ 'plugin.properties').Count -ne 1) { throw 'Ambiguous plugin descriptor.' }
    $entry = $archive.GetEntry('plugin.properties')
    if ($null -eq $entry -or $entry.Length -gt $PluginMetadataMaxBytes) { throw 'Missing or oversized plugin descriptor.' }
    $properties = ConvertFrom-PluginProperties (Read-PluginMetadataText $entry.Open() -Budget $Budget)
    if ($properties['plugin.id'] -notmatch '^[a-zA-Z0-9][a-zA-Z0-9._-]*$' -or -not $properties['plugin.version']) { throw 'Invalid plugin identity.' }
    $names = @{}
    $namespace = $properties['pixiv.display-namespace']
    $key = $properties['pixiv.display-name-key']
    if (-not $IdentityOnly -and $namespace -match '^[a-zA-Z0-9._-]+$' -and $key) {
        if ($null -eq $script:PluginMetadataLocales) {
            $catalogPath = Join-Path $PSScriptRoot 'locales.json'
            if (-not (Test-Path -LiteralPath $catalogPath)) {
                $catalogPath = Join-Path $PSScriptRoot '../pixivdownload-app/src/main/resources/i18n/locales.json'
            }
            $script:PluginMetadataLocales = @(((Read-PluginMetadataFile $catalogPath) | ConvertFrom-Json).locales)
        }
        foreach ($locale in $script:PluginMetadataLocales) {
            $suffix = if ($locale.resourceSuffix) { '_' + $locale.resourceSuffix } else { '' }
            $entry = $archive.GetEntry("i18n/web/$namespace$suffix.properties")
            if ($null -ne $entry -and $entry.Length -le $PluginMetadataMaxBytes) {
                $dictionary = ConvertFrom-PluginProperties (Read-PluginMetadataText $entry.Open() -Budget $Budget)
                $nameLocale = if ($locale.resourceSuffix) { $locale.resourceSuffix } else { $locale.tag.Split('-')[0] }
                if ($dictionary[$key]) { $names[$nameLocale] = $dictionary[$key] }
            }
        }
    }
    return [pscustomobject]@{ id = $properties['plugin.id']; version = $properties['plugin.version'];
        requires = $properties['plugin.requires']; description = $properties['plugin.description']; displayNames = $names }
}

function Test-InstallerSdkCompatibility([string]$Required, [string]$Sdk, [string]$AppVersion, [string]$PluginVersion) {
    $sdkPattern = '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)\.?([1-9]\d*))?$'
    if ($Sdk -cnotmatch $sdkPattern) { return $false }
    $nightly = '-nightly\.[0-9]{8}\.[1-9][0-9]{0,8}\.[1-9][0-9]{0,8}$'
    $appSuffix = [regex]::Match($AppVersion, $nightly).Value
    $pluginSuffix = [regex]::Match($PluginVersion, $nightly).Value
    if ($PluginVersion.Contains('-nightly.') -and
        (-not $pluginSuffix -or $pluginSuffix -cne $appSuffix -or $pluginSuffix -cne [regex]::Match($Required, $nightly).Value)) { return $false }
    if ([string]::IsNullOrWhiteSpace($Required) -or $Required.Trim() -eq '*') { return $true }
    $Required = $Required.Trim()
    if ($Required -match $nightly) { return $Required -ceq ($Sdk + $appSuffix) -and $appSuffix -ne '' }
    if ($Required.StartsWith('=') -or $Required.Contains('-')) {
        $exact = $Required
        if ($exact.StartsWith('=')) { $exact = $exact.Substring(1) }
        if ($exact -cnotmatch $sdkPattern) { return $false }
        $exact = $exact -replace '-(alpha|beta|rc)\.?([0-9]+)$', '-$1.$2'
        $actual = $Sdk -replace '-(alpha|beta|rc)\.?([0-9]+)$', '-$1.$2'
        return $exact -ceq $actual
    }
    if ($Required -notmatch '^(?:>=\s*)?[vV]?(\d+)(?:\.(\d+))?(?:\.\d+)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$') { return $false }
    $major = 0; $minor = 0
    if (-not [int]::TryParse($Matches[1], [ref]$major)) { return $false }
    if ($Matches[2] -and -not [int]::TryParse($Matches[2], [ref]$minor)) { return $false }
    if ($Sdk -notmatch '^(\d+)\.(\d+)\.\d+(?:-(?:alpha|beta|rc)\.?\d+)?$') { return $false }
    return $major -eq [int]$Matches[1] -and $minor -le [int]$Matches[2]
}

function Compare-InstallerPluginVersion([string]$Left, [string]$Right) {
    $pattern = '^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$'
    if ($Left -notmatch $pattern) { throw 'Invalid installed plugin version.' }
    $a = @($Matches[1], $Matches[2], $Matches[3], $Matches[4])
    if ($Right -notmatch $pattern) { throw 'Invalid bundled plugin version.' }
    $b = @($Matches[1], $Matches[2], $Matches[3], $Matches[4])
    for ($i = 0; $i -lt 3; $i++) {
        $av = 0; $bv = 0
        [void][int]::TryParse($a[$i], [ref]$av); [void][int]::TryParse($b[$i], [ref]$bv)
        $cmp = $av.CompareTo($bv)
        if ($cmp) { return $cmp }
    }
    if (-not $a[3] -and -not $b[3]) { return 0 }
    if (-not $a[3]) { return 1 }; if (-not $b[3]) { return -1 }
    $ap = $a[3].Split('.'); $bp = $b[3].Split('.')
    for ($i = 0; $i -lt [Math]::Min($ap.Count, $bp.Count); $i++) {
        $an = $ap[$i] -match '^\d+$'; $bn = $bp[$i] -match '^\d+$'
        if ($an -and $bn) {
            $av = $ap[$i].TrimStart('0'); $bv = $bp[$i].TrimStart('0')
            $cmp = $av.Length.CompareTo($bv.Length)
            if (-not $cmp) { $cmp = [string]::CompareOrdinal($av, $bv) }
        }
        elseif ($an -ne $bn) { $cmp = if ($an) { -1 } else { 1 } }
        else { $cmp = [string]::CompareOrdinal($ap[$i], $bp[$i]) }
        if ($cmp) { return $cmp }
    }
    return $ap.Count.CompareTo($bp.Count)
}
