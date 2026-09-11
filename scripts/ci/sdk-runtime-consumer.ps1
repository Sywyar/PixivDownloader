#requires -Version 7.2
# Exercise the delivered SDK tools and both built Maven examples against the complete fixed host.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BuiltProject,
    [Parameter(Mandatory = $true)][string]$WorkDirectory,
    [string]$RuntimeArchive,
    [switch]$Development
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../release-e2e/runtime.ps1')
$BuiltProject = [IO.Path]::GetFullPath($BuiltProject)
$WorkDirectory = [IO.Path]::GetFullPath($WorkDirectory)
if (Test-Path -LiteralPath $WorkDirectory) { throw 'SDK runtime consumer directory must be new.' }
[IO.Directory]::CreateDirectory($WorkDirectory) | Out-Null
$cache = Join-Path $WorkDirectory 'cache'
[IO.Directory]::CreateDirectory($cache) | Out-Null
$metadata = [IO.File]::ReadAllText((Join-Path $BuiltProject 'sdk-project.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
$runtime = $metadata.developmentRuntime
$cachedArchive = Join-Path $cache "$($runtime.archive.sha256).zip"
if ($RuntimeArchive) { [IO.File]::Copy([IO.Path]::GetFullPath($RuntimeArchive), $cachedArchive) }
$Username = 'sdk-runtime-test'
$Password = 'SdkRuntimeTest2026'
$tools = Join-Path $BuiltProject 'tools/sdk-tools.jar'
$java = (Get-Command java).Source
$processes = @()
$sessions = @()

function Invoke-SdkJava([string[]]$Arguments, [string]$Directory, [string]$Log, [switch]$Background) {
    $info = [Diagnostics.ProcessStartInfo]::new($java)
    $info.WorkingDirectory = $Directory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    foreach ($key in @('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) { $info.Environment.Remove($key) | Out-Null }
    $process = [Diagnostics.Process]::Start($info)
    $process | Add-Member -NotePropertyName Stdout -NotePropertyValue ($process.StandardOutput.ReadToEndAsync())
    $process | Add-Member -NotePropertyName Stderr -NotePropertyValue ($process.StandardError.ReadToEndAsync())
    $process | Add-Member -NotePropertyName LogPath -NotePropertyValue $Log
    if ($Background) { return $process }
    try {
        if (-not $process.WaitForExit(180000)) { throw 'SDK preparation/setup exceeded three minutes.' }
        [IO.File]::WriteAllText($Log, $process.Stdout.Result + $process.Stderr.Result, [Text.UTF8Encoding]::new($false))
        if ($process.ExitCode -ne 0) { throw "SDK command failed: $Log" }
    } finally { if (-not $process.HasExited) { $process.Kill($true) } }
}

try {
    foreach ($example in @(
        @{Source=$BuiltProject; Name='a'; Page='/example-minimal.html'},
        @{Source=(Join-Path $BuiltProject 'examples/download-type-plugin'); Name='b'; Page='/example-download-gallery.html'}
    )) {
        $project = Join-Path $WorkDirectory $example.Name
        Copy-Item -LiteralPath $example.Source -Destination $project -Recurse
        $artifacts = @(Get-ChildItem -LiteralPath (Join-Path $project 'target') -File -Filter '*.jar' |
            Where-Object { $_.Name -notmatch '-(sources|javadoc|original)\.jar$' })
        if ($artifacts.Count -ne 1) { throw 'Expected one current plugin artifact.' }
        if (Test-Path -LiteralPath (Join-Path $project '.dev')) { throw 'Built SDK project must have no previous runtime state.' }
        $toolArgs = @('-Dfile.encoding=UTF-8', "-Dpixivdownload.sdk.cache-dir=$cache", '-jar', $tools)
        Invoke-SdkJava ($toolArgs + @('prepare', $project)) $project (Join-Path $WorkDirectory "$($example.Name)-prepare.log")
        $hostDirectory = Join-Path $WorkDirectory "host-$($example.Name)"
        [IO.Directory]::CreateDirectory($hostDirectory) | Out-Null
        Push-Location $hostDirectory
        try {
            & jar --extract --file $cachedArchive $runtime.host.file
            if ($LASTEXITCODE -ne 0) { throw 'Cannot extract the verified host for setup.' }
        } finally { Pop-Location }
        $hostJar = Join-Path $hostDirectory $runtime.host.file
        if ((Get-FileHash -LiteralPath $hostJar -Algorithm SHA256).Hash -ine $runtime.host.sha256) { throw 'Host identity mismatch.' }
        $state = Join-Path $project ".dev/state/$($runtime.archive.sha256)"
        $setupArgs = @('-Dfile.encoding=UTF-8')
        foreach ($kind in @('config', 'state', 'data', 'instance')) {
            $directory = Join-Path $state $kind
            [IO.Directory]::CreateDirectory($directory) | Out-Null
            $setupArgs += "-Dpixivdownload.$kind-dir=$directory"
        }
        [IO.File]::WriteAllText((Join-Path $state 'config/config.yaml'),
            "app.language: en-US`nproxy.enabled: false`nmaintenance.enabled: false`n", [Text.UTF8Encoding]::new($false))
        Invoke-SdkJava ($setupArgs + @('-jar', $hostJar, '--setup', "--username=$Username", "--password=$Password", '--mode=solo', '--proxy-enabled=false')) `
            $project (Join-Path $WorkDirectory "$($example.Name)-setup.log")
        $launchArgs = $toolArgs + @('run', $project, $artifacts[0].FullName, '--no-gui')
        $launchDirectory = $project
        if ($Development) {
            $launchArgs = @('-Dfile.encoding=UTF-8', "-Dpixivdownload.sdk.cache-dir=$cache", '-cp',
                ((Join-Path $BuiltProject 'target/test-classes') + [IO.Path]::PathSeparator + $tools), 'sdk.DevelopmentLauncher',
                $tools, 'develop', $project, (Join-Path $project 'target/classes'), '--no-gui')
            $launchDirectory = Join-Path $project '.dev'
        }
        $process = Invoke-SdkJava $launchArgs $launchDirectory (Join-Path $WorkDirectory "$($example.Name)-run.log") -Background
        $processes += $process
        $current = Join-Path $project '.dev/current-run.json'
        $deadline = [DateTime]::UtcNow.AddMinutes(3)
        do {
            Assert-ArtifactAlive $process 'SDK tools'
            if (Test-Path -LiteralPath $current) { break }
            Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $deadline)
        $session = [IO.File]::ReadAllText($current, [Text.Encoding]::UTF8) | ConvertFrom-Json
        if ($Development -and $session.pid -ne $process.Id) { throw 'Development host left the launched JVM.' }
        $hostProcess = [Diagnostics.Process]::GetProcessById($session.pid)
        $null = $hostProcess.Handle
        $sessions += @{Project=$project; Process=$process; Host=$hostProcess; Metadata=$session; ToolArgs=$toolArgs}
        $healthy = $false
        do {
            Assert-ArtifactAlive $process 'SDK startup'
            try { $healthy = (Invoke-RestMethod -Uri "http://127.0.0.1:$($session.port)/actuator/health" -TimeoutSec 5).status -eq 'UP' }
            catch { Start-Sleep -Milliseconds 300 }
        } while (-not $healthy -and [DateTime]::UtcNow -lt $deadline)
        if (-not $healthy) { throw 'SDK host did not become healthy.' }
        $run = Join-Path $project ".dev/runs/$($session.run)"
        $official = Get-Content -LiteralPath (Join-Path $run 'plugins-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($Development) {
            $descriptor = [IO.File]::ReadAllText((Join-Path $project 'target/classes/plugin.properties'), [Text.Encoding]::UTF8)
            $pluginId = [regex]::Match($descriptor, '(?m)^plugin\.id=(.+)\r?$').Groups[1].Value.Trim()
        } else {
            $receiptLine = Get-Content -LiteralPath (Join-Path $run 'install.log') -Encoding UTF8 | Where-Object { $_.StartsWith('PIXIV_SDK_INSTALL_RESULT=') }
            $receipt = $receiptLine.Substring('PIXIV_SDK_INSTALL_RESULT='.Length) | ConvertFrom-Json
            $pluginId = $receipt.pluginId
        }
        $login = New-ReleaseLogin $session.port
        $status = Assert-ReleaseStatus -Port $session.port -Session $login -ExpectedIds (@($official.id) + @($pluginId)) `
            -EvidencePath (Join-Path $WorkDirectory "$($example.Name)-status.json")
        if ($Development) {
            $plugin = @($status.plugins | Where-Object { $_.id -eq $pluginId })[0]
            if ($plugin.executionMode -ne 'HOST_PROCESS_FULL_TRUST') { throw 'Development source did not use the host JVM.' }
            if ([IO.File]::ReadAllText((Join-Path $project 'target/classes/plugin.properties'), [Text.Encoding]::UTF8) -cne $descriptor) {
                throw 'Development launch modified the compiled descriptor.'
            }
        }
        $page = Invoke-WebRequest -Uri "http://127.0.0.1:$($session.port)$($example.Page)" -WebSession $login -TimeoutSec 10
        if ($page.StatusCode -ne 200) { throw 'The current SDK plugin page is unavailable.' }
    }
    if ($sessions[0].Metadata.pid -eq $sessions[1].Metadata.pid -or $sessions[0].Metadata.port -eq $sessions[1].Metadata.port) {
        throw 'Two SDK projects shared a running host.'
    }
    foreach ($session in $sessions) {
        Invoke-SdkJava ($session.ToolArgs + @('stop', $session.Project)) $session.Project (Join-Path $session.Project 'stop.log')
        if (-not $session.Process.WaitForExit(60000) -or $session.Process.ExitCode -ne 0) { throw 'SDK host did not stop normally.' }
        $session.Host.Refresh()
        if (-not $session.Host.HasExited) { throw 'SDK left a running host behind.' }
    }
    Write-Host "PASS: SDK examples load with all official plugins, independent state, shared cache and normal stop (development=$Development)."
} finally {
    foreach ($process in $processes) {
        if (-not $process.HasExited) { $process.Kill($true); $process.WaitForExit() }
        [IO.File]::WriteAllText($process.LogPath, $process.Stdout.Result + $process.Stderr.Result, [Text.UTF8Encoding]::new($false))
    }
}
