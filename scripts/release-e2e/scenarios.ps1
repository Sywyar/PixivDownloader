# Final-artifact scenarios share the production launcher, isolated state and observer.

function Test-ApplicationScenario {
    param([string]$Label, [string]$Root, [string]$Launcher, [string]$RuntimeRoot, [string]$LogRoot,
        [string]$Provider = '', [string]$ConfiguredProvider = '', [switch]$Headless,
        [switch]$FirstRun, [switch]$BootstrapPrompt, [bool]$Recovery = $false,
        [string[]]$CrashedIds = @(), [string[]]$UnavailableIds = @(), [scriptblock]$Exercise,
        [switch]$InjectsFailure)
    $expected = @(Read-ExpectedPluginIds (Get-ManifestPath $Root))
    Set-IsolatedRuntimeEnvironment $RuntimeRoot
    $port = Get-FreePort
    $config = Join-Path $RuntimeRoot 'config/config.yaml'
    [IO.Directory]::CreateDirectory((Split-Path -Parent $config)) | Out-Null
    $configLines = @('server.address: 127.0.0.1', "server.port: $port", 'app.language: en-US',
        'proxy.enabled: false', 'update.enabled: false', 'maintenance.enabled: false',
        "download.root-folder: $($RuntimeRoot.Replace('\', '/'))/downloads")
    if ($ConfiguredProvider) { $configLines += "app.gui-provider: $ConfiguredProvider" }
    [IO.File]::WriteAllLines($config, $configLines, [Text.UTF8Encoding]::new($false))
    $probeRoot = Join-Path $RuntimeRoot 'probe'
    [IO.Directory]::CreateDirectory($probeRoot) | Out-Null
    $application = $null
    $result = [ordered]@{ label = $Label; passed = $false; error = ''; provider = $Provider }
    try {
        if (-not $FirstRun) {
            $setup = Start-ArtifactProcess -Launcher $Launcher -WorkingDirectory $Root -Arguments @(
                '--setup', "--username=$Username", "--password=$Password", '--mode=solo', '--proxy-enabled=false'
            ) -LogPrefix "$LogRoot-setup" -Wait
            if ($setup.ExitCode -ne 0) { throw "$Label setup failed with exit code $($setup.ExitCode)" }
        }
        $env:JAVA_TOOL_OPTIONS += " -javaagent:`"$($script:ReleaseTools.Agent)=$probeRoot`""
        $arguments = @('--server.address=127.0.0.1', "--server.port=$port")
        if ($Headless) { $arguments = @('--no-gui') + $arguments }
        $application = Start-ArtifactProcess -Launcher $Launcher -WorkingDirectory $Root `
            -Arguments $arguments -LogPrefix "$LogRoot-application"
        Connect-ReleaseProbe $application $probeRoot
        $session = $null
        if (-not $BootstrapPrompt) {
            Wait-ForHealthyApplication -Port $port -Process $application -Label $Label
            if (-not $FirstRun) { $session = New-ReleaseLogin $port }
        }
        if (-not $Headless) {
            Wait-ReleaseDesktop $application $probeRoot $Provider -BootstrapPrompt:$BootstrapPrompt | Out-Null
        }
        Assert-ReleaseStable -Process $application -ProbeRoot $probeRoot -Port $port -Session $session `
            -ExpectedIds $expected -Provider $Provider -Recovery $Recovery -CrashedIds $CrashedIds `
            -UnavailableIds $UnavailableIds -BootstrapPrompt:$BootstrapPrompt
        if (-not $CrashedIds.Count -and -not $UnavailableIds.Count) { Assert-ReleaseNoFatalLog $Root }
        $run = [pscustomobject]@{ Process = $application; ProbeRoot = $probeRoot; Port = $port;
            Session = $session; ExpectedIds = $expected; Root = $Root; Launcher = $Launcher;
            RuntimeRoot = $RuntimeRoot; LogRoot = $LogRoot; Provider = $Provider }
        if ($Exercise) { & $Exercise $run }
        Invoke-ReleaseProbe $application $probeRoot 'logs' | Out-Null
        Stop-ReleaseApplication $application $probeRoot
        Assert-ReleaseLogParity $Root $LogRoot
        if (-not $CrashedIds.Count -and -not $UnavailableIds.Count -and -not $InjectsFailure) { Assert-ReleaseNoFatalLog $Root }
        $result.passed = $true
        Write-Host "PASS: $Label" -ForegroundColor Green
    } catch {
        $result.error = $_.Exception.Message
        Write-Host "FAIL: $Label - $($_.Exception.Message)"
        foreach ($log in @("$LogRoot-setup.stdout.log", "$LogRoot-setup.stderr.log",
                "$LogRoot-application.stdout.log", "$LogRoot-application.stderr.log",
                "$LogRoot-second.stdout.log", "$LogRoot-second.stderr.log",
                (Join-Path $Root 'log/latest.log'), (Join-Path $probeRoot 'probe-error.txt'))) {
            if (Test-Path -LiteralPath $log -PathType Leaf) {
                Write-Host "Log: $log (last 200 lines)"
                Get-Content -LiteralPath $log -Encoding UTF8 -Tail 200
            }
        }
        throw
    } finally {
        try { Stop-ArtifactProcess $application } finally {
            Save-ReleaseEvidence -Label $Label -Root $Root -RuntimeRoot $RuntimeRoot -LogRoot $LogRoot -Result $result
        }
    }
}

