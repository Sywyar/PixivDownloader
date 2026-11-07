[CmdletBinding()]
param(
    [ValidateSet('prepare', 'run')][string]$Command = 'run',
    [Parameter(Mandatory = $true)][string]$Jar,
    [Parameter(Mandatory = $true)][string]$RuntimeDirectory,
    [ValidateSet('true', 'false')][string]$Offline = 'false',
    [string]$LocalRepository,
    [string]$UserSettings,
    [string]$GlobalSettings
)

$ErrorActionPreference = 'Stop'
$Utf8 = New-Object Text.UTF8Encoding($false)
[Console]::OutputEncoding = $Utf8
$OutputEncoding = $Utf8
if ($Command -eq 'prepare' -and $env:OS -eq 'Windows_NT' -and $PSVersionTable.PSVersion.Major -ge 6) {
    # Use the same Windows host as Maven for consistent process-tree ownership.
    . (Join-Path $PSScriptRoot 'aot-prepare-process.ps1')
    $windowsPowerShell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $forwarded = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath,
        '-Command', $Command, '-Jar', $Jar, '-RuntimeDirectory', $RuntimeDirectory, '-Offline', $Offline)
    foreach ($name in @('LocalRepository', 'UserSettings', 'GlobalSettings')) {
        $value = Get-Variable -Name $name -ValueOnly
        if ($value) { $forwarded += @("-$name", $value) }
    }
    & $windowsPowerShell @forwarded
    exit $LASTEXITCODE
}
foreach ($name in @('MAVEN_OPTS', 'MAVEN_DEBUG_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) {
    $options = [Environment]::GetEnvironmentVariable($name)
    if (-not $options) { continue }
    $filtered = [regex]::Replace($options, '(?:"[^"]*"|''[^'']*''|[^\s"''])+', {
        param($token)
        $argument = $token.Value -replace '["'']', ''
        if ($argument -match '^(?i)-(?:agentlib:jdwp(?:=|$)|Xrunjdwp:|javaagent:.*[/\\]debugger-agent\.jar(?:=|$))') { return '' }
        return $token.Value
    })
    if ($filtered -cne $options) {
        [Environment]::SetEnvironmentVariable($name, $filtered.Trim(), 'Process')
        Write-Host "AOT: isolated debugger options from $name; child processes run without JDWP."
    }
}
if ($PSVersionTable.PSVersion.Major -lt 6) {
    $env:PSModulePath = (Join-Path $PSHOME 'Modules') + [IO.Path]::PathSeparator + $env:PSModulePath
}
if ($Command -eq 'prepare' -and $env:OS -eq 'Windows_NT') {
    . (Join-Path $PSScriptRoot 'aot-prepare-process.ps1')
}
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Jar = [IO.Path]::GetFullPath($Jar)
$RuntimeDirectory = [IO.Path]::GetFullPath($RuntimeDirectory)
$Tool = Join-Path $PSScriptRoot 'aot-cache.mjs'
. (Join-Path $PSScriptRoot 'package-local-plugin-staging.ps1')

function Assert-PlainPath([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "AOT runtime paths cannot contain links: $current"
            }
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
}

function Read-State([string]$Path) {
    Assert-PlainPath $Path
    $file = Get-Item -LiteralPath $Path
    if ($file.PSIsContainer -or $file.Length -gt 1MB) { throw "Invalid AOT state file: $Path" }
    return [IO.File]::ReadAllText($Path, $Utf8) | ConvertFrom-Json
}

function Assert-Bundle($State) {
    if ($State.schemaVersion -ne 1 -or $State.bundle -cnotmatch '^[a-f0-9]{64}$' -or
        $State.cache -cnotmatch '^[a-f0-9]{64}$' -or @($State.files).Count -lt 2) {
        throw 'Invalid AOT runtime state; run AOT Prepare in a new runtime directory.'
    }
    $bundle = Join-Path $RuntimeDirectory "artifacts/$($State.bundle)"
    foreach ($file in $State.files) {
        if ($file.name -cnotmatch '^(application\.jar|plugins/[a-z0-9-]+\.(jar|zip))$' -or
            $file.sha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Invalid AOT artifact record.' }
        $artifact = Join-Path $bundle $file.name
        Assert-PlainPath $artifact
        if ((Get-Sha256Hex $artifact) -cne $file.sha256) { throw "AOT artifact changed: $artifact" }
    }
    return $bundle
}

Assert-PlainPath $RuntimeDirectory
if (Test-Path -LiteralPath $RuntimeDirectory) {
    if (-not (Test-Path -LiteralPath (Join-Path $RuntimeDirectory '.aot-packaged') -PathType Leaf)) {
        throw 'Runtime directory is not owned by AOT Prepare; choose a new -Daot.runtime.directory.'
    }
} elseif ($Command -eq 'run') {
    throw 'Packaged runtime is missing; run AOT Prepare first.'
} else {
    [void][IO.Directory]::CreateDirectory($RuntimeDirectory)
    [IO.File]::WriteAllText((Join-Path $RuntimeDirectory '.aot-packaged'), '1', $Utf8)
}
Assert-PlainPath (Join-Path $RuntimeDirectory '.lock')
try { $lock = [IO.File]::Open((Join-Path $RuntimeDirectory '.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
catch { throw "Cannot acquire AOT runtime lock '$RuntimeDirectory': $($_.Exception.GetBaseException().Message) Close another AOT Prepare/Packaged Run if it is still active." }

$staging = $null
$temporaryState = $null
Push-Location -LiteralPath $ProjectRoot
try {
    $statePath = Join-Path $RuntimeDirectory 'current.json'
    Assert-PlainPath $statePath
    if ($Command -eq 'prepare') {
        Write-Host 'AOT packaged prepare: building host and official plugins (tests run separately).'
        $wrapper = Join-Path $ProjectRoot 'mvnw'
        if ($env:OS -eq 'Windows_NT') { $wrapper += '.cmd' }
        $buildArgs = @('-B', '-ntp', '-pl', 'pixivdownload-app,pixivdownload-official-plugins', '-am',
            'verify', '-P!aot', '-DskipTests', '-Dspring-boot.run.skip=true')
        if ($Offline -eq 'true') { $buildArgs += '-o' }
        if ($LocalRepository) { $buildArgs += "-Dmaven.repo.local=$LocalRepository" }
        if ($UserSettings -and (Test-Path -LiteralPath $UserSettings -PathType Leaf)) { $buildArgs += @('-s', $UserSettings) }
        if ($GlobalSettings -and (Test-Path -LiteralPath $GlobalSettings -PathType Leaf)) { $buildArgs += @('-gs', $GlobalSettings) }
        & $wrapper @buildArgs
        if ($LASTEXITCODE -ne 0) { throw "AOT Maven build failed: $LASTEXITCODE" }
        Assert-BootJarBoundary $Jar
        $plugins = @(Get-OfficialDistributionPlugins -IncludeOptional)
        $files = @([ordered]@{ name = 'application.jar'; sha256 = (Get-Sha256Hex $Jar) })
        foreach ($plugin in $plugins) {
            $source = Find-ModulePluginArtifact $plugin $ProjectRoot
            $files += [ordered]@{ name = "plugins/$($plugin.Module).$(Get-OfficialPluginArtifactExtension $plugin)";
                sha256 = (Get-Sha256Hex $source) }
        }
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $bundleKey = ([BitConverter]::ToString($sha.ComputeHash($Utf8.GetBytes(($files | ConvertTo-Json -Compress))))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
        $cacheKey = & node $Tool key --jar $Jar
        if ($LASTEXITCODE -ne 0 -or $cacheKey -cnotmatch '^[a-f0-9]{64}$') { throw 'AOT cache identity failed.' }
        $state = [ordered]@{ schemaVersion = 1; bundle = $bundleKey; cache = $cacheKey; files = $files }
        $bundle = Join-Path $RuntimeDirectory "artifacts/$bundleKey"
        Assert-PlainPath $bundle
        if (-not (Test-Path -LiteralPath $bundle)) {
            $staging = Join-Path $RuntimeDirectory ('.staging-' + [Guid]::NewGuid().ToString('N'))
            [void][IO.Directory]::CreateDirectory($staging)
            Copy-Item -LiteralPath $Jar -Destination (Join-Path $staging 'application.jar')
            Write-Host 'AOT packaged prepare: staging local unsigned plugins for this development runtime.'
            [void](Stage-OfficialPlugins -AppDir $staging -Plugins $plugins -ProjectRoot $ProjectRoot -AllowUnsignedLocalPlugins)
            [void][IO.Directory]::CreateDirectory((Split-Path -Parent $bundle))
            [IO.Directory]::Move($staging, $bundle)
            $staging = $null
        } else { Write-Host 'AOT packaged prepare: reusing existing artifact bundle.' }
        [void](Assert-Bundle $state)
        $cache = Join-Path $RuntimeDirectory "cache/$cacheKey"
        Assert-PlainPath $cache
        & node $Tool prepare --jar (Join-Path $bundle 'application.jar') --cache-dir $cache
        if ($LASTEXITCODE -ne 0) { throw "AOT cache preparation failed: $LASTEXITCODE" }
        $temporaryState = Join-Path $RuntimeDirectory ('.current-' + [Guid]::NewGuid().ToString('N') + '.json')
        [IO.File]::WriteAllText($temporaryState, ($state | ConvertTo-Json -Depth 5), $Utf8)
        if (Test-Path -LiteralPath $statePath) { [IO.File]::Replace($temporaryState, $statePath, [NullString]::Value) }
        else { [IO.File]::Move($temporaryState, $statePath) }
        $temporaryState = $null
        Write-Host "AOT packaged prepare: ready, plugins=$($plugins.Count), runtime=$RuntimeDirectory"
    } else {
        if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { throw 'Run AOT Prepare before AOT Packaged Run.' }
        $state = Read-State $statePath
        $bundle = Assert-Bundle $state
        $cache = Join-Path $RuntimeDirectory "cache/$($state.cache)"
        $instance = Join-Path $RuntimeDirectory 'instance'
        Assert-PlainPath $cache
        Assert-PlainPath $instance
        Assert-PlainPath (Join-Path $instance 'ipc')
        [void][IO.Directory]::CreateDirectory($instance)
        Set-Location -LiteralPath $instance
        Write-Host "AOT packaged run: starting prepared local artifacts, instance=$instance"
        & node $Tool run --jar (Join-Path $bundle 'application.jar') --cache-dir $cache --plugins-dir (Join-Path $bundle 'plugins') --instance-dir (Join-Path $instance 'ipc')
        if ($LASTEXITCODE -ne 0) { throw "AOT packaged run failed: $LASTEXITCODE" }
    }
} finally {
    Pop-Location
    $lock.Dispose()
    if ($staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
    if ($temporaryState) { Remove-Item -LiteralPath $temporaryState -Force }
}
