# Execute orchestration with local fixtures; publishing and application processes are replaced at their boundaries.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
Set-StrictMode -Version Latest
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path

function Read-Program {
    param([string]$Path)
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($Path, [ref]$null, [ref]$errors)
    if ($errors.Count) { throw ($errors | Out-String) }
    $functions = @($ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.FunctionDefinitionAst] })
    $statements = @($ast.EndBlock.Statements | Where-Object {
        $_ -notin $functions -and -not ($_ -is [Management.Automation.Language.PipelineAst] -and
            $_.PipelineElements[0] -is [Management.Automation.Language.CommandAst] -and
            $_.PipelineElements[0].InvocationOperator -eq 'Dot')
    })
    return [pscustomobject]@{
        Functions = $functions
        Main = [scriptblock]::Create($(if ($ast.ParamBlock) { $ast.ParamBlock.Extent.Text }) + "`n" + (($statements | ForEach-Object { $_.Extent.Text }) -join "`n"))
    }
}

function Assert-Equal {
    param($Actual, $Expected)
    if (($Actual -join '|') -cne ($Expected -join '|')) { throw "Expected [$Expected], got [$Actual]" }
}

function Assert-Rejected {
    param([scriptblock]$Action, [string]$Message)
    try { & $Action } catch {
        if ($_.Exception.Message -notlike "*$Message*") { throw }
        return
    }
    throw "Expected rejection: $Message"
}

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$fixture = Join-Path $tempBase ('pixiv-workflow-test-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($fixture) | Out-Null
try {
    & {
        if (-not (Test-Path -LiteralPath (Join-Path $repo 'node_modules/yaml/package.json') -PathType Leaf)) {
            Push-Location $repo
            try {
                & npm ci --ignore-scripts --no-audit --no-fund
                if ($LASTEXITCODE -ne 0) { throw 'Could not install locked orchestration test dependencies.' }
            } finally { Pop-Location }
        }
        $reader = Join-Path $fixture 'read-action.cjs'
        [IO.File]::WriteAllText($reader, @'
const fs = require('node:fs');
const path = require('node:path');
const yaml = require(path.join(process.argv[2], 'node_modules/yaml'));
const action = yaml.parse(fs.readFileSync(path.join(process.argv[2], '.github/actions/stage-release-plugins/action.yml'), 'utf8'));
process.stdout.write(action.runs.steps.find(step => step.run).run);
'@, [Text.UTF8Encoding]::new($false))
        $source = (& node $reader $repo) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw 'Could not read actual catalog staging action.' }
        $stageRoot = Join-Path $fixture 'catalog-stage'
        [IO.Directory]::CreateDirectory((Join-Path $stageRoot 'scripts')) | Out-Null
        [IO.File]::WriteAllText((Join-Path $stageRoot 'scripts/stage-official-plugin-inputs-from-catalog.ps1'), @'
param($OutputDir, $SignatureToolJar, $IncludeOptional, $RequireProguard, $ManifestUrl)
[IO.File]::WriteAllText((Join-Path (Get-Location) 'observed-url.txt'), $ManifestUrl)
if (-not $IncludeOptional -or -not $RequireProguard) { throw 'Missing signature/packaging validation.' }
'@, [Text.UTF8Encoding]::new($false))
        function Get-ChildItem { return [pscustomobject]@{ Name='signature.jar'; FullName='fixture-signature.jar'; LastWriteTime=[DateTime]::UtcNow } }
        $originalCommit = $env:PLUGIN_MANIFEST_COMMIT
        $originalName = $env:MANIFEST_NAME
        Push-Location $stageRoot
        try {
            $env:PLUGIN_MANIFEST_COMMIT = 'a' * 40
            foreach ($name in @('manifest.json', 'nightly-manifest.json')) {
                $env:MANIFEST_NAME = $name
                & ([scriptblock]::Create($source))
                Assert-Equal ([IO.File]::ReadAllText((Join-Path $stageRoot 'observed-url.txt'))) "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/$env:PLUGIN_MANIFEST_COMMIT/$name"
            }
            $env:PLUGIN_MANIFEST_COMMIT = 'master'
            Assert-Rejected { & ([scriptblock]::Create($source)) } 'Invalid plugin manifest commit'
            $env:PLUGIN_MANIFEST_COMMIT = 'a' * 40
            $env:MANIFEST_NAME = '../manifest.json'
            Assert-Rejected { & ([scriptblock]::Create($source)) } 'Invalid plugin manifest filename'
        } finally {
            Pop-Location
            $env:PLUGIN_MANIFEST_COMMIT = $originalCommit
            $env:MANIFEST_NAME = $originalName
        }
    }
    & {
        $commands = @(Get-Content (Join-Path $repo '.github/workflows/quality-gate.yml') |
            Where-Object { $_ -match '^\s+run: mvn\b.*-Pofficial-surveys' })
        Assert-Equal $commands.Count 1
        function mvn { $script:mavenArguments = @($args) }
        & ([scriptblock]::Create(($commands[0] -replace '^\s+run: ', '')))
        foreach ($property in @('maven.javadoc.skip', 'maven.source.skip')) {
            Assert-Equal (@($script:mavenArguments | Where-Object { $_ -ceq "-D$property=true" }).Count) 1
        }
    }
    & {
        $program = Read-Program (Join-Path $repo 'scripts/test-release-artifacts.ps1')
        foreach ($definition in $program.Functions) { . ([scriptblock]::Create($definition.Extent.Text)) }
        $script:observed = [Collections.Generic.List[string]]::new()
        $script:probes = [Collections.Generic.List[string]]::new()
        function Expand-Archive {
            param($LiteralPath, $DestinationPath)
            [IO.Directory]::CreateDirectory($DestinationPath) | Out-Null
            [IO.File]::WriteAllText((Join-Path $DestinationPath 'run.bat'), 'fixture')
            [IO.File]::WriteAllText((Join-Path $DestinationPath "PixivDownload-$Version.jar"), 'fixture')
        }
        function Initialize-ReleaseProbe {
            param($ApplicationJar, $Destination)
            if (-not (Test-Path -LiteralPath $ApplicationJar -PathType Leaf)) { throw 'Missing application API input.' }
            $script:probes.Add($ApplicationJar)
            return @{ Agent = 'fixture'; Fixture = 'fixture' }
        }
        function Test-ApplicationScenario { param($Label) $script:observed.Add($Label) }
        function Test-ReleaseAdverseLayouts {
            param($Label, $ScenarioGroup)
            $script:observed.Add("$Label-adverse-$ScenarioGroup")
        }
        function Assert-NoInstalledCopy { }
        function Wait-ArtifactProcessExit { }
        function Start-Process {
            param($FilePath, $ArgumentList, $WindowStyle, [switch]$PassThru)
            if ($FilePath -like '*unins000.exe') { return @{ExitCode=0} }
            $installDir = ($ArgumentList | Where-Object { $_ -like '/DIR=*' }).Substring(5).Trim('"')
            foreach ($name in @('PixivDownload.exe', 'runtime/bin/server/jvm.dll', 'unins000.exe', "app/PixivDownload-$Version.jar")) {
                $target = Join-Path $installDir $name
                [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
                [IO.File]::WriteAllText($target, 'fixture')
            }
            return @{ExitCode=0}
        }
        $inputFile = Join-Path $fixture 'input'
        [IO.File]::WriteAllText($inputFile, 'fixture')
        foreach ($distribution in @('java-standard', 'full-offline', 'windows-installer', 'all')) {
            $script:observed.Clear(); $script:probes.Clear()
            $parameters = @{ Version='1.2.3'; Distribution=$distribution; WorkRoot=$fixture }
            if ($distribution -in @('all', 'java-standard')) { $parameters.JavaZipPath = $inputFile }
            if ($distribution -in @('all', 'full-offline')) { $parameters.FullOfflineZipPath = $inputFile }
            if ($distribution -in @('all', 'windows-installer')) { $parameters.InstallerPath = $inputFile }
            & $program.Main @parameters
            $expected = @(foreach ($kind in @('java-standard', 'full-offline', 'windows-installer')) {
                if ($distribution -in @('all', $kind)) {
                    foreach ($mode in @('headless', 'gui-compose', 'gui-swing')) { "$kind-$mode" }
                    if ($kind -eq 'windows-installer') { "$kind-adverse-all" }
                }
            })
            Assert-Equal $script:observed $expected
            Assert-Equal $script:probes.Count 1
            if ($distribution -eq 'windows-installer' -and $script:probes[0] -notlike '*installed*app*PixivDownload-1.2.3.jar') {
                throw 'Installer shard did not initialize its probe from its own application.'
            }
        }
        foreach ($group in @('startup', 'failures', 'recovery')) {
            $script:observed.Clear(); $script:probes.Clear()
            & $program.Main -Version 1.2.3 -Distribution windows-installer -ScenarioGroup $group -InstallerPath $inputFile -WorkRoot $fixture
            $expected = if ($group -eq 'startup') {
                @('windows-installer-headless', 'windows-installer-gui-compose', 'windows-installer-gui-swing')
            } else { @("windows-installer-adverse-$group") }
            Assert-Equal $script:observed $expected
            Assert-Equal $script:probes.Count 1
        }
        Assert-Rejected { & $program.Main -Version 1.2.3 -Distribution java-standard -ScenarioGroup startup } 'requires the windows-installer'
        foreach ($distribution in @('java-standard', 'full-offline', 'windows-installer')) {
            Assert-Rejected { & $program.Main -Version 1.2.3 -Distribution $distribution -WorkRoot $fixture } 'not found'
        }
        Assert-Rejected { & $program.Main -Version 1.2.3 -JavaZipPath $inputFile -FullOfflineZipPath $inputFile } 'packaged JVM'
        Assert-Rejected { & $program.Main -Version 1.2.3 -Distribution unknown } 'ValidateSet'
    }

    & {
        $program = Read-Program (Join-Path $repo 'scripts/publish-plugin-releases.ps1')
        foreach ($definition in $program.Functions) { . ([scriptblock]::Create($definition.Extent.Text)) }
        . (Join-Path $repo 'scripts/market-content-publication.ps1')
        $toolDirectory = Join-Path $fixture 'pixivdownload-sdk-tools/target'
        [IO.Directory]::CreateDirectory($toolDirectory) | Out-Null
        $toolJar = Join-Path $toolDirectory 'pixivdownload-sdk-tools-fixture.jar'
        $script:builds = [Collections.Generic.List[object]]::new()
        $script:writes = [Collections.Generic.List[string]]::new()
        $script:buildExit = 0
        $script:existing = $false
        $script:missingAssetSuffixes = @()
        $plugins = @(@{Id='first'; Module='first'}, @{Id='second'; Module='second'})
        foreach ($plugin in $plugins) {
            $descriptor = Join-Path $fixture "$($plugin.Module)/src/main/resources/plugin.properties"
            [IO.Directory]::CreateDirectory((Split-Path -Parent $descriptor)) | Out-Null
            [IO.File]::WriteAllText($descriptor, 'plugin.version=1.0.0')
        }
        function Resolve-SignatureToolJar { return 'fixture' }
        function Get-PixivDownloadSdkVersion { return '1.0.0' }
        function Get-OfficialDistributionPlugins { param([switch]$IncludeOptional) return $plugins }
        function Get-MavenCommand { return 'Invoke-FakeMaven' }
        function Get-MarketSourceCommit { return ('c' * 40) }
        function Invoke-FakeMaven {
            $script:builds.Add(@($args))
            if ($script:buildExit -eq 0 -and ($args -join ',') -match 'pixivdownload-sdk-tools') {
                [IO.File]::WriteAllText($toolJar, 'tool fixture')
            }
            $global:LASTEXITCODE = $script:buildExit
        }
        function Find-ModulePluginArtifact { param($Plugin) return (Join-Path $fixture "$($Plugin.Module)/src/main/resources/plugin.properties") }
        function Assert-OfficialPluginArtifact { return @{'plugin.version'='1.0.0'} }
        function Assert-ProguardProcessedArtifact { }
        function Get-OfficialPluginArtifactName { param($Plugin, $Version) return "$($Plugin.Id)-$Version.jar" }
        function Get-NightlyPluginVersion { param($SourceVersion, $Suffix) return "$SourceVersion-$Suffix" }
        $script:requiredSdkValues = [Collections.Generic.List[string]]::new()
        function Set-StagedPluginVersion {
            param($StagedArtifact, $Plugin, $Version, $RequiredSdk)
            $script:requiredSdkValues.Add($RequiredSdk)
        }
        function Write-StagedCompanionFiles {
            param($StagedArtifact)
            return @{Sha='fixture'; ShaFile="$StagedArtifact.sha256"; SigFile="$StagedArtifact.sig"}
        }
        function New-OfficialMarketContent {
            $null = Resolve-MarketContentTool $fixture
            if ($script:existing -and $script:contentMode -and $args[6] -cne ('d' * 40)) {
                throw 'A release retry must retain the original frozen source commit.'
            }
            if ($script:contentMode) { return @((Join-Path $fixture 'market-content.json'), (Join-Path $fixture 'content-doc.md')) }
            return @()
        }
        function Get-OfficialMarketContentHash { return ($(if ($script:contentMode -eq 'conflict') { 'b' } else { 'a' }) * 64) }
        function Read-OfficialMarketContent {
            $null = Resolve-MarketContentTool $fixture
            if ($script:contentMode -eq 'readback-failure') { throw 'Public market content readback failed.' }
            $script:readbacks++
            return $null
        }
        function Download-ReleaseAsset { return (Join-Path $fixture 'input') }
        function gh {
            $global:LASTEXITCODE = 0
            if ($args[1] -eq 'view') {
                if (-not $script:existing) { $global:LASTEXITCODE = 1; return 'release not found' }
                $id = $args[2] -replace '-v.*$', ''
                $assets = @(foreach ($suffix in @('', '.sha256', '.sig')) {
                    if ($script:missingAssetSuffixes -notcontains $suffix) { @{name="$id-1.0.0.jar$suffix"} }
                })
                if ($script:contentMode -eq 'complete') { $assets += @(@{name='market-content.json'}, @{name='content-doc.md'}) }
                return (@{body=$(if ($script:contentMode) { 'market-content-sha256=' + ('a' * 64) + ' market-source-commit=' + ('d' * 40) } else { '' });
                    assets=$assets} | ConvertTo-Json -Compress)
            }
            $script:writes.Add((@($args | ForEach-Object { $_ }) -join ' '))
        }
        foreach ($mode in @('nightly', 'force', 'stable', 'skip', 'failure', 'prebuilt')) {
            if (Test-Path -LiteralPath $toolJar) { Remove-Item -LiteralPath $toolJar }
            if ($mode -eq 'prebuilt') { [IO.File]::WriteAllText($toolJar, 'prebuilt fixture') }
            $script:contentMode = ''; $script:readbacks = 0
            $script:builds.Clear(); $script:writes.Clear()
            $script:requiredSdkValues.Clear()
            $script:existing = $mode -eq 'skip'
            $script:buildExit = if ($mode -eq 'failure') { 1 } else { 0 }
            $parameters = @{ProjectRoot=$fixture; OfficialKeyId='fixture'; PrivateKeyFile=(Join-Path $fixture 'input')}
            if ($mode -in @('nightly', 'failure', 'prebuilt')) { $parameters.NightlyBuildVersion = '1.2.3-nightly.20260909.1.1' }
            if ($mode -eq 'prebuilt') { $parameters.UsePrebuiltArtifacts = $true }
            if ($mode -eq 'force') { $parameters.Force = $true }
            if ($mode -eq 'failure') {
                Assert-Rejected { & $program.Main @parameters } 'Maven plugin build failed'
                Assert-Equal $script:writes.Count 0
            } else {
                & $program.Main @parameters
                Assert-Equal $script:builds.Count $(if ($mode -eq 'prebuilt') { 0 } elseif ($mode -eq 'stable') { 3 } else { 1 })
                Assert-Equal $script:writes.Count $(if ($mode -eq 'skip') { 0 } elseif ($mode -in @('nightly', 'prebuilt')) { 2 } else { 4 })
                if ($mode -in @('nightly', 'prebuilt')) {
                    Assert-Equal $script:requiredSdkValues @('1.0.0-nightly.20260909.1.1', '1.0.0-nightly.20260909.1.1')
                }
                if ($mode -in @('nightly', 'force')) {
                    Assert-Equal $script:builds[0] @('-Pofficial-surveys', '-pl', 'first,second,pixivdownload-sdk-tools', '-am', 'verify', '-DskipTests')
                }
            }
        }
        foreach ($mode in @('resume', 'complete', 'conflict', 'readback-failure')) {
            $script:contentMode = $mode; $script:existing = $true; $script:readbacks = 0
            $script:builds.Clear(); $script:writes.Clear()
            $parameters = @{ProjectRoot=$fixture; OfficialKeyId='fixture'; PrivateKeyFile=(Join-Path $fixture 'input')}
            if ($mode -in @('resume', 'complete')) { Remove-Item -LiteralPath $toolJar }
            else { $parameters.UsePrebuiltArtifacts = $true }
            if ($mode -eq 'conflict') {
                Assert-Rejected { & $program.Main @parameters } 'Market content changed'
                Assert-Equal $script:writes.Count 0
            } elseif ($mode -eq 'readback-failure') {
                Assert-Rejected { & $program.Main @parameters } 'readback failed'
                Assert-Equal $script:writes.Count 1
            } else {
                & $program.Main @parameters
                Assert-Equal $script:writes.Count $(if ($mode -eq 'complete') { 0 } else { 2 })
                Assert-Equal $script:readbacks 2
                foreach ($write in $script:writes) {
                    if ($write -notmatch 'release upload.*market-content.json.*content-doc.md' -or $write -match 'delete-asset|--clobber') {
                        throw 'Content recovery must upload only missing immutable assets.'
                    }
                }
            }
            Assert-Equal $script:builds.Count $(if ($mode -in @('resume', 'complete')) { 1 } else { 0 })
            if ($script:builds.Count) {
                Assert-Equal $script:builds[0] @('-Pofficial-surveys', '-pl', 'pixivdownload-sdk-tools', '-am', 'verify', '-DskipTests')
            }
        }
        foreach ($missing in @(@(''), @('.sha256'), @('.sig'), @('.sha256', '.sig'))) {
            $script:missingAssetSuffixes = $missing
            $script:contentMode = ''; $script:existing = $true; $script:readbacks = 0
            $script:builds.Clear(); $script:writes.Clear()
            $parameters.UsePrebuiltArtifacts = $true
            if ($missing -contains '') {
                Assert-Rejected { & $program.Main @parameters } 'Bump plugin.version instead of publishing new bytes under an existing tag'
                Assert-Equal $script:writes.Count 0
                Assert-Equal $script:readbacks 0
            } else {
                & $program.Main @parameters
                $expectedPaths = @($missing | ForEach-Object { (Join-Path $fixture 'input') + $_ }) -join ' '
                Assert-Equal $script:writes @($plugins | ForEach-Object {
                    "release upload $($_.Id)-v1.0.0 $expectedPaths --repo Sywyar/PixivDownloader-plugins"
                })
                Assert-Equal $script:readbacks 2
            }
            Assert-Equal $script:builds.Count 0
        }
        $script:missingAssetSuffixes = @()
        Remove-Item -LiteralPath $toolJar
        $script:builds.Clear(); $script:writes.Clear()
        $parameters.UsePrebuiltArtifacts = $true
        Assert-Rejected { & $program.Main @parameters } 'Expected one verified SDK tools JAR'
        [IO.File]::WriteAllText($toolJar, '')
        Assert-Rejected { & $program.Main @parameters } 'Expected one verified SDK tools JAR'
        Assert-Equal $script:builds.Count 0
        Assert-Equal $script:writes.Count 0
    }
    & {
        . (Join-Path $repo 'scripts/plugin-distribution-common.ps1')
        . (Join-Path $repo 'scripts/market-content-publication.ps1')
        $program = Read-Program (Join-Path $repo 'scripts/generate-market-manifest.ps1')
        foreach ($definition in $program.Functions) { . ([scriptblock]::Create($definition.Extent.Text)) }
        $fixtureUtf8 = New-Object System.Text.UTF8Encoding($false)
        $marketPlugin = @(Get-OfficialRequiredPlugins)[0]
        $ProjectRoot = $repo
        $sourceDescriptor = Read-PropertiesFile (Join-Path $repo "$($marketPlugin.Module)/src/main/resources/plugin.properties")
        $currentSdk = Get-PixivDownloadSdkVersion -ProjectRoot $repo
        $marketRoot = Join-Path $fixture 'market'
        $resources = Join-Path $marketRoot "$($marketPlugin.Module)/src/main/resources"
        [IO.Directory]::CreateDirectory((Join-Path $resources 'i18n/web')) | Out-Null
        $descriptorPath = Join-Path $resources 'plugin.properties'
        $descriptorText = [IO.File]::ReadAllText((Join-Path $repo "$($marketPlugin.Module)/src/main/resources/plugin.properties"), $fixtureUtf8)
        $descriptorText = [char]0xFEFF + $descriptorText + "`n# ignored`ninvalid`n=ignored`npixiv.display-name-key=discarded`n" +
            [char]0xFEFF + "pixiv.display-name-key = fixture.label `npixiv.description-key=fixture.summary`n"
        $label = "Name $([char]0x4E2D) = label"
        foreach ($suffix in @('', '_en')) {
            $bundle = "i18n/web/$($sourceDescriptor['pixiv.display-namespace'])$suffix.properties"
            Copy-Item -LiteralPath (Join-Path $repo "$($marketPlugin.Module)/src/main/resources/$bundle") -Destination (Join-Path $resources $bundle)
            [IO.File]::AppendAllText((Join-Path $resources $bundle), "`n# ignored`ninvalid`n=ignored`nfixture.label=discarded`n" +
                [char]0xFEFF + "fixture.label = $label$suffix `nfixture.summary = summary$suffix `n", $fixtureUtf8)
        }
        $metadata = 'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties'
        [IO.Directory]::CreateDirectory((Split-Path -Parent (Join-Path $marketRoot $metadata))) | Out-Null
        Copy-Item -LiteralPath (Join-Path $repo $metadata) -Destination (Join-Path $marketRoot $metadata)
        $output = Join-Path $marketRoot 'manifest.json'
        $publishedFiles = @{}
        function Get-OfficialDistributionPlugins { param([switch]$IncludeOptional) return @($marketPlugin) }
        function Get-OfficialDefaultInstalledPlugins { return @($marketPlugin) }
        function Resolve-SignatureToolJar { return 'fixture' }
        function Invoke-PluginSignatureTool {
            param($Tool, $Arguments)
            if ($Arguments[0] -eq 'verify-manifest') { return }
            if ($Arguments[0] -eq 'artifact') {
                Assert-Equal $Arguments[[Array]::IndexOf($Arguments, '--plugin-id') + 1] $marketPlugin.Id
                Assert-Equal $Arguments[[Array]::IndexOf($Arguments, '--version') + 1] $fixtureVersion
                Assert-Equal (Test-Path -LiteralPath $Arguments[[Array]::IndexOf($Arguments, '--artifact') + 1]) $true
            } elseif ($Arguments[0] -eq 'manifest') {
                $manifestPath = $Arguments[[Array]::IndexOf($Arguments, '--manifest') + 1]
                $signingManifest = [IO.File]::ReadAllText($manifestPath, $fixtureUtf8) | ConvertFrom-Json
                Assert-Equal @($signingManifest.entries).Count 1
                Assert-Equal $Arguments[[Array]::IndexOf($Arguments, '--repository-id') + 1] 'official'
            } else { throw 'Unexpected signing command in market fixture.' }
            $destination = $Arguments[[Array]::IndexOf($Arguments, '--out') + 1]
            [IO.File]::WriteAllText($destination, '{"formatVersion":1,"algorithm":"Ed25519","keyId":"fixture","value":"fixture"}', $fixtureUtf8)
        }
        function gh {
            $global:LASTEXITCODE = 0
            if ($args[0] -eq 'api') {
                $file = $args[1] -replace '^.*/contents/', ''
                if ($publishedFiles.ContainsKey($file)) { return [Convert]::ToBase64String($publishedFiles[$file]) }
                $global:LASTEXITCODE = 1
                return 'HTTP 404'
            }
            if ($args[0] -eq 'release' -and $args[1] -eq 'view') {
                return (@{assets=@(@{name=$fixtureAssetName; downloadCount=0}); publishedAt='2026-01-01T00:00:00Z'} | ConvertTo-Json -Depth 5 -Compress)
            }
            if ($args[0] -eq 'release' -and $args[1] -eq 'download') {
                $directory = $args[[Array]::IndexOf($args, '--dir') + 1]
                $name = $args[[Array]::IndexOf($args, '--pattern') + 1]
                $archive = [IO.Compression.ZipFile]::Open((Join-Path $directory $name), [IO.Compression.ZipArchiveMode]::Create)
                try {
                    $stream = New-Object IO.StreamWriter($archive.CreateEntry('plugin.properties').Open(), $fixtureUtf8)
                    try { $stream.Write("plugin.id=$($marketPlugin.Id)`nplugin.version=$fixtureVersion`nplugin.requires=$($case.Expected)`nplugin.dependencies=fixture-dependency@2.0`n") }
                    finally { $stream.Dispose() }
                } finally { $archive.Dispose() }
                return
            }
            throw 'Unexpected GitHub operation in market fixture.'
        }
        $cases = @(
            @{Required=$sourceDescriptor['plugin.requires']; Expected=$sourceDescriptor['plugin.requires']},
            @{Required=$currentSdk; Expected=$currentSdk},
            @{Required="=$currentSdk"; Expected="=$currentSdk"},
            @{Required='7.2.3-rc.4'; Expected='7.2.3-rc.4'},
            @{Required='=7.2.3-rc.4'; Expected='=7.2.3-rc.4'},
            @{Required=$currentSdk; Expected="$currentSdk-nightly.20260909.1.1"; Nightly='8.2.3-nightly.20260909.1.1'}
        )
        foreach ($case in $cases) {
            [IO.File]::WriteAllText($descriptorPath, ($descriptorText -replace '(?m)^plugin\.requires=.*$', "plugin.requires=$($case.Required)"), $fixtureUtf8)
            $parameters = @{
                ProjectRoot=$marketRoot; Repo='fixture/distribution'; OfficialKeyId='fixture';
                PrivateKeyFile=(Join-Path $fixture 'input'); CurationFile=(Join-Path $repo 'scripts/market-curation.json'); OutputFile=$output
            }
            $fixtureVersion = $sourceDescriptor['plugin.version']
            if ($case.ContainsKey('Nightly')) {
                $parameters.NightlyBuildVersion = $case.Nightly
                $fixtureVersion += '-nightly.20260909.1.1'
            }
            $fixtureAssetName = Get-OfficialPluginArtifactName $marketPlugin $fixtureVersion
            & $program.Main @parameters
            $manifestBytes = [IO.File]::ReadAllBytes($output)
            Assert-Equal (($manifestBytes[0..2] -join ',') -eq '239,187,191') $false
            $manifestText = $fixtureUtf8.GetString($manifestBytes)
            Assert-Equal $manifestText.Contains("`r") $false
            $manifest = $manifestText | ConvertFrom-Json
            Assert-Equal @($manifest.entries).Count 1
            Assert-Equal $manifest.schemaVersion '1'
            Assert-Equal $manifest.entries[0].market.displayName.zh $label
            Assert-Equal $manifest.entries[0].market.displayName.en "${label}_en"
            Assert-Equal $manifest.entries[0].market.summary.zh 'summary'
            Assert-Equal $manifest.entries[0].market.summary.en 'summary_en'
            $package = $manifest.entries[0].packages[0]
            foreach ($signature in @($package.signature, ([IO.File]::ReadAllText("$output.sig", $fixtureUtf8) | ConvertFrom-Json))) {
                Assert-Equal $signature.formatVersion 1
                Assert-Equal $signature.algorithm 'Ed25519'
                Assert-Equal $signature.keyId 'fixture'
                Assert-Equal $signature.value 'fixture'
            }
            Assert-Equal $package.signatureUrl "$($package.packageUrl).sig"
            Assert-Equal $package.requiredSdk $case.Expected
            Assert-Equal $package.requiredCoreApi $case.Expected
            Assert-Equal $package.channel $(if ($case.ContainsKey('Nightly')) { 'nightly' } else { 'stable' })
            Assert-Equal @($package.dependencies) @('fixture-dependency@2.0')
            if ($case.ContainsKey('Nightly')) {
                & node (Join-Path $repo 'scripts/ci/assert-nightly-publication.mjs') plugins '8.2.3-nightly.20260910.2.1' $output
                if ($LASTEXITCODE -ne 0) { throw 'Generated Nightly manifest was rejected by publication guard.' }
            }
        }
        foreach ($missing in @(
            @{Path=$descriptorPath; Message='Missing source descriptor'},
            @{Path=(Join-Path $resources $bundle); Message='Missing i18n bundle'}
        )) {
            $originalBytes = [IO.File]::ReadAllBytes($missing.Path)
            $originalOutput = [IO.File]::ReadAllText($output, $fixtureUtf8)
            try {
                Remove-Item -LiteralPath $missing.Path
                Assert-Rejected { & $program.Main @parameters } $missing.Message
                Assert-Equal ([IO.File]::ReadAllText($output, $fixtureUtf8)) $originalOutput
            } finally { [IO.File]::WriteAllBytes($missing.Path, $originalBytes) }
        }
        $metadataPath = Join-Path $marketRoot $metadata
        $metadataBytes = [IO.File]::ReadAllBytes($metadataPath)
        try {
            Remove-Item -LiteralPath $metadataPath
            Assert-Rejected { & $program.Main @parameters } 'SDK metadata not found'
            [IO.File]::WriteAllText($metadataPath, 'version=invalid', $fixtureUtf8)
            Assert-Rejected { & $program.Main @parameters } 'SDK metadata contains an invalid semantic version'
        } finally { [IO.File]::WriteAllBytes($metadataPath, $metadataBytes) }
        $case = @{ Expected=$currentSdk }
        $parameters.Remove('NightlyBuildVersion')
        foreach ($fixtureVersion in @('5.0.0', '6.0.0', '7.0.0', '7.0.0')) {
            [IO.File]::WriteAllText($descriptorPath, ($descriptorText -replace '(?m)^plugin\.version=.*$', "plugin.version=$fixtureVersion"), $fixtureUtf8)
            $fixtureAssetName = Get-OfficialPluginArtifactName $marketPlugin $fixtureVersion
            & $program.Main @parameters
            $manifest = [IO.File]::ReadAllText($output, $fixtureUtf8) | ConvertFrom-Json
            Assert-Equal @($manifest.entries[0].packages).Count 1
            $publishedFiles['manifest.json'] = [IO.File]::ReadAllBytes($output)
            $publishedFiles['manifest.json.sig'] = [IO.File]::ReadAllBytes("$output.sig")
            if ($fixtureVersion -ne '5.0.0') {
                $reference = $manifest.entries[0].history
                $bytes = [IO.File]::ReadAllBytes((Join-Path $marketRoot $reference.path))
                Assert-Equal $bytes.Length $reference.sizeBytes
                $history = $fixtureUtf8.GetString($bytes) | ConvertFrom-Json
                Assert-Equal @($history.packages.version) $(if ($fixtureVersion -eq '6.0.0') { @('5.0.0') } else { @('6.0.0','5.0.0') })
                $publishedFiles[$reference.path] = $bytes
            }
        }
        $publishedFiles[$reference.path] = $fixtureUtf8.GetBytes('tampered')
        Assert-Rejected { & $program.Main @parameters } 'History digest mismatch'
        $publishedFiles = @{}
        $legacyCuration = Get-Content -LiteralPath $parameters.CurationFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $legacyCuration._legacySdkVersions = @('7.0.0', '7.1.0')
        $parameters.CurationFile = Join-Path $marketRoot 'curation.json'
        [IO.File]::WriteAllText($parameters.CurationFile, ($legacyCuration | ConvertTo-Json -Depth 20), $fixtureUtf8)
        $olderHistory = $null
        foreach ($item in @(
            @{Version='5.0.0'; Sdk='7.0'; Main=@('5.0.0')},
            @{Version='6.0.0'; Sdk='7.1'; Main=@('6.0.0','5.0.0')},
            @{Version='7.0.0'; Sdk='8.0'; Main=@('7.0.0','6.0.0','5.0.0')},
            @{Version='8.0.0'; Sdk='8.0'; Main=@('8.0.0','6.0.0','5.0.0')},
            @{Version='9.0.0-beta.1'; Sdk='8.0'; Main=@('9.0.0-beta.1','8.0.0','6.0.0','5.0.0')},
            @{Version='10.0.0'; Sdk='8.0'; Main=@('10.0.0','6.0.0','5.0.0')},
            @{Version='10.0.0'; Sdk='8.0'; Main=@('10.0.0','6.0.0','5.0.0')}
        )) {
            $fixtureVersion = $item.Version
            $case = @{Expected=$item.Sdk}
            [IO.File]::WriteAllText($descriptorPath, ($descriptorText -replace '(?m)^plugin\.version=.*$', "plugin.version=$fixtureVersion"), $fixtureUtf8)
            $fixtureAssetName = Get-OfficialPluginArtifactName $marketPlugin $fixtureVersion
            & $program.Main @parameters
            $manifest = [IO.File]::ReadAllText($output, $fixtureUtf8) | ConvertFrom-Json
            Assert-Equal @($manifest.entries[0].packages.version) $item.Main
            $publishedFiles['manifest.json'] = [IO.File]::ReadAllBytes($output)
            $publishedFiles['manifest.json.sig'] = [IO.File]::ReadAllBytes("$output.sig")
            if ($manifest.entries[0].PSObject.Properties['history']) {
                $reference = $manifest.entries[0].history
                Assert-Equal $reference.path "history/$($marketPlugin.Id)-$($reference.sha256).json"
                $bytes = [IO.File]::ReadAllBytes((Join-Path $marketRoot $reference.path))
                $publishedFiles[$reference.path] = $bytes
                if ($fixtureVersion -eq '8.0.0') { $olderHistory = $reference }
                if ($fixtureVersion -eq '9.0.0-beta.1') {
                    Assert-Equal $reference.path $olderHistory.path
                    Assert-Equal $manifest.entries[0].packages[0].channel 'beta'
                }
            }
            if ($olderHistory) {
                Assert-Equal (Get-FileHash (Join-Path $marketRoot $olderHistory.path)).Hash.ToLowerInvariant() $olderHistory.sha256
            }
        }
    }
    & {
        . (Join-Path $repo 'scripts/plugin-distribution-common.ps1')
        $program = Read-Program (Join-Path $repo 'scripts/stage-official-plugin-inputs-from-catalog.ps1')
        foreach ($definition in $program.Functions) { . ([scriptblock]::Create($definition.Extent.Text)) }
        $SdkVersion = '7.2.3'
        foreach ($required in @('7.1', '>=7.2', '=7.2.3')) { Assert-Equal (Test-Compatible $required) $true }
        foreach ($required in @('8.0', '=7.2.4', '7.2.3-rc.1', 'bad')) { Assert-Equal (Test-Compatible $required) $false }
        $historyPath = Join-Path $fixture 'history-source.json'
        [IO.File]::WriteAllText($historyPath, '{"pluginId":"fixture","packages":[{"version":"2.0.0","requiredSdk":"7.1"}]}')
        $entry = [pscustomobject]@{ pluginId='fixture'; packages=@([pscustomobject]@{version='3.0.0';requiredSdk='9.0'});
            history=[pscustomobject]@{path="history/fixture-$((Get-FileHash $historyPath).Hash.ToLowerInvariant()).json";sizeBytes=(Get-Item $historyPath).Length;
                sha256=(Get-FileHash $historyPath).Hash.ToLowerInvariant();versions=1} }
        function Invoke-DownloadFile {
            param($Url, $OutFile)
            Assert-Equal $Url "https://example.test/frozen/$($entry.history.path)"
            Copy-Item -LiteralPath $historyPath -Destination $OutFile
        }
        Assert-Equal ($null -eq (Select-CatalogPackage $entry)) $true
        $history = Read-CatalogHistory $entry 'https://example.test/frozen/manifest.json' $fixture
        Assert-Equal (Select-CatalogPackage $history).version '2.0.0'
        $entry.history.sha256 = 'a' * 64
        $entry.history.path = "history/fixture-$($entry.history.sha256).json"
        Assert-Rejected { Read-CatalogHistory $entry 'https://example.test/frozen/manifest.json' $fixture } 'digest mismatch'
        $entry.history.path = '../manifest.json'
        Assert-Rejected { Read-CatalogHistory $entry 'https://example.test/frozen/manifest.json' $fixture } 'Invalid signed history'
    }
    & {
        . (Join-Path $repo 'scripts/plugin-distribution-common.ps1')
        $fixtureEncoding = [Text.UTF8Encoding]::new($false)
        $sdk = Get-PixivDownloadSdkVersion -ProjectRoot $repo
        $manifestJson = @{entries=@(Get-OfficialDistributionPlugins | ForEach-Object {
            @{pluginId=$_.Id; packages=@(@{version='1.2.3'; requiredSdk=$sdk;
                packageUrl='https://example.test/asset.jar'; sha256=('a' * 64); expectedSizeBytes=1;
                signature=@{formatVersion=1; algorithm='Ed25519'; keyId='fixture'; value='fixture'}})}
        })} | ConvertTo-Json -Depth 8
        function java {
            Assert-Equal $args[0] '-cp'
            Assert-Equal (Test-Path -LiteralPath $args[1] -PathType Leaf) $true
            Assert-Equal $args[3] 'verify-manifest'
            $global:LASTEXITCODE = 0
        }
        function Invoke-WebRequest {
            param($Uri, $OutFile, [switch]$UseBasicParsing, $TimeoutSec)
            $requests.Add($Uri)
            switch ($Uri) {
                'https://example.test/manifest.json' { [IO.File]::WriteAllText($OutFile, $manifestJson, $fixtureEncoding) }
                'https://example.test/manifest.json.sig' { [IO.File]::WriteAllText($OutFile, 'fixture', $fixtureEncoding) }
                'https://example.test/asset.jar' { throw 'Fixture asset download failed.' }
                default { throw "Unexpected request: $Uri" }
            }
        }
        foreach ($case in @('source', 'tool-inside', 'tool-equal', 'build-root', 'repo-root', 'ancestor', 'separate-input')) {
            $root = Join-Path $fixture "catalog-input-$case"
            $project = Join-Path $root 'project'
            $scripts = Join-Path $project 'scripts'
            [IO.Directory]::CreateDirectory($scripts) | Out-Null
            foreach ($name in @('stage-official-plugin-inputs-from-catalog.ps1', 'plugin-distribution-common.ps1')) {
                Copy-Item -LiteralPath (Join-Path $repo "scripts/$name") -Destination $scripts
            }
            $metadata = 'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties'
            [IO.Directory]::CreateDirectory((Split-Path -Parent (Join-Path $project $metadata))) | Out-Null
            Copy-Item -LiteralPath (Join-Path $repo $metadata) -Destination (Join-Path $project $metadata)
            $output = Join-Path $project 'build/plugin-inputs'
            $tool = Join-Path $project 'build/plugin-inputs-extra/signature.jar'
            $expectedError = 'protected'
            switch ($case) {
                'source' { $output = Join-Path $project 'src'; $expectedError = 'source directory' }
                'tool-inside' { $tool = Join-Path $output 'signature.jar' }
                'tool-equal' { $output = $tool }
                'build-root' { $output = Join-Path $project 'build'; $expectedError = 'build root' }
                'repo-root' { $output = $project; $expectedError = 'repository root' }
                'ancestor' { $output = $root; $expectedError = 'ancestor' }
                'separate-input' { $expectedError = 'Fixture asset download failed' }
            }
            [IO.Directory]::CreateDirectory((Split-Path -Parent $tool)) | Out-Null
            [IO.File]::WriteAllText($tool, 'signature fixture', $fixtureEncoding)
            $marker = $tool
            if ($case -ne 'tool-equal') {
                [IO.Directory]::CreateDirectory($output) | Out-Null
                $marker = Join-Path $output 'keep.txt'
                [IO.File]::WriteAllText($marker, 'existing output', $fixtureEncoding)
            }
            $requests = [Collections.Generic.List[string]]::new()
            $failure = $null
            try {
                & (Join-Path $scripts 'stage-official-plugin-inputs-from-catalog.ps1') -OutputDir $output -SignatureToolJar $tool -ManifestUrl 'https://example.test/manifest.json'
            } catch { $failure = $_ }
            Assert-Equal ([IO.File]::ReadAllText($tool, $fixtureEncoding)) 'signature fixture'
            Assert-Equal (Test-Path -LiteralPath $marker) ($case -ne 'separate-input')
            Assert-Equal $requests.Count $(if ($case -eq 'separate-input') { 3 } else { 0 })
            if ($null -eq $failure -or $failure.Exception.Message -notlike "*$expectedError*") { throw "Unexpected catalog result: $failure" }
        }
    }
    & {
        $program = Read-Program (Join-Path $repo 'scripts/publish-plugin-releases.ps1')
        $rewrite = @($program.Functions | Where-Object { $_.Name -eq 'Set-StagedPluginVersion' })
        Assert-Equal $rewrite.Count 1
        . ([scriptblock]::Create($rewrite[0].Extent.Text))
        $Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        $source = Join-Path $fixture 'nightly-descriptor-source'
        $check = Join-Path $fixture 'nightly-descriptor-check'
        [IO.Directory]::CreateDirectory($source) | Out-Null
        [IO.Directory]::CreateDirectory($check) | Out-Null
        [IO.File]::WriteAllText((Join-Path $source 'plugin.properties'), "plugin.id=first`nplugin.version=1.0.0`nplugin.requires==7.2.3-rc.4`n")
        $artifact = Join-Path $fixture 'nightly-descriptor.jar'
        $jar = (Get-Command jar).Source
        & $jar --create --file $artifact -C $source plugin.properties
        if ($LASTEXITCODE -ne 0) { throw 'Could not create Nightly descriptor fixture.' }
        function Assert-OfficialPluginArtifact {
            return @{'plugin.version'='1.0.0-nightly.20260909.1.1'; 'plugin.requires'='1.0.0-nightly.20260909.1.1'}
        }
        Set-StagedPluginVersion -StagedArtifact $artifact -Plugin @{Id='first'} -Version '1.0.0-nightly.20260909.1.1' -RequiredSdk '1.0.0-nightly.20260909.1.1'
        Push-Location $check
        try {
            & $jar --extract --file $artifact plugin.properties
            if ($LASTEXITCODE -ne 0) { throw 'Could not inspect rewritten Nightly descriptor.' }
        } finally { Pop-Location }
        Assert-Equal @(Get-Content -LiteralPath (Join-Path $check 'plugin.properties') | Where-Object { $_ -match '^plugin\.(version|requires)=' }) @('plugin.version=1.0.0-nightly.20260909.1.1', 'plugin.requires=1.0.0-nightly.20260909.1.1')
    }
    & {
        $program = Read-Program (Join-Path $repo 'scripts/ci/release-build-candidates.ps1')
        foreach ($definition in $program.Functions) { . ([scriptblock]::Create($definition.Extent.Text)) }
        $sha = 'a' * 40
        function git { $global:LASTEXITCODE = 0; return $sha }
        $source = Join-Path $fixture 'producer'
        $destination = Join-Path $fixture 'consumer'
        $candidates = Join-Path $fixture 'candidates'
        $module = 'pixivdownload-plugin-example'
        foreach ($root in @($source, $destination)) {
            [IO.Directory]::CreateDirectory((Join-Path $root "$module/src/main/resources")) | Out-Null
            [IO.File]::WriteAllText((Join-Path $root "$module/src/main/resources/plugin.properties"), 'plugin.id=example')
            [IO.File]::WriteAllText((Join-Path $root 'pom.xml'), "<project><modules><module>$module</module></modules></project>")
        }
        [IO.Directory]::CreateDirectory((Join-Path $source "$module/target/classes")) | Out-Null
        [IO.File]::WriteAllText((Join-Path $source "$module/target/classes/Example.class"), 'classes')
        [IO.File]::WriteAllText((Join-Path $source "$module/target/$module-1.0.jar"), 'verified jar')
        $parameters = @{Directory=$candidates; SourceSha=$sha; RunId='12'; Attempt='2'}
        & $program.Main @parameters -Mode Export -ProjectRoot $source
        $manifestPath = Join-Path $candidates 'manifest.json'
        $original = [IO.File]::ReadAllText($manifestPath)
        foreach ($field in @('sourceSha', 'runId', 'attempt', 'build')) {
            $manifest = $original | ConvertFrom-Json
            $manifest.$field = 'wrong'
            [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5))
            Assert-Rejected { & $program.Main @parameters -Mode Import -ProjectRoot $destination } 'provenance mismatch'
        }
        [IO.File]::WriteAllText($manifestPath, $original)
        $manifest = $original | ConvertFrom-Json
        $manifest.attempt = '3'
        [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5))
        Assert-Rejected { & $program.Main @parameters -Mode Import -ProjectRoot $destination } 'provenance mismatch'
        $manifest.attempt = '1'
        [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5))
        $payload = Join-Path $candidates "$module/target/$module-1.0.jar"
        [IO.File]::WriteAllText($payload, 'tampered jar')
        Assert-Rejected { & $program.Main @parameters -Mode Import -ProjectRoot $destination } 'SHA-256 mismatch'
        if (Test-Path -LiteralPath (Join-Path $destination "$module/target")) { throw 'Invalid candidates wrote target files.' }
        [IO.File]::WriteAllText($payload, 'verified jar')
        & $program.Main @parameters -Mode Import -ProjectRoot $destination
        Assert-Equal ([IO.File]::ReadAllText((Join-Path $destination "$module/target/$module-1.0.jar"))) 'verified jar'
        Assert-Rejected { & $program.Main @parameters -Mode Import -ProjectRoot $destination } 'must be absent'
    }
    & {
        $fixtureEncoding = [Text.UTF8Encoding]::new($false)
        foreach ($case in @('same', 'ancestor', 'child', 'source-root', 'outside', 'build-root', 'separate', 'patch-failure')) {
            $root = Join-Path $fixture "inno-input-$case"
            $scripts = Join-Path $root 'scripts'
            [IO.Directory]::CreateDirectory($scripts) | Out-Null
            foreach ($name in @('prepare-inno-admin-loader.ps1', 'plugin-distribution-common.ps1')) {
                Copy-Item -LiteralPath (Join-Path $repo "scripts/$name") -Destination $scripts
            }
            [IO.File]::WriteAllText((Join-Path $scripts 'set-windows-exe-requested-execution-level.ps1'), @'
param($Path, $Level)
if ($Level -ne 'requireAdministrator') { throw 'Unexpected execution level.' }
if ($case -eq 'patch-failure') { throw 'Fixture manifest patch failed.' }
[IO.File]::AppendAllText($Path, ':requireAdministrator')
'@, $fixtureEncoding)
            $source = Join-Path $root 'build/toolchain'
            $output = Join-Path $root 'build/toolchain-prepared'
            $expectedError = 'protected'
            switch ($case) {
                'same' { $output = $source }
                'ancestor' { $source = Join-Path $output 'original' }
                'child' { $output = Join-Path $source 'prepared' }
                'source-root' { $source = $root; $expectedError = 'into its source directory' }
                'outside' { $output = Join-Path $fixture "outside-$case"; $expectedError = 'outside the build directory' }
                'build-root' { $output = Join-Path $root 'build'; $expectedError = 'build root' }
                'separate' { $expectedError = $null }
                'patch-failure' { $expectedError = 'Fixture manifest patch failed' }
            }
            [IO.Directory]::CreateDirectory($source) | Out-Null
            foreach ($name in @('ISCC.exe', 'SetupLdr.e32', 'SetupLdr.e64')) {
                [IO.File]::WriteAllText((Join-Path $source $name), $name, $fixtureEncoding)
            }
            [IO.Directory]::CreateDirectory($output) | Out-Null
            $marker = Join-Path $output 'keep.txt'
            [IO.File]::WriteAllText($marker, 'existing output', $fixtureEncoding)
            $compiler = Join-Path $source 'ISCC.exe'
            $result = $null
            $failure = $null
            try { $result = & (Join-Path $scripts 'prepare-inno-admin-loader.ps1') -CompilerPath $compiler -OutputDirectory $output }
            catch { $failure = $_ }
            foreach ($name in @('ISCC.exe', 'SetupLdr.e32', 'SetupLdr.e64')) {
                Assert-Equal ([IO.File]::ReadAllText((Join-Path $source $name), $fixtureEncoding)) $name
            }
            Assert-Equal (Test-Path -LiteralPath $marker) ($case -notin @('separate', 'patch-failure'))
            if ($expectedError) {
                if ($null -eq $failure -or $failure.Exception.Message -notlike "*$expectedError*") { throw "Unexpected Inno result: $failure" }
            } else {
                if ($failure) { throw $failure }
                Assert-Equal $result (Join-Path $output 'ISCC.exe')
                foreach ($name in @('SetupLdr.e32', 'SetupLdr.e64')) {
                    Assert-Equal ([IO.File]::ReadAllText((Join-Path $output $name), $fixtureEncoding)) "$($name):requireAdministrator"
                }
            }
        }
    }
    & {
        function jlink { $runtimeBuilds.Add('jlink'); $global:LASTEXITCODE = 17 }
        function jpackage { throw 'Unexpected package build.' }
        function Get-Command {
            param($Name, $ErrorAction)
            if ($Name -eq 'mvn.cmd') { return @{Source='Invoke-PackagingMaven'} }
            if ($Name -eq 'iscc.exe') { return @{Source=$compilerFixture} }
            return Microsoft.PowerShell.Core\Get-Command @PSBoundParameters
        }
        function Invoke-PackagingMaven {
            Assert-Equal $args @('verify', '-DskipTests', '-Dapp.release.version=1.2.3')
            $runtimeBuilds.Add('maven')
            $global:LASTEXITCODE = 17
        }
        $fixtureEncoding = [Text.UTF8Encoding]::new($false)
        foreach ($case in @('missing', 'empty', 'extension', 'jar-overlap', 'plugins-overlap', 'key-overlap', 'tool-overlap', 'inno-overlap', 'inno-adjacent', 'separate-input', 'source-build', 'whitespace-build')) {
            $root = Join-Path $fixture "package-input-$case"
            $scripts = Join-Path $root 'scripts'
            [IO.Directory]::CreateDirectory($scripts) | Out-Null
            foreach ($name in @('package-local.ps1', 'plugin-distribution-common.ps1', 'ffmpeg-release-integrity.ps1',
                    'package-local-plugin-staging.ps1', 'package-local-installer-catalog.ps1')) {
                Copy-Item -LiteralPath (Join-Path $repo "scripts/$name") -Destination $scripts
            }
            $metadata = 'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties'
            [IO.Directory]::CreateDirectory((Split-Path -Parent (Join-Path $root $metadata))) | Out-Null
            Copy-Item -LiteralPath (Join-Path $repo $metadata) -Destination (Join-Path $root $metadata)
            $marker = Join-Path $root 'build/input/keep.txt'
            [IO.Directory]::CreateDirectory((Split-Path -Parent $marker)) | Out-Null
            [IO.File]::WriteAllText($marker, 'existing output', $fixtureEncoding)
            $inputJar = Join-Path $root 'build/input-extra/supplied.jar'
            if ($case -eq 'jar-overlap') { $inputJar = Join-Path $root 'build/out/supplied.jar' }
            [IO.Directory]::CreateDirectory((Split-Path -Parent $inputJar)) | Out-Null
            $archive = [IO.Compression.ZipFile]::Open($inputJar, [IO.Compression.ZipArchiveMode]::Create)
            try {
                $writer = [IO.StreamWriter]::new($archive.CreateEntry('META-INF/pixivdownload-proguard.properties').Open(), $fixtureEncoding)
                try { $writer.Write("formatVersion=1`nshrink=true`noptimize=true`nobfuscate=false`nproguard.version=fixture`n") }
                finally { $writer.Dispose() }
            } finally { $archive.Dispose() }
            $inputHash = (Get-FileHash -LiteralPath $inputJar -Algorithm SHA256).Hash
            $parameters = @{Version='1.2.3'; PrebuiltJar=$inputJar; SkipPlugins=$true; SkipPortable=$true; SkipOfflinePortable=$true; SkipInstaller=$true}
            $compilerFixture = Join-Path $root 'build/inno-admin-loader-extra/ISCC.exe'
            $protectedInput = $inputJar
            $expectedError = 'protected input'
            switch ($case) {
                'missing' { $parameters.PrebuiltJar = Join-Path $root 'missing.jar'; $expectedError = 'not found' }
                'empty' { $parameters.PrebuiltJar = Join-Path $root 'empty.jar'; [IO.File]::WriteAllBytes($parameters.PrebuiltJar, [byte[]]@()); $expectedError = 'empty' }
                'extension' { $parameters.PrebuiltJar = Join-Path $root 'input.txt'; [IO.File]::WriteAllText($parameters.PrebuiltJar, 'input', $fixtureEncoding); $expectedError = 'not a .jar' }
                'plugins-overlap' {
                    $protectedInput = Join-Path $root 'build/runtime/plugins'
                    [IO.Directory]::CreateDirectory($protectedInput) | Out-Null
                    $parameters.SkipPlugins = $false
                    $parameters.PrebuiltPluginsDir = $protectedInput
                }
                'key-overlap' {
                    $protectedInput = Join-Path $root 'build/out/key.pem'
                    [IO.Directory]::CreateDirectory((Split-Path -Parent $protectedInput)) | Out-Null
                    [IO.File]::WriteAllText($protectedInput, 'fixture only', $fixtureEncoding)
                    $parameters.PrivateKeyFile = $protectedInput
                }
                'tool-overlap' {
                    $protectedInput = Join-Path $root 'build/app-image-online/signature.jar'
                    [IO.Directory]::CreateDirectory((Split-Path -Parent $protectedInput)) | Out-Null
                    [IO.File]::WriteAllText($protectedInput, 'fixture only', $fixtureEncoding)
                    $parameters.SignatureToolJar = $protectedInput
                }
                { $_ -in @('inno-overlap', 'inno-adjacent') } {
                    if ($case -eq 'inno-overlap') {
                        $compilerFixture = Join-Path $root 'build/inno-admin-loader/ISCC.exe'
                        $expectedError = 'protected'
                    }
                    else { $expectedError = 'Command failed: jlink' }
                    [IO.Directory]::CreateDirectory((Split-Path -Parent $compilerFixture)) | Out-Null
                    [IO.File]::WriteAllText($compilerFixture, 'compiler fixture', $fixtureEncoding)
                    $protectedInput = $compilerFixture
                    $parameters.SkipInstaller = $false
                }
                'separate-input' { $expectedError = 'Command failed: jlink' }
                'source-build' { $parameters.PrebuiltJar = ''; $expectedError = 'Command failed: Invoke-PackagingMaven' }
                'whitespace-build' { $parameters.PrebuiltJar = ' '; $expectedError = 'Command failed: Invoke-PackagingMaven' }
            }
            $runtimeBuilds = [Collections.Generic.List[string]]::new()
            $originalLocation = (Get-Location).Path
            Assert-Rejected { & (Join-Path $scripts 'package-local.ps1') @parameters } $expectedError
            Assert-Equal (Get-Location).Path $originalLocation
            Assert-Equal (Test-Path -LiteralPath $protectedInput) $true
            Assert-Equal (Get-FileHash -LiteralPath $inputJar -Algorithm SHA256).Hash $inputHash
            Assert-Equal $runtimeBuilds.Count $(if ($case -in @('separate-input', 'inno-adjacent', 'source-build', 'whitespace-build')) { 1 } else { 0 })
            if ($case -in @('separate-input', 'inno-adjacent')) {
                Assert-Equal (Test-Path -LiteralPath $marker) $false
                $staged = Join-Path $root 'build/input/PixivDownload-1.2.3.jar'
                Assert-Equal (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash $inputHash
            } elseif ($case -in @('source-build', 'whitespace-build')) {
                Assert-Equal $runtimeBuilds @('maven')
            } else {
                Assert-Equal ([IO.File]::ReadAllText($marker, $fixtureEncoding)) 'existing output'
            }
        }
    }
    & {
        . (Join-Path $repo 'scripts/release-e2e/scenarios.ps1')
        $root = Join-Path $fixture 'adverse'
        [IO.Directory]::CreateDirectory((Join-Path $root 'plugins')) | Out-Null
        $manifest = Join-Path $root 'plugins-manifest.json'
        $entries = @(@{id='gui-compose'; file='gui-compose.jar'; required=$false},
            @{id='gui-swing'; file='gui-swing.jar'; required=$false},
            @{id='required'; file='required.jar'; required=$true})
        [IO.File]::WriteAllText($manifest, ($entries | ConvertTo-Json))
        foreach ($entry in $entries) { [IO.File]::WriteAllText((Join-Path $root "plugins/$($entry.file)"), 'original') }
        $script:ReleaseTools = @{ Fixture=(Join-Path $fixture 'input') }
        $script:observed = [Collections.Generic.List[string]]::new()
        function Get-ManifestPath { return $manifest }
        function Get-PixivDownloadSdkVersion { return '1.0.0' }
        function Write-UnsignedLocalPluginProvenanceSidecar {
            param($ArtifactPath)
            [IO.File]::WriteAllText("$ArtifactPath.sidecar", 'fixture')
            return "$ArtifactPath.sidecar"
        }
        function Test-ApplicationScenario {
            param($Label, $RuntimeRoot, $Provider, $ConfiguredProvider, [switch]$Headless,
                [switch]$FirstRun, [switch]$InjectsFailure, [switch]$BootstrapPrompt, [switch]$Recovery,
                $UnavailableIds, $CrashedIds, $Exercise)
            $script:observed.Add((@($Label, $RuntimeRoot, $Provider, $ConfiguredProvider, $Headless,
                $FirstRun, $InjectsFailure, $BootstrapPrompt, $Recovery, ($UnavailableIds -join ','),
                ($CrashedIds -join ','), [bool]$Exercise) -join '|'))
        }
        function Test-ReleaseLogRotation { param($Label) $script:observed.Add($Label) }
        $parameters = @{Label='installer'; Root=$root; Launcher='fixture'; RuntimeRoot='runtime'; LogRoot='logs'}
        Test-ReleaseAdverseLayouts @parameters
        $complete = @($script:observed)
        $script:observed.Clear()
        foreach ($group in @('failures', 'recovery')) { Test-ReleaseAdverseLayouts @parameters -ScenarioGroup $group }
        Assert-Equal $script:observed $complete
        Assert-Equal $complete.Count 9
        foreach ($entry in $entries) { Assert-Equal ([IO.File]::ReadAllText((Join-Path $root "plugins/$($entry.file)"))) 'original' }
    }
    & {
        $installer = Join-Path $repo 'packaging/windows/inno/installer-plugin-install.ps1'
        $program = Read-Program $installer
        foreach ($function in $program.Functions) { . ([scriptblock]::Create($function.Extent.Text)) }
        $SdkVersion = '7.4.0'
        function New-PlanEntry($Id, $Version, $Dependencies) {
            [pscustomobject]@{ pluginId=$Id; market=@{latestVersion=$Version}; packages=@([pscustomobject]@{
                version=$Version; packageUrl='https://example.invalid/fixture.invalid'; expectedSizeBytes=1;
                sha256=('0'*64); signature=@{value='fixture'}; requiredSdk='*'; dependencies=$Dependencies
            }) }
        }
        $shared = New-PlanEntry 'shared' '1.3.0' @()
        $first = New-PlanEntry 'first' '1.0.0' @('shared@1.0')
        $second = New-PlanEntry 'second' '1.0.0' @('shared@1.2', 'absent?@9.0')
        $manifest = [pscustomobject]@{ entries=@($shared, $first, $second) }
        $plan = [ordered]@{}
        foreach ($id in @('first', 'second', 'first', 'SHARED')) { Add-Plan $manifest $id '*' $plan @{} }
        Assert-Equal @($plan.Values.PluginId) @('shared', 'first', 'second')
        Assert-Equal @($plan.Values.Version) @('1.3.0', '1.0.0', '1.0.0')
        $fallback = New-PlanEntry 'fallback' '2.0.0' @()
        $fallback.packages += $shared.packages[0]
        $selection = [ordered]@{}
        Add-Plan ([pscustomobject]@{entries=@($fallback)}) 'fallback' '1.1' $selection @{}
        Assert-Equal $selection['fallback'].Version '1.3.0'
        Assert-Rejected { Add-Plan $manifest 'missing' '*' ([ordered]@{}) @{} } 'Dependency not found'
        Assert-Rejected { Add-Plan $manifest 'shared' '2.0' ([ordered]@{}) @{} } 'No installable package'
        $cycle = [pscustomobject]@{ entries=@((New-PlanEntry 'left' '1.0.0' @('right')), (New-PlanEntry 'right' '1.0.0' @('left'))) }
        Assert-Rejected { Add-Plan $cycle 'left' '*' ([ordered]@{}) @{} } 'Dependency cycle'
        $incompatible = New-PlanEntry 'incompatible' '1.0.0' @()
        $incompatible.packages[0].requiredSdk = '8.0'
        Assert-Rejected { Add-Plan ([pscustomobject]@{entries=@($incompatible)}) 'incompatible' '*' ([ordered]@{}) @{} } 'requires SDK'
        $second.packages[0].dependencies = @('shared@2.0')
        $conflict = [ordered]@{}
        Add-Plan $manifest 'first' '*' $conflict @{}
        Assert-Rejected { Add-Plan $manifest 'second' '*' $conflict @{} } 'does not satisfy dependency'
        foreach ($ids in @('first,second', 'shared,second')) {
            $root = Join-Path $fixture ('installer-plan-' + $ids.Replace(',', '-'))
            [IO.Directory]::CreateDirectory($root) | Out-Null
            $manifestPath = Join-Path $root 'manifest.json'
            $progressPath = Join-Path $root 'progress.txt'
            $installPath = Join-Path $root 'app'
            [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
            & (Get-Process -Id $PID).Path -NoProfile -File $installer -ManifestFile $manifestPath -PluginIds $ids -InstallDir $installPath -ProgressFile $progressPath -SignatureToolJar unused -JavaPath unused -SdkVersion $SdkVersion
            Assert-Equal $LASTEXITCODE 1
            $progress = [IO.File]::ReadAllText($progressPath, [Text.Encoding]::UTF8)
            if ($progress -notmatch '^ERROR\|.*does not satisfy dependency') { throw "Unexpected planning error: $progress" }
            Assert-Equal (Test-Path -LiteralPath $installPath) $false
        }
        $second.packages[0].dependencies = @('shared@1.2')
        $bytes = [Text.Encoding]::UTF8.GetBytes('fixture')
        $hasher = [Security.Cryptography.SHA256]::Create()
        try { $hash = ([BitConverter]::ToString($hasher.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
        finally { $hasher.Dispose() }
        foreach ($entry in $manifest.entries) {
            $entry.packages[0].packageUrl = 'https://example.invalid/fixture.jar'
            $entry.packages[0].sha256 = $hash
            $entry.packages[0].expectedSizeBytes = $bytes.Length
            $entry.packages[0].signature = @{formatVersion=1; algorithm='Ed25519'; keyId='fixture'; value='fixture'}
        }
        $boundaries = @'

function Download-Package($Item, [string]$Target) {
    [IO.File]::WriteAllText($Target, 'fixture', [Text.UTF8Encoding]::new($false))
}
function Verify-Package($Item, [string]$ArtifactPath, [string]$SignaturePath) {
    [IO.File]::AppendAllText($ProgressFile + '.order', $Item.PluginId + "`n", [Text.UTF8Encoding]::new($false))
    if ($ProgressFile.Contains('verify-failure') -and $Item.PluginId -eq 'first') { throw 'Fixture signature failure.' }
}

'@
        $driver = Join-Path $fixture 'installer-with-local-download.ps1'
        Copy-Item -LiteralPath (Join-Path $repo 'packaging/windows/inno/installer-progress.ps1') -Destination $fixture
        [IO.File]::WriteAllText($driver, ([IO.File]::ReadAllText($installer).Insert($program.Functions[-1].Extent.EndOffset, $boundaries)), [Text.UTF8Encoding]::new($false))
        foreach ($case in @('success', 'verify-failure')) {
            $root = Join-Path $fixture "installer-execute-$case"
            [IO.Directory]::CreateDirectory($root) | Out-Null
            $manifestPath = Join-Path $root 'manifest.json'
            $progressPath = Join-Path $root 'progress.txt'
            $installPath = Join-Path $root 'app'
            [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
            & (Get-Process -Id $PID).Path -NoProfile -File $driver -ManifestFile $manifestPath -PluginIds 'first,second,first' -InstallDir $installPath -ProgressFile $progressPath -SignatureToolJar unused -JavaPath unused -SdkVersion $SdkVersion
            $expectedExit = if ($case -eq 'success') { 0 } else { 1 }
            Assert-Equal $LASTEXITCODE $expectedExit
            $progress = [IO.File]::ReadAllText($progressPath, [Text.Encoding]::UTF8).Trim()
            $order = [IO.File]::ReadAllLines($progressPath + '.order', [Text.Encoding]::UTF8)
            if ($case -eq 'success') {
                Assert-Equal $order @('shared', 'first', 'second')
                Assert-Equal $progress 'DONE|3'
            } else {
                Assert-Equal $order @('shared', 'first')
                if ($progress -notmatch '^ERROR\|.*Fixture signature failure') { throw "Unexpected installation error: $progress" }
            }
            $installed = @(if ($case -eq 'success') { 'shared', 'first', 'second' } else { 'shared' })
            $artifacts = @(Get-ChildItem -LiteralPath (Join-Path $installPath 'plugins') -File -Filter '*.jar')
            Assert-Equal $artifacts.Count $installed.Count
            foreach ($id in $installed) {
                $version = if ($id -eq 'shared') { '1.3.0' } else { '1.0.0' }
                $artifact = Join-Path $installPath "plugins/$id-$version.jar"
                Assert-Equal ([IO.File]::ReadAllText($artifact, [Text.Encoding]::UTF8)) 'fixture'
                Assert-Equal (Test-Path -LiteralPath "$artifact.sig") $true
                Assert-Equal (Test-Path -LiteralPath (Join-Path $installPath "plugins/provenance/$id-$version.jar.pixiv-plugin-provenance")) $true
            }
            Assert-Equal ([IO.File]::ReadAllLines((Join-Path $installPath 'config/config.yaml'), [Text.Encoding]::UTF8)) @($installed | ForEach-Object { "plugins.$_.enabled: true" })
        }
    }
    if ($env:OS -eq 'Windows_NT') {
        & {
            $program = Read-Program (Join-Path $repo 'scripts/package-local.ps1')
            $compilerFunction = $program.Functions | Where-Object { $_.Name -eq 'Get-InnoSetupCompiler' }
            . ([scriptblock]::Create($compilerFunction.Extent.Text))
            & (Join-Path $PSScriptRoot 'installer-helper.test.ps1') -CompilerPath (Get-InnoSetupCompiler)
        }
    }
    Write-Host 'PASS: complete acceptance across independent groups, verified candidate reuse, prebuilt rejection and publication failure propagation.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ((Split-Path -Parent $resolved).TrimEnd('\', '/') -ne $tempBase -or
        (Split-Path -Leaf $resolved) -notlike 'pixiv-workflow-test-*') { throw 'Unsafe fixture cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

# The expected native-command failure must not become the runner's final exit code.
$global:LASTEXITCODE = 0
