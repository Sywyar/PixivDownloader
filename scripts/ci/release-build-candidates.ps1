# Transfer only unsigned plugin outputs from this run's completed release artifact gate.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Export', 'Import')][string]$Mode,
    [Parameter(Mandatory = $true)][string]$Directory,
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '../..'),
    [string]$SourceSha = $env:GITHUB_SHA,
    [string]$RunId = $env:GITHUB_RUN_ID,
    [string]$Attempt = $env:GITHUB_RUN_ATTEMPT
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$Directory = [IO.Path]::GetFullPath($Directory)
if ($SourceSha -notmatch '^[0-9a-f]{40}$' -or $RunId -notmatch '^[1-9][0-9]*$' -or $Attempt -notmatch '^[1-9][0-9]*$') {
    throw 'Candidate source SHA, run ID and attempt are required.'
}
$head = & git -C $ProjectRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $head -cne $SourceSha) { throw 'Candidate source does not match checkout.' }
[xml]$pom = [IO.File]::ReadAllText((Join-Path $ProjectRoot 'pom.xml'), [Text.Encoding]::UTF8)
$modules = @($pom.project.modules.module | Where-Object {
    $_ -eq 'pixivdownload-plugin-signature' -or
    (Test-Path -LiteralPath (Join-Path $ProjectRoot "$_/src/main/resources/plugin.properties") -PathType Leaf)
})
$manifestPath = Join-Path $Directory 'manifest.json'

function Assert-CandidatePath([string]$RelativePath) {
    if ($RelativePath -notmatch '^([a-z0-9-]+)/target/(classes/.+|[a-z0-9.-]+\.jar)$' -or
        $modules -cnotcontains $Matches[1] -or $RelativePath -match '(^|/)(\.|\.\.)(/|$)|\\|:') {
        throw "Invalid candidate path: $RelativePath"
    }
}

function Assert-RegularPath([string]$Root, [string]$RelativePath) {
    $current = $Root
    foreach ($part in $RelativePath.Split('/')) {
        $current = [IO.Path]::Combine($current, $part)
        $attributes = [IO.File]::GetAttributes($current)
        if ($attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked candidate path: $RelativePath" }
    }
    if ($attributes -band [IO.FileAttributes]::Directory) { throw "Candidate is not a file: $RelativePath" }
    return $current
}

if ($Mode -eq 'Export') {
    if (Test-Path -LiteralPath $Directory) { throw 'Candidate export directory must be new.' }
    $files = @()
    foreach ($module in $modules) {
        $target = Join-Path $ProjectRoot "$module/target"
        $jars = @(Get-ChildItem -LiteralPath $target -File | Where-Object {
            $_.Name -like "$module-*.jar" -and $_.Name -notmatch '-(sources|javadoc|original)\.jar$'
        })
        if ($jars.Count -ne 1) { throw "Expected one final candidate JAR for $module." }
        $classes = @(Get-ChildItem -LiteralPath (Join-Path $target 'classes') -Recurse -File)
        if ($classes.Count -eq 0) { throw "Missing candidate classes for $module." }
        foreach ($file in @($classes) + @($jars)) {
            $relative = $file.FullName.Substring($ProjectRoot.Length + 1).Replace('\', '/')
            Assert-CandidatePath $relative
            $source = Assert-RegularPath $ProjectRoot $relative
            $destination = Join-Path $Directory $relative
            [IO.Directory]::CreateDirectory((Split-Path -Parent $destination)) | Out-Null
            [IO.File]::Copy($source, $destination)
            $files += @{path=$relative; sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()}
        }
    }
    $manifest = @{schemaVersion=1; sourceSha=$SourceSha; runId=$RunId; attempt=$Attempt;
        build='java17-verify-official-surveys-proguard'; modules=$modules; files=$files}
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
} else {
    $manifest = [IO.File]::ReadAllText($manifestPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1 -or $manifest.sourceSha -cne $SourceSha -or
        $manifest.runId -cne $RunId -or $manifest.attempt -notmatch '^[1-9][0-9]*$' -or
        [long]$manifest.attempt -gt [long]$Attempt -or
        $manifest.build -cne 'java17-verify-official-surveys-proguard' -or
        ($manifest.modules -join ',') -cne ($modules -join ',')) { throw 'Candidate provenance mismatch.' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($file in $manifest.files) {
        Assert-CandidatePath $file.path
        if (-not $seen.Add($file.path)) { throw 'Duplicate candidate path.' }
        $source = Assert-RegularPath $Directory $file.path
        if ($file.sha256 -notmatch '^[0-9a-f]{64}$' -or
            (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ine $file.sha256) {
            throw "Candidate SHA-256 mismatch: $($file.path)"
        }
    }
    foreach ($module in $modules) {
        if (@($manifest.files | Where-Object { $_.path -like "$module/target/$module-*.jar" }).Count -ne 1 -or
            @($manifest.files | Where-Object { $_.path -like "$module/target/classes/*" }).Count -eq 0) {
            throw "Incomplete candidate module: $module"
        }
        if (Test-Path -LiteralPath (Join-Path $ProjectRoot "$module/target")) {
            throw "Candidate target must be absent before import: $module"
        }
    }
    # All metadata, paths and digests pass before the first target is written. JARs follow classes
    # so the existing artifact freshness checks remain meaningful after artifact transfer.
    foreach ($file in @($manifest.files | Sort-Object { $_.path -notmatch '/target/classes/' })) {
        $destination = Join-Path $ProjectRoot $file.path
        [IO.Directory]::CreateDirectory((Split-Path -Parent $destination)) | Out-Null
        [IO.File]::Copy((Join-Path $Directory $file.path), $destination)
        if ($file.path -notmatch '/target/classes/') {
            [IO.File]::SetLastWriteTimeUtc($destination, [DateTime]::UtcNow)
        }
    }
}
Write-Host "$Mode verified release candidates for $SourceSha (run $RunId, attempt $Attempt)."
