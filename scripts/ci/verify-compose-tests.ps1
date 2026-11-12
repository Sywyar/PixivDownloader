$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '../../pixivdownload-plugin-gui-compose')
try {
    # Require reports from this invocation, even when the Gradle process returns zero.
    Get-ChildItem build/test-results/test/TEST-*.xml -File -ErrorAction SilentlyContinue | Remove-Item
    & ./gradlew.bat --daemon mavenTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $reports = @(Get-ChildItem build/test-results/test/TEST-*.xml -File)
    if ($reports.Count -eq 0) { throw 'Compose test reports are missing.' }
    $count = 0
    foreach ($report in $reports) {
        [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw -Encoding UTF8
        $suite = $xml.testsuite
        if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
            throw "Compose tests failed or were skipped: $($report.Name)"
        }
        $count += [int]$suite.tests
    }
    if ($count -eq 0) { throw 'No Compose tests ran.' }
} finally {
    Pop-Location
}