function Assert-ReleaseNoFatalLog {
    param([string]$Root)
    $text = Get-Content -LiteralPath (Join-Path $Root 'log/latest.log') -Raw -Encoding UTF8
    if ($text -match 'ExceptionInInitializerError|ClassFormatError|UnsatisfiedLinkError|NoClassDefFoundError|RELEASE_E2E_PLUGIN_CRASH') {
        throw 'Unexpected class initialization or native failure in the final artifact.'
    }
}

function Save-ReleaseEvidence {
    param([string]$Label, [string]$Root, [string]$RuntimeRoot, [string]$LogRoot, $Result)
    $report = Join-Path $script:ReleaseReportRoot $Label
    [IO.Directory]::CreateDirectory($report) | Out-Null
    $Result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $report 'result.json') -Encoding UTF8
    foreach ($item in @((Join-Path $Root 'log'), (Join-Path $RuntimeRoot 'probe'))) {
        if (Test-Path -LiteralPath $item) { Copy-Item -LiteralPath $item -Destination $report -Recurse }
    }
    foreach ($log in @("$LogRoot-setup.stdout.log", "$LogRoot-setup.stderr.log",
            "$LogRoot-application.stdout.log", "$LogRoot-application.stderr.log",
            "$LogRoot-second.stdout.log", "$LogRoot-second.stderr.log")) {
        if (Test-Path -LiteralPath $log) { Copy-Item -LiteralPath $log -Destination $report }
    }
}

function Assert-ReleaseLogParity {
    param([string]$Root, [string]$LogRoot)
    $text = Get-Content -LiteralPath (Join-Path $Root 'log/latest.log') -Raw -Encoding UTF8
    $html = Get-Content -LiteralPath (Join-Path $Root 'log/html/latest.html') -Raw -Encoding UTF8
    if ([regex]::Matches($html, '<!DOCTYPE html>').Count -ne 1 -or -not $html.TrimEnd().EndsWith('</html>')) {
        throw 'HTML log is appended across runs or was not closed.'
    }
    $plainHtml = [Net.WebUtility]::HtmlDecode(($html -replace '<[^>]+>', ''))
    $console = (Get-Content -LiteralPath "$LogRoot-application.stdout.log" -Raw -Encoding UTF8) +
        (Get-Content -LiteralPath "$LogRoot-application.stderr.log" -Raw -Encoding UTF8)
    $unicode = [string][char]0x4e2d + [char]0x6587
    foreach ($marker in @("RELEASE_E2E_STDOUT <probe>&`" $unicode", 'RELEASE_E2E_STDERR', 'RELEASE_E2E_JUL',
            'RELEASE_E2E_STACK', 'RELEASE_E2E_CAUSE', 'RELEASE_E2E_SUPPRESSED', 'RELEASE_E2E_PARTIAL')) {
        foreach ($output in @($console, $text, $plainHtml)) {
            if ([regex]::Matches($output, [regex]::Escape($marker)).Count -ne 1) {
                throw "Console/text/HTML log lost or duplicated $marker"
            }
        }
    }
}

