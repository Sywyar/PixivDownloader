# Run on Windows with pwsh or Windows PowerShell. No external test framework.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ast = [Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot '../test-release-artifacts.ps1'), [ref]$null, [ref]$null)
foreach ($definition in $ast.FindAll({param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst]}, $false)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}
. (Join-Path $PSScriptRoot 'runtime.ps1')
. (Join-Path $PSScriptRoot 'scenarios.ps1')

function Assert-Rejected {
    param([scriptblock]$Action, [string]$Message)
    try { & $Action } catch {
        if ($_.Exception.Message -notlike "*$Message*") { throw }
        return
    }
    throw "False success: expected $Message"
}

$WorkRoot = [IO.Path]::GetTempPath()
$context = New-TestSessionRoot
$children = @()
try {
    foreach ($code in @(0, 17)) {
        $child = Start-Process $env:ComSpec -ArgumentList "/d /c exit $code" -PassThru -WindowStyle Hidden
        $children += $child
        $child.WaitForExit()
        Assert-Rejected { Assert-ArtifactAlive $child 'early startup' } "code $code"
        Assert-Rejected { Wait-ForHealthyApplication 1 $child 'startup' } "code $code"
        Assert-Rejected { Assert-ReleaseStable -Process $child -Seconds 1 } "code $code"
    }
    $parent = Get-Process -Id $PID
    $parent | Add-Member EngineProcess $child
    Assert-Rejected { Assert-ArtifactAlive $parent 'launcher still alive' } 'JVM exited'
    $parent.PSObject.Properties.Remove('EngineProcess')

    $probe = Join-Path $context.Session 'probe'
    [IO.Directory]::CreateDirectory($probe) | Out-Null
    foreach ($stale in @($false, $true)) {
        if ($stale) {
            [IO.File]::WriteAllText((Join-Path $probe 'response.json'), '{"nonce":"old-run","ok":true}')
        }
        Assert-Rejected { Invoke-ReleaseProbe $parent $probe 'ping' -TimeoutSeconds 1 } 'did not answer'
        Remove-Item -LiteralPath (Join-Path $probe 'request.txt')
    }

    $agentRoot = Join-Path $context.Session 'startup-agent'
    $agentClasses = Join-Path $agentRoot 'classes'
    $startupProbe = Join-Path $agentRoot 'probe'
    New-Item -ItemType Directory -Path $agentClasses, $startupProbe | Out-Null
    & javac --release 17 -encoding UTF-8 -d $agentClasses `
        (Join-Path $PSScriptRoot 'ReleaseProbeAgent.java') `
        (Join-Path $PSScriptRoot 'fixtures/DelayedLauncher.java')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot compile startup observer regression.' }
    $manifest = Join-Path $agentRoot 'manifest.mf'
    [IO.File]::WriteAllText($manifest, "Premain-Class: releasee2e.ReleaseProbeAgent`n`n", [Text.Encoding]::ASCII)
    $agentJar = Join-Path $agentRoot 'observer.jar'
    & jar --create --file $agentJar --manifest $manifest -C $agentClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Cannot package startup observer regression.' }
    $child = Start-Process java -ArgumentList @(
        "-javaagent:`"$agentJar=$startupProbe`"", '-cp', "`"$agentClasses`"",
        'top.sywyar.pixivdownload.gui.DelayedLauncher', "`"$startupProbe`""
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $agentRoot 'stdout.log') `
        -RedirectStandardError (Join-Path $agentRoot 'stderr.log')
    $children += $child
    $null = $child.Handle
    Connect-ReleaseProbe $child $startupProbe
    $desktop = Invoke-ReleaseProbe $child $startupProbe 'desktop'
    if ($desktop.applicationLoaded) { throw 'Observer loaded the application entry prematurely.' }
    Assert-Rejected { Wait-ReleaseDesktop $child $startupProbe '' -BootstrapPrompt -TimeoutSeconds 1 } 'did not render'
    Assert-Rejected { Invoke-ReleaseProbe $child $startupProbe 'shutdown' } 'Application entry has not loaded'
    New-Item -ItemType File -Path (Join-Path $startupProbe 'load') | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $desktop = Invoke-ReleaseProbe $child $startupProbe 'desktop'
        if ($desktop.applicationLoaded) { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Observer did not see the delayed entry load.' }
        Start-Sleep -Milliseconds 50
    } while ($true)
    if (Test-ReleaseDesktopReady $desktop '' -BootstrapPrompt) { throw 'Loaded entry without a window was accepted.' }
    Assert-Rejected { Invoke-ReleaseProbe $child $startupProbe 'unknown-command' } 'Unknown probe command'
    New-Item -ItemType File -Path (Join-Path $startupProbe 'exit') | Out-Null
    Wait-ArtifactProcessExit $child 'Delayed entry fixture' 15
    if ($child.ExitCode -ne 0) { throw "Delayed entry fixture failed with code $($child.ExitCode)." }
    Assert-Rejected { Wait-ReleaseDesktop $child $startupProbe '' -BootstrapPrompt -TimeoutSeconds 1 } 'exited'

    $good = [pscustomobject]@{applicationLoaded=$true; provider='gui-compose'; bootstrapPrompts=0; contentColors=30; windows=@('window')}
    if (-not (Test-ReleaseDesktopReady $good 'gui-compose')) { throw 'Rendered GUI rejected.' }
    foreach ($bad in @(
        [pscustomobject]@{applicationLoaded=$true; provider='gui-swing'; bootstrapPrompts=0; contentColors=30; windows=@('window')},
        [pscustomobject]@{applicationLoaded=$true; provider='gui-compose'; bootstrapPrompts=0; contentColors=1; windows=@('window')},
        [pscustomobject]@{applicationLoaded=$true; provider='gui-compose'; bootstrapPrompts=0; contentColors=30; windows=@()},
        [pscustomobject]@{applicationLoaded=$true; provider=''; bootstrapPrompts=1; contentColors=30; windows=@('dialog'); bootstrapTextRendered=$true}
    )) {
        if (Test-ReleaseDesktopReady $bad 'gui-compose') { throw 'Wrong, blank or absent GUI accepted.' }
    }
    if (-not (Test-ReleaseDesktopReady $bad '' -BootstrapPrompt)) { throw 'Bootstrap dialog rejected.' }
    $bad.contentColors = 2
    if (-not (Test-ReleaseDesktopReady $bad '' -BootstrapPrompt)) { throw 'Monochrome readable prompt rejected.' }
    $bad.bootstrapTextRendered = $false
    if (Test-ReleaseDesktopReady $bad '' -BootstrapPrompt) { throw 'Unreadable bootstrap text accepted.' }
    $bad.bootstrapTextRendered = $true
    $bad.bootstrapPrompts = 2
    if (Test-ReleaseDesktopReady $bad '' -BootstrapPrompt) { throw 'Duplicate bootstrap prompts accepted.' }
    $savedProbe = ${function:Invoke-ReleaseProbe}
    function Invoke-ReleaseProbe { return $bad }
    Assert-Rejected { Wait-ReleaseDesktop $parent $probe 'gui-compose' -TimeoutSeconds 1 } 'did not render'
    function Invoke-ReleaseProbe { throw 'EDT unresponsive' }
    Assert-Rejected { Wait-ReleaseDesktop $parent $probe 'gui-compose' -TimeoutSeconds 1 } 'EDT unresponsive'
    foreach ($bootstrap in @($false, $true)) {
        $ready = if ($bootstrap) { $bad } else { $good }
        $bad.bootstrapPrompts = 1
        $script:desktopReads = 0
        function Invoke-ReleaseProbe {
            if (++$script:desktopReads -eq 1) { return [pscustomobject]@{applicationLoaded=$false} }
            return $ready
        }
        $observed = Wait-ReleaseDesktop $parent $probe $ready.provider -BootstrapPrompt:$bootstrap -TimeoutSeconds 3
        if ($script:desktopReads -ne 2 -or -not $observed.applicationLoaded) {
            throw 'Desktop wait did not retry startup readiness.'
        }
    }
    Set-Item Function:Invoke-ReleaseProbe $savedProbe

    # Drive the real observation loop with a process which exits after readiness.
    function Invoke-RestMethod {
        param($Uri, [switch]$UseBasicParsing, $WebSession, $TimeoutSec)
        if ($Uri.EndsWith('/actuator/health')) { return [pscustomobject]@{status='UP'} }
        return $script:status
    }
    $child = Start-Process $env:ComSpec -ArgumentList '/d /c "ping -n 2 127.0.0.1 >nul & exit 0"' `
        -PassThru -WindowStyle Hidden
    $children += $child
    Assert-ArtifactAlive $child 'ready'
    Assert-Rejected { Assert-ReleaseStable -Process $child -Seconds 3 } 'code 0'
    $script:status = [pscustomobject]@{recoveryMode=$false; plugins=@(
        [pscustomobject]@{id='optional'; status='STARTED'; runtimePhase='STARTED'})}
    Assert-ReleaseStatus 1 $null @('optional') | Out-Null
    $script:status.plugins[0].status = 'CRASHED'
    $script:status.plugins[0].runtimePhase = 'STOPPED'
    Assert-Rejected { Assert-ReleaseStatus 1 $null @('optional') } 'not healthy'
    Assert-ReleaseStatus 1 $null @('optional') -CrashedIds @('optional') | Out-Null
    Assert-Rejected { Assert-ReleaseStatus 1 $null @('optional') -Recovery $true } 'recovery mode'
    $script:status.recoveryMode = $true
    Assert-ReleaseStatus 1 $null @('optional') -CrashedIds @('optional') -Recovery $true | Out-Null
    $script:status.plugins[0].runtimePhase = 'STARTED'
    Assert-Rejected { Assert-ReleaseStatus 1 $null @('optional') -Recovery $true -CrashedIds @('optional') } 'not reported'

    $child = Start-Process $env:ComSpec -ArgumentList '/d /c "ping -n 2 127.0.0.1 >nul & exit 17"' `
        -PassThru -WindowStyle Hidden
    $children += $child
    Assert-Rejected { Stop-ReleaseApplication $child $probe } 'shutdown returned 17'
    Remove-Item -LiteralPath (Join-Path $probe 'request.txt')
    $child = Start-Process $env:ComSpec -ArgumentList '/d /c ping -n 30 127.0.0.1 >nul' `
        -PassThru -WindowStyle Hidden
    $children += $child
    Assert-Rejected { Wait-ArtifactProcessExit $child 'stuck child' 1 } 'did not exit'
    if (-not $child.HasExited) { throw 'Timed out child was not cleaned up.' }
    Write-Host 'PASS: release E2E rejects early/late exit, dead JVM, stale probes, broken GUI, unexpected recovery and failed shutdown.'
} finally {
    foreach ($child in $children) { Stop-ArtifactProcess $child }
    Remove-TestSessionRoot $context
}
