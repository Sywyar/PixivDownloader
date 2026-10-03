# 暂存与公开回读共用 SDK 合同，发布脚本不另行解析文档。
function Resolve-MarketContentTool {
    param([string]$ProjectRoot)
    $directory = Join-Path $ProjectRoot 'pixivdownload-sdk-tools/target'
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        throw 'Expected one verified SDK tools JAR for market content.'
    }
    $jars = @(Get-ChildItem -LiteralPath $directory -File |
        Where-Object { $_.Name -match '^pixivdownload-sdk-tools-.+\.jar$' -and $_.Name -notmatch '-(sources|javadoc)\.jar$' })
    if ($jars.Count -ne 1 -or $jars[0].Length -eq 0) { throw 'Expected one verified SDK tools JAR for market content.' }
    return $jars[0].FullName
}

function Invoke-MarketContentTool {
    param([string]$ProjectRoot, [string[]]$Arguments)
    $tool = Resolve-MarketContentTool $ProjectRoot
    & java '-Djava.net.useSystemProxies=true' '-jar' $tool @Arguments | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw 'SDK market content validation failed.' }
}

function New-OfficialMarketContent {
    param([string]$ProjectRoot, [string]$Repository, [string]$Tag, [string]$PluginId, [string]$SourceVersion, [string]$StageRoot,
        [string]$SourceCommit)
    $curation = Join-Path $ProjectRoot 'scripts/market-curation.json'
    $directory = Join-Path $StageRoot ('content-' + [Guid]::NewGuid().ToString('N'))
    $base = "https://github.com/$Repository/releases/download/$Tag/"
    $arguments = @('market-content-prepare', $ProjectRoot, $curation, $PluginId, $SourceVersion, $base, $directory)
    if ($SourceCommit) {
        if ($SourceCommit -cnotmatch '^[a-f0-9]{40}$') { throw 'Invalid market source commit.' }
        $arguments += "https://github.com/Sywyar/PixivDownloader/blob/$SourceCommit/"
    }
    Invoke-MarketContentTool $ProjectRoot $arguments
    return @(Get-ChildItem -LiteralPath $directory -File | ForEach-Object { $_.FullName })
}

function Read-OfficialMarketContent {
    param([string]$ProjectRoot, [string]$Repository, [string]$Tag, [string[]]$AssetNames, [string]$TemporaryRoot, [string]$ExpectedHash)
    if ($AssetNames -notcontains 'market-content.json') {
        if ($ExpectedHash) { throw 'Versioned market content metadata is missing.' }
        return $null
    }
    $directory = Join-Path $TemporaryRoot ('content-' + [Guid]::NewGuid().ToString('N'))
    $base = "https://github.com/$Repository/releases/download/$Tag/"
    Invoke-MarketContentTool $ProjectRoot @('market-content-download', $base, $directory)
    if ($ExpectedHash -and (Get-OfficialMarketContentHash @((Join-Path $directory 'market-content.json'))) -cne $ExpectedHash) {
        throw 'Public market content metadata does not match the release identity.'
    }
    return (Get-Content -LiteralPath (Join-Path $directory 'market-content.json') -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Get-OfficialMarketContentHash {
    param([string[]]$Paths)
    $metadata = @($Paths | Where-Object { [IO.Path]::GetFileName($_) -eq 'market-content.json' })
    if ($metadata.Count -ne 1) { throw 'Market content metadata is missing.' }
    return (Get-FileHash -LiteralPath $metadata[0] -Algorithm SHA256).Hash.ToLowerInvariant()
}