function Test-ReleaseLogRotation {
    param([string]$Root, [string]$Launcher, [string]$RuntimeRoot, [string]$Label)
    Set-IsolatedRuntimeEnvironment $RuntimeRoot
    $markers = @()
    $result = [ordered]@{ label = $Label; passed = $false; error = '' }
    try {
        for ($run = 0; $run -lt 7; $run++) {
            $marker = 'rotation-' + [Guid]::NewGuid().ToString('N')
            $markers += $marker
            $process = Start-ArtifactProcess -Launcher $Launcher -WorkingDirectory $Root `
                -Arguments @('--help', "--probe=$marker") -LogPrefix (Join-Path $RuntimeRoot $marker) -Wait
            if ($process.ExitCode -ne 0) { throw "Help command exited with $($process.ExitCode)" }
            $text = Get-Content -LiteralPath (Join-Path $Root 'log/latest.log') -Raw -Encoding UTF8
            $html = Get-Content -LiteralPath (Join-Path $Root 'log/html/latest.html') -Raw -Encoding UTF8
            if (-not $text.Contains($marker) -or -not $html.Contains($marker)) { throw 'Current log is missing its run marker.' }
            if ($run -gt 0 -and ($text.Contains($markers[$run - 1]) -or $html.Contains($markers[$run - 1]))) {
                throw 'Latest logs contain a previous run.'
            }
            $textHistory = @(Get-ChildItem -LiteralPath (Join-Path $Root 'log') -Filter 'pixiv-download_*.log' | Sort-Object Name)
            $htmlHistory = @(Get-ChildItem -LiteralPath (Join-Path $Root 'log/html') -Filter 'pixiv-download_*.html' | Sort-Object Name)
            if ($textHistory.Count -gt 6 -or $htmlHistory.Count -ne $textHistory.Count) { throw 'Log retention or pairing failed.' }
            if (($textHistory.BaseName -join ',') -ne ($htmlHistory.BaseName -join ',')) { throw 'Text and HTML sessions differ.' }
            if ($text -cne (Get-Content $textHistory[-1].FullName -Raw -Encoding UTF8) -or
                $html -cne (Get-Content $htmlHistory[-1].FullName -Raw -Encoding UTF8)) { throw 'Latest and dated logs differ.' }
        }
        if ($textHistory.Count -ne 6) { throw 'Expected current run plus exactly five historical runs.' }
        foreach ($file in @($textHistory) + @($htmlHistory)) {
            if ((Get-Content $file.FullName -Raw -Encoding UTF8).Contains($markers[0])) { throw 'Oldest run was not removed.' }
        }
        $result.passed = $true
        Write-Host "PASS: $Label (current plus five previous runs)" -ForegroundColor Green
    } catch {
        $result.error = $_.Exception.Message
        throw
    } finally {
        Save-ReleaseEvidence -Label $Label -Root $Root -RuntimeRoot $RuntimeRoot -LogRoot '' -Result $result
    }
}

function Test-ReleaseDuplicateInstance {
    param($Run)
    $before = Get-Content (Join-Path $Run.Root 'log/latest.log') -Raw -Encoding UTF8
    $options = $env:JAVA_TOOL_OPTIONS
    try {
        Set-IsolatedRuntimeEnvironment $Run.RuntimeRoot
        $second = Start-ArtifactProcess -Launcher $Run.Launcher -WorkingDirectory $Run.Root `
            -Arguments @('--no-gui') -LogPrefix "$($Run.LogRoot)-second" -Wait
        if ($second.ExitCode -ne 0) { throw 'Second instance did not exit normally.' }
        Assert-ArtifactAlive $Run.Process 'Original instance'
        $after = Get-Content (Join-Path $Run.Root 'log/latest.log') -Raw -Encoding UTF8
        if (-not $after.StartsWith($before)) { throw 'Second instance overwrote the active log.' }
    } finally { $env:JAVA_TOOL_OPTIONS = $options }
}

function Assert-ReleaseRecoveryGate {
    param($Run)
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($Run.Port)/api/download/extensions" `
            -WebSession $Run.Session -TimeoutSec 10 | Out-Null
        throw 'Recovery mode exposed the download API.'
    } catch {
        if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 503) { throw }
    }
    $management = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$($Run.Port)/plugin-manage.html" `
        -WebSession $Run.Session -TimeoutSec 10
    if ($management.StatusCode -ne 200) { throw 'Recovery management page is unavailable.' }
}

