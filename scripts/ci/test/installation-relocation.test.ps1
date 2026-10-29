[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$CompilerPath,
    [switch]$CompileOnly
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$fixture = Join-Path $repo ('build/installation-relocation-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($fixture) | Out-Null
$utf8 = [Text.UTF8Encoding]::new($true)
Copy-Item -LiteralPath (Join-Path $repo 'packaging/windows/inno/PixivDownload-relocation.iss.inc') -Destination $fixture
Copy-Item -LiteralPath (Join-Path $repo 'packaging/windows/inno/PixivDownload-relocation-messages.iss.inc') -Destination $fixture
[IO.File]::WriteAllText((Join-Path $fixture 'payload.txt'), 'installed', $utf8)
$source = @'
#define AppName "RelocationFixture-__ID__"
#define AppExeName "payload.txt"
[Setup]
AppId=__ID__
AppName={#AppName}
AppVersion=1.0
DefaultDirName={tmp}\unused-relocation-fixture
PrivilegesRequired=lowest
CreateUninstallRegKey=no
UsePreviousAppDir=no
DisableDirPage=yes
DisableProgramGroupPage=yes
OutputDir=.
OutputBaseFilename=installation-relocation
Compression=none
[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "zhcn"; MessagesFile: "compiler:Default.isl"
Name: "zhhant"; MessagesFile: "compiler:Default.isl"
Name: "ja"; MessagesFile: "compiler:Default.isl"
Name: "ko"; MessagesFile: "compiler:Default.isl"
[CustomMessages]
#include "PixivDownload-relocation-messages.iss.inc"
[Files]
Source: "payload.txt"; DestDir: "{app}"
[Code]
const
  AppRegistryKey = 'fixture';
  InnoUninstallRegistryKey = 'fixture';
var
  LegacyMsiProductCode: String;
function QueryMachineStringValue(const Key, Name: String; var Value: String): Boolean;
begin
  Value := ExpandConstant('{param:REGISTERED|}');
  Result := Value <> '';
end;
function Unquote(const Value: String): String;
begin
  Result := RemoveQuotes(Value);
end;
#include "PixivDownload-relocation.iss.inc"
function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := PrepareInstallationRelocation;
  if (Result = '') and (ExpandConstant('{param:INTERRUPT|0}') = '1') then
    Result := 'Fixture interrupted before installation';
end;
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then FinishInstallationRegistration;
end;
'@
$source = $source.Replace('__ID__', [Guid]::NewGuid().ToString('N'))
$sourcePath = Join-Path $fixture 'fixture.iss'
[IO.File]::WriteAllText($sourcePath, $source, $utf8)
$compilerArgs = @('/Q')
if ($CompileOnly) { $compilerArgs += '/O-' }
& $CompilerPath @compilerArgs $sourcePath
if ($LASTEXITCODE -ne 0) { throw "Relocation fixture compilation failed: $LASTEXITCODE" }
if ($CompileOnly) {
    Write-Output 'Compilation passed; no executable was produced or run.'
    exit 0
}

function Invoke-Fixture([string]$Executable, [string[]]$ExtraArgs, [string]$LogName, [switch]$ExpectFailure) {
    $arguments = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', ('/LOG="' + (Join-Path $fixture $LogName) + '"')) + $ExtraArgs
    $process = Start-Process -FilePath $Executable -ArgumentList $arguments -WindowStyle Hidden -PassThru
    try {
        $null = $process.Handle
        if (-not $process.WaitForExit(60000)) {
            Stop-Process -Id $process.Id
            throw 'Relocation fixture timed out'
        }
        if (($process.ExitCode -eq 0) -eq [bool]$ExpectFailure) { throw "Unexpected exit $($process.ExitCode): $LogName" }
    } finally { $process.Dispose() }
}

$installer = Join-Path $fixture 'installation-relocation.exe'
foreach ($scenario in @('relocated', 'interrupted')) {
    $oldDirectory = Join-Path $fixture ($scenario + '-A')
    $newDirectory = Join-Path $fixture ($scenario + '-B [new] ' + [char]0x4e2d)
    Invoke-Fixture $installer @(('/DIR="' + $oldDirectory + '"')) ($scenario + '-install.log')
    Copy-Item -LiteralPath $oldDirectory -Destination $newDirectory -Recurse
    $oldDigest = (Get-FileHash -LiteralPath (Join-Path $oldDirectory 'unins000.dat') -Algorithm SHA256).Hash
    $arguments = @(('/DIR="' + $newDirectory + '"'), ('/REGISTERED="' + $oldDirectory + '"'))
    Invoke-Fixture $installer $arguments ($scenario + '-unconfirmed.log') -ExpectFailure
    if ((Get-FileHash -LiteralPath (Join-Path $newDirectory 'unins000.dat') -Algorithm SHA256).Hash -ne $oldDigest) {
        throw 'Unconfirmed relocation changed the uninstall log'
    }
    if ($scenario -eq 'interrupted') {
        Invoke-Fixture $installer ($arguments + @('/RELOCATE=1', '/INTERRUPT=1')) ($scenario + '-interrupted.log') -ExpectFailure
        if (([IO.File]::ReadAllLines((Join-Path $newDirectory '.pixivdownload-installation'), $utf8))[0] -ne 'PixivDownload relocation 1') {
            throw 'Interrupted relocation did not retain its pending marker'
        }
    }
    Invoke-Fixture $installer ($arguments + '/RELOCATE=1') ($scenario + '-repair.log')
    $marker = [IO.File]::ReadAllLines((Join-Path $newDirectory '.pixivdownload-installation'), $utf8)
    if ($marker[0] -ne 'PixivDownload installation 1' -or $marker[1] -ne $newDirectory) { throw 'Installed marker is incorrect' }
    $backups = @(Get-ChildItem -LiteralPath (Join-Path $newDirectory 'state/installation-backups') -File -Recurse -Filter 'unins000.dat.bak')
    if (-not ($backups | Where-Object { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash -eq $oldDigest })) {
        throw 'Original uninstall records were not retained intact'
    }
    if ((Get-FileHash -LiteralPath (Join-Path $oldDirectory 'unins000.dat') -Algorithm SHA256).Hash -ne $oldDigest) {
        throw 'Relocation changed the previous installation'
    }
    $userFile = Join-Path $newDirectory 'state/user-data.txt'
    [IO.File]::WriteAllText($userFile, 'preserve', $utf8)
    Invoke-Fixture (Join-Path $newDirectory 'unins000.exe') @() ($scenario + '-uninstall.log')
    if (-not (Test-Path -LiteralPath (Join-Path $oldDirectory 'payload.txt')) -or
        (Test-Path -LiteralPath (Join-Path $newDirectory 'payload.txt')) -or
        -not (Test-Path -LiteralPath $userFile)) { throw 'Uninstall affected the wrong folder or user data' }
}
Write-Output "Relocation and interrupted-repair checks passed. Logs: $fixture"
