# Each release keeps one cumulative checkpoint, not a growing Nightly ledger.
function Read-PluginDownloadCount($Value) {
    $number = 0L
    if (-not [long]::TryParse([string]$Value, [Globalization.NumberStyles]::None,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$number)) {
        throw 'Invalid download count.'
    }
    return $number
}

function Add-PluginDownloadCount([long]$Left, [long]$Right) {
    if ($Left -lt 0 -or $Right -lt 0 -or $Right -gt [long]::MaxValue - $Left) {
        throw 'Total download count overflow.'
    }
    return ($Left + $Right)
}

function Read-ReleaseDownloadCheckpoint([string]$Body) {
    $marker = '<!-- pixivdownload-downloads:'
    if (-not $Body.Contains($marker)) { return @{ Present = $false; Retired = 0L; AssetId = 0L; Count = 0L } }
    $pattern = '<!-- pixivdownload-downloads:v1 retired=([0-9]{1,19}) asset=([0-9]{1,19}) count=([0-9]{1,19}) -->'
    $found = [regex]::Matches($Body, $pattern)
    if ($found.Count -ne 1 -or [regex]::Matches($Body, [regex]::Escape($marker)).Count -ne 1) {
        throw 'Invalid release download checkpoint.'
    }
    $value = @{ Present = $true; Retired = Read-PluginDownloadCount $found[0].Groups[1].Value
        AssetId = Read-PluginDownloadCount $found[0].Groups[2].Value
        Count = Read-PluginDownloadCount $found[0].Groups[3].Value }
    if (($value.AssetId -eq 0 -and ($value.Retired -ne 0 -or $value.Count -ne 0)) -or
            $value.Retired -lt $value.Count) { throw 'Invalid release download checkpoint.' }
    return $value
}

function Write-ReleaseDownloadNotes([string]$Directory, [string]$Body,
        $Checkpoint = @{ Retired = 0L; AssetId = 0L; Count = 0L }) {
    $body = [regex]::Replace($Body, '<!-- pixivdownload-downloads:[^\r\n]*-->', '').TrimEnd()
    $body += "`n`n<!-- pixivdownload-downloads:v1 retired=$($Checkpoint.Retired) asset=$($Checkpoint.AssetId) count=$($Checkpoint.Count) -->"
    $path = Join-Path $Directory 'download-statistics-notes.txt'
    [IO.File]::WriteAllText($path, $body, (New-Object Text.UTF8Encoding($false)))
    return $path
}

function Get-ReleasePluginAsset($Release, [string]$Tag, [string]$PluginId, [string]$Module) {
    if ($Tag -ceq "$PluginId-nightly") {
        $pattern = '^' + [regex]::Escape($Module) + '-[0-9]+\.[0-9]+\.[0-9]+(?:-(?:alpha|beta|rc)\.?[0-9]+)?-nightly\.[0-9]{8}\.[0-9]+\.[0-9]+\.(?:jar|zip)$'
    } elseif ($Tag.StartsWith("$PluginId-v", [StringComparison]::Ordinal)) {
        $version = $Tag.Substring($PluginId.Length + 2)
        if ($version -match '-nightly[.-]') { return $null }
        $pattern = '^' + [regex]::Escape("$Module-$version") + '\.(?:jar|zip)$'
    } else { return $null }
    $assets = @($Release.assets | Where-Object { $_.name -cmatch $pattern })
    if ($assets.Count -gt 1) { throw "Ambiguous plugin artifacts on release $Tag." }
    if ($assets.Count -eq 1) { return $assets[0] }
    return $null
}

function Get-MarketReleaseIndex([string]$Repo) {
    $index = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $page = 1
    do {
        $raw = & gh api "repos/$Repo/releases?per_page=100&page=$page"
        if ($LASTEXITCODE -ne 0 -or -not $raw) { throw "Failed to read release statistics page $page." }
        $json = $raw -join "`n"
        if ([Text.Encoding]::UTF8.GetByteCount($json) -gt 16MB) { throw 'Release statistics page exceeds 16 MiB.' }
        if (-not $json.TrimStart().StartsWith('[')) { throw 'Invalid release statistics page.' }
        $releases = @(($json | ConvertFrom-Json) | ForEach-Object { $_ })
        if ($releases.Count -gt 100) { throw 'Invalid release statistics page size.' }
        foreach ($release in $releases) {
            $tag = [string]$release.tag_name
            if ([string]::IsNullOrWhiteSpace($tag) -or -not $seen.Add($tag)) {
                throw 'Missing or repeated release identity in statistics pages.'
            }
            if (-not $release.draft) { $index.Add($tag, $release) }
        }
        $page++
    } while ($releases.Count -eq 100)
    Write-Host "Read release statistics: $($seen.Count) releases, $($page - 1) pages."
    return ,$index
}

