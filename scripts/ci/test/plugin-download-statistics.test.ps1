$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
. (Join-Path $repoRoot 'scripts/plugin-download-statistics.ps1')
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$fixture = Join-Path $tempBase ('plugin-statistics-test-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($fixture) | Out-Null

function Assert-Statistics($Actual, $Current, $Previous) {
    if ($Actual.DownloadCount -ne $Current -or $Actual.PreviousDownloadCount -ne $Previous -or
            $Actual.TotalDownloadCount -ne ($Current + $Previous)) {
        throw "Unexpected statistics: $($Actual | ConvertTo-Json -Compress)"
    }
}
function Assert-Count($Actual, $Expected) {
    if ($Actual -ne $Expected) { throw "Expected $Expected, got $Actual" }
}
function Assert-Fails([scriptblock]$Action, [string]$Reason) {
    try { & $Action } catch {
        if ($_.Exception.Message -notlike "*$Reason*") { throw }
        return
    }
    throw "Expected failure: $Reason"
}
function New-Asset([long]$Id, [string]$Version, [long]$Count) {
    return @{id=$Id; name="sample-$Version.jar"; download_count=$Count}
}
function New-Release([string]$Tag, $Assets, [string]$Body = '') {
    return @{tag_name=$Tag; draft=$false; body=$Body; assets=@($Assets)}
}

try {
    & {
        $script:pages = ,@(
            (New-Release 'sample-v1.0.0' (New-Asset 1 '1.0.0' 4)),
            (New-Release 'sample-v1.1.0' (New-Asset 2 '1.1.0' 6)),
            (New-Release 'sample-v1.2.0' @((New-Asset 3 '1.2.0' 9), @{id=30;name='sample-1.2.0.jar.sig';download_count=999})),
            (New-Release 'sample-nightly' (New-Asset 4 '1.2.0-nightly.20200101.1.1' 2) '<!-- pixivdownload-downloads:v1 retired=10 asset=100 count=10 -->'),
            (New-Release 'sample-v1.0.0-nightly.20200101.1.1' (New-Asset 5 '1.0.0-nightly.20200101.1.1' 999))
        )
        $script:requests = 0
        $script:pageFailure = 0
        $script:rawPage = $null
        function gh {
            $global:LASTEXITCODE = 0
            if ($args.Count -ne 2 -or $args[0] -ne 'api' -or
                    $args[1] -notmatch '^repos/fixture/plugins/releases\?per_page=100&page=([1-9][0-9]*)$') {
                throw 'Unexpected release statistics request.'
            }
            $page = [int]$Matches[1]
            $script:requests++
            if ($script:pageFailure -eq $page) { $global:LASTEXITCODE = 1; return }
            if ($null -ne $script:rawPage) { return $script:rawPage }
            ConvertTo-Json -InputObject @($script:pages[$page - 1]) -Depth 8
        }
        $index = Get-MarketReleaseIndex 'fixture/plugins'
        Assert-Count $script:requests 1
        Assert-Statistics (Get-PluginDownloadStatistics $index sample sample sample-v1.2.0 sample-1.2.0.jar) 9 10
        Assert-Statistics (Get-PluginDownloadStatistics $index sample sample sample-nightly sample-1.2.0-nightly.20200101.1.1.jar) 2 10
        $index['sample-v1.0.0'].assets[0].download_count = 7
        Assert-Statistics (Get-PluginDownloadStatistics $index sample sample sample-v1.2.0 sample-1.2.0.jar) 9 13
        $index['sample-v1.0.0'].body = '<!-- pixivdownload-downloads:v1 retired=5 asset=1 count=5 -->'
        Assert-Statistics (Get-PluginDownloadStatistics $index sample sample sample-v1.2.0 sample-1.2.0.jar) 9 13
        $index['sample-v1.0.0'].assets = @()
        Assert-Statistics (Get-PluginDownloadStatistics $index sample sample sample-v1.2.0 sample-1.2.0.jar) 9 11
        $index['sample-nightly'].body = ''
        Assert-Statistics (Get-PluginDownloadStatistics $index sample sample sample-nightly sample-1.2.0-nightly.20200101.1.1.jar) 2 0

        $script:pages = @(@(1..100 | ForEach-Object { New-Release "other-v0.$_.0" @() }),
            @((New-Release 'sample-v1.2.0' (New-Asset 3 '1.2.0' 9))))
        $script:requests = 0
        $index = Get-MarketReleaseIndex 'fixture/plugins'
        Assert-Count $script:requests 2
        Assert-Statistics (Get-PluginDownloadStatistics $index sample sample sample-v1.2.0 sample-1.2.0.jar) 9 0
        $script:pageFailure = 2
        Assert-Fails { Get-MarketReleaseIndex 'fixture/plugins' } 'Failed to read release statistics page 2'
        $script:pageFailure = 0
        $script:pages[1] = @($script:pages[0][0])
        Assert-Fails { Get-MarketReleaseIndex 'fixture/plugins' } 'repeated release identity'
        $script:rawPage = '[' + (' ' * 16MB) + ']'
        Assert-Fails { Get-MarketReleaseIndex 'fixture/plugins' } 'exceeds 16 MiB'
        $script:rawPage = '[]'
        Assert-Count (Get-MarketReleaseIndex 'fixture/plugins').Count 0
        Assert-Count (Read-PluginDownloadCount ([long]::MaxValue)) ([long]::MaxValue)
        Assert-Fails { Read-PluginDownloadCount -1 } 'Invalid download count'
        foreach ($body in @('<!-- pixivdownload-downloads:v2 -->',
                '<!-- pixivdownload-downloads:v1 retired=1 asset=4 count=2 -->',
                '<!-- pixivdownload-downloads:v1 retired=9223372036854775808 asset=4 count=2 -->',
                ('<!-- pixivdownload-downloads:v1 retired=1 asset=4 count=1 -->' * 2))) {
            Assert-Fails { Read-ReleaseDownloadCheckpoint $body } 'Invalid'
        }
        Assert-Fails { Add-PluginDownloadCount ([long]::MaxValue) 1 } 'overflow'
    }

    & {
        # Run the publisher with only build, signing and GitHub process boundaries replaced.
        $parseErrors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile(
            (Join-Path $repoRoot 'scripts/publish-plugin-releases.ps1'), [ref]$null, [ref]$parseErrors)
        if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
        $functions = @($ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.FunctionDefinitionAst] })
        foreach ($definition in $functions) { . ([scriptblock]::Create($definition.Extent.Text)) }
        $statements = @($ast.EndBlock.Statements | Where-Object {
            $_ -notin $functions -and -not ($_ -is [Management.Automation.Language.PipelineAst] -and
                $_.PipelineElements[0] -is [Management.Automation.Language.CommandAst] -and
                $_.PipelineElements[0].InvocationOperator -eq 'Dot')
        })
        $publisher = [scriptblock]::Create($ast.ParamBlock.Extent.Text + "`n" + (($statements | ForEach-Object { $_.Extent.Text }) -join "`n"))
        $key = Join-Path $fixture 'key-fixture'
        [IO.File]::WriteAllText($key, 'fixture')
        function Resolve-SignatureToolJar { return 'fixture' }
        function Resolve-MarketContentTool { return 'fixture' }
        function Get-PixivDownloadSdkVersion { return '7.0.0' }
        function Get-MarketSourceCommit { return ('c' * 40) }
        function Get-OfficialDistributionPlugins { param([switch]$IncludeOptional) return @(@{Id='sample';Module='sample'}) }
        function Read-SourceVersion { return '1.2.0' }
        function Get-NightlyPluginVersion { param($SourceVersion,$Suffix) return "$SourceVersion-$Suffix" }
        function Get-OfficialPluginArtifactName { param($Plugin,$Version) return "sample-$Version.jar" }
        function Build-StagedPluginArtifact { param($Plugin,$Version,$AssetName) return (Join-Path $fixture $AssetName) }
        function Build-StagedNightlyPluginArtifact { param($Plugin,$SourceVersion,$Version,$AssetName) return (Join-Path $fixture $AssetName) }
        function Write-StagedCompanionFiles { param($StagedArtifact,$AssetName,$Plugin,$Version) return @{ShaFile="$StagedArtifact.sha256";SigFile="$StagedArtifact.sig"} }
        function New-OfficialMarketContent { return @() }
        function Get-OfficialMarketContentHash { return ('a' * 64) }
        function Read-OfficialMarketContent { return $null }
        $script:release = $null
        $script:nextId = 10
        $script:fault = ''
        $script:deletes = 0
        $script:edits = 0
        function gh {
            $global:LASTEXITCODE = 0
            $operation = $args[1]
            $tag = $args[2]
            switch ($operation) {
                view {
                    if ($null -eq $script:release) { $global:LASTEXITCODE=1; return 'release not found' }
                    $assets = @($script:release.assets | ForEach-Object {
                        @{name=$_.name;downloadCount=$_.download_count;apiUrl="https://api.github.com/repos/fixture/plugins/releases/assets/$($_.id)"}
                    })
                    return (@{body=$script:release.body;assets=$assets} | ConvertTo-Json -Depth 8)
                }
                edit {
                    $script:edits++
                    if ($script:fault -eq 'checkpoint') { $global:LASTEXITCODE=1; return }
                    $script:release.body = [IO.File]::ReadAllText($args[[Array]::IndexOf($args,'--notes-file')+1], [Text.Encoding]::UTF8)
                    if ($script:fault -eq 'checkpoint-response') { $global:LASTEXITCODE=1 }
                    return
                }
                delete-asset {
                    $checkpoint = Read-ReleaseDownloadCheckpoint $script:release.body
                    if (-not $checkpoint.Present) { throw 'Deletion occurred before durable checkpoint.' }
                    $script:deletes++
                    if ($script:fault -eq 'delete') { $global:LASTEXITCODE=1; return }
                    $deletedName = $args[3]
                    $script:release.assets = @($script:release.assets | Where-Object { $_.name -cne $deletedName })
                    return
                }
                create {
                    $body = [IO.File]::ReadAllText($args[[Array]::IndexOf($args,'--notes-file')+1], [Text.Encoding]::UTF8)
                    $script:release = New-Release $tag @() $body
                }
                upload { }
                default { throw "Unexpected publication command: $operation" }
            }
            $paths = @($args[3])
            if ($operation -eq 'create' -and $args[3] -eq '--repo') { return }
            if ($script:fault -eq 'upload') { $global:LASTEXITCODE=1; return }
            foreach ($path in $paths) {
                $name = [IO.Path]::GetFileName($path)
                if ($name -like '*.jar') {
                    $script:release.assets += @{id=$script:nextId;name=$name;download_count=0}
                    $script:nextId++
                }
            }
        }
        foreach ($nightly in @($false,$true)) {
            $tag = if ($nightly) { 'sample-nightly' } else { 'sample-v1.2.0' }
            $version = if ($nightly) { '1.2.0-nightly.20200101.1.1' } else { '1.2.0' }
            $parameters = @{Repo='fixture/plugins';ProjectRoot=$fixture;PrivateKeyFile=$key;OfficialKeyId='fixture';UsePrebuiltArtifacts=$true}
            if ($nightly) { $parameters.NightlyBuildVersion='8.0.0-nightly.20200101.1.1' } else { $parameters.Force=$true }
            foreach ($failure in @('checkpoint','checkpoint-response','delete','upload')) {
                $script:release = New-Release $tag (New-Asset 1 $version 12)
                $script:deletes=0; $script:edits=0; $script:fault=$failure
                Assert-Fails { & $publisher @parameters } $(switch ($failure) {
                    checkpoint { 'Failed to save download checkpoint' }
                    checkpoint-response { 'Failed to save download checkpoint' }
                    delete { 'delete-asset failed' }
                    upload { 'upload failed' }
                })
                if ($failure -like 'checkpoint*') { Assert-Count $script:deletes 0 }
                $expected = 12
                if ($failure -eq 'checkpoint-response') {
                    $script:release.assets[0].download_count = 14
                    $expected = 14
                }
                $script:fault=''
                & $publisher @parameters
                $index=@{}; $index[$tag]=$script:release
                Assert-Statistics (Get-PluginDownloadStatistics $index sample sample $tag "sample-$version.jar") 0 $expected
                $script:release.assets[0].download_count=3
                & $publisher @parameters
                Assert-Statistics (Get-PluginDownloadStatistics $index sample sample $tag "sample-$version.jar") 0 ($expected + 3)
                Assert-Count ([regex]::Matches($script:release.body,'pixivdownload-downloads:').Count) 1
            }
            $script:release=$null; $script:fault='upload'
            Assert-Fails { & $publisher @parameters } $(if ($nightly) {'create failed'} else {'upload failed'})
            $script:fault=''
            & $publisher @parameters
            $index=@{}; $index[$tag]=$script:release
            Assert-Statistics (Get-PluginDownloadStatistics $index sample sample $tag "sample-$version.jar") 0 0
            if ($nightly) {
                foreach ($run in 2..10) {
                    $parameters.NightlyBuildVersion = "8.0.0-nightly.20200101.$run.1"
                    $script:release.assets[0].download_count = 1
                    & $publisher @parameters
                    Assert-Count $script:release.assets.Count 1
                    Assert-Count ([regex]::Matches($script:release.body, 'pixivdownload-downloads:').Count) 1
                    Assert-Statistics (Get-PluginDownloadStatistics $index sample sample $tag "sample-1.2.0-nightly.20200101.$run.1.jar") 0 ($run - 1)
                }
            }
        }
    }
    Write-Host 'PASS: batch pagination, channel isolation, historical increments, replacement checkpoints, interrupted publication and retry totals.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ((Split-Path -Parent $resolved).TrimEnd('\', '/') -ne $tempBase -or
            (Split-Path -Leaf $resolved) -notlike 'plugin-statistics-test-*') { throw 'Unsafe fixture cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
$global:LASTEXITCODE=0
