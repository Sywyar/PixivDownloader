[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InstallerPath,
    [Parameter(Mandatory = $true)][string]$TestRoot,
    [switch]$ConfirmIsolatedMachine
)

$ErrorActionPreference = 'Stop'
if (-not $ConfirmIsolatedMachine) { throw 'Run this test only in a disposable Windows VM with -ConfirmIsolatedMachine.' }
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Administrator required.' }
$InstallerPath = (Resolve-Path -LiteralPath $InstallerPath).Path
$TestRoot = [IO.Path]::GetFullPath($TestRoot)
if (Test-Path -LiteralPath $TestRoot) { throw 'TestRoot must not exist.' }
$machine = [Microsoft.Win32.RegistryKey]::OpenBaseKey('LocalMachine', 'Registry64')
$applicationKey = 'Software\sywyar\PixivDownload'
$uninstallKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{4D4F3566-C6C0-4D24-9242-86059B2A84A5}_is1'
$shell = New-Object -ComObject Shell.Application
$links = @(
    (Join-Path ([Environment]::GetFolderPath('CommonPrograms')) 'PixivDownload.lnk'),
    (Join-Path ([Environment]::GetFolderPath('CommonDesktopDirectory')) 'PixivDownload.lnk')
)
$startup = Join-Path ([Environment]::GetFolderPath('Startup')) 'PixivDownload.lnk'
$utf8 = [Text.UTF8Encoding]::new($false)

