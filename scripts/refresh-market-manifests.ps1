# Refresh signed catalog statistics without changing package identities or history.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RepositoryDirectory,
    [Parameter(Mandatory = $true)][string]$PrivateKeyFile,
    [string]$Repo = 'Sywyar/PixivDownloader-plugins',
    [string]$OfficialKeyId = 'pixivdownloader-official-root-2026-07',
    [string]$SignatureToolJar
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'plugin-distribution-common.ps1')
. (Join-Path $PSScriptRoot 'plugin-download-statistics.ps1')
$SignatureToolJar = Resolve-SignatureToolJar (Split-Path -Parent $PSScriptRoot) $SignatureToolJar
$RepositoryDirectory = (Resolve-Path -LiteralPath $RepositoryDirectory).Path
$utf8 = New-Object Text.UTF8Encoding($false)
$generatedTime = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
$manifestNames = @('manifest.json', 'nightly-manifest.json')
$jsonOptions = @{}
if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $jsonOptions.DateKind = 'String' }
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$staging = Join-Path $tempBase ('market-refresh-' + [Guid]::NewGuid().ToString('N'))

function Assert-CatalogSize([string]$Path) {
    $length = (Get-Item -LiteralPath $Path).Length
    if ($length -eq 0 -or $length -gt 1MB) { throw "Invalid catalog size: $Path" }
}

[IO.Directory]::CreateDirectory($staging) | Out-Null
try {
    $releases = Get-MarketReleaseIndex $Repo
    # Stage both signed catalogs before replacing the checkout used for publication.
    foreach ($name in $manifestNames) {
        Write-Host "Refreshing $name from published release statistics."
        $source = Join-Path $RepositoryDirectory $name
        Assert-CatalogSize $source
        Assert-CatalogSize "$source.sig"
        Invoke-PluginSignatureTool $SignatureToolJar @('verify-manifest', '--manifest', $source,
            '--signature', "$source.sig", '--repository-id', 'official', '--policy', 'official')
        $manifest = [IO.File]::ReadAllText($source, $utf8) | ConvertFrom-Json @jsonOptions
        if ($manifest.schemaVersion -ne 1 -or @($manifest.entries).Count -eq 0) {
            throw "Unsupported or empty catalog: $name"
        }
        $changed = 0
        foreach ($entry in $manifest.entries) {
            $packages = @($entry.packages | Where-Object { $_.version -ceq $entry.market.latestVersion })
            if ($packages.Count -ne 1) { throw "Missing or ambiguous latest package: $($entry.pluginId)" }
            $package = $packages[0]
            $url = [string]$package.packageUrl
            if ($url -cnotmatch ('^https://github\.com/' + [regex]::Escape($Repo) + '/releases/download/([^/?#]+)/([^/?#]+)$')) {
                throw "Unexpected package repository: $($entry.pluginId)"
            }
            $tag = [Uri]::UnescapeDataString($Matches[1])
            $assetName = [Uri]::UnescapeDataString($Matches[2])
            if (-not $releases.ContainsKey($tag)) { throw "Published asset missing: $tag/$assetName" }
            $release = $releases[$tag]
            $assets = @($release.assets | Where-Object { $_.name -ceq $assetName })
            if ($release.draft -or $release.tag_name -cne $tag -or $assets.Count -ne 1) {
                throw "Published asset missing or ambiguous: $tag/$assetName"
            }
            $asset = $assets[0]
            if ($asset.browser_download_url -cne $url -or $asset.size -ne $package.expectedSizeBytes) {
                throw "Published asset identity changed: $tag/$assetName"
            }
            if ($asset.PSObject.Properties.Name -contains 'digest' -and $asset.digest -and
                    $asset.digest -cne "sha256:$($package.sha256)") {
                throw "Published asset digest changed: $tag/$assetName"
            }
            $suffix = '-' + $package.version + [IO.Path]::GetExtension($assetName)
            if (-not $assetName.EndsWith($suffix, [StringComparison]::Ordinal)) { throw 'Invalid plugin artifact name.' }
            $module = $assetName.Substring(0, $assetName.Length - $suffix.Length)
            $statistics = Get-PluginDownloadStatistics $releases $entry.pluginId $module $tag $assetName
            if ($entry.market.totalDownloadCount -ne $statistics.TotalDownloadCount -or
                    $entry.market.downloadCount -ne $statistics.DownloadCount) { $changed++ }
            $entry.market.downloadCount = $statistics.DownloadCount
            $entry.market.previousDownloadCount = $statistics.PreviousDownloadCount
            $entry.market.totalDownloadCount = $statistics.TotalDownloadCount
        }
        $manifest.generatedTime = $generatedTime
        $output = Join-Path $staging $name
        $json = ($manifest | ConvertTo-Json -Depth 64) -replace "`r`n", "`n"
        [IO.File]::WriteAllText($output, $json + "`n", $utf8)
        Assert-CatalogSize $output
        Invoke-PluginSignatureTool $SignatureToolJar @('manifest', '--manifest', $output,
            '--repository-id', 'official', '--key-id', $OfficialKeyId,
            '--private-key', $PrivateKeyFile, '--out', "$output.sig")
        Invoke-PluginSignatureTool $SignatureToolJar @('verify-manifest', '--manifest', $output,
            '--signature', "$output.sig", '--repository-id', 'official', '--policy', 'official')
        Write-Host "Prepared ${name}: $(@($manifest.entries).Count) plugins, $changed download counts changed."
    }
    foreach ($name in $manifestNames) {
        foreach ($file in @($name, "$name.sig")) {
            Copy-Item -LiteralPath (Join-Path $staging $file) -Destination (Join-Path $RepositoryDirectory $file) -Force
        }
    }
    Write-Host 'Refreshed both signed plugin catalogs; package identities and history are unchanged.'
} finally {
    $resolved = [IO.Path]::GetFullPath($staging)
    if ((Split-Path -Parent $resolved).TrimEnd('\', '/') -ne $tempBase -or
            (Split-Path -Leaf $resolved) -notlike 'market-refresh-*') { throw 'Unsafe staging cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
