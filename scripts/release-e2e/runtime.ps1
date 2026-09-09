# Test-only helpers. All control files live in the owned E2E session.

function Initialize-ReleaseProbe {
    param([string]$ApplicationJar, [string]$Destination)
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $agentClasses = Join-Path $Destination 'agent-classes'
    $fixtureClasses = Join-Path $Destination 'fixture-classes'
    New-Item -ItemType Directory -Path $agentClasses, $fixtureClasses | Out-Null
    & javac --release 17 -encoding UTF-8 -d $agentClasses (Join-Path $PSScriptRoot 'ReleaseProbeAgent.java')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot compile release observer.' }
    $manifest = Join-Path $Destination 'agent-manifest.mf'
    [IO.File]::WriteAllText($manifest, "Premain-Class: releasee2e.ReleaseProbeAgent`n`n", [Text.Encoding]::ASCII)
    $agent = Join-Path $Destination 'observer.jar'
    & jar --create --file $agent --manifest $manifest -C $agentClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Cannot package release observer.' }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($ApplicationJar)
    $classpath = New-Object 'System.Collections.Generic.List[string]'
    $apiRoot = Join-Path $Destination 'api'
    $classpath.Add($apiRoot)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match '^BOOT-INF/classes/(top/sywyar/pixivdownload/plugin/api/.+\.class)$') {
                $target = Join-Path $apiRoot $Matches[1]
                [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target)
            } elseif ($entry.FullName -match '^BOOT-INF/lib/(pf4j-[^/]+\.jar|pixivdownload-plugin-api-[^/]+\.jar)$') {
                $target = Join-Path $Destination $Matches[1]
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target)
                $classpath.Add($target)
            }
        }
    } finally { $archive.Dispose() }
    & javac --release 17 -encoding UTF-8 -cp ($classpath -join [IO.Path]::PathSeparator) `
        -d $fixtureClasses (Join-Path $PSScriptRoot 'FailingGuiPlugin.java')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot compile failure fixture against the final application API.' }
    $sdkVersion = Get-PixivDownloadSdkVersion -ProjectRoot (Join-Path $PSScriptRoot '../..')
    [IO.File]::WriteAllText((Join-Path $fixtureClasses 'plugin.properties'), @"
plugin.id=release-e2e-broken-gui
plugin.version=1.0.0
plugin.requires=$sdkVersion
plugin.class=releasee2e.fixture.FailingGuiPlugin
plugin.provider=release-e2e
plugin.description=Desktop startup failure fixture
pixiv.kind=feature
pixiv.lifecycle-policy=process-restart
pixiv.execution-mode=host-process-full-trust
"@, [Text.Encoding]::ASCII)
    $fixture = Join-Path $Destination 'failing-gui.jar'
    & jar --create --file $fixture -C $fixtureClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Cannot package failure fixture.' }
    return [pscustomobject]@{ Agent = $agent; Fixture = $fixture }
}

function Assert-ArtifactAlive {
    param($Process, [string]$Label)
    $Process.Refresh()
    if ($Process.HasExited) { throw "$Label exited unexpectedly with code $($Process.ExitCode)" }
    if ($Process.PSObject.Properties['EngineProcess']) {
        $Process.EngineProcess.Refresh()
        if ($Process.EngineProcess.HasExited) {
            throw "$Label JVM exited unexpectedly with code $($Process.EngineProcess.ExitCode)"
        }
    }
}

function Invoke-ReleaseProbe {
    param($Process, [string]$ProbeRoot, [string]$Command, [string]$Target = '', [int]$TimeoutSeconds = 15)
    Assert-ArtifactAlive $Process $Command
    $nonce = [Guid]::NewGuid().ToString('N')
    $responsePath = Join-Path $ProbeRoot 'response.json'
    # A single in-flight request owns the response; avoid replacing a file while Windows reads it.
    if (Test-Path -LiteralPath $responsePath) { Remove-Item -LiteralPath $responsePath }
    $temporary = Join-Path $ProbeRoot 'request.tmp'
    $request = Join-Path $ProbeRoot 'request.txt'
    [IO.File]::WriteAllText($temporary, "$nonce`n$Command`n$Target`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($temporary, $request)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-Path -LiteralPath $responsePath) {
            $response = Get-Content -LiteralPath $responsePath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($response.nonce -eq $nonce) {
                if (-not $response.ok) { throw "Release observer failed: $($response.error)" }
                if ($Command -eq 'desktop') {
                    $response | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ProbeRoot 'desktop.json') -Encoding UTF8
                }
                return $response
            }
        }
        Assert-ArtifactAlive $Process $Command
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Release observer did not answer $Command within $TimeoutSeconds seconds"
}

function Connect-ReleaseProbe {
    param($Process, [string]$ProbeRoot)
    $response = Invoke-ReleaseProbe $Process $ProbeRoot 'ping'
    $engine = Get-Process -Id $response.pid
    # Retain a native handle before exit so Get-Process can report the JVM exit code.
    $null = $engine.Handle
    $ancestor = [int]$response.pid
    for ($depth = 0; $depth -lt 8 -and $ancestor -ne $Process.Id; $depth++) {
        $parent = Get-CimInstance Win32_Process -Filter "ProcessId = $ancestor"
        if ($null -eq $parent) { throw 'Cannot establish ownership of the observed JVM.' }
        $ancestor = [int]$parent.ParentProcessId
    }
    if ($ancestor -ne $Process.Id) { throw 'Observer belongs to an unrelated process.' }
    $Process | Add-Member -NotePropertyName EngineProcess -NotePropertyValue $engine -Force
}

