[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$JavaZipPath,
    [Parameter(Mandatory = $true)][string]$FullOfflineZipPath,
    [string]$InstallerPath,
    [string]$AppImagePath,
    [string]$WorkRoot
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Username = "release-e2e"
$Password = "ReleaseE2ePassword2026"
$UninstallKeys = @(
    "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{4D4F3566-C6C0-4D24-9242-86059B2A84A5}_is1",
    "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\{4D4F3566-C6C0-4D24-9242-86059B2A84A5}_is1",
    "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{4D4F3566-C6C0-4D24-9242-86059B2A84A5}_is1",
    "HKCU:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\{4D4F3566-C6C0-4D24-9242-86059B2A84A5}_is1"
)

function Resolve-RequiredFile {
    param([string]$Path, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label not found or not a file: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Resolve-OptionalDirectory {
    param([string]$Path, [string]$Label)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return ""
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Label not found or not a directory: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function New-TestSessionRoot {
    $base = $WorkRoot
    if ([string]::IsNullOrWhiteSpace($base)) {
        $base = if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
            [System.IO.Path]::GetTempPath()
        } else {
            $env:RUNNER_TEMP
        }
    }
    [System.IO.Directory]::CreateDirectory($base) | Out-Null
    $resolvedBase = (Resolve-Path -LiteralPath $base).Path
    $session = Join-Path $resolvedBase ("pixiv-release-e2e-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $session | Out-Null
    return [pscustomobject]@{ Base = $resolvedBase; Session = $session }
}

function Remove-TestSessionRoot {
    param($Context)
    if ($null -eq $Context -or -not (Test-Path -LiteralPath $Context.Session)) {
        return
    }
    $candidate = [System.IO.Path]::GetFullPath($Context.Session)
    if ((Split-Path -Parent $candidate) -ne [System.IO.Path]::GetFullPath($Context.Base) -or
        (Split-Path -Leaf $candidate) -notlike "pixiv-release-e2e-*") {
        throw "Refusing to remove unsafe test session root: $candidate"
    }
    Remove-Item -LiteralPath $candidate -Recurse -Force
}

function Assert-NoInstalledCopy {
    $existing = @($UninstallKeys | Where-Object { Test-Path -LiteralPath $_ })
    if ($existing.Count -ne 0) {
        throw "Refusing installer E2E because PixivDownload is already registered: $($existing -join ', ')"
    }
}

function Get-ManifestPath {
    param([string]$Root)
    $candidates = @(
        (Join-Path $Root "plugins-manifest.json"),
        (Join-Path $Root "plugins\plugins-manifest.json")
    )
    $found = @($candidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($found.Count -ne 1) {
        throw "Expected exactly one plugins-manifest.json under $Root, found $($found.Count)"
    }
    return $found[0]
}

function Read-ExpectedPluginIds {
    param([string]$ManifestPath)
    $manifest = @(Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json)
    $ids = @($manifest | ForEach-Object { $_.id })
    if ($ids.Count -eq 0 -or $ids -contains $null -or (@($ids | Sort-Object -Unique)).Count -ne $ids.Count) {
        throw "Invalid plugin id set in $ManifestPath"
    }
    return $ids
}

function Set-IsolatedRuntimeEnvironment {
    param([string]$RuntimeRoot)
    New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null
    $normalized = $RuntimeRoot.Replace("\", "/")
    $env:JAVA_TOOL_OPTIONS = @(
        "-Dfile.encoding=UTF-8",
        "-Dpixivdownload.config-dir=`"$normalized/config`"",
        "-Dpixivdownload.state-dir=`"$normalized/state`"",
        "-Dpixivdownload.data-dir=`"$normalized/data`"",
        "-Dpixivdownload.instance-dir=`"$normalized/instance`""
    ) -join " "
    $env:NO_PROXY = "localhost,127.0.0.1"
}

function Assert-SafeArguments {
    param([string[]]$Arguments)
    foreach ($argument in $Arguments) {
        if ($argument -notmatch '^[A-Za-z0-9._:=!/-]+$') {
            throw "Unsafe release E2E launcher argument: $argument"
        }
    }
}

function Start-ArtifactProcess {
    param(
        [string]$Launcher,
        [string]$WorkingDirectory,
        [string[]]$Arguments,
        [string]$LogPrefix,
        [switch]$Wait
    )
    Assert-SafeArguments $Arguments
    $stdout = "$LogPrefix.stdout.log"
    $stderr = "$LogPrefix.stderr.log"
    if ([System.IO.Path]::GetExtension($Launcher) -ieq ".bat") {
        $commandLine = "/d /s /c `"`"$Launcher`" $($Arguments -join ' ')`""
        $process = Start-Process -FilePath $env:ComSpec -ArgumentList $commandLine `
            -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    } else {
        $process = Start-Process -FilePath $Launcher -ArgumentList $Arguments `
            -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    }
    if ($Wait) {
        Wait-ArtifactProcessExit -Process $process -Label $Launcher -TimeoutSeconds 180
    }
    return $process
}

function Stop-ArtifactProcess {
    param($Process)
    if ($null -eq $Process) {
        return
    }
    $Process.Refresh()
    if (-not $Process.HasExited) {
        & taskkill.exe /PID $Process.Id /T /F | Out-Null
        $Process.WaitForExit(30000) | Out-Null
    }
}

function Wait-ArtifactProcessExit {
    param($Process, [string]$Label, [int]$TimeoutSeconds)
    if (-not $Process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-ArtifactProcess $Process
        throw "$Label did not exit within $TimeoutSeconds seconds"
    }
    $Process.WaitForExit()
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Wait-ForHealthyApplication {
    param([int]$Port, $Process, [string]$Label)
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        $Process.Refresh()
        if ($Process.HasExited) {
            throw "$Label exited before becoming healthy with code $($Process.ExitCode)"
        }
        try {
            $health = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/health" `
                -TimeoutSec 5
            if ($health.status -eq "UP") {
                $info = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/info" `
                    -TimeoutSec 5
                if ($info.StatusCode -ne 200) {
                    throw "$Label actuator info returned HTTP $($info.StatusCode)"
                }
                return
            }
        } catch {
            if ([DateTime]::UtcNow -ge $deadline) {
                throw
            }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "$Label did not become healthy within three minutes"
}

function Assert-PluginRuntimeStatus {
    param([int]$Port, [string[]]$ExpectedPluginIds, [string]$Label)
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $body = @{ username = $Username; password = $Password; rememberMe = $false } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$Port/api/auth/login" `
        -ContentType "application/json" -Body $body -WebSession $session -TimeoutSec 10
    if (-not $login.ok) {
        throw "$Label login did not return ok=true"
    }
    $status = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/plugins/status" `
        -WebSession $session -TimeoutSec 15
    if ($status.recoveryMode) {
        throw "$Label entered plugin recovery mode"
    }
    foreach ($pluginId in $ExpectedPluginIds) {
        $matches = @($status.plugins | Where-Object { $_.id -eq $pluginId })
        if ($matches.Count -ne 1) {
            throw "$Label expected one status row for $pluginId, found $($matches.Count)"
        }
        if ($matches[0].status -ne "STARTED" -or $matches[0].runtimePhase -ne "STARTED") {
            throw "$Label plugin $pluginId is not fully started: status=$($matches[0].status), phase=$($matches[0].runtimePhase)"
        }
    }
}

function Test-ApplicationLayout {
    param([string]$Label, [string]$Root, [string]$Launcher, [string]$RuntimeRoot, [string]$LogRoot)
    $manifestPath = Get-ManifestPath $Root
    $expectedPluginIds = @(Read-ExpectedPluginIds $manifestPath)
    Set-IsolatedRuntimeEnvironment $RuntimeRoot
    $setup = Start-ArtifactProcess -Launcher $Launcher -WorkingDirectory $Root -Arguments @(
        "--setup",
        "--username=$Username",
        "--password=$Password",
        "--mode=solo",
        "--proxy-enabled=false"
    ) -LogPrefix "$LogRoot-setup" -Wait
    if ($setup.ExitCode -ne 0) {
        throw "$Label setup failed with exit code $($setup.ExitCode)"
    }

    $port = Get-FreePort
    $application = $null
    try {
        $application = Start-ArtifactProcess -Launcher $Launcher -WorkingDirectory $Root -Arguments @(
            "--no-gui",
            "--server.address=127.0.0.1",
            "--server.port=$port"
        ) -LogPrefix "$LogRoot-application"
        Wait-ForHealthyApplication -Port $port -Process $application -Label $Label
        Assert-PluginRuntimeStatus -Port $port -ExpectedPluginIds $expectedPluginIds -Label $Label
        Write-Host "PASS: $Label ($($expectedPluginIds.Count) external plugin(s))" -ForegroundColor Green
    } finally {
        Stop-ArtifactProcess $application
    }
}

function Test-JavaArchive {
    param([string]$Label, [string]$Archive, [string]$Destination)
    Expand-Archive -LiteralPath $Archive -DestinationPath $Destination
    $launcher = Join-Path $Destination "run.bat"
    $jar = Join-Path $Destination "PixivDownload-$Version.jar"
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf) -or
        -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "$Label does not contain run.bat and the exact versioned application JAR"
    }
    Test-ApplicationLayout -Label $Label -Root $Destination -Launcher $launcher `
        -RuntimeRoot (Join-Path $Destination ".e2e-runtime") -LogRoot (Join-Path $Destination "e2e")
}

function Assert-PackagedRuntime {
    param([string]$Root, [string]$Label)
    $launcher = Join-Path $Root "PixivDownload.exe"
    $jvm = Join-Path $Root "runtime\bin\server\jvm.dll"
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf) -or
        -not (Test-Path -LiteralPath $jvm -PathType Leaf)) {
        throw "$Label does not contain PixivDownload.exe and its packaged JVM"
    }
    return $launcher
}

$resolvedJavaZip = Resolve-RequiredFile $JavaZipPath "Java distribution"
$resolvedFullOfflineZip = Resolve-RequiredFile $FullOfflineZipPath "Full-offline distribution"
$resolvedInstaller = if ([string]::IsNullOrWhiteSpace($InstallerPath)) {
    ""
} else {
    Resolve-RequiredFile $InstallerPath "Windows installer"
}
$resolvedAppImage = Resolve-OptionalDirectory $AppImagePath "Windows app image"
if ([string]::IsNullOrWhiteSpace($resolvedInstaller) -and [string]::IsNullOrWhiteSpace($resolvedAppImage)) {
    throw "Specify InstallerPath or AppImagePath so the packaged JVM is exercised."
}

$oldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$oldNoProxy = $env:NO_PROXY
$context = $null
$uninstaller = ""
try {
    $context = New-TestSessionRoot
    Test-JavaArchive -Label "java-standard" -Archive $resolvedJavaZip `
        -Destination (Join-Path $context.Session "java-standard")
    Test-JavaArchive -Label "full-offline" -Archive $resolvedFullOfflineZip `
        -Destination (Join-Path $context.Session "full-offline")

    if (-not [string]::IsNullOrWhiteSpace($resolvedAppImage)) {
        $launcher = Assert-PackagedRuntime $resolvedAppImage "Windows app image"
        Test-ApplicationLayout -Label "windows-app-image" -Root $resolvedAppImage -Launcher $launcher `
            -RuntimeRoot (Join-Path $context.Session "app-image-runtime") `
            -LogRoot (Join-Path $context.Session "app-image")
    }

    if (-not [string]::IsNullOrWhiteSpace($resolvedInstaller)) {
        Assert-NoInstalledCopy
        $installDir = Join-Path $context.Session "installed"
        $uninstaller = Join-Path $installDir "unins000.exe"
        $installer = Start-Process -FilePath $resolvedInstaller -ArgumentList @(
            "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-", "/NOICONS", "/LANG=en",
            "/DIR=`"$installDir`""
        ) -WindowStyle Hidden -PassThru
        Wait-ArtifactProcessExit -Process $installer -Label "Windows installer" -TimeoutSeconds 600
        if ($installer.ExitCode -ne 0) {
            throw "Windows installer failed with exit code $($installer.ExitCode)"
        }
        $launcher = Assert-PackagedRuntime $installDir "Installed application"
        if (-not (Test-Path -LiteralPath $uninstaller -PathType Leaf)) {
            throw "Installed application does not contain unins000.exe"
        }
        Test-ApplicationLayout -Label "windows-installer" -Root $installDir -Launcher $launcher `
            -RuntimeRoot (Join-Path $context.Session "installer-runtime") `
            -LogRoot (Join-Path $context.Session "installer")
    }
} finally {
    $uninstallFailure = ""
    if (-not [string]::IsNullOrWhiteSpace($uninstaller) -and
        (Test-Path -LiteralPath $uninstaller -PathType Leaf)) {
        try {
            $uninstall = Start-Process -FilePath $uninstaller -ArgumentList @(
                "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART"
            ) -WindowStyle Hidden -PassThru
            Wait-ArtifactProcessExit -Process $uninstall -Label "Windows uninstaller" -TimeoutSeconds 300
            if ($uninstall.ExitCode -ne 0) {
                $uninstallFailure = "Windows uninstaller exited with code $($uninstall.ExitCode)"
            }
        } catch {
            $uninstallFailure = "Windows uninstaller failed: $($_.Exception.Message)"
        }
    }
    $env:JAVA_TOOL_OPTIONS = $oldJavaToolOptions
    $env:NO_PROXY = $oldNoProxy
    Remove-TestSessionRoot $context
    if (-not [string]::IsNullOrWhiteSpace($uninstallFailure)) {
        throw $uninstallFailure
    }
}

if (-not [string]::IsNullOrWhiteSpace($resolvedInstaller)) {
    Assert-NoInstalledCopy
}
Write-Host "All requested release artifacts passed runtime acceptance." -ForegroundColor Green
