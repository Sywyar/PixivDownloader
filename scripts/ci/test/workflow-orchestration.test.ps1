# Execute orchestration with local fixtures; publishing and application processes are replaced at their boundaries.
$ErrorActionPreference = 'Stop'
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
                $assets = @(@{name="$id-1.0.0.jar"}, @{name="$id-1.0.0.jar.sha256"}, @{name="$id-1.0.0.jar.sig"})
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
        $sourceDescriptor = Read-SourceDescriptor $marketPlugin.Module
        $currentSdk = Get-PixivDownloadSdkVersion -ProjectRoot $repo
        $marketRoot = Join-Path $fixture 'market'
        $resources = Join-Path $marketRoot "$($marketPlugin.Module)/src/main/resources"
        [IO.Directory]::CreateDirectory((Join-Path $resources 'i18n/web')) | Out-Null
        $descriptorPath = Join-Path $resources 'plugin.properties'
        $descriptorText = [IO.File]::ReadAllText((Join-Path $repo "$($marketPlugin.Module)/src/main/resources/plugin.properties"), $fixtureUtf8)
        foreach ($suffix in @('', '_en')) {
            $bundle = "i18n/web/$($sourceDescriptor['pixiv.display-namespace'])$suffix.properties"
            Copy-Item -LiteralPath (Join-Path $repo "$($marketPlugin.Module)/src/main/resources/$bundle") -Destination (Join-Path $resources $bundle)
        }
        $metadata = 'pixivdownload-sdk-info/src/main/resources/META-INF/pixivdownload-sdk.properties'
        [IO.Directory]::CreateDirectory((Split-Path -Parent (Join-Path $marketRoot $metadata))) | Out-Null
        Copy-Item -LiteralPath (Join-Path $repo $metadata) -Destination (Join-Path $marketRoot $metadata)
        $output = Join-Path $marketRoot 'manifest.json'
        function Get-OfficialDistributionPlugins { param([switch]$IncludeOptional) return @($marketPlugin) }
        function Get-OfficialDefaultInstalledPlugins { return @($marketPlugin) }
        function Resolve-SignatureToolJar { return 'fixture' }
        function Invoke-PluginSignatureTool {
            param($Tool, $Arguments)
            $destination = $Arguments[[Array]::IndexOf($Arguments, '--out') + 1]
            [IO.File]::WriteAllText($destination, '{"formatVersion":1,"algorithm":"Ed25519","keyId":"fixture","value":"fixture"}', $fixtureUtf8)
        }
        function gh {
            $global:LASTEXITCODE = 0
            if ($args[0] -eq 'api') { $global:LASTEXITCODE = 1; return }
            if ($args[0] -eq 'release' -and $args[1] -eq 'view') {
                return (@{assets=@(@{name=$fixtureAssetName; downloadCount=0}); publishedAt='2026-01-01T00:00:00Z'} | ConvertTo-Json -Depth 5 -Compress)
            }
            if ($args[0] -eq 'release' -and $args[1] -eq 'download') {
                $directory = $args[[Array]::IndexOf($args, '--dir') + 1]
                $name = $args[[Array]::IndexOf($args, '--pattern') + 1]
                [IO.File]::WriteAllText((Join-Path $directory $name), 'fixture-artifact', $fixtureUtf8)
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
            $manifest = [IO.File]::ReadAllText($output, $fixtureUtf8) | ConvertFrom-Json
            Assert-Equal @($manifest.entries).Count 1
            $package = $manifest.entries[0].packages[0]
            Assert-Equal $package.requiredSdk $case.Expected
            Assert-Equal $package.requiredCoreApi $case.Expected
            if ($case.ContainsKey('Nightly')) {
                & node (Join-Path $repo 'scripts/ci/assert-nightly-publication.mjs') plugins '8.2.3-nightly.20260910.2.1' $output
                if ($LASTEXITCODE -ne 0) { throw 'Generated Nightly manifest was rejected by publication guard.' }
            }
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
        $program = Read-Program (Join-Path $repo 'scripts/package-local.ps1')
        $definition = $program.Functions | Where-Object { $_.Name -eq 'Resolve-PrebuiltJar' }
        . ([scriptblock]::Create($definition.Extent.Text))
        Assert-Equal (Resolve-PrebuiltJar '') $null
        Assert-Rejected { Resolve-PrebuiltJar (Join-Path $fixture 'missing.jar') } 'not found'
        $empty = Join-Path $fixture 'empty.jar'
        [IO.File]::WriteAllBytes($empty, [byte[]]@())
        Assert-Rejected { Resolve-PrebuiltJar $empty } 'empty'
        Assert-Rejected { Resolve-PrebuiltJar (Join-Path $fixture 'input') } 'not a .jar'
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
    Write-Host 'PASS: complete acceptance across independent groups, verified candidate reuse, prebuilt rejection and publication failure propagation.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ((Split-Path -Parent $resolved).TrimEnd('\', '/') -ne $tempBase -or
        (Split-Path -Leaf $resolved) -notlike 'pixiv-workflow-test-*') { throw 'Unsafe fixture cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

# The expected native-command failure must not become the runner's final exit code.
$global:LASTEXITCODE = 0
