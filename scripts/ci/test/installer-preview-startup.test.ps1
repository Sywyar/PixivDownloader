param([Parameter(Mandatory = $true)][string]$CompilerPath, [string]$AppImageDirectory)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$fixture = Join-Path $repo 'target/installer-preview-startup'
$source = Join-Path $fixture 'source'
[IO.Directory]::CreateDirectory($source) | Out-Null
$utf8 = New-Object Text.UTF8Encoding($true)
function Write-Fixture([string]$Name, [string]$Content) {
    [IO.File]::WriteAllText((Join-Path $source $Name), $Content, $utf8)
}
function Read-Inno([string]$Name) {
    [IO.File]::ReadAllText((Join-Path $repo "packaging/windows/inno/$Name"), [Text.Encoding]::UTF8).Replace("`r`n", "`n")
}
. (Join-Path $repo 'scripts/plugin-distribution-common.ps1')
$sdk = Get-PixivDownloadSdkVersion -ProjectRoot $repo
Copy-Item -Path (Join-Path $repo 'packaging/windows/inno/*') -Destination $source -Recurse -Force
if (-not $AppImageDirectory) {
    $AppImageDirectory = Join-Path $fixture 'app-image'
    [IO.Directory]::CreateDirectory($AppImageDirectory) | Out-Null
    [IO.File]::WriteAllText((Join-Path $AppImageDirectory 'fixture.txt'), 'compile input', $utf8)
}
$signature = Join-Path $fixture 'signature.jar'
[IO.File]::WriteAllText($signature, 'compile input', $utf8)
$main = Read-Inno 'PixivDownload.iss'
$main = $main.Replace('..\..\..\scripts\', "$repo\scripts\").Replace('..\..\..\pixivdownload-app\', "$repo\pixivdownload-app\")
$main = [regex]::Replace($main, '(?m)^AppId=.*$', 'AppId=PixivDownloader-preview-startup-test')
$main = $main.Replace('PrivilegesRequired=admin', "PrivilegesRequired=lowest`nCreateAppDir=no`nUninstallable=no`nCreateUninstallRegKey=no")
$main = [regex]::Replace($main, '(?ms)^\[(?:Registry|Icons|Run|InstallDelete|UninstallDelete)\]\n.*?(?=^\[|\z)', '')
$main = [regex]::Replace($main, '(?m)^UsePreviousAppDir=.*$', 'UsePreviousAppDir=no')
$main = $main.Replace('[Code]', "[Code]`nprocedure TestTick; forward;`nprocedure TestBeforeHelper; forward;")
$main += "`n#include `"startup-test.iss.inc`"`n"
Write-Fixture 'PixivDownload.iss' $main
$maintenance = Read-Inno 'PixivDownload-maintenance.iss.inc'
$maintenance = [regex]::Replace($maintenance, '(?s)(procedure ResolveExistingInstallation;.*?begin\n)', '$1' + @'
  ExistingInstallationResolved := True;
  ExistingInstallationFound := ExpandConstant('{param:SCENARIO|fresh}') <> 'fresh';
  ExistingInstallDir := ExpandConstant('{param:DIR|}');
  ShowMaintenancePage := False;
  exit;
'@)
Write-Fixture 'PixivDownload-maintenance.iss.inc' $maintenance
$lifecycle = Read-Inno 'PixivDownload-lifecycle.iss.inc'
$lifecycle = $lifecycle.Replace('  InitializePluginPreview;', @'
  InitializePluginPreview;
  WizardForm.Position := poDesigned; WizardForm.Left := -10000;
  if not WizardSilent then PreviewSetTimer(PreviewTableHost.Handle, 2, 25, 0);
'@)
$lifecycle = $lifecycle.Replace("  if PreviewBusy then begin Cancel := False; Confirm := False; exit; end;", "  Cancel := True; Confirm := False; exit;")
$lifecycle = [regex]::Replace($lifecycle, '(?s)function PrepareToInstall\(var NeedsRestart: Boolean\): String;.*?\nend;', @'
function PrepareToInstall(var NeedsRestart: Boolean): String;
var Validation: String;
begin
  Result := 'Startup fixture stops before installation';
  if WizardSilent then
  begin
    Validation := ValidatePluginPreview;
    if (Validation = '') and PreviewLoaded and PreviewValidated and (not PreviewPending) then
      Log('PREVIEW_STARTUP_PASSED:silent');
  end;
end;
'@)
Write-Fixture 'PixivDownload-lifecycle.iss.inc' $lifecycle
$preview = Read-Inno 'PixivDownload-preview.iss.inc'
$preview = $preview.Replace('  Language := ActiveLanguage;', "  TestBeforeHelper;`n  Language := ActiveLanguage;")
Write-Fixture 'PixivDownload-preview.iss.inc' $preview
$table = Read-Inno 'PixivDownload-preview-table.iss.inc'
$table = $table.Replace('  if (Msg = $0113) and (WParam = 1) then', "  if (Msg = `$0113) and (WParam = 2) then begin TestTick; exit; end;`n  if (Msg = `$0113) and (WParam = 1) then")
Write-Fixture 'PixivDownload-preview-table.iss.inc' $table
Write-Fixture 'startup-test.iss.inc' @'
var TestCalls, TestStage, TestExpected: Integer; TestFinished: Boolean;
procedure CheckStartup(Value: Boolean; const Message: String);
begin if not Value then RaiseException(Message); end;

procedure TestBeforeHelper;
begin
  if WizardSilent then exit;
  TestCalls := TestCalls + 1;
  CheckStartup(WizardForm.Showing, 'Helper started before window display');
  CheckStartup(PreviewProgress.Showing and (PreviewProgress.Style = npbstMarquee), 'Loading progress not visible');
  CheckStartup(not WizardForm.NextButton.Enabled, 'Next enabled while checking');
  CheckStartup(not PreviewTableHost.Visible, 'Stale rows visible while checking');
  CheckStartup(not PreviewPending, 'Pending timer retained during helper execution');
  Log('STARTUP_HELPER: visible=1; progress=1; call=' + IntToStr(TestCalls));
  if (ExpandConstant('{param:SCENARIO|fresh}') = 'retry') and (TestCalls = 1) then
    RaiseException('Injected scan failure');
end;

procedure TestTick;
begin
  if TestFinished or PreviewBusy then exit;
  try
    if WizardForm.CurPageID = PluginPreviewPage.ID then
    begin
      if PreviewPending then exit;
      if (TestStage = 0) and (ExpandConstant('{param:SCENARIO|fresh}') = 'retry') then
      begin
        CheckStartup((not PreviewLoaded) and (not PreviewProgress.Visible), 'Failed scan kept loading');
        CheckStartup(PreviewEdit.Visible and PreviewEdit.Enabled, 'Retry unavailable');
        CheckStartup(not WizardForm.NextButton.Enabled, 'Failed scan allowed Next');
        TestStage := 1;
        PreviewEditClick(nil);
        exit;
      end;
      CheckStartup(PreviewLoaded and (not PreviewProgress.Visible), 'Preview did not finish');
      CheckStartup(PreviewTableHost.Visible and WizardForm.NextButton.Enabled, 'Loaded preview unavailable');
      if TestStage <= 1 then
      begin
        TestExpected := 1;
        if ExpandConstant('{param:SCENARIO|fresh}') = 'retry' then TestExpected := 2;
        CheckStartup(TestCalls = TestExpected, 'Initial scan repeated');
        SchedulePluginPreview;
        SendMessage(PreviewTableHost.Handle, $0113, 1, 0);
        CheckStartup(TestCalls = TestExpected, 'Cached preview or stale message rescanned');
        TestStage := 3;
        PostMessage(WizardForm.NextButton.Handle, $00F5, 0, 0);
      end
      else if TestStage = 4 then
      begin
        CheckStartup(TestCalls = TestExpected, 'Returning to preview rescanned');
        WizardForm.DirEdit.Text := WizardDirValue + '-changed';
        SchedulePluginPreview;
        CancelPendingPluginPreview;
        SendMessage(PreviewTableHost.Handle, $0113, 1, 0);
        CheckStartup(TestCalls = TestExpected, 'Cancelled pending scan ran');
        SchedulePluginPreview;
        SchedulePluginPreview;
        TestStage := 5;
      end
      else if TestStage = 5 then
      begin
        CheckStartup(TestCalls = TestExpected + 1, 'Directory change did not scan exactly once');
        Log('PREVIEW_STARTUP_PASSED:' + ExpandConstant('{param:SCENARIO|fresh}'));
        TestFinished := True;
        PostMessage(WizardForm.CancelButton.Handle, $00F5, 0, 0);
      end;
    end
    else if WizardForm.CurPageID = wpReady then
    begin
      CheckStartup(TestStage = 3, 'Unexpected ready page');
      TestStage := 4;
      PostMessage(WizardForm.BackButton.Handle, $00F5, 0, 0);
    end
    else PostMessage(WizardForm.NextButton.Handle, $00F5, 0, 0);
  except
    TestFinished := True;
    Log('PREVIEW_STARTUP_FAILED:' + GetExceptionMessage);
    PostMessage(WizardForm.CancelButton.Handle, $00F5, 0, 0);
  end;
end;

<event('DeinitializeSetup')>
procedure ReleaseStartupTest;
begin
  if Assigned(PreviewTableHost) then PreviewKillTimer(PreviewTableHost.Handle, 2);
end;
'@
& $CompilerPath '/Q' "/DAppImageDir=$AppImageDirectory" "/DOutputDir=$fixture" "/DSignatureToolJar=$signature" "/DSdkVersion=$sdk" (Join-Path $source 'PixivDownload.iss')
if ($LASTEXITCODE -ne 0) { throw "Startup fixture compilation failed: $LASTEXITCODE" }
$exe = Join-Path $fixture 'PixivDownload-0.0.1-local-win-x64-setup.exe'
foreach ($scenario in @('fresh', 'upgrade', 'retry', 'silent')) {
    $installDir = Join-Path $fixture ($scenario + '-install')
    if ($scenario -eq 'upgrade') {
        [IO.Directory]::CreateDirectory($installDir) | Out-Null
        $plugins = Join-Path $AppImageDirectory 'plugins'
        if (Test-Path -LiteralPath $plugins) { Copy-Item -LiteralPath $plugins -Destination $installDir -Recurse -Force }
    }
    $log = Join-Path $fixture ($scenario + '.log')
    $arguments = @('/LANG=en', '/NORESTART', '/SUPPRESSMSGBOXES', "/SCENARIO=$scenario", "/DIR=`"$installDir`"", "/LOG=`"$log`"")
    if ($scenario -eq 'silent') { $arguments += '/VERYSILENT' }
    $process = Start-Process -FilePath $exe -ArgumentList $arguments -WindowStyle Hidden -PassThru
    try {
        if (-not $process.WaitForExit(60000)) { throw "Startup fixture timed out: $scenario" }
        $content = [IO.File]::ReadAllText($log, [Text.Encoding]::UTF8)
        if ($content -notmatch "PREVIEW_STARTUP_PASSED:$scenario" -or $content -match 'PREVIEW_STARTUP_FAILED:|Starting the installation process') {
            throw "Startup fixture failed: $scenario ($log)"
        }
        if (Test-Path -LiteralPath (Join-Path $installDir 'fixture.txt')) { throw 'Fixture installed application files.' }
        Write-Host "PASS: startup $scenario"
        $content -split "`r?`n" | Where-Object { $_ -match 'Plugin preview helpers extracted:|Plugin preview loaded:' } | Write-Host
    } finally {
        if (-not $process.HasExited) { & taskkill.exe /PID $process.Id /T /F | Out-Null }
        $process.Dispose()
    }
}
$outputs = @('source', 'app-image', 'upgrade-install', 'signature.jar', 'PixivDownload-0.0.1-local-win-x64-setup.exe')
foreach ($output in $outputs) {
    $path = [IO.Path]::GetFullPath((Join-Path $fixture $output))
    if (-not $path.StartsWith($fixture + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unsafe startup fixture cleanup path.'
    }
    if (Test-Path -LiteralPath $path) {
        if (((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            @(Get-ChildItem -LiteralPath $path -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) {
            throw 'Startup fixture cleanup contains a reparse point.'
        }
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}
