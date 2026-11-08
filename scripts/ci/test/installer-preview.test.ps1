param([string]$CompilerPath)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$fixture = Join-Path $repo ('target/installer-preview-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($fixture) | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
foreach ($file in @('installer-plugin-plan.ps1', 'installer-plugin-install.ps1', 'installer-progress.ps1')) {
    Copy-Item -LiteralPath (Join-Path $repo "packaging/windows/inno/$file") -Destination $fixture
}
Copy-Item -LiteralPath (Join-Path $repo 'scripts/plugin-package-metadata.ps1') -Destination $fixture
$catalog = [IO.File]::ReadAllText((Join-Path $repo 'pixivdownload-app/src/main/resources/i18n/locales.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
$catalog.locales += [pscustomobject]@{ tag = 'qaa'; resourceSuffix = 'qaa'; status = 'candidate' }
[IO.File]::WriteAllText((Join-Path $fixture 'locales.json'), ($catalog | ConvertTo-Json -Depth 8), $utf8)
. (Join-Path $fixture 'installer-plugin-plan.ps1') -Library

function Assert-Preview($Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-PreviewThrows([scriptblock]$Operation, [string]$Message) {
    try { & $Operation | Out-Null } catch { return }
    throw $Message
}
function New-PreviewPackage([string]$Directory, [string]$File, [string]$Id, [string]$Version, [string]$Requires, [string]$Source = 'MARKET_CATALOG') {
    [IO.Directory]::CreateDirectory($Directory) | Out-Null
    $path = Join-Path $Directory $File
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $entry = $zip.CreateEntry('plugin.properties')
        $writer = New-Object IO.StreamWriter($entry.Open(), $utf8)
        try { $writer.Write("plugin.id=$Id`nplugin.version=$Version`nplugin.requires=$Requires`nplugin.description=Plugin $Id`npixiv.display-namespace=$Id`npixiv.display-name-key=plugin.name`n") }
        finally { $writer.Dispose() }
        $entry = $zip.CreateEntry("i18n/web/${Id}_en.properties")
        $writer = New-Object IO.StreamWriter($entry.Open(), $utf8)
        try { $writer.Write("plugin.name=Display $Id") } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    $companions = Get-PlanCompanions $path
    foreach ($sidecar in $companions[1..4]) { [IO.File]::WriteAllText($sidecar, 'keep sidecar', $utf8) }
    [IO.Directory]::CreateDirectory((Join-Path $Directory 'provenance')) | Out-Null
    $official = if ($Source -eq 'MARKET_CATALOG') { 'true' } else { 'false' }
    if ($Source -eq 'COMMUNITY_CATALOG') { $Source = 'MARKET_CATALOG' }
    [IO.File]::WriteAllText($companions[5], "source=$Source`nofficialRepository=$official`nartifactSha256=$hash`n", $utf8)
    return [pscustomobject]@{ id = $Id; version = $Version; requires = $Requires; file = $File; sha256 = $hash; required = $false; description = "Plugin $Id"; displayNames = @{en = "Display $Id"} }
}

try {
    $localized = New-PreviewPackage (Join-Path $fixture 'localized') 'localized.jar' 'localized' '1.0.0' '2.0'
    $zip = [IO.Compression.ZipFile]::Open((Join-Path $fixture 'localized/localized.jar'), [IO.Compression.ZipArchiveMode]::Update)
    try {
        $writer = New-Object IO.StreamWriter($zip.CreateEntry('i18n/web/localized_qaa.properties').Open(), $utf8)
        try { $writer.Write('plugin.name=Additional locale') } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
    $localized = Get-PluginPackageMetadata (Join-Path $fixture 'localized/localized.jar')
    Assert-Preview ($localized.displayNames.qaa -eq 'Additional locale') 'Catalog locale was not discovered.'
    Assert-Preview ($localized.displayNames.en -eq 'Display localized') 'Existing display name changed.'
    Assert-Preview (Test-InstallerSdkCompatibility '2.1.99' '2.1.0' '1.0.0' '1.0.0') 'SDK patch must not become a lower bound.'
    Assert-Preview (-not (Test-InstallerSdkCompatibility '1.2' '2.0.0' '1.0.0' '1.0.0')) 'Cross-major SDK accepted.'
    Assert-Preview (Test-InstallerSdkCompatibility '=2.0.0-rc6' '2.0.0-rc.6' '1.0.0' '1.0.0') 'Historical SDK spelling rejected.'
    Assert-Preview (-not (Test-InstallerSdkCompatibility '2.0.0-rc.6' '2.0.0' '1.0.0' '1.0.0')) 'Prerelease SDK treated as stable.'
    Assert-Preview (Test-InstallerSdkCompatibility '2.0.0-nightly.20260101.1.2' '2.0.0' '1.0.0-nightly.20260101.1.2' '1.0.0-nightly.20260101.1.2') 'Matching nightly rejected.'
    Assert-Preview (-not (Test-InstallerSdkCompatibility '2.0' '2.0.0' '1.0.0' '1.0.0-nightly.20260101.1.2')) 'Nightly package accepted by stable host.'
    Assert-Preview ((Compare-InstallerPluginVersion '1.0.0-rc.10' '1.0.0-rc.2') -gt 0) 'Numeric prerelease ordering failed.'
    Assert-Preview ((Compare-InstallerPluginVersion '1.0.0-rc.7' '1.0.0-rc6') -lt 0) 'Plugin comparator incorrectly normalized legacy SDK spelling.'
    Assert-Preview ((Compare-InstallerPluginVersion '1.0.0+one' '1.0.0+two') -eq 0) 'Build metadata changed precedence.'
    $application = Join-Path $fixture 'application'
    $plugins = Join-Path $application 'plugins'
    $payload = Join-Path $fixture 'payload'
    $bundles = @(
        (New-PreviewPackage $payload 'regular.jar' 'regular' '2.0.0' '2.0'),
        (New-PreviewPackage $payload 'higher.jar' 'higher' '2.0.0' '2.0'),
        (New-PreviewPackage $payload 'local.jar' 'local' '2.0.0' '2.0'),
        (New-PreviewPackage $payload 'database.jar' 'database' '2.0.0' '2.0')
    )
    New-PreviewPackage $plugins 'regular-old.jar' 'regular' '1.0.0' '1.0' | Out-Null
    New-PreviewPackage $plugins 'higher.jar' 'higher' '3.0.0' '2.0' | Out-Null
    New-PreviewPackage $plugins 'local.jar' 'local' '1.0.0' '2.0' 'LOCAL_UPLOAD' | Out-Null
    New-PreviewPackage $plugins 'database-old.jar' 'database' '3.0.0' '1.0' | Out-Null
    New-PreviewPackage $plugins 'manual.jar' 'manual' '1.0.0' '1.0' 'COMMUNITY_CATALOG' | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $application 'config')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $application 'config/config.yaml'), "database.provider: database`n", $utf8)
    $inputManifest = Join-Path $fixture 'bundles.json'
    Save-PlanJson $bundles $inputManifest
    $fresh = New-InstallerPluginPlan $inputManifest (Join-Path $fixture 'fresh') '2.0.0' '1.0.0' 'en'
    Assert-Preview (@($fresh.rows | Where-Object selected).Count -eq 4) 'Fresh installation did not select compatible defaults.'
    $wrapped = Join-Path $fixture 'wrapped.zip'
    $zip = [IO.Compression.ZipFile]::Open($wrapped, [IO.Compression.ZipArchiveMode]::Create)
    try { [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $payload 'regular.jar'), 'inner.jar') }
    finally { $zip.Dispose() }
    Assert-Preview ((Get-PluginPackageMetadata $wrapped).id -eq 'regular') 'Single-JAR ZIP metadata was not read.'
    $metadataBudget = @{ Remaining = 1MB }
    $null = Get-PluginPackageMetadata $wrapped -Budget $metadataBudget
    $consumed = 1MB - $metadataBudget.Remaining
    Assert-Preview ($consumed -gt 0) 'Nested metadata did not consume the shared budget.'
    $metadataBudget.Remaining = $consumed
    $null = Get-PluginPackageMetadata $wrapped -Budget $metadataBudget
    Assert-PreviewThrows { Get-PluginPackageMetadata $wrapped -Budget $metadataBudget } 'Metadata budget was reset for each plugin.'
    $before = Get-PlanSnapshot $application
    $plan = New-InstallerPluginPlan $inputManifest $application '2.0.0' '1.0.0' 'en'
    Assert-Preview ($plan.rows.Count -eq 5) 'Incorrect inventory count.'
    Assert-Preview ($plan.rows[0].id -eq 'database' -and $plan.rows[0].required) 'Current database not prioritized.'
    Assert-Preview (($plan.rows | Where-Object id -eq 'higher').selected -eq $false) 'Higher version selected by default.'
    Assert-Preview (($plan.rows | Where-Object id -eq 'local').selected -eq $false) 'Local package selected by default.'
    Assert-Preview (($plan.rows | Where-Object id -eq 'regular').selected) 'Normal compatible upgrade not selected.'
    Assert-Preview (($plan.rows | Where-Object id -eq 'manual').selectable -eq $false) 'No replacement incorrectly offered a checkbox.'
    Assert-Preview (($plan.rows | Where-Object id -eq 'manual').source -eq 'COMMUNITY_CATALOG') 'Community provenance was mistaken for the official source.'
    Assert-Preview ((Get-PlanSnapshot $application | ConvertTo-Json -Compress) -eq ($before | ConvertTo-Json -Compress)) 'Preview modified installation.'
    $choicePath = Join-Path $fixture 'choices.ini'
    $choiceLines = @('[choices]')
    for ($i = 0; $i -lt $plan.rows.Count; $i++) { $choiceLines += "row$i=0" }
    [IO.File]::WriteAllLines($choicePath, $choiceLines, [Text.Encoding]::Unicode)
    Assert-PreviewThrows { Set-PlanChoices $plan $choicePath } 'Incompatible database did not block installation.'
    $choiceLines[1] = 'row0=1'
    [IO.File]::WriteAllLines($choicePath, $choiceLines, [Text.Encoding]::Unicode)
    Set-PlanChoices $plan $choicePath
    $savedPlan = Join-Path $fixture 'plan.json'
    Save-PlanJson $plan $savedPlan
    $engine = (Get-Process -Id $PID).Path
    $arguments = @('-NoProfile', '-File', (Join-Path $fixture 'installer-plugin-plan.ps1'), '-ManifestFile', $inputManifest,
        '-InstallDir', $application, '-PlanFile', $savedPlan, '-ChoicesFile', $choicePath)
    & $engine @arguments -Action Validate
    Assert-Preview ($LASTEXITCODE -eq 0) 'Real helper validation failed.'
    $allowed = ConvertFrom-PluginProperties ([IO.File]::ReadAllText("$savedPlan.files.ini"))
    foreach ($bundle in $bundles) {
        foreach ($source in (Get-PlanCompanions (Join-Path $payload $bundle.file))) {
            $relative = $source.Substring($payload.Length+1)
            if ($allowed['plugins\' + $relative] -eq '1') {
                $destination = Join-Path $plugins $relative
                [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
                Copy-Item -LiteralPath $source -Destination $destination -Force
            }
        }
    }
    & $engine @arguments -Action Finalize
    Assert-Preview ($LASTEXITCODE -eq 0) 'Real helper finalization failed.'
    foreach ($id in @('higher', 'local', 'manual')) {
        foreach ($path in (Get-PlanCompanions (Join-Path $plugins "$id.jar"))) {
            Assert-Preview ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq $before[$path]) "Retained companion changed: $path"
        }
    }
    foreach ($id in @('regular', 'database')) {
        foreach ($path in (Get-PlanCompanions (Join-Path $plugins "$id-old.jar"))) { Assert-Preview (-not (Test-Path -LiteralPath $path)) 'Approved old companion survived reconciliation.' }
    }
    $manifestAfter = [IO.File]::ReadAllText((Join-Path $plugins 'plugins-manifest.json')) | ConvertFrom-Json
    Assert-Preview (@($manifestAfter).Count -eq 2) 'Manifest claims retained plugins were replaced.'
    $plan = New-InstallerPluginPlan $inputManifest $application '2.0.0' '1.0.0' 'en'
    Save-PlanJson $plan $savedPlan
    [IO.File]::AppendAllText((Join-Path $plugins 'higher.jar.sig'), 'changed')
    $ErrorActionPreference = 'Continue'
    & $engine @arguments -Action Validate 2>$null
    $ErrorActionPreference = 'Stop'
    Assert-Preview ($LASTEXITCODE -eq 1) 'Changed sidecar did not invalidate preview.'
    New-PreviewPackage $plugins 'duplicate.jar' 'higher' '3.0.0' '2.0' | Out-Null
    Assert-PreviewThrows { New-InstallerPluginPlan $inputManifest $application '2.0.0' '1.0.0' 'en' } 'Duplicate installed id accepted.'
    $oversized = Join-Path $fixture 'oversized.properties'
    [IO.File]::WriteAllText($oversized, ('a' * (1MB + 1)))
    Assert-PreviewThrows { Read-PluginMetadataFile $oversized } 'Oversized metadata accepted.'
    if ($CompilerPath) {
        $native = Join-Path $fixture 'native'
        [IO.Directory]::CreateDirectory($native) | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $repo 'packaging/windows/inno') -Filter '*.iss.inc' |
            Copy-Item -Destination $native
        $image = Join-Path $fixture 'image'
        $imagePlugins = Join-Path $image 'plugins'
        $target = Join-Path $fixture 'installed'
        $targetPlugins = Join-Path $target 'plugins'
        $distribution = @(
            (New-PreviewPackage $imagePlugins 'retain.jar' 'retain' '1.0.0' '2.0'),
            (New-PreviewPackage $imagePlugins 'upgrade.jar' 'upgrade' '2.0.0' '2.0')
        )
        New-PreviewPackage $targetPlugins 'retain.jar' 'retain' '3.0.0' '2.0' | Out-Null
        New-PreviewPackage $targetPlugins 'old-upgrade.jar' 'upgrade' '1.0.0' '2.0' | Out-Null
        Save-PlanJson $distribution (Join-Path $imagePlugins 'plugins-manifest.json')
        [IO.File]::WriteAllText((Join-Path $image 'fixture.txt'), 'installed')
        [IO.File]::WriteAllText((Join-Path $fixture 'signature.jar'), 'compile fixture')
        $retained = Get-PlanSnapshot $target
        $source = [IO.File]::ReadAllText((Join-Path $repo 'packaging/windows/inno/PixivDownload.iss'))
        $source = $source.Replace('[Setup]', ('[Setup]' + "`nSourceDir=" + (Join-Path $repo 'packaging/windows/inno') + "`nUninstallable=no`nCreateUninstallRegKey=no"))
        $source = $source.Replace('PrivilegesRequired=admin', 'PrivilegesRequired=lowest')
        $source = $source.Replace('4D4F3566-C6C0-4D24-9242-86059B2A84A5', [Guid]::NewGuid().ToString())
        $source = $source.Replace('Software\sywyar\PixivDownload', ('Software\sywyar\PreviewFixture-' + [Guid]::NewGuid().ToString('N')))
        foreach ($section in @('Registry', 'Icons', 'Run')) {
            $source = [regex]::Replace($source, ('(?ms)^\[' + $section + '\].*?(?=^\[)'), '')
        }
        # 在首次调用前禁用本机安装探测，使夹具仅使用自己的临时目录。
        $lifecyclePath = Join-Path $native 'PixivDownload-lifecycle.iss.inc'
        $lifecycle = [IO.File]::ReadAllText($lifecyclePath)
        $lifecycle = $lifecycle.Replace('  ResolveExistingInstallation;', "  ExistingInstallationResolved := True;`n  ResolveExistingInstallation;")
        [IO.File]::WriteAllText($lifecyclePath, $lifecycle, $utf8)
        [IO.File]::WriteAllText((Join-Path $native 'fixture.iss'), $source, $utf8)
        & $CompilerPath '/Q' "/DAppImageDir=$image" "/DSignatureToolJar=$fixture/signature.jar" '/DSdkVersion=2.0.0' '/DAppVersion=1.0.0' "/DOutputDir=$native" (Join-Path $native 'fixture.iss')
        Assert-Preview ($LASTEXITCODE -eq 0) 'Production installer fixture did not compile.'
        $installer = Join-Path $native 'PixivDownload-1.0.0-win-x64-setup.exe'
        $log = Join-Path $fixture 'install.log'
        $process = Start-Process -FilePath $installer -WindowStyle Hidden -PassThru -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/LANG=en', ('/DIR="' + $target + '"'), ('/LOG="' + $log + '"'))
        $null = $process.Handle
        try {
            if (-not $process.WaitForExit(60000)) { $process.Kill(); throw 'Production fixture installation timed out.' }
            if ($process.ExitCode -ne 0) { throw ("Installer exit=$($process.ExitCode)`n" + ((Get-Content -LiteralPath $log -Tail 25) -join "`n")) }
        } finally { $process.Dispose() }
        foreach ($path in (Get-PlanCompanions (Join-Path $targetPlugins 'retain.jar'))) {
            Assert-Preview ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq $retained[$path]) 'Real installer overwrote a retained companion.'
        }
        Assert-Preview (Test-Path -LiteralPath (Join-Path $target 'fixture.txt')) 'Application payload was not copied.'
        Assert-Preview (-not (Test-Path -LiteralPath (Join-Path $targetPlugins 'old-upgrade.jar'))) 'Real installer did not reconcile approved plugin.'
        Assert-Preview ((Get-PluginPackageMetadata (Join-Path $targetPlugins 'upgrade.jar')).version -eq '2.0.0') 'Real installer did not install approved plugin.'
        Write-Host 'Production installer copy and reconciliation passed.'
    }
    Write-Host 'Installer preview behavior passed.'
} finally {
    # 此目录由本次测试创建，不含用户安装数据。
    if ([IO.Path]::GetFullPath($fixture).StartsWith([IO.Path]::GetFullPath((Join-Path $repo 'target/installer-preview-')), [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $fixture -Recurse -Force
    }
}
exit 0