function Test-ReleaseRuntimeFailures {
    param($Run)
    $crashed = @()
    foreach ($id in @('mail', 'download-workbench')) {
        Invoke-ReleaseProbe $Run.Process $Run.ProbeRoot 'crash' $id | Out-Null
        $crashed += $id
        $recovery = $id -eq 'download-workbench'
        Wait-ReleaseTransition $Run $crashed -Recovery $recovery
        Assert-ReleaseStable -Process $Run.Process -ProbeRoot $Run.ProbeRoot -Port $Run.Port -Session $Run.Session `
            -ExpectedIds $Run.ExpectedIds -Provider $Run.Provider -CrashedIds $crashed -Recovery $recovery
        if ($recovery) { Assert-ReleaseRecoveryGate $Run }
        $desktop = Invoke-ReleaseProbe $Run.Process $Run.ProbeRoot 'desktop'
        if (-not ($desktop.windows | ConvertTo-Json -Depth 4).Contains("RELEASE_E2E_PLUGIN_CRASH $id")) {
            throw "Plugin failure notification is not visible: $id"
        }
        Copy-Item (Join-Path $Run.ProbeRoot 'window.png') (Join-Path $Run.ProbeRoot "$id-failure.png")
        $log = Get-Content (Join-Path $Run.Root 'log/latest.log') -Raw -Encoding UTF8
        if (-not $log.Contains("RELEASE_E2E_PLUGIN_CRASH $id")) { throw "Missing plugin failure diagnostic: $id" }
    }
}

function Wait-ReleaseTransition {
    param($Run, [string[]]$CrashedIds, [bool]$Recovery = $false)
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        Assert-ArtifactAlive $Run.Process 'Plugin failure transition'
        try {
            Assert-ReleaseStatus -Port $Run.Port -Session $Run.Session -ExpectedIds $Run.ExpectedIds `
                -CrashedIds $CrashedIds -Recovery $Recovery -EvidencePath (Join-Path $Run.ProbeRoot 'plugins.json') | Out-Null
            return
        } catch {
            if ([DateTime]::UtcNow -ge $deadline) { throw }
        }
        Start-Sleep -Milliseconds 500
    } while ($true)
}

