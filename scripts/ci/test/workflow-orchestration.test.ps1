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
        Main = [scriptblock]::Create($ast.ParamBlock.Extent.Text + "`n" + (($statements | ForEach-Object { $_.Extent.Text }) -join "`n"))
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
        function Test-ReleaseAdverseLayouts { param($Label) $script:observed.Add("$Label-adverse") }
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
                    if ($kind -eq 'windows-installer') { "$kind-adverse" }
                }
            })
            Assert-Equal $script:observed $expected
            Assert-Equal $script:probes.Count 1
            if ($distribution -eq 'windows-installer' -and $script:probes[0] -notlike '*installed*app*PixivDownload-1.2.3.jar') {
                throw 'Installer shard did not initialize its probe from its own application.'
            }
        }
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
        foreach ($mode in @('nightly', 'force', 'stable', 'skip', 'failure')) {
            $script:builds.Clear(); $script:writes.Clear()
            $script:existing = $mode -eq 'skip'
            $script:buildExit = if ($mode -eq 'failure') { 1 } else { 0 }
            $parameters = @{ProjectRoot=$fixture; OfficialKeyId='fixture'; PrivateKeyFile=(Join-Path $fixture 'input')}
            if ($mode -in @('nightly', 'failure')) { $parameters.NightlyBuildVersion = '1.2.3-nightly.20260909.1.1' }
            if ($mode -eq 'force') { $parameters.Force = $true }
            if ($mode -eq 'failure') {
                Assert-Rejected { & $program.Main @parameters } 'Maven plugin build failed'
                Assert-Equal $script:writes.Count 0
            } else {
                & $program.Main @parameters
                Assert-Equal $script:builds.Count $(if ($mode -eq 'skip') { 0 } elseif ($mode -eq 'stable') { 2 } else { 1 })
                Assert-Equal $script:writes.Count $(if ($mode -eq 'skip') { 0 } elseif ($mode -eq 'nightly') { 2 } else { 4 })
                if ($mode -in @('nightly', 'force')) {
                    Assert-Equal $script:builds[0] @('-Pofficial-surveys', '-pl', 'first,second', '-am', 'verify', '-DskipTests')
                }
            }
        }
    }
    Write-Host 'PASS: independent release distributions, complete default acceptance, plugin build batching and failure propagation.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ((Split-Path -Parent $resolved).TrimEnd('\', '/') -ne $tempBase -or
        (Split-Path -Leaf $resolved) -notlike 'pixiv-workflow-test-*') { throw 'Unsafe fixture cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

# The expected native-command failure must not become the runner's final exit code.
$global:LASTEXITCODE = 0
