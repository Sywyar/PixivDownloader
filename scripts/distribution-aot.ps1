function Expand-DistributionApplication {
    param([string]$Java, [string]$Jar, [string]$Destination)
    & $Java '-Dfile.encoding=UTF-8' '-Djarmode=tools' -jar $Jar extract --destination $Destination | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Application extraction failed (exit $LASTEXITCODE)." }
    $entry = Join-Path $Destination (Split-Path -Leaf $Jar)
    if (-not (Test-Path -LiteralPath $entry -PathType Leaf)) { throw 'Extracted application entry is missing.' }
    return $entry
}

function Test-DistributionAotCache {
    param([string]$Java, [string]$Jar, [string]$Cache, [string]$Training)
    $log = Join-Path $Training 'verify-aot.log'
    Push-Location -LiteralPath $Training
    try {
        & $Java '-Dfile.encoding=UTF-8' '-Djava.awt.headless=true' '-Dpixivdownload.instance-dir=instance' `
            '-Dspring.context.exit=onRefresh' '-XX:AOTMode=on' "-XX:AOTCache=$Cache" '-Xlog:aot=info' `
            -cp $Jar top.sywyar.pixivdownload.gui.GuiLauncher --no-gui --server.port=0 > $log 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Distribution AOT verification failed; see $log" }
        if (-not (Select-String -LiteralPath $log -Pattern 'Mapped static\s+region' -Quiet) -or
            -not (Select-String -LiteralPath $log -Pattern 'Root WebApplicationContext: initialization completed' -Quiet)) {
            throw "Distribution JVM did not map the AOT cache; see $log"
        }
        Write-Host 'Distribution AOT: relocated cache verified with packaged runtime.'
    } finally { Pop-Location }
}

function New-DistributionAotCache {
    param([string]$Java, [string]$Jar, [string]$Cache, [string]$Training, [string]$Plugins)
    if ($Plugins) {
        $candidate = $Cache + '.pending'
        & $Java '-Dfile.encoding=UTF-8' "-Dpixivdownload.plugins-dir=$Plugins" `
            "-Dpixivdownload.config-dir=$Training/source-config" -cp $Jar `
            top.sywyar.pixivdownload.startup.DistributionLauncher prepare-gui $candidate $Training
        if ($LASTEXITCODE -ne 0) { throw "GUI AOT preparation failed (exit $LASTEXITCODE)." }
        Move-Item -LiteralPath $candidate -Destination $Cache -Force
        Move-Item -LiteralPath ($candidate + '.identity') -Destination ($Cache + '.identity') -Force
    } else {
        & $Java '-Dfile.encoding=UTF-8' -cp $Jar top.sywyar.pixivdownload.startup.DistributionLauncher prepare $Cache $Training
    }
    if ($LASTEXITCODE -ne 0) { throw "Distribution AOT preparation failed (exit $LASTEXITCODE)." }
    if (-not (Test-Path -LiteralPath $Cache -PathType Leaf)) { throw 'Distribution AOT cache is missing.' }
}