function Get-PluginDownloadStatistics($Releases, [string]$PluginId, [string]$Module,
        [string]$LatestTag, [string]$LatestAssetName) {
    if (-not $Releases.ContainsKey($LatestTag)) { throw "Published release missing: $LatestTag" }
    $latest = Get-ReleasePluginAsset $Releases[$LatestTag] $LatestTag $PluginId $Module
    if ($null -eq $latest -or $latest.name -cne $LatestAssetName) {
        throw "Published asset missing or ambiguous: $LatestTag/$LatestAssetName"
    }
    $current = Read-PluginDownloadCount $latest.download_count
    $total = 0L
    foreach ($tag in $Releases.Keys) {
        if ($LatestTag -ceq "$PluginId-nightly") {
            if ($tag -cne $LatestTag) { continue }
        } elseif (-not $tag.StartsWith("$PluginId-v", [StringComparison]::Ordinal) -or $tag -match '-nightly[.-]') {
            continue
        }
        $release = $Releases[$tag]
        $asset = Get-ReleasePluginAsset $release $tag $PluginId $Module
        $checkpoint = Read-ReleaseDownloadCheckpoint ([string]$release.body)
        $subtotal = $checkpoint.Retired
        if ($null -ne $asset) {
            $count = Read-PluginDownloadCount $asset.download_count
            $assetId = Read-PluginDownloadCount $asset.id
            if ($assetId -eq 0) { throw "Invalid asset identity on release $tag." }
            if ($assetId -eq $checkpoint.AssetId) {
                if ($count -lt $checkpoint.Count) { throw "Download count regressed on release $tag." }
                # The checkpoint may precede deletion; count the still-present asset once.
                $subtotal -= $checkpoint.Count
            }
            $subtotal = Add-PluginDownloadCount $subtotal $count
        } elseif (-not $checkpoint.Present) {
            throw "Plugin artifact missing without a download checkpoint: $tag"
        }
        $total = Add-PluginDownloadCount $total $subtotal
    }
    return @{ DownloadCount = $current; PreviousDownloadCount = ($total - $current); TotalDownloadCount = $total }
}

function Save-ReleaseDownloadCheckpoint([string]$Repo, [string]$Tag, $Release, $Plugin,
        [string]$Directory, [string]$NewBody, [string]$NightlyTitle) {
    $checkpoint = Read-ReleaseDownloadCheckpoint ([string]$Release.Body)
    $asset = Get-ReleasePluginAsset $Release $Tag $Plugin.Id $Plugin.Module
    if ($null -ne $asset) {
        # gh release view exposes GraphQL IDs; its API URL carries the REST asset ID.
        if ($asset.apiUrl -cnotmatch ('^https://api\.github\.com/repos/' + [regex]::Escape($Repo) + '/releases/assets/([1-9][0-9]*)$')) {
            throw "Invalid asset identity on release $Tag."
        }
        $assetId = Read-PluginDownloadCount $Matches[1]
        # shortcut: Downloads after this snapshot can be lost; revisit if GitHub supports atomic replacement.
        $count = Read-PluginDownloadCount $asset.downloadCount
        if ($checkpoint.AssetId -eq $assetId) {
            if ($count -lt $checkpoint.Count) { throw "Download count regressed on release $Tag." }
            $checkpoint.Retired -= $checkpoint.Count
        }
        $checkpoint.Retired = Add-PluginDownloadCount $checkpoint.Retired $count
        $checkpoint.AssetId = $assetId
        $checkpoint.Count = $count
    } elseif (-not $checkpoint.Present) {
        # Without an asset or checkpoint, a zero baseline cannot be established.
        throw "Plugin artifact missing without a download checkpoint: $Tag"
    }
    $body = if ($PSBoundParameters.ContainsKey('NewBody')) { $NewBody } else { [string]$Release.Body }
    $notesFile = Write-ReleaseDownloadNotes $Directory $body $checkpoint
    $arguments = @('release', 'edit', $Tag, '--repo', $Repo, '--notes-file', $notesFile)
    if ($NightlyTitle) { $arguments += @('--title', $NightlyTitle, '--prerelease') }
    & gh @arguments
    if ($LASTEXITCODE -ne 0) { throw "Failed to save download checkpoint for $Tag; assets were not deleted." }
    Write-Host "Saved download checkpoint: $Tag, asset $($checkpoint.AssetId), retired $($checkpoint.Retired)."
}