function New-ReleaseLogin {
    param([int]$Port)
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $body = @{ username = $Username; password = $Password; rememberMe = $false } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$Port/api/auth/login" `
        -ContentType 'application/json' -Body $body -WebSession $session -TimeoutSec 10
    if (-not $login.ok) { throw 'Release test login failed.' }
    return $session
}

function Assert-ReleaseStatus {
    param([int]$Port, $Session, [string[]]$ExpectedIds, [string[]]$CrashedIds = @(),
        [string[]]$UnavailableIds = @(), [bool]$Recovery = $false, [string]$EvidencePath = '')
    $health = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 5
    if ($health.status -ne 'UP') { throw 'Application became unhealthy.' }
    $status = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/plugins/status" `
        -WebSession $Session -TimeoutSec 60
    if ($EvidencePath) { $status | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $EvidencePath -Encoding UTF8 }
    if ([bool]$status.recoveryMode -ne $Recovery) { throw "Unexpected recovery mode: $($status.recoveryMode)" }
    foreach ($id in @($ExpectedIds + $CrashedIds + $UnavailableIds | Sort-Object -Unique)) {
        $rows = @($status.plugins | Where-Object { $_.id -eq $id })
        if ($UnavailableIds -contains $id) {
            if (@($rows | Where-Object { $_.status -eq 'STARTED' }).Count) {
                throw "Unavailable plugin unexpectedly started: $id"
            }
            continue
        }
        if ($rows.Count -ne 1) { throw "Expected one plugin status for $id" }
        if ($CrashedIds -contains $id) {
            if ($rows[0].status -ne 'CRASHED' -or $rows[0].runtimePhase -in @('STARTED', 'QUIESCED')) {
                throw "Plugin crash was not reported or services remain active: $id ($($rows[0].status)/$($rows[0].runtimePhase))"
            }
        } elseif ($rows[0].status -ne 'STARTED' -or $rows[0].runtimePhase -ne 'STARTED') {
            throw "Plugin is not healthy: $id ($($rows[0].status)/$($rows[0].runtimePhase))"
        }
    }
    return $status
}

function Wait-ReleaseDesktop {
    param($Process, [string]$ProbeRoot, [string]$Provider, [switch]$BootstrapPrompt, [int]$TimeoutSeconds = 180)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $desktop = Invoke-ReleaseProbe $Process $ProbeRoot 'desktop'
        if (Test-ReleaseDesktopReady $desktop $Provider -BootstrapPrompt:$BootstrapPrompt) { return $desktop }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Desktop did not render the expected provider/prompt: $($desktop | ConvertTo-Json -Compress -Depth 4)"
}

function Test-ReleaseDesktopReady {
    param($Desktop, [string]$Provider, [switch]$BootstrapPrompt)
    if (-not $Desktop.applicationLoaded) { return $false }
    $rendered = if ($BootstrapPrompt) { $Desktop.bootstrapTextRendered } else { $Desktop.contentColors -gt 8 }
    return $Desktop.provider -eq $Provider -and $Desktop.bootstrapPrompts -eq [int][bool]$BootstrapPrompt `
        -and @($Desktop.windows).Count -gt 0 -and $rendered
}

function Assert-ReleaseStable {
    param($Process, [string]$ProbeRoot, [int]$Port, $Session, [string[]]$ExpectedIds,
        [string]$Provider = '', [string[]]$CrashedIds = @(), [string[]]$UnavailableIds = @(),
        [bool]$Recovery = $false, [switch]$BootstrapPrompt, [int]$Seconds = 10)
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        Assert-ArtifactAlive $Process 'Runtime observation'
        if ($null -ne $Session) {
            Assert-ReleaseStatus -Port $Port -Session $Session -ExpectedIds $ExpectedIds `
                -CrashedIds $CrashedIds -UnavailableIds $UnavailableIds -Recovery $Recovery `
                -EvidencePath (Join-Path $ProbeRoot 'plugins.json') | Out-Null
        } elseif (-not $BootstrapPrompt) {
            $health = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 5
            if ($health.status -ne 'UP') { throw 'Application became unhealthy.' }
        }
        if ($Provider -or $BootstrapPrompt) {
            $desktop = Invoke-ReleaseProbe $Process $ProbeRoot 'desktop'
            if (-not (Test-ReleaseDesktopReady $desktop $Provider -BootstrapPrompt:$BootstrapPrompt)) {
                throw "Desktop changed or stopped rendering: $($desktop | ConvertTo-Json -Compress -Depth 4)"
            }
        }
        Assert-ArtifactAlive $Process 'Runtime observation'
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
}

function Stop-ReleaseApplication {
    param($Process, [string]$ProbeRoot)
    Assert-ArtifactAlive $Process 'Before requested shutdown'
    # The application may finish before the acknowledgement file can be read.
    $nonce = [Guid]::NewGuid().ToString('N')
    $temporary = Join-Path $ProbeRoot 'request.tmp'
    [IO.File]::WriteAllText($temporary, "$nonce`nshutdown`n", [Text.Encoding]::ASCII)
    [IO.File]::Move($temporary, (Join-Path $ProbeRoot 'request.txt'))
    Wait-ArtifactProcessExit -Process $Process -Label 'Graceful application shutdown' -TimeoutSeconds 180
    if ($Process.ExitCode -ne 0) { throw "Application shutdown returned $($Process.ExitCode)" }
    if ($Process.PSObject.Properties['EngineProcess']) {
        Wait-ArtifactProcessExit -Process $Process.EngineProcess -Label 'Graceful JVM shutdown' -TimeoutSeconds 5
        if ($Process.EngineProcess.ExitCode -ne 0) { throw "JVM shutdown returned $($Process.EngineProcess.ExitCode)" }
    }
}