function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Read-Registration([string]$Key, [string]$Name) {
    $entry = $machine.OpenSubKey($Key)
    try { if ($null -ne $entry) { return $entry.GetValue($Name) } } finally { if ($entry) { $entry.Dispose() } }
}
function Assert-Registration([string]$Directory) {
    Assert-True ((Read-Registration $applicationKey 'InstallLocation') -eq $Directory) 'Custom install location differs.'
    Assert-True ((Read-Registration $applicationKey 'installed') -eq 1) 'Installed flag differs.'
    Assert-True ((Read-Registration $uninstallKey 'InstallLocation').TrimEnd('\') -eq $Directory) 'Uninstall install location differs.'
    foreach ($name in @('UninstallString', 'QuietUninstallString')) {
        $value = Read-Registration $uninstallKey $name
        Assert-True ($value.StartsWith('"' + $Directory + '\unins000.exe"')) "$name points outside installation."
    }
    Assert-True ((Read-Registration $uninstallKey 'DisplayIcon') -eq ($Directory + '\PixivDownload.exe')) 'Display icon differs.'
    foreach ($path in $links) { Assert-Shortcut $path $Directory '' }
    $marker = [IO.File]::ReadAllLines((Join-Path $Directory '.pixivdownload-installation'), $utf8)
    Assert-True ($marker.Count -eq 2 -and $marker[0] -eq 'PixivDownload installation 1' -and $marker[1] -eq $Directory) 'Installation marker differs.'
}
function Assert-Shortcut([string]$Path, [string]$Directory, [string]$Arguments) {
    Assert-True (Test-Path -LiteralPath $Path) "Missing shortcut: $Path"
    $shortcut = $shell.Namespace((Split-Path -Parent $Path)).ParseName((Split-Path -Leaf $Path)).GetLink
    Assert-True ($shortcut.Path -eq ($Directory + '\PixivDownload.exe')) "Shortcut target differs: $Path"
    Assert-True ($shortcut.WorkingDirectory -eq $Directory) "Shortcut working directory differs: $Path"
    Assert-True ($shortcut.Arguments -eq $Arguments) "Shortcut arguments differ: $Path"
    $icon = ''
    $iconIndex = $shortcut.GetIconLocation([ref]$icon)
    Assert-True ($icon -eq ($Directory + '\PixivDownload.exe') -and $iconIndex -eq 0) "Shortcut icon differs: $Path"
}
function Invoke-Installer([string]$Executable, [string[]]$Extra, [string]$Label, [switch]$Reject) {
    $arguments = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', ('/LOG="' + $TestRoot + '\' + $Label + '.log"')) + $Extra
    $process = Start-Process -FilePath $Executable -ArgumentList $arguments -WindowStyle Hidden -PassThru
    try {
        $null = $process.Handle
        if (-not $process.WaitForExit(600000)) { Stop-Process -Id $process.Id -Force; throw "Installer timed out: $Label" }
        Assert-True (($process.ExitCode -eq 0) -ne [bool]$Reject) "Unexpected exit $($process.ExitCode): $Label"
    } finally { $process.Dispose() }
}
function File-Inventory([string]$Directory) {
    $inventory = @{}
    Get-ChildItem -LiteralPath $Directory -File -Recurse -Force | ForEach-Object {
        $inventory[$_.FullName.Substring($Directory.Length)] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    }
    return $inventory
}
function Assert-Unchanged([string]$Directory, $Expected) {
    $actual = File-Inventory $Directory
    Assert-True ($actual.Count -eq $Expected.Count) 'Original installation file count changed.'
    foreach ($key in $Expected.Keys) { Assert-True ($actual[$key] -eq $Expected[$key]) "Original installation changed: $key" }
}

try {
    Assert-True ($null -eq (Read-Registration $applicationKey 'InstallLocation')) 'Existing installation detected.'
    Assert-True ($null -eq (Read-Registration $uninstallKey 'UninstallString')) 'Existing uninstaller detected.'
    foreach ($path in @($links) + $startup) { Assert-True (-not (Test-Path -LiteralPath $path)) "Existing shortcut: $path" }
    [IO.Directory]::CreateDirectory($TestRoot) | Out-Null
    foreach ($enabled in @($false, $true)) {
        $label = if ($enabled) { 'startup-on' } else { 'startup-off' }
        $old = Join-Path $TestRoot ($label + '-A ' + [char]0x4e2d)
        $target = Join-Path $TestRoot ($label + '-B [moved] ' + [char]0x4e2d)
        Invoke-Installer $InstallerPath @(('/DIR="' + $old + '"')) ($label + '-install')
        Assert-Registration $old
        Assert-True (-not (Test-Path -LiteralPath $startup)) 'Fresh installation enabled startup.'
        [IO.Directory]::CreateDirectory((Join-Path $old 'state')) | Out-Null
        [IO.File]::WriteAllText((Join-Path $old 'state/user-data.txt'), 'preserve user state', $utf8)
        [IO.File]::WriteAllText((Join-Path $old 'plugins/third-party-marker.txt'), 'preserve external plugin', $utf8)
        $arguments = '--no-gui --server.port=18181'
        if ($enabled) {
            Copy-Item -LiteralPath $links[0] -Destination $startup
            $shortcut = $shell.Namespace((Split-Path -Parent $startup)).ParseName((Split-Path -Leaf $startup)).GetLink
            $shortcut.Arguments = $arguments
            $shortcut.Save($startup)
        }
        $before = File-Inventory $old
        Copy-Item -LiteralPath $old -Destination $target -Recurse
        $destination = @('/DIR="' + $target + '"')
        Invoke-Installer $InstallerPath $destination ($label + '-unconfirmed') -Reject
        Assert-Registration $old
        Assert-Unchanged $old $before
        Assert-Unchanged $target $before
        Invoke-Installer $InstallerPath ($destination + '/RELOCATE=1') ($label + '-relocate')
        Assert-Registration $target
        Assert-Unchanged $old $before
        if ($enabled) { Assert-Shortcut $startup $target $arguments }
        else { Assert-True (-not (Test-Path -LiteralPath $startup)) 'Relocation enabled startup.' }
        $backups = @(Get-ChildItem -LiteralPath (Join-Path $target 'state/installation-backups') -File -Recurse -Filter 'unins000.dat.bak')
        Assert-True ($backups.Count -eq 1) 'Uninstall backup count differs.'
        Assert-True ((Get-FileHash -LiteralPath $backups[0].FullName -Algorithm SHA256).Hash -eq $before['\unins000.dat']) 'Old uninstall backup changed.'
        Invoke-Installer $InstallerPath $destination ($label + '-repair')
        Assert-Registration $target
        if ($enabled) { Assert-Shortcut $startup $target $arguments }
        else {
            Copy-Item -LiteralPath $links[0] -Destination $startup
            $shortcut = $shell.Namespace((Split-Path -Parent $startup)).ParseName((Split-Path -Leaf $startup)).GetLink
            $shortcut.Path = $old + '\PixivDownload.exe'
            $shortcut.WorkingDirectory = $old
            $shortcut.SetIconLocation(($old + '\PixivDownload.exe'), 0)
            $shortcut.Save($startup)
        }
        Invoke-Installer (Join-Path $target 'unins000.exe') @() ($label + '-uninstall')
        Assert-Unchanged $old $before
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $target 'PixivDownload.exe'))) 'New payload remains after uninstall.'
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $target '.pixivdownload-installation'))) 'Installation marker remains after uninstall.'
        foreach ($relative in @('state/user-data.txt', 'plugins/third-party-marker.txt')) {
            Assert-True (Test-Path -LiteralPath (Join-Path $target $relative)) "User data removed: $relative"
        }
        Assert-True (Test-Path -LiteralPath $backups[0].FullName) 'Uninstall backup removed.'
        Assert-True ($null -eq (Read-Registration $applicationKey 'InstallLocation')) 'Custom registration remains after uninstall.'
        Assert-True ($null -eq (Read-Registration $uninstallKey 'UninstallString')) 'Uninstall registration remains after uninstall.'
        foreach ($path in $links) { Assert-True (-not (Test-Path -LiteralPath $path)) "Shortcut remains after uninstall: $path" }
        if ($enabled) { Assert-True (-not (Test-Path -LiteralPath $startup)) 'Owned startup shortcut remains after uninstall.' }
        else { Assert-Shortcut $startup $old ''; Remove-Item -LiteralPath $startup }
        Write-Output "PASS: $label install, refusal, relocation, repair, registry, shortcuts, backups and uninstall"
    }
} finally { $machine.Dispose() }
