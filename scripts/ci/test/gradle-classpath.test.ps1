# 验证真实 Gradle 任务在配置缓存命中后仍拒绝缺失或变化的 Maven 输入。
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$PSNativeCommandUseErrorActionPreference = $false
$module = Join-Path $PSScriptRoot '../../../pixivdownload-plugin-gui-compose'
$module = [IO.Path]::GetFullPath($module)
$fixture = Join-Path $module ('target/classpath-test-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($fixture) | Out-Null
$manifest = Join-Path $fixture 'classpath.txt'
$dependency = Join-Path $fixture 'sdk-classes'
$wrapper = Join-Path $module $(if ($env:OS -eq 'Windows_NT') { 'gradlew.bat' } else { 'gradlew' })
$utf8 = [Text.UTF8Encoding]::new($false)
$script:invocation = 0

function Invoke-Verification {
    param([string]$Task, [bool]$Success, [string]$Expected)
    $script:invocation++
    $log = Join-Path $fixture ("run-$script:invocation.log")
    & $wrapper --daemon --console=plain --project-cache-dir (Join-Path $fixture 'gradle-state') `
        "-PmavenClasspathFile=$manifest" $Task *> $log
    $code = $LASTEXITCODE
    $text = [IO.File]::ReadAllText($log, [Text.Encoding]::UTF8)
    if (($code -eq 0) -ne $Success -or -not $text.Contains($Expected)) {
        throw "Gradle classpath verification failed (exit $code): $text"
    }
    Write-Host "Gradle classpath check $script:invocation passed: $Expected"
}

Push-Location $module
try {
    Invoke-Verification help $true 'BUILD SUCCESSFUL'
    Invoke-Verification compilePlugin $false 'Missing Maven-owned SDK classpath'
    [IO.Directory]::CreateDirectory($dependency) | Out-Null
    [IO.File]::WriteAllText($manifest, $dependency, $utf8)
    Invoke-Verification verifyMavenClasspath $true 'BUILD SUCCESSFUL'
    Invoke-Verification verifyMavenClasspath $true 'Configuration cache entry reused'
    [IO.Directory]::Delete($dependency)
    Invoke-Verification verifyMavenClasspath $false 'Maven-owned SDK classpath contains missing entries'
    [IO.File]::WriteAllText($manifest, '', $utf8)
    Invoke-Verification verifyMavenClasspath $false 'Missing Maven-owned SDK classpath'
    $marker = Join-Path $module 'build/generated/proguard/pixivdownload-proguard.properties'
    Invoke-Verification proguardMarker $true 'BUILD SUCCESSFUL'
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) { throw 'ProGuard marker is missing' }
    Remove-Item -LiteralPath $marker -Force
    Invoke-Verification proguardMarker $true 'Configuration cache entry reused'
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) { throw 'ProGuard marker was not regenerated' }
} finally {
    Pop-Location
    Remove-Item -LiteralPath $fixture -Recurse -Force
}
exit 0
