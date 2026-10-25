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
    param([scriptblock]$Action, [string[]]$Message)
    try { & $Action } catch {
        $failure = $_.Exception.Message
        if (-not @($Message | Where-Object { $failure -like "*$_*" }).Count) { throw }
        return
    }
    throw "False success: expected $Message"
}

function Start-BlockedDesktop {
    foreach ($name in @('blocked', 'release', 'edt-timeout.txt')) {
        $path = Join-Path $startupProbe $name
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path }
    }
    New-Item -ItemType File -Path (Join-Path $startupProbe 'block') | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while (-not (Test-Path -LiteralPath (Join-Path $startupProbe 'blocked'))) {
        Assert-ArtifactAlive $child 'Blocked EDT fixture'
        if ([DateTime]::UtcNow -ge $deadline) { throw 'EDT fixture did not block.' }
        Start-Sleep -Milliseconds 50
    }
}

$WorkRoot = [IO.Path]::GetTempPath()
$context = New-TestSessionRoot
$children = @()
try {
    & {
        $port = Get-FreePort
        $responseFile = Join-Path $context.Session 'health-response.json'
        $readyFile = Join-Path $context.Session 'health-ready'
        $server = Start-Job -ArgumentList $port, $responseFile, $readyFile -ScriptBlock {
            param($Port, $ResponseFile, $ReadyFile)
            $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
            try {
                $listener.Start()
                [IO.File]::WriteAllText($ReadyFile, 'ready')
                while ($true) {
                    $client = $listener.AcceptTcpClient()
                    try {
                        $stream = $client.GetStream()
                        $reader = [IO.StreamReader]::new($stream)
                        $path = $reader.ReadLine().Split(' ')[1]
                        while ($reader.ReadLine()) {}
                        $code = 200
                        $body = '{}'
                        $contentType = 'application/json'
                        if ($path -eq '/actuator/health') {
                            $reply = Get-Content -Raw -LiteralPath $ResponseFile | ConvertFrom-Json
                            $code = $reply.code
                            $body = $reply.body
                            $contentType = $reply.contentType
                        } elseif ($path -eq '/api/plugins/status') {
                            $body = '{"recoveryMode":true,"plugins":[]}'
                        }
                        $bytes = [Text.Encoding]::UTF8.GetBytes($body)
                        $headers = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 $code Fixture`r`nContent-Type: $contentType`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n")
                        $stream.Write($headers, 0, $headers.Length)
                        $stream.Write($bytes, 0, $bytes.Length)
                    } finally {
                        if ($null -ne $reader) { $reader.Dispose() }
                        $client.Dispose()
                    }
                }
            } finally { $listener.Stop() }
        }
        $process = Get-Process -Id $PID
        try {
            $deadline = [DateTime]::UtcNow.AddSeconds(15)
            while (-not [IO.File]::Exists($readyFile)) {
                if ($server.State -ne 'Running' -or [DateTime]::UtcNow -ge $deadline) { throw 'Health fixture did not start.' }
                Start-Sleep -Milliseconds 50
            }
            foreach ($case in @(
                @{code=200; body='{"status":"UP"}'; expected='UP'; valid=$true},
                @{code=503; body='{"status":"DOWN"}'; expected='DOWN'; valid=$true},
                @{code=201; body='{"status":"UP"}'; expected='UP'; valid=$false},
                @{code=503; body='{"status":"DOWN"}'; expected='UP'; valid=$false},
                @{code=200; body='{"status":"UP"}'; expected='DOWN'; valid=$false},
                @{code=200; body='{"status":"DOWN"}'; expected='DOWN'; valid=$false},
                @{code=503; body='{"status":"UP"}'; expected='DOWN'; valid=$false},
                @{code=500; body='{"status":"DOWN"}'; expected='DOWN'; valid=$false},
                @{code=503; body='invalid'; expected='DOWN'; valid=$false}
            )) {
                foreach ($contentType in @('application/json', 'application/vnd.spring-boot.actuator.v3+json')) {
                    $case.contentType = $contentType
                    [IO.File]::WriteAllText($responseFile, ($case | ConvertTo-Json), [Text.Encoding]::ASCII)
                    if ($case.valid) {
                        Wait-ForHealthyApplication $port $process 'Health fixture' $case.expected
                        Assert-ReleaseStable -Process $process -Port $port -ExpectedHealth $case.expected -Seconds 0
                        if ($case.expected -eq 'DOWN') {
                            Assert-ReleaseStatus $port $null @() -Recovery $true -ExpectedHealth 'DOWN' | Out-Null
                        }
                    } else {
                        $rejected = $false
                        try { Assert-ReleaseHealth $port $case.expected } catch { $rejected = $true }
                        if (-not $rejected) { throw "Invalid health response accepted: $($case | ConvertTo-Json -Compress)" }
                    }
                }
            }
        } finally {
            $process.Dispose()
            Stop-Job $server
            Remove-Job $server
        }
    }
    foreach ($kind in @('bat', 'exe')) {
        foreach ($code in @(0, 17)) {
            $launcher = $env:ComSpec
            $arguments = @('/d', '/c', 'exit', "$code")
            if ($kind -eq 'bat') {
                $launcher = Join-Path $context.Session 'unobserved launcher.bat'
                [IO.File]::WriteAllText($launcher, "@echo off`r`nexit /b %1`r`n", [Text.Encoding]::ASCII)
                $arguments = @("$code")
            }
            foreach ($wait in @($false, $true)) {
                $result = Start-ArtifactProcess -Launcher $launcher -WorkingDirectory $context.Session `
                    -Arguments $arguments -LogPrefix (Join-Path $context.Session "unobserved-$kind-$code-$wait") -Wait:$wait
                if ($wait) {
                    $exitCode = $result
                } else {
                    try {
                        Wait-ArtifactProcessExit $result 'Unobserved launcher' 15
                        $exitCode = $result.ExitCode
                    } finally { $result.Dispose() }
                }
                if ($exitCode -isnot [int] -or $exitCode -ne $code) {
                    throw "$kind lost exit code $code without a handle observer (wait=$wait)."
                }
            }
        }
    }
    & {
        $launched = [Collections.Generic.List[object]]::new()
        $failures = [Collections.Generic.List[string]]::new()
        function Start-Process {
            param($FilePath, $ArgumentList, $WorkingDirectory, $WindowStyle, [switch]$PassThru,
                $RedirectStandardOutput, $RedirectStandardError)
            $process = Microsoft.PowerShell.Management\Start-Process @PSBoundParameters
            $launched.Add([pscustomobject]@{ Process=$process; Handle=$process.SafeHandle; Id=$process.Id })
            return $process
        }
        $waitForExit = ${function:Wait-ArtifactProcessExit}
        try {
            foreach ($kind in @('bat', 'exe')) {
                foreach ($code in @(0, 17)) {
                    $prefix = Join-Path $context.Session "$kind-$code"
                    $launcher = $env:ComSpec
                    $arguments = @('/d', '/c', 'exit', "$code")
                    if ($kind -eq 'bat') {
                        $launcher = Join-Path $context.Session 'waited launcher.bat'
                        [IO.File]::WriteAllText($launcher, "@echo off`r`necho drained-output`r`nexit /b %1`r`n", [Text.Encoding]::ASCII)
                        $arguments = @("$code")
                    }
                    $exitCode = Start-ArtifactProcess -Launcher $launcher -WorkingDirectory $context.Session `
                        -Arguments $arguments -LogPrefix $prefix -Wait
                    if ($exitCode -isnot [int] -or $exitCode -ne $code) { $failures.Add("$kind lost exit code $code") }
                    if (-not $launched[-1].Handle.IsClosed) { $failures.Add("$kind retained its completed process handle") }
                    if ($kind -eq 'bat' -and ([IO.File]::ReadAllText("$prefix.stdout.log")).Trim() -ne 'drained-output') {
                        throw 'Waited launcher returned before its output drained.'
                    }
                }
            }
            & {
                function Wait-ArtifactProcessExit {
                    param($Process, $Label, $TimeoutSeconds)
                    & $waitForExit -Process $Process -Label $Label -TimeoutSeconds 1
                }
                Assert-Rejected {
                    Start-ArtifactProcess -Launcher $env:ComSpec -WorkingDirectory $context.Session `
                        -Arguments @('/d', '/c', 'ping', '-n', '30', '127.0.0.1') `
                        -LogPrefix (Join-Path $context.Session 'timed-out') -Wait
                } 'did not exit'
            }
            if (Get-Process -Id $launched[-1].Id -ErrorAction SilentlyContinue) { throw 'Timed out launcher still runs.' }
            if (-not $launched[-1].Handle.IsClosed) { $failures.Add('Timed out launcher retained its process handle') }
            $process = Start-ArtifactProcess -Launcher $env:ComSpec -WorkingDirectory $context.Session `
                -Arguments @('/d', '/c', 'exit', '17') -LogPrefix (Join-Path $context.Session 'asynchronous')
            $process.WaitForExit()
            if ($process.ExitCode -ne 17 -or $launched[-1].Handle.IsClosed) {
                throw 'Asynchronous launcher did not retain caller-owned exit status.'
            }
            if ($failures.Count) { throw ($failures -join '; ') }
        } finally {
            foreach ($entry in $launched) {
                if (-not $entry.Handle.IsClosed) {
                    try { Stop-ArtifactProcess $entry.Process } finally { $entry.Process.Dispose() }
                }
            }
        }
    }
    & {
        $failures = [Collections.Generic.List[string]]::new()
        $observed = [Collections.Generic.List[object]]::new()
        $options = $env:JAVA_TOOL_OPTIONS
        $noProxy = $env:NO_PROXY
        function Get-Process {
            param($Id)
            $process = Microsoft.PowerShell.Management\Get-Process -Id $Id
            $observed.Add([pscustomobject]@{ Process=$process; Handle=$process.SafeHandle })
            return $process
        }
        function Invoke-ReleaseProbe { return [pscustomobject]@{ pid=$PID } }
        function Get-CimInstance {
            if ($ownership -eq 'query-failed') { throw 'process query failed' }
            if ($ownership -eq 'unrelated') { return [pscustomobject]@{ ParentProcessId=$PID } }
            return $null
        }
        try {
            foreach ($ownership in @('missing', 'unrelated', 'query-failed')) {
                $message = switch ($ownership) {
                    'missing' { 'Cannot establish ownership' }
                    'unrelated' { 'Observer belongs to an unrelated process' }
                    'query-failed' { 'process query failed' }
                }
                Assert-Rejected { Connect-ReleaseProbe ([pscustomobject]@{Id=-1}) '' } $message
                if (-not $observed[-1].Handle.IsClosed) { $failures.Add("Rejected $ownership observer retained its process handle") }
            }

            function Start-ArtifactProcess {
                $process = Microsoft.PowerShell.Management\Start-Process $env:ComSpec `
                    -ArgumentList '/d /c ping -n 30 127.0.0.1 >nul' -PassThru -WindowStyle Hidden
                $observed.Add([pscustomobject]@{ Process=$process; Handle=$process.SafeHandle })
                return $process
            }
            function Connect-ReleaseProbe {
                param($Process)
                $Process | Add-Member EngineProcess (Get-Process -Id $Process.Id)
            }
            function Wait-ForHealthyApplication {}
            function Assert-ReleaseStable {}
            function Assert-ReleaseNoFatalLog {}
            function Assert-ReleaseLogParity {}
            function Stop-ReleaseApplication { param($Process) Stop-ArtifactProcess $Process }
            $root = Join-Path $context.Session 'application-lifetime'
            [IO.Directory]::CreateDirectory($root) | Out-Null
            [IO.File]::WriteAllText((Join-Path $root 'plugins-manifest.json'), '[{"id":"fixture"}]', [Text.Encoding]::UTF8)
            $script:ReleaseTools = [pscustomobject]@{ Agent='fixture' }
            $script:ReleaseReportRoot = Join-Path $root 'reports'
            foreach ($fail in @($false, $true)) {
                $before = $observed.Count
                $run = {
                    Test-ApplicationScenario -FirstRun -Headless -Label "lifetime-$fail" -Root $root `
                        -Launcher 'fixture' -RuntimeRoot (Join-Path $root "runtime-$fail") `
                        -LogRoot (Join-Path $root "log-$fail") -Exercise { if ($fail) { throw 'exercise failed' } }
                }
                if ($fail) { Assert-Rejected $run 'exercise failed' } else { & $run }
                if ($observed.Count -ne $before + 2 -or -not $observed[-1].Handle.IsClosed -or -not $observed[-2].Handle.IsClosed) {
                    $failures.Add("Application retained launcher or observer handle after failure=$fail")
                }
                $report = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $root "reports/lifetime-$fail/result.json") | ConvertFrom-Json
                if ($report.passed -eq $fail) { throw 'Application cleanup changed its saved result.' }
            }

            $entry = $ast.EndBlock.Statements | Where-Object {
                $_ -is [Management.Automation.Language.TryStatementAst]
            } | Select-Object -Last 1
            function Assert-NoInstalledCopy {}
            function Assert-PackagedRuntime { return 'fixture' }
            function Test-ApplicationLayout { if ($case -eq 'layout') { throw 'layout failed' } }
            function Start-Process {
                param($FilePath, $ArgumentList, $WindowStyle, [switch]$PassThru)
                $code = if (($FilePath -eq 'fixture-installer' -and $case -eq 'install') -or
                    ($FilePath -ne 'fixture-installer' -and $case -eq 'uninstall')) { 17 } else { 0 }
                [IO.Directory]::CreateDirectory((Split-Path -Parent $uninstaller)) | Out-Null
                [IO.File]::WriteAllText($uninstaller, 'fixture', [Text.Encoding]::UTF8)
                $process = Microsoft.PowerShell.Management\Start-Process $env:ComSpec `
                    -ArgumentList "/d /c exit $code" -WindowStyle Hidden -PassThru
                $observed.Add([pscustomobject]@{ Process=$process; Handle=$process.SafeHandle })
                return $process
            }
            foreach ($case in @('success', 'install', 'layout', 'uninstall')) {
                $before = $observed.Count
                $resolvedJavaZip = $resolvedFullOfflineZip = $resolvedAppImage = ''
                $resolvedInstaller = 'fixture-installer'
                $ReportRoot = Join-Path $root 'installer-reports'
                $oldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
                $oldNoProxy = $env:NO_PROXY
                $run = [scriptblock]::Create($entry.Extent.Text)
                if ($case -eq 'success') { & $run } else {
                    $message = switch ($case) {
                        'install' { 'installer failed with exit code 17' }
                        'layout' { 'layout failed' }
                        'uninstall' { 'uninstaller exited with code 17' }
                    }
                    Assert-Rejected $run $message
                }
                if ($observed.Count -ne $before + 2 -or -not $observed[-1].Handle.IsClosed -or -not $observed[-2].Handle.IsClosed) {
                    $failures.Add("Installer or uninstaller retained a process handle after $case")
                }
            }
            if ($failures.Count) { throw ($failures -join '; ') }
        } finally {
            foreach ($entry in $observed) {
                if (-not $entry.Handle.IsClosed) {
                    try { if ($entry.Process.Id -ne $PID) { Stop-ArtifactProcess $entry.Process } }
                    finally { $entry.Process.Dispose() }
                }
            }
            $env:JAVA_TOOL_OPTIONS = $options
            $env:NO_PROXY = $noProxy
        }
    }
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
        $pending = [IO.File]::ReadAllText((Join-Path $probe 'request.txt'))
        Assert-Rejected { Invoke-ReleaseProbe $parent $probe 'ping' -TimeoutSeconds 1 } 'did not answer'
        if ([IO.File]::ReadAllText((Join-Path $probe 'request.txt')) -cne $pending) {
            throw 'A subsequent request replaced an unconsumed request.'
        }
        Remove-Item -LiteralPath (Join-Path $probe 'request.txt')
    }
    Assert-Rejected { Invoke-ReleaseProbe $parent $probe 'ping' -Deadline ([DateTime]::UtcNow.AddSeconds(-1)) } 'did not answer'
    if (Test-Path -LiteralPath (Join-Path $probe 'request.txt')) {
        throw 'An expired deadline still published a request.'
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
    # 尚未查询桌面或触发超时，也必须能取得启动线程现场。
    $startupSample = Join-Path $startupProbe 'startup-threads-1.txt'
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while (-not (Test-Path -LiteralPath $startupSample -PathType Leaf)) {
        Assert-ArtifactAlive $child 'Startup thread evidence'
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Startup thread evidence missing before desktop observation.' }
        Start-Sleep -Milliseconds 50
    }
    $threads = Get-Content -LiteralPath $startupSample -Raw -Encoding UTF8
    if (-not $threads.Contains('DelayedLauncher.main') -or -not $threads.Contains('TIMED_WAITING')) {
        throw 'Startup thread evidence omitted the waiting application entry.'
    }
    $desktop = Invoke-ReleaseProbe $child $startupProbe 'desktop'
    if ($desktop.applicationLoaded) { throw 'Observer loaded the application entry prematurely.' }
    # The shared deadline may expire during polling or during the final IPC request.
    Assert-Rejected { Wait-ReleaseDesktop $child $startupProbe '' -BootstrapPrompt -TimeoutSeconds 1 } @(
        'Desktop did not render', 'Release observer did not answer desktop before its deadline')
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
    Start-BlockedDesktop
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $blockedSample = @(Get-ChildItem -LiteralPath $startupProbe -Filter 'startup-threads-*.txt' |
            Where-Object { (Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8).Contains('blockEventThread') })
        if ($blockedSample.Count) { break }
        Assert-ArtifactAlive $child 'Startup evidence with blocked EDT'
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Startup evidence sampler depended on the blocked EDT.' }
        Start-Sleep -Milliseconds 50
    } while ($true)
    Assert-Rejected { Invoke-ReleaseProbe $child $startupProbe 'dismiss' -AllowPendingDesktop } 'TimeoutException'
    $threads = Get-Content -LiteralPath (Join-Path $startupProbe 'edt-timeout.txt') -Raw -Encoding UTF8
    if (-not $threads.Contains('EDT task started: false') -or
        -not $threads.Contains('AWT-EventQueue') -or -not $threads.Contains('blockEventThread')) {
        throw 'EDT timeout evidence omitted the queued task or blocking thread.'
    }
    $threadPath = Join-Path $startupProbe 'edt-timeout.txt'
    Remove-Item -LiteralPath $threadPath
    New-Item -ItemType Directory -Path $threadPath | Out-Null
    Assert-Rejected { Invoke-ReleaseProbe $child $startupProbe 'desktop' } 'TimeoutException'
    $errorResponse = Get-Content -LiteralPath (Join-Path $startupProbe 'response.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $errorResponse.error.Contains('Suppressed:')) {
        throw 'Thread evidence write failure replaced or hid the original timeout.'
    }
    if ($errorResponse.code -ne 'EDT_TIMEOUT' -or $errorResponse.taskStarted -cne $false) {
        throw 'Queued EDT timeout did not carry structured state.'
    }
    Remove-Item -LiteralPath $threadPath
    New-Item -ItemType File -Path (Join-Path $startupProbe 'release-after-timeout') | Out-Null
    $elapsed = [Diagnostics.Stopwatch]::StartNew()
    $desktop = Wait-ReleaseDesktop $child $startupProbe '' -BootstrapPrompt -TimeoutSeconds 15
    if ($elapsed.Elapsed.TotalSeconds -lt 5) { throw 'Recovery did not exercise a queued EDT timeout.' }
    if ($desktop.bootstrapPrompts -ne 1) { throw 'Expired dismissal ran after EDT recovery.' }

    foreach ($visible in @($true, $false)) {
        $request = Join-Path $startupProbe $(if ($visible) { 'show-feedback' } else { 'hide-feedback' })
        New-Item -ItemType File -Path $request | Out-Null
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while (Test-Path -LiteralPath $request) {
            Assert-ArtifactAlive $child 'Feedback visibility fixture'
            if ([DateTime]::UtcNow -ge $deadline) { throw 'Feedback visibility did not change.' }
            Start-Sleep -Milliseconds 50
        }
        $desktop = Invoke-ReleaseProbe $child $startupProbe 'desktop'
        if ((Test-ReleaseDesktopReady $desktop '' -BootstrapPrompt) -eq $visible) {
            throw 'Hidden text was required or visible unpainted text was accepted.'
        }
    }

    Start-BlockedDesktop
    $elapsed.Restart()
    Assert-Rejected { Wait-ReleaseDesktop $child $startupProbe '' -BootstrapPrompt -TimeoutSeconds 7 } 'did not answer desktop'
    if ($elapsed.Elapsed.TotalSeconds -lt 7 -or $elapsed.Elapsed.TotalSeconds -gt 9) {
        throw 'Desktop retries reset or overran the shared deadline.'
    }
    New-Item -ItemType File -Path (Join-Path $startupProbe 'release') | Out-Null
    Wait-ReleaseDesktop $child $startupProbe '' -BootstrapPrompt -TimeoutSeconds 15 | Out-Null

    Start-BlockedDesktop
    $elapsed.Restart()
    Assert-Rejected { Assert-ReleaseStable -Process $child -ProbeRoot $startupProbe -BootstrapPrompt -Seconds 15 } 'TimeoutException'
    if ($elapsed.Elapsed.TotalSeconds -gt 8) { throw 'Stable observation retried an unresponsive EDT.' }
    New-Item -ItemType File -Path (Join-Path $startupProbe 'release') | Out-Null
    Wait-ReleaseDesktop $child $startupProbe '' -BootstrapPrompt -TimeoutSeconds 15 | Out-Null

    Remove-Item -LiteralPath (Join-Path $startupProbe 'release')
    New-Item -ItemType File -Path (Join-Path $startupProbe 'block-inspection') | Out-Null
    $elapsed.Restart()
    Assert-Rejected { Wait-ReleaseDesktop $child $startupProbe '' -BootstrapPrompt -TimeoutSeconds 15 } 'TimeoutException'
    $errorResponse = Get-Content -LiteralPath (Join-Path $startupProbe 'response.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $threads = Get-Content -LiteralPath $threadPath -Raw -Encoding UTF8
    if ($errorResponse.taskStarted -cne $true -or -not $threads.Contains('EDT task started: true') -or
        -not (Test-Path -LiteralPath (Join-Path $startupProbe 'inspection-started')) -or $elapsed.Elapsed.TotalSeconds -gt 8) {
        throw 'An already running desktop inspection was retried or lost its evidence.'
    }
    New-Item -ItemType File -Path (Join-Path $startupProbe 'release') | Out-Null
    Wait-ReleaseDesktop $child $startupProbe '' -BootstrapPrompt -TimeoutSeconds 15 | Out-Null
    Invoke-ReleaseProbe $child $startupProbe 'dismiss' | Out-Null
    $desktop = Invoke-ReleaseProbe $child $startupProbe 'desktop'
    if ($desktop.bootstrapPrompts -ne 0) { throw 'Fresh dismissal did not close the fixture window.' }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while (@(Get-ChildItem -LiteralPath $startupProbe -Filter 'startup-threads-*.txt').Count -lt 6) {
        Assert-ArtifactAlive $child 'Bounded startup evidence'
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Startup evidence did not finish its bounded sample set.' }
        Start-Sleep -Milliseconds 100
    }
    Start-Sleep -Seconds 11
    if (@(Get-ChildItem -LiteralPath $startupProbe -Filter 'startup-threads-*.txt').Count -ne 6) {
        throw 'Startup evidence continued beyond its six-snapshot budget.'
    }
    New-Item -ItemType File -Path (Join-Path $startupProbe 'exit') | Out-Null
    Wait-ArtifactProcessExit $child 'Delayed entry fixture' 15
    if ($child.ExitCode -ne 0) { throw "Delayed entry fixture failed with code $($child.ExitCode)." }
    Assert-Rejected { Wait-ReleaseDesktop $child $startupProbe '' -BootstrapPrompt -TimeoutSeconds 1 } 'exited'

    $good = [pscustomobject]@{ok=$true; applicationLoaded=$true; provider='gui-compose'; bootstrapPrompts=0; contentColors=30; windows=@('window')}
    if (-not (Test-ReleaseDesktopReady $good 'gui-compose')) { throw 'Rendered GUI rejected.' }
    foreach ($bad in @(
        [pscustomobject]@{applicationLoaded=$true; provider='gui-swing'; bootstrapPrompts=0; contentColors=30; windows=@('window')},
        [pscustomobject]@{applicationLoaded=$true; provider='gui-compose'; bootstrapPrompts=0; contentColors=1; windows=@('window')},
        [pscustomobject]@{applicationLoaded=$true; provider='gui-compose'; bootstrapPrompts=0; contentColors=30; windows=@()},
        [pscustomobject]@{ok=$true; applicationLoaded=$true; provider=''; bootstrapPrompts=1; contentColors=30; windows=@('dialog'); bootstrapTextRendered=$true}
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
            if (++$script:desktopReads -eq 1) { return [pscustomobject]@{ok=$true; applicationLoaded=$false} }
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
        return $script:status
    }
    function Invoke-WebRequest {
        param($Uri, [switch]$UseBasicParsing, $TimeoutSec)
        return [pscustomobject]@{StatusCode=200; Content='{"status":"UP"}'}
    }
    $exitSignal = Join-Path $context.Session 'exit-after-health'
    $exitFixture = Join-Path $context.Session 'exit-after-health.ps1'
    [IO.File]::WriteAllText($exitFixture, @'
param([string]$Signal)
while (-not [IO.File]::Exists($Signal)) { Start-Sleep -Milliseconds 50 }
exit 0
'@, [Text.Encoding]::ASCII)
    $child = Start-Process $parent.Path -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', "`"$exitFixture`"", '-Signal', "`"$exitSignal`"") -PassThru -WindowStyle Hidden
    $children += $child
    Assert-ArtifactAlive $child 'ready'
    $readHealth = ${function:Invoke-WebRequest}
    function Invoke-WebRequest {
        param($Uri, [switch]$UseBasicParsing, $TimeoutSec)
        [IO.File]::WriteAllText($exitSignal, 'exit', [Text.Encoding]::ASCII)
        if (-not $child.WaitForExit(15000)) { throw 'Ready fixture did not exit after its health response.' }
        return & $readHealth @PSBoundParameters
    }
    try {
        Assert-Rejected { Assert-ReleaseStable -Process $child -Seconds 3 } 'code 0'
    } finally {
        Set-Item Function:Invoke-WebRequest $readHealth
    }
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
    Write-Host 'PASS: release E2E retries queued desktop startup within one deadline; rejects active/stable EDT stalls, early/late exit, dead JVM, stale probes, broken GUI, unexpected recovery and failed shutdown.'
} catch {
    Write-Host $_.ScriptStackTrace
    Get-ChildItem -LiteralPath $context.Session -Recurse -File |
        Where-Object { $_.Name -in @('edt-timeout.txt', 'probe-error.txt', 'stderr.log') } |
        ForEach-Object {
            Write-Host "Fixture diagnostic: $($_.FullName)"
            Get-Content -LiteralPath $_.FullName -Tail 200 -Encoding UTF8 | ForEach-Object { Write-Host $_ }
        }
    throw
} finally {
    foreach ($child in $children) {
        try { Stop-ArtifactProcess $child } finally {
            if ($child.PSObject.Properties['EngineProcess']) { $child.EngineProcess.Dispose() }
            $child.Dispose()
        }
    }
    if (Test-Path Variable:parent) { $parent.Dispose() }
    Remove-TestSessionRoot $context
}
