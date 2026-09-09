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
        function Get-OfficialDistributionPlugins { param([switch]$IncludeOptional) return $plugins }
        function Get-MavenCommand { return 'Invoke-FakeMaven' }
        function Invoke-FakeMaven {
            $script:builds.Add(@($args))
            $global:LASTEXITCODE = $script:buildExit
        }
        function Find-ModulePluginArtifact { param($Plugin) return (Join-Path $fixture "$($Plugin.Module)/src/main/resources/plugin.properties") }
        function Assert-OfficialPluginArtifact { return @{'plugin.version'='1.0.0'} }
        function Assert-ProguardProcessedArtifact { }
        function Get-OfficialPluginArtifactName { param($Plugin, $Version) return "$($Plugin.Id)-$Version.jar" }
        function Get-NightlyPluginVersion { param($SourceVersion, $Suffix) return "$SourceVersion-$Suffix" }
        function Set-StagedPluginVersion { }
        function Write-StagedCompanionFiles {
            param($StagedArtifact)
            return @{Sha='fixture'; ShaFile="$StagedArtifact.sha256"; SigFile="$StagedArtifact.sig"}
        }
        function gh {
            $global:LASTEXITCODE = 0
            if ($args[1] -eq 'view') {
                if (-not $script:existing) { $global:LASTEXITCODE = 1; return 'release not found' }
                $id = $args[2] -replace '-v.*$', ''
                return (@{assets=@(@{name="$id-1.0.0.jar"}, @{name="$id-1.0.0.jar.sha256"}, @{name="$id-1.0.0.jar.sig"})} | ConvertTo-Json -Compress)
            }
            $script:writes.Add(($args -join ' '))
        }
        foreach ($mode in @('nightly', 'force', 'stable', 'skip', 'failure', 'prebuilt')) {
            $script:builds.Clear(); $script:writes.Clear()
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
                Assert-Equal $script:builds.Count $(if ($mode -in @('skip', 'prebuilt')) { 0 } elseif ($mode -eq 'stable') { 2 } else { 1 })
                Assert-Equal $script:writes.Count $(if ($mode -eq 'skip') { 0 } elseif ($mode -in @('nightly', 'prebuilt')) { 2 } else { 4 })
                if ($mode -in @('nightly', 'force')) {
                    Assert-Equal $script:builds[0] @('-Pofficial-surveys', '-pl', 'first,second', '-am', 'verify', '-DskipTests')
                }
            }
        }
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