function Test-ReleaseGuiFailures {
    param($Run)
    Invoke-ReleaseProbe $Run.Process $Run.ProbeRoot 'crash' 'gui-compose' | Out-Null
    $desktop = Wait-ReleaseDesktop $Run.Process $Run.ProbeRoot 'gui-swing'
    Wait-ReleaseTransition $Run @('gui-compose')
    $warnings = @($desktop.windows | Where-Object {
        $_.type -eq 'javax.swing.JDialog' -and $_.text.Contains('gui-compose')
    })
    if ($warnings.Count -ne 1) {
        throw 'GUI fallback warning does not identify the failed provider.'
    }
    Assert-ReleaseStable -Process $Run.Process -ProbeRoot $Run.ProbeRoot -Port $Run.Port -Session $Run.Session `
        -ExpectedIds $Run.ExpectedIds -Provider 'gui-swing' -CrashedIds @('gui-compose')
    Copy-Item (Join-Path $Run.ProbeRoot 'window.png') (Join-Path $Run.ProbeRoot 'fallback.png')
    Invoke-ReleaseProbe $Run.Process $Run.ProbeRoot 'dismiss' | Out-Null
    Invoke-ReleaseProbe $Run.Process $Run.ProbeRoot 'crash' 'gui-compose' | Out-Null
    Wait-ReleaseDesktop $Run.Process $Run.ProbeRoot 'gui-swing' | Out-Null
    Assert-ReleaseStable -Process $Run.Process -ProbeRoot $Run.ProbeRoot -Port $Run.Port -Session $Run.Session `
        -ExpectedIds $Run.ExpectedIds -Provider 'gui-swing' -CrashedIds @('gui-compose')
    Invoke-ReleaseProbe $Run.Process $Run.ProbeRoot 'crash' 'gui-swing' | Out-Null
    Wait-ReleaseDesktop $Run.Process $Run.ProbeRoot '' -BootstrapPrompt | Out-Null
    Wait-ReleaseTransition $Run @('gui-compose', 'gui-swing')
    Assert-ReleaseStable -Process $Run.Process -ProbeRoot $Run.ProbeRoot -Port $Run.Port -Session $Run.Session `
        -ExpectedIds $Run.ExpectedIds -CrashedIds @('gui-compose', 'gui-swing') -BootstrapPrompt
}

function Test-ReleaseAdverseLayouts {
    param([string]$Label, [string]$Root, [string]$Launcher, [string]$RuntimeRoot, [string]$LogRoot,
        [ValidateSet('all', 'failures', 'recovery')][string]$ScenarioGroup = 'all')
    if ($ScenarioGroup -in @('all', 'failures')) {
        Test-ApplicationScenario -Label "$Label-first-run" -Root $Root -Launcher $Launcher `
            -RuntimeRoot "$RuntimeRoot-first" -LogRoot "$LogRoot-first" -Provider 'gui-compose' -FirstRun
        Test-ApplicationScenario -Label "$Label-plugin-runtime-failures" -Root $Root -Launcher $Launcher `
            -RuntimeRoot "$RuntimeRoot-runtime-fault" -LogRoot "$LogRoot-runtime-fault" -Provider 'gui-compose' `
            -Exercise ${function:Test-ReleaseRuntimeFailures} -InjectsFailure
        Test-ApplicationScenario -Label "$Label-gui-runtime-failures" -Root $Root -Launcher $Launcher `
            -RuntimeRoot "$RuntimeRoot-gui-fault" -LogRoot "$LogRoot-gui-fault" -Provider 'gui-compose' `
            -Exercise ${function:Test-ReleaseGuiFailures} -InjectsFailure

        $fixture = Join-Path $Root 'plugins/release-e2e-broken-gui.jar'
        Copy-Item -LiteralPath $script:ReleaseTools.Fixture -Destination $fixture
        $sdkMajor = [int](Get-PixivDownloadSdkVersion -ProjectRoot (Join-Path $PSScriptRoot '../..')).Split('.')[0]
        $sidecar = Write-UnsignedLocalPluginProvenanceSidecar -ArtifactPath $fixture `
            -VerifiedAt ([DateTime]::UtcNow.ToString('o')) -AppSdkMajor $sdkMajor
        try {
            Test-ApplicationScenario -Label "$Label-gui-initializer-failure" -Root $Root -Launcher $Launcher `
                -RuntimeRoot "$RuntimeRoot-initializer" -LogRoot "$LogRoot-initializer" -Provider 'gui-compose' `
                -ConfiguredProvider 'release-e2e-broken-gui' -CrashedIds @('release-e2e-broken-gui')
        } finally {
            Remove-Item -LiteralPath $fixture, $sidecar
        }
    }
    if ($ScenarioGroup -in @('all', 'recovery')) {
        Test-ReleaseUnavailableLayouts -Label $Label -Root $Root -Launcher $Launcher -RuntimeRoot $RuntimeRoot -LogRoot $LogRoot
    }
}

function Test-ReleaseUnavailableLayouts {
    param([string]$Label, [string]$Root, [string]$Launcher, [string]$RuntimeRoot, [string]$LogRoot)
    $manifest = Get-Content (Get-ManifestPath $Root) -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($scenario in @('no-gui', 'missing-required', 'corrupt-required', 'core-shell')) {
        $hidden = New-Object 'System.Collections.Generic.List[object]'
        try {
            $ids = @(switch ($scenario) {
                'no-gui' { @('gui-compose', 'gui-swing') }
                'core-shell' { @($manifest.id) }
                default { @($manifest | Where-Object { $_.required } | ForEach-Object { $_.id }) }
            })
            if (-not $ids.Count) { throw 'Adverse scenario selected no plugins.' }
            foreach ($entry in @($manifest | Where-Object { $ids -contains $_.id })) {
                $file = Join-Path $Root "plugins/$([IO.Path]::GetFileName($entry.file))"
                $backup = "$file.e2e-hidden"
                Move-Item -LiteralPath $file -Destination $backup
                $hidden.Add([pscustomobject]@{ File = $file; Backup = $backup })
                if ($scenario -eq 'corrupt-required') { [IO.File]::WriteAllText($file, 'invalid archive', [Text.Encoding]::ASCII) }
            }
            $prompt = $scenario -eq 'no-gui'
            $headless = $scenario -eq 'core-shell'
            $provider = if ($prompt -or $headless) { '' } else { 'gui-compose' }
            $exercise = if ($prompt) { $null } else { ${function:Assert-ReleaseRecoveryGate} }
            Test-ApplicationScenario -Label "$Label-$scenario" -Root $Root -Launcher $Launcher `
                -RuntimeRoot "$RuntimeRoot-$scenario" -LogRoot "$LogRoot-$scenario" -Provider $provider `
                -BootstrapPrompt:$prompt -Headless:$headless -Recovery:(-not $prompt) -UnavailableIds $ids -Exercise $exercise
        } finally {
            foreach ($entry in $hidden) {
                if (Test-Path -LiteralPath $entry.File) { Remove-Item -LiteralPath $entry.File }
                Move-Item -LiteralPath $entry.Backup -Destination $entry.File
            }
        }
    }
    Test-ReleaseLogRotation -Root $Root -Launcher $Launcher -RuntimeRoot "$RuntimeRoot-rotation" -Label "$Label-log-rotation"
}
