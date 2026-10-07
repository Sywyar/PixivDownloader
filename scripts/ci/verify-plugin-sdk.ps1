[CmdletBinding()]
param([string]$ProjectRoot)
$ErrorActionPreference = 'Stop'
if (-not $ProjectRoot) { $ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
. (Join-Path $ProjectRoot 'scripts/plugin-distribution-common.ps1')
. (Join-Path $ProjectRoot 'scripts/market-content-publication.ps1')
$tool = Resolve-MarketContentTool $ProjectRoot
$sdkVersion = Get-PixivDownloadSdkVersion $ProjectRoot
[xml]$pom = Get-Content -LiteralPath (Join-Path $ProjectRoot 'pom.xml') -Raw -Encoding UTF8
$dependencyVersion = [string]($pom.project.build.plugins.plugin | Where-Object { $_.artifactId -eq 'maven-dependency-plugin' }).version
if (-not $dependencyVersion) { throw 'Maven dependency plugin version is missing.' }
$modules = @('pixivdownload-sdk-info', 'pixivdownload-plugin-api', 'pixivdownload-core-api')
$candidate = @($modules | ForEach-Object { Find-ModuleJar $_ $ProjectRoot }) -join [IO.Path]::PathSeparator
$baselines = @{ candidate = $candidate }
foreach ($plugin in @(Get-OfficialDistributionPlugins -IncludeOptional)) {
    $artifact = Find-ModulePluginArtifact $plugin $ProjectRoot
    $descriptor = Read-PluginDescriptor $artifact
    $version = & node (Join-Path $PSScriptRoot 'plugin-version-policy.mjs') --sdk-baseline $descriptor['plugin.requires'] $sdkVersion
    if ($LASTEXITCODE -ne 0) { throw "Invalid SDK requirement for $($plugin.Id)." }
    $version = [string]$version
    if (-not $baselines.ContainsKey($version)) {
        $directory = Join-Path $ProjectRoot "target/plugin-sdk-baselines/$version"
        foreach ($module in $modules) {
            $mavenArgs = @('-B', '-ntp', '-N', '-f', (Join-Path $ProjectRoot 'pom.xml'),
                "org.apache.maven.plugins:maven-dependency-plugin:${dependencyVersion}:copy",
                "-Dartifact=io.github.sywyar.pixivdownloader:${module}:${version}:jar",
                "-DoutputDirectory=$directory", '-Dtransitive=false')
            & mvn @mavenArgs
            if ($LASTEXITCODE -ne 0) { throw "Cannot resolve declared SDK baseline $version ($module)." }
        }
        $baselines[$version] = @($modules | ForEach-Object { Join-Path $directory "$_-$version.jar" }) -join [IO.Path]::PathSeparator
    }
    & java -jar $tool verify-sdk-usage $artifact $baselines[$version] $candidate
    if ($LASTEXITCODE -ne 0) { throw "Plugin $($plugin.Id) does not satisfy its declared SDK requirement." }
    Write-Host "Verified $($plugin.Id): plugin.requires=$($descriptor['plugin.requires']); baseline=$version"
}
