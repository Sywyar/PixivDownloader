$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$program = Join-Path $repoRoot 'scripts/refresh-market-manifests.ps1'
$utf8 = New-Object Text.UTF8Encoding($false)
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$fixture = Join-Path $tempBase ('market-refresh-test-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($fixture) | Out-Null
$names = @('manifest.json', 'nightly-manifest.json')
$global:marketRefreshTestFault = ''
$global:marketRefreshTestRequests = 0

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

# Digest fixtures replace external tools; the actual script updates and preserves files.
function java {
    $global:LASTEXITCODE = 0
    $command = $args[3]
    $manifestPath = $args[[Array]::IndexOf($args, '--manifest') + 1]
    $name = Split-Path -Leaf $manifestPath
    $digest = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
    if ($command -eq 'verify-manifest') {
        $signaturePath = $args[[Array]::IndexOf($args, '--signature') + 1]
        if ([IO.File]::ReadAllText($signaturePath) -ne $digest) { $global:LASTEXITCODE = 1 }
    } elseif ($command -eq 'manifest') {
        if ($global:marketRefreshTestFault -eq 'sign' -and $name -eq 'nightly-manifest.json') {
            $global:LASTEXITCODE = 1
            return
        }
        if ($global:marketRefreshTestFault -eq 'sign-invalid') { $digest = 'invalid' }
        $signaturePath = $args[[Array]::IndexOf($args, '--out') + 1]
        [IO.File]::WriteAllText($signaturePath, $digest, $utf8)
    } else { throw "Unexpected signature operation: $command" }
}

function gh {
    $global:LASTEXITCODE = 0
    Assert-True ($args.Count -eq 2 -and $args[0] -eq 'api') 'Refresh must only read release metadata.'
    Assert-True ($args[1] -eq 'repos/fixture/plugins/releases?per_page=100&page=1') 'Unexpected API endpoint.'
    $global:marketRefreshTestRequests++
    if ($global:marketRefreshTestFault -eq 'api') { $global:LASTEXITCODE = 1; return }
    $releases = @(foreach ($nightly in @($false, $true)) {
        $tag = if ($nightly) { 'sample-nightly' } else { 'sample-v1.4.0' }
        $version = if ($nightly) { '2.0.0-nightly.20261101.1.1' } else { '1.4.0' }
        $asset = @{
            id = $(if ($nightly) { 2 } else { 1 }); name = "sample-$version.jar"; size = 100; download_count = 12
            browser_download_url = "https://github.com/fixture/plugins/releases/download/$tag/sample-$version.jar"
            digest = ('sha256:' + ('a' * 64))
        }
        if ($nightly) {
            switch ($global:marketRefreshTestFault) {
                'missing' { $asset.name = 'replaced.jar' }
                'size' { $asset.size = 99 }
                'url' { $asset.browser_download_url = 'https://example.org/file.jar' }
                'digest' { $asset.digest = 'sha256:bad' }
                'negative' { $asset.download_count = -1 }
                'fraction' { $asset.download_count = 1.5 }
                'overflow' { $asset.download_count = [long]::MaxValue }
            }
        }
        @{ tag_name = $tag; draft = $false; assets = @($asset)
            body = $(if ($nightly) { '<!-- pixivdownload-downloads:v1 retired=9 asset=100 count=9 -->' } else { '' }) }
    })
    $releases += @{tag_name='sample-v1.3.0'; draft=$false; body=''; assets=@(@{id=3; name='sample-1.3.0.jar'; download_count=9})}
    ConvertTo-Json -InputObject $releases -Depth 8
}

function Reset-Fixture {
    foreach ($name in $names) {
        $nightly = $name -eq 'nightly-manifest.json'
        $version = if ($nightly) { '2.0.0-nightly.20261101.1.1' } else { '1.4.0' }
        $tag = if ($nightly) { 'sample-nightly' } else { 'sample-v1.4.0' }
        $manifest = [ordered]@{
            schemaVersion = 1; generatedTime = '2020-01-01T00:00:00Z'
            entries = @([ordered]@{
                pluginId = 'sample'
                market = [ordered]@{latestVersion=$version; downloadCount=4; previousDownloadCount=9; totalDownloadCount=13
                    updatedTime='2020-01-01T08:00:00+08:00'; displayName=@{zh=([string][char]0x4E2D); en='Sample'}}
                packages = @(@{version=$version; packageUrl="https://github.com/fixture/plugins/releases/download/$tag/sample-$version.jar"
                    expectedSizeBytes=100; sha256=('a'*64); signature=@{value='original'}; requiredSdk='1.0'; releasedTime='2020-01-01T00:00:00Z'})
                history = @{path='history/unchanged.json'; sha256=('b'*64); versions=2}
            })
        }
        $path = Join-Path $fixture $name
        [IO.File]::WriteAllText($path, ($manifest | ConvertTo-Json -Depth 16), $utf8)
        [IO.File]::WriteAllText("$path.sig", (Get-FileHash -LiteralPath $path).Hash, $utf8)
    }
}

try {
    $inputFile = Join-Path $fixture 'tool-and-key-fixture'
    [IO.File]::WriteAllText($inputFile, 'fixture', $utf8)
    $parameters = @{RepositoryDirectory=$fixture; Repo='fixture/plugins'; PrivateKeyFile=$inputFile; SignatureToolJar=$inputFile}
    Reset-Fixture
    $original = @{}
    foreach ($name in $names) { $original[$name] = [IO.File]::ReadAllText((Join-Path $fixture $name)) }
    & $program @parameters
    Assert-True ($global:marketRefreshTestRequests -eq 1) 'Both catalogs must share one release list request.'
    foreach ($name in $names) {
        $json = [IO.File]::ReadAllText((Join-Path $fixture $name))
        $current = $json | ConvertFrom-Json
        Assert-True ($current.entries[0].market.downloadCount -eq 12) 'Latest asset count was not refreshed.'
        Assert-True ($current.entries[0].market.totalDownloadCount -eq 21) 'Previous downloads were not preserved.'
        Assert-True ($current.generatedTime -ne '2020-01-01T00:00:00Z') 'Generation time was not refreshed.'
        Assert-True ($json.Contains('2020-01-01T08:00:00+08:00')) 'Release timestamp text changed.'
        $before = $original[$name] | ConvertFrom-Json
        $current.generatedTime = $before.generatedTime
        $current.entries[0].market.downloadCount = $before.entries[0].market.downloadCount
        $current.entries[0].market.totalDownloadCount = $before.entries[0].market.totalDownloadCount
        Assert-True (($current | ConvertTo-Json -Depth 16) -ceq ($before | ConvertTo-Json -Depth 16)) 'Non-statistical catalog fields changed.'
    }
    & $program @parameters
    foreach ($name in $names) {
        $current = Get-Content -LiteralPath (Join-Path $fixture $name) -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-True ($current.entries[0].market.previousDownloadCount -eq 9 -and $current.entries[0].market.totalDownloadCount -eq 21) 'Refresh double-counted previous downloads.'
    }
    $failures = [ordered]@{
        signature='Plugin signature tool failed'; api='Failed to read release'; missing='Published asset missing'
        size='Published asset identity changed'; url='Published asset identity changed'; digest='Published asset digest changed'
        negative='Invalid download count'; fraction='Invalid download count'; overflow='Total download count overflow'
        sign='Plugin signature tool failed'; 'sign-invalid'='Plugin signature tool failed'; oversize='Invalid catalog size'
    }
    foreach ($failure in $failures.Keys) {
        Reset-Fixture
        $global:marketRefreshTestFault = $failure
        if ($failure -eq 'signature') { [IO.File]::WriteAllText((Join-Path $fixture 'nightly-manifest.json.sig'), 'bad') }
        if ($failure -eq 'oversize') { [IO.File]::WriteAllText((Join-Path $fixture 'manifest.json'), ('x' * (1MB + 1))) }
        $hashes = @{}
        foreach ($name in $names) {
            foreach ($file in @($name, "$name.sig")) { $hashes[$file] = (Get-FileHash -LiteralPath (Join-Path $fixture $file)).Hash }
        }
        $rejected = $false
        try { & $program @parameters } catch {
            Assert-True ($_.Exception.Message -like "*$($failures[$failure])*") "Unexpected failure for ${failure}: $($_.Exception.Message)"
            $rejected = $true
        }
        Assert-True $rejected "Expected rejection: $failure"
        foreach ($file in $hashes.Keys) {
            Assert-True ((Get-FileHash -LiteralPath (Join-Path $fixture $file)).Hash -eq $hashes[$file]) "Partial catalog replacement after $failure"
        }
    }
    Write-Host 'PASS: both channels, unchanged identities, repeated refresh, signature/API/asset/count failures and no partial replacement.'
} finally {
    Remove-Variable -Name marketRefreshTestFault -Scope Global
    Remove-Variable -Name marketRefreshTestRequests -Scope Global
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ((Split-Path -Parent $resolved).TrimEnd('\', '/') -ne $tempBase -or
            (Split-Path -Leaf $resolved) -notlike 'market-refresh-test-*') { throw 'Unsafe fixture cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
$global:LASTEXITCODE = 0
