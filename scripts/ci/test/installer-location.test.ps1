param([Parameter(Mandatory = $true)][string]$CompilerPath)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$fixture = Join-Path $repo ('target/installer-location-' + [Guid]::NewGuid().ToString('N'))
$source = Join-Path $fixture 'source'
$image = Join-Path $fixture 'image'
$utf8 = New-Object Text.UTF8Encoding($true)
[IO.Directory]::CreateDirectory($source) | Out-Null
[IO.Directory]::CreateDirectory($image) | Out-Null
[IO.File]::WriteAllText((Join-Path $image 'fixture.txt'), 'compile input', $utf8)
$id = [Guid]::NewGuid().ToString().ToUpperInvariant()
$registryPath = "Software\Microsoft\Windows\CurrentVersion\Uninstall\{$id}_is1"
$registry = [Microsoft.Win32.RegistryKey]::OpenBaseKey('CurrentUser', 'Registry64')
function Write-Fixture([string]$Name, [string]$Content) { [IO.File]::WriteAllText((Join-Path $source $Name), $Content, $utf8) }
function Read-Inno([string]$Name) { [IO.File]::ReadAllText((Join-Path $repo "packaging/windows/inno/$Name"), [Text.Encoding]::UTF8).Replace("`r`n", "`n") }
try {
    Copy-Item -Path (Join-Path $repo 'packaging/windows/inno/*') -Destination $source -Recurse
    . (Join-Path $repo 'scripts/plugin-distribution-common.ps1')
    $sdk = Get-PixivDownloadSdkVersion -ProjectRoot $repo
    $main = (Read-Inno 'PixivDownload.iss').Replace('4D4F3566-C6C0-4D24-9242-86059B2A84A5', $id)
    $main = $main.Replace('..\..\..\scripts\', "$repo\scripts\").Replace('..\..\..\pixivdownload-app\', "$repo\pixivdownload-app\")
    $main = $main.Replace('PrivilegesRequired=admin', "PrivilegesRequired=lowest`nUninstallable=no`nCreateUninstallRegKey=no")
    $main = [regex]::Replace($main, '(?ms)^\[(?:Files|Registry|Icons|Run|InstallDelete|UninstallDelete)\]\n.*?(?=^\[|\z)', '')
    $main = $main.Replace("AppRegistryKey = 'Software\sywyar\PixivDownload'", "AppRegistryKey = '$registryPath'")
    $main = $main.Replace('[Code]', "[Code]`nprocedure FinishLocationTest; forward;`nprocedure AcceptDirectoryDialog; forward;`nfunction TestConfirm(const Text: String; Kind: TMsgBoxType; Buttons: Integer): Integer; forward;")
    $main += "`n#include `"location-test.iss.inc`"`n"
    Write-Fixture 'PixivDownload.iss' $main
    $maintenance = Read-Inno 'PixivDownload-maintenance.iss.inc'
    $maintenance = [regex]::Replace($maintenance, '(?s)function QueryMachineStringValue\(.*?\nend;', @'
function QueryMachineStringValue(const Key, Name: String; var Value: String): Boolean;
begin Result := RegQueryStringValue(HKCU64, Key, Name, Value); end;
'@)
    $maintenance = [regex]::Replace($maintenance, '(?s)function FindLegacyMsiProductCode\(.*?\nend;', @'
function FindLegacyMsiProductCode(var ProductCode: String): Boolean;
begin Result := False; end;
'@)
    $maintenance = $maintenance.Replace("MsgBox(FmtMessage(CustomMessage('InstallationLocationReinstall')", "TestConfirm(FmtMessage(CustomMessage('InstallationLocationReinstall')")
    Write-Fixture 'PixivDownload-maintenance.iss.inc' $maintenance
    $lifecycle = Read-Inno 'PixivDownload-lifecycle.iss.inc'
    $lifecycle = $lifecycle.Replace('  InitializePluginPreview;', "  InitializePluginPreview;`n  WizardForm.Position := poDesigned; WizardForm.Left := -10000;")
    $lifecycle = $lifecycle.Replace('  if PreviewBusy then begin Cancel := False; Confirm := False; exit; end;', '  Cancel := True; Confirm := False; exit;')
    $lifecycle = [regex]::Replace($lifecycle, '(?s)function PrepareToInstall\(var NeedsRestart: Boolean\): String;.*?\nend;', @'
function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := 'Location fixture stops before installation';
  FinishLocationTest;
  if not WizardSilent then PostMessage(WizardForm.CancelButton.Handle, 245, 0, 0);
end;
'@)
    Write-Fixture 'PixivDownload-lifecycle.iss.inc' $lifecycle
    $table = Read-Inno 'PixivDownload-preview-table.iss.inc'
    $table = $table.Replace('  if (Msg = $0113) and (WParam = 1) then', "  if (Msg = `$0113) and (WParam = 2) then begin AcceptDirectoryDialog; exit; end;`n  if (Msg = `$0113) and (WParam = 1) then")
    Write-Fixture 'PixivDownload-preview-table.iss.inc' $table
    Write-Fixture 'location-test.iss.inc' @'
var SawDirectory, Approve, TestFinished, DirectoryDialogAccepted: Boolean; ConfirmationCount: Integer;
function TestActivePopup(Window: Longword): Longword;
external 'GetLastActivePopup@user32.dll stdcall';
function TestDialogButton(Window: Longword; Id: Integer): Longword;
external 'GetDlgItem@user32.dll stdcall';
procedure AcceptDirectoryDialog;
var Popup, Button: Longword;
begin
  Popup := TestActivePopup(WizardForm.Handle);
  if Popup = WizardForm.Handle then exit;
  Button := TestDialogButton(Popup, IDYES);
  if Button = 0 then exit;
  PreviewKillTimer(PreviewTableHost.Handle, 2);
  DirectoryDialogAccepted := True;
  PostMessage(Button, $00F5, 0, 0);
end;
<event('DeinitializeSetup')>
procedure ReleaseLocationTest;
begin if Assigned(PreviewTableHost) then PreviewKillTimer(PreviewTableHost.Handle, 2); end;
function TestConfirm(const Text: String; Kind: TMsgBoxType; Buttons: Integer): Integer;
begin
  if (Buttons and MB_DEFBUTTON2) = 0 then RaiseException('Reinstall confirmation must default to No');
  ConfirmationCount := ConfirmationCount + 1;
  Result := IDNO;
  if Approve then Result := IDYES;
end;
procedure CheckLocation(Value: Boolean; const Message: String);
begin if not Value then RaiseException(Message); end;
procedure FinishLocationTest;
var Scenario: String;
begin
  if TestFinished then exit;
  Scenario := ExpandConstant('{param:CASE|}');
  CheckLocation(ConfirmInstallationDirectory(False) = '', 'Preinstall target was not confirmed');
  if not WizardSilent then
    CheckLocation(SawDirectory = ((Scenario <> 'healthy') and (Scenario <> 'explicit-moved')), 'Wrong directory page visibility');
  if Scenario = 'explicit-moved' then
    CheckLocation(SameInstallationDirectory(WizardDirValue, ExpandConstant('{param:DIR|}')), 'Explicit directory overwritten');
  if Scenario = 'choose-moved' then CheckLocation(DirectoryDialogAccepted, 'Native existing-folder prompt was not exercised');
  TestFinished := True;
  Log('LOCATION_PASSED');
end;
<event('ShouldSkipPage')>
function SkipUnrelatedPreview(PageID: Integer): Boolean;
begin Result := PageID = PluginPreviewPage.ID; end;
<event('NextButtonClick')>
function CheckSilentRefusal(PageID: Integer): Boolean;
begin
  Result := True;
  if WizardSilent and (PageID = wpSelectDir) and (ExpandConstant('{param:CASE|}') = 'silent-missing') then
  begin
    CheckLocation(ConfirmInstallationDirectory(False) <> '', 'Silent missing directory accepted');
    Log('LOCATION_REFUSED');
  end;
end;
<event('CurPageChanged')>
procedure CheckLocationPage(PageID: Integer);
var Scenario, Original: String;
begin
  if WizardSilent or TestFinished then exit;
  if (PageID = wpPreparing) or (PageID = wpInstalling) then exit;
  try
    Scenario := ExpandConstant('{param:CASE|}');
    if PageID = wpSelectDir then
    begin
      SawDirectory := True;
      if Scenario <> 'fresh' then
      begin
        CheckLocation(ExistingLocationMissing, 'Missing installation not detected');
        CheckLocation(WizardForm.SelectDirLabel.Caption = CustomMessage('InstallationLocationMissing'), 'Missing location explanation absent');
        CheckLocation(not NextButtonClick(PageID), 'Unconfirmed reinstall accepted');
        CheckLocation(ConfirmInstallationDirectory(False) <> '', 'Preinstall accepted unconfirmed directory');
        if Scenario = 'choose-moved' then
        begin
          WizardForm.DirEdit.Text := ExpandConstant('{param:MOVED|}');
          PreviewSetTimer(PreviewTableHost.Handle, 2, 25, 0);
        end
        else Approve := True;
        CheckLocation(NextButtonClick(PageID), 'Selected location rejected');
        CheckLocation(ConfirmInstallationDirectory(False) = '', 'Confirmed location rejected at preinstall');
        Original := WizardDirValue;
        WizardForm.DirEdit.Text := Original + '-other';
        CheckLocation(ConfirmInstallationDirectory(False) <> '', 'Confirmation leaked to another directory');
        WizardForm.DirEdit.Text := Original;
      end
      else CheckLocation((not ExistingLocationMissing) and NextButtonClick(PageID) and (ConfirmationCount = 0), 'Fresh installation prompted');
    end;
    if PageID = wpReady then
    begin
      FinishLocationTest;
      PostMessage(WizardForm.CancelButton.Handle, $00F5, 0, 0);
    end
    else PostMessage(WizardForm.NextButton.Handle, $00F5, 0, 0);
  except
    Log('LOCATION_FAILED:' + GetExceptionMessage);
    PostMessage(WizardForm.CancelButton.Handle, $00F5, 0, 0);
  end;
end;
'@
    & $CompilerPath '/Q' "/DAppImageDir=$image" "/DOutputDir=$fixture" "/DSignatureToolJar=$(Join-Path $image 'fixture.txt')" "/DSdkVersion=$sdk" (Join-Path $source 'PixivDownload.iss')
    if ($LASTEXITCODE -ne 0) { throw 'Location fixture compilation failed.' }
    $moved = Join-Path $fixture ('moved [new] ' + [char]0x4e2d)
    [IO.Directory]::CreateDirectory($moved) | Out-Null
    [IO.File]::WriteAllText((Join-Path $moved 'PixivDownload.exe'), 'presence fixture', $utf8)
    $scenarios = @('fresh', 'healthy', 'missing', 'empty', 'data-only', 'choose-moved', 'explicit-moved', 'silent-missing', 'silent-explicit') |
        ForEach-Object { @{ Case = $_; Language = 'en' } }
    $scenarios += @('zhcn', 'zhhant', 'ja', 'ko') | ForEach-Object { @{ Case = 'empty'; Language = $_ } }
    foreach ($scenario in $scenarios) {
        $case = $scenario.Case
        $language = $scenario.Language
        $registered = Join-Path $fixture $case
        if ($case -in @('healthy', 'empty', 'data-only')) { [IO.Directory]::CreateDirectory($registered) | Out-Null }
        if ($case -eq 'healthy') { [IO.File]::WriteAllText((Join-Path $registered 'PixivDownload.exe'), 'presence fixture', $utf8) }
        if ($case -eq 'data-only') { [IO.File]::WriteAllText((Join-Path $registered 'config.txt'), 'preserve', $utf8) }
        if ($case -ne 'fresh') {
            $key = $registry.CreateSubKey($registryPath)
            try {
                $key.SetValue('InstallLocation', $registered)
                $key.SetValue('Inno Setup: App Path', $registered)
                $key.SetValue('UninstallString', '"' + (Join-Path $registered 'unins000.exe') + '"')
                $key.SetValue('DisplayVersion', '0.0.0')
            } finally { $key.Dispose() }
        }
        $log = Join-Path $fixture ($case + '-' + $language + '.log')
        $arguments = @("/LANG=$language", '/SUPPRESSMSGBOXES', '/NORESTART', "/CASE=$case", "/MOVED=`"$moved`"", "/LOG=`"$log`"")
        if ($case -eq 'fresh') { $arguments += "/DIR=`"$registered`"" }
        if ($case -eq 'explicit-moved') { $arguments += "/DIR=`"$moved`"" }
        if ($case -like 'silent-*') { $arguments += '/VERYSILENT' }
        if ($case -eq 'silent-explicit') { $arguments += "/DIR=`"$registered`"" }
        $process = Start-Process -FilePath (Join-Path $fixture 'PixivDownload-0.0.1-local-win-x64-setup.exe') -ArgumentList $arguments -WindowStyle Hidden -PassThru
        try {
            if (-not $process.WaitForExit(30000)) { throw "Location fixture timed out: $case" }
            $text = [IO.File]::ReadAllText($log, [Text.Encoding]::UTF8)
            $expected = if ($case -eq 'silent-missing') { 'LOCATION_REFUSED' } else { 'LOCATION_PASSED' }
            if ($text -notmatch $expected -or $text -match 'LOCATION_FAILED:|Starting the installation process') { throw "Location fixture failed: $case ($log)" }
            if (Test-Path -LiteralPath (Join-Path $registered 'fixture.txt')) { throw 'Location fixture wrote application files.' }
            if ($case -eq 'data-only' -and [IO.File]::ReadAllText((Join-Path $registered 'config.txt')) -ne 'preserve') { throw 'Existing data changed.' }
            Write-Host "PASS: installation location $case ($language)"
        } finally {
            if (-not $process.HasExited) { & taskkill.exe /PID $process.Id /T /F | Out-Null }
            $process.Dispose()
            $registry.DeleteSubKeyTree($registryPath, $false)
        }
    }
} finally {
    $registry.DeleteSubKeyTree($registryPath, $false)
    $registry.Dispose()
}
