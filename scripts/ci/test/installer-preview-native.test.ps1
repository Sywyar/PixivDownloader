param([Parameter(Mandatory = $true)][string]$CompilerPath, [switch]$CaptureImages,
    [string[]]$Languages = @('en', 'zhcn', 'zhhant', 'ja', 'ko'))
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$fixture = Join-Path $repo 'target/installer-preview-native'
[IO.Directory]::CreateDirectory($fixture) | Out-Null
$utf8 = New-Object Text.UTF8Encoding($true)
foreach ($file in @('PixivDownload-preview.iss.inc', 'PixivDownload-preview-table.iss.inc', 'PixivDownload-preview-messages.iss.inc')) {
    Copy-Item -LiteralPath (Join-Path $repo "packaging/windows/inno/$file") -Destination $fixture
}
. (Join-Path $repo 'scripts/plugin-distribution-common.ps1')
$sdk = Get-PixivDownloadSdkVersion -ProjectRoot $repo
$program = @'
#define SdkVersion "__SDK__"
#define AppVersion "1.0.0"
#define AppImageDir "."
[Setup]
AppName=Plugin preview verification
AppVersion=1.0
DefaultDirName={tmp}\unused-preview-fixture
CreateAppDir=no
Uninstallable=no
CreateUninstallRegKey=no
PrivilegesRequired=lowest
DisableStartupPrompt=yes
WizardStyle=modern
WizardSizePercent=130,120
OutputDir=.
OutputBaseFilename=preview-test
Compression=none
[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "zhcn"; MessagesFile: "compiler:Default.isl"
Name: "zhhant"; MessagesFile: "compiler:Default.isl"
Name: "ja"; MessagesFile: "compiler:Default.isl"
Name: "ko"; MessagesFile: "compiler:Default.isl"
[CustomMessages]
#include "PixivDownload-preview-messages.iss.inc"
[Code]
type TInstallerProgressConsumer = procedure(const Line: String);
type TPreviewMessage = record
  Window, Msg, WParam, LParam, Time: Longword; X, Y: Integer;
end;
function PreviewPeek(var Msg: TPreviewMessage; Window, Min, Max, Remove: Longword): Boolean;
external 'PeekMessageW@user32.dll stdcall';
function PreviewDispatch(var Msg: TPreviewMessage): Longint;
external 'DispatchMessageW@user32.dll stdcall';
function PreviewShowWindow(Window: Longword; Command: Integer): Boolean;
external 'ShowWindow@user32.dll stdcall';
function PreviewGetFocus: Longword;
external 'GetFocus@user32.dll stdcall';
procedure PumpPreview;
var Msg: TPreviewMessage;
begin while PreviewPeek(Msg, 0, 0, 0, 1) do PreviewDispatch(Msg); end;
var OptionalPluginsPage: TWizardPage;
function QuoteArg(const Value: String): String;
begin Result := '"' + Value + '"'; end;
function SameInstallationDirectory(const Left, Right: String): Boolean;
begin Result := CompareText(Left, Right) = 0; end;
procedure RunInstallerHelper(const FileName, Parameters, ProgressPath, FailureMessage: String;
  OnProgress: TInstallerProgressConsumer);
begin RaiseException('Unexpected helper invocation during UI test'); end;
#include "PixivDownload-preview.iss.inc"
function ShouldInstallApplicationFiles: Boolean;
begin Result := True; end;
function PreviewRectMessage(Window, Msg, Param: Longword; var Bounds: TRect): Longint;
external 'SendMessageW@user32.dll stdcall';
function PreviewTextMessage(Window, Msg, Param: Longword; Text: String): Longint;
external 'SendMessageW@user32.dll stdcall';

function CellText(Row, Column: Integer): String;
var Item: TPreviewListItem; Count: Integer;
begin
  Item.SubItem := Column; Item.Text := StringOfChar(#0, 1024); Item.TextMax := 1024;
  Count := PreviewItemMessage(PreviewTable, $1073, Row, Item);
  Result := Copy(Item.Text, 1, Count);
end;

procedure Check(Value: Boolean; const Message: String);
begin if not Value then RaiseException(Message); end;

procedure FocusRow(Row: Integer);
var Item: TPreviewListItem;
begin
  Item.StateMask := 3; Item.State := 3;
  PreviewItemMessage(PreviewTable, $102B, Row, Item);
end;

procedure ClickRow(Row: Integer; Checkbox: Boolean);
var Bounds: TRect; Hit: TPreviewHit; X, Point: Integer;
begin
  Bounds.Left := 2;
  Check(PreviewRectMessage(PreviewTable, $100E, Row, Bounds) <> 0, 'Missing row bounds');
  Hit.Y := (Bounds.Top + Bounds.Bottom) div 2;
  if Checkbox then
  begin
    for X := 1 to Bounds.Left do begin
      Hit.X := X; PreviewHitMessage(PreviewTable, $1012, 0, Hit);
      if (Hit.Flags and 8) <> 0 then break;
    end;
    Check((Hit.Flags and 8) <> 0, 'Checkbox hit area missing');
  end else Hit.X := Bounds.Left + ScaleX(5);
  Point := Hit.X or (Hit.Y shl 16);
  PostMessage(PreviewTable, $0201, 1, Point);
  PostMessage(PreviewTable, $0202, 0, Point);
  PumpPreview;
end;

procedure InitializeWizard;
var I: Integer;
begin
  OptionalPluginsPage := CreateCustomPage(wpSelectDir, 'Fixture', '');
  InitializePluginPreview;
  SetArrayLength(PreviewRows, 5);
  for I := 0 to 4 do begin
    PreviewRows[I].Name := 'Plugin ' + IntToStr(I);
    PreviewRows[I].CurrentVersion := '3.0.0'; PreviewRows[I].BundledVersion := '2.0.0';
    PreviewRows[I].Reason := 'newer'; PreviewRows[I].Operation := 'downgrade';
    PreviewRows[I].Selectable := True; PreviewRows[I].Compatible := True;
  end;
  PreviewRows[0].Name := 'Database support';
  PreviewRows[0].Required := True; PreviewRows[0].Compatible := False; PreviewRows[0].Reason := 'incompatible';
  PreviewRows[2].Source := 'LOCAL_UPLOAD'; PreviewRows[2].Reason := 'source';
  PreviewRows[3].Reason := 'incompatible'; PreviewRows[3].Compatible := False;
  PreviewRows[3].Name := 'PixivBatchDownloader import support with a long display name';
  PreviewRows[3].CurrentVersion := '1.0.0-rc.3';
  PreviewRows[3].Selectable := False; PreviewRows[3].BundledVersion := '';
  PreviewRows[4].Reason := ''; PreviewRows[4].Selected := True; PreviewRows[4].Selectable := False;
  PreviewRows[4].DefaultSelected := True;
  PreviewLoaded := True; PreviewDirectory := WizardDirValue;
  RenderPluginPreview;
  Log('PREVIEW_CHECK:default');
end;

procedure CheckPreview;
var Row, Column: Integer; Value: String;
begin
  WizardForm.InnerNotebook.ActivePage := PluginPreviewPage.Surface;
  WizardForm.Left := -10000; WizardForm.Visible := True;
  PreviewShowWindow(WizardForm.Handle, 4);
  RenderPluginPreview;
  WizardForm.ActiveControl := PreviewEdit;
  PostMessage(WizardForm.Handle, $B005, 9, 0);
  PumpPreview;
  Check(PreviewGetFocus = PreviewTable, 'Tab did not reach the native table');
  PostMessage(WizardForm.Handle, $B005, 9, 0); PumpPreview;
  Check(PreviewGetFocus = PreviewOther.Handle, 'Tab did not leave the native table');
  Check(SendMessage(PreviewTable, $1004, 0, 0) = 4, 'Normal rows not collapsed');
  Check(SendMessage(SendMessage(PreviewTable, $101F, 0, 0), $1200, 0, 0) = 5, 'Preview column missing');
  for Row := 0 to 3 do begin
    Check(CellText(Row, 0) = PreviewRows[Row].Name, 'Reason leaked into plugin name');
    Check(CellText(Row, 3) <> '', 'Attention row has no separate reason');
    Check(CellText(Row, 4) = PreviewOutcome(Row), 'Action column lost its outcome');
    for Column := 1 to 4 do begin
      Value := CellText(Row, Column);
      Check(SendMessage(PreviewTable, $101D, Column, 0) >=
        PreviewTextMessage(PreviewTable, $1057, 0, Value) + ScaleX(4), 'Preview value is clipped');
    end;
  end;
  Check(not WizardForm.NextButton.Enabled, 'Required incompatibility did not block Next');
  Check(SendMessage(PreviewTable, $102C, 0, $F000) = 0, 'Preview exposes a checkbox');
  SendMessage(PreviewEdit.Handle, 245, 0, 0);
  Log('PREVIEW_CHECK:editing');
  Check(SendMessage(PreviewTable, $102C, 0, $F000) = $1000, 'Special row checkbox missing');
  ClickRow(0, True);
  Log('PREVIEW_CHECK:checkbox');
  Check(PreviewRows[0].Selected, 'Checkbox did not toggle exactly once');
  ClickRow(0, False);
  Log('PREVIEW_CHECK:label');
  Check(not PreviewRows[0].Selected, 'Label did not toggle exactly once');
  FocusRow(0);
  SendMessage(PreviewTable, $0100, 32, 0); SendMessage(PreviewTable, $0101, 32, 0);
  Check(PreviewRows[0].Selected, 'Space did not toggle exactly once');
  SendMessage(PreviewOther.Handle, 245, 0, 0);
  Check(SendMessage(PreviewTable, $1004, 0, 0) = 5, 'Normal group did not expand');
  FocusRow(4);
  SendMessage(PreviewTable, $0100, 32, 0);
  Check(PreviewRows[4].Selected and (SendMessage(PreviewTable, $102C, 4, $F000) = 0), 'Regular row became editable');
  SendMessage(PreviewCancelEdit.Handle, 245, 0, 0);
  Check(not PreviewRows[0].Selected, 'Cancel did not restore selection');
  Check(PreviewExpanded and (SendMessage(PreviewTable, $1004, 0, 0) = 5), 'Editing reset disclosure');
  SendMessage(PreviewEdit.Handle, 245, 0, 0); ClickRow(0, True); SendMessage(PreviewEdit.Handle, 245, 0, 0);
  Check(WizardForm.NextButton.Enabled, 'Resolved required plugin still blocks Next');
  Check(SendMessage(PreviewTable, $102C, 0, $F000) = 0, 'Done left checkbox visible');
  SendMessage(PreviewEdit.Handle, 245, 0, 0); SendMessage(PreviewDefaults.Handle, 245, 0, 0);
  Check(not PreviewRows[0].Selected, 'Default selection not restored');
  Log('PREVIEW_NATIVE_PASSED:' + ActiveLanguage);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var I: Integer; Capture: String;
begin
  if CurStep <> ssInstall then exit;
  try CheckPreview; except Log('PREVIEW_NATIVE_FAILED:' + GetExceptionMessage); end;
  Capture := ExpandConstant('{param:CAPTURE|}');
  if Capture <> '' then begin
    PreviewEditing := False; PreviewExpanded := False; RenderPluginPreview;
    WizardForm.InnerNotebook.ActivePage := PluginPreviewPage.Surface;
    WizardForm.PageNameLabel.Caption := CustomMessage('PreviewTitle');
    WizardForm.PageDescriptionLabel.Caption := 'SDK {#SdkVersion}';
    WizardForm.Left := -10000;
    WizardForm.Visible := True;
    PreviewShowWindow(WizardForm.Handle, 4);
    PumpPreview;
    Log('CAPTURE_HANDLE:' + IntToStr(WizardForm.Handle));
    for I := 1 to 200 do begin
      PumpPreview; Sleep(100);
      if FileExists(Capture) then break;
    end;
  end;
  Abort;
end;
'@
$source = Join-Path $fixture 'preview-test.iss'
[IO.File]::WriteAllText($source, $program.Replace('__SDK__', $sdk), $utf8)
& $CompilerPath '/Q' $source
if ($LASTEXITCODE -ne 0) { throw 'Native preview fixture compilation failed.' }
foreach ($locale in $Languages) {
    $log = Join-Path $fixture "$locale.log"
    Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
    $arguments = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/LANG=$locale", ('/LOG="' + $log + '"'))
    $captureSignal = Join-Path $fixture "$locale.capture-done"
    if ($CaptureImages) {
        Remove-Item -LiteralPath $captureSignal -Force -ErrorAction SilentlyContinue
        $arguments += '/CAPTURE="' + $captureSignal + '"'
    }
    $process = Start-Process -FilePath (Join-Path $fixture 'preview-test.exe') -ArgumentList $arguments -WindowStyle Hidden -PassThru
    $null = $process.Handle
    try {
        if ($CaptureImages) {
            Add-Type -AssemblyName System.Drawing
            if (-not ('PreviewCapture' -as [type])) {
                Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class PreviewCapture {
  [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window, out Rect rect);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr window, IntPtr dc, uint flags);
  [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
}
'@
            }
            for ($attempt = 0; $attempt -lt 100; $attempt++) {
                if (Test-Path -LiteralPath $log) {
                    $stream = [IO.File]::Open($log, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
                    $reader = New-Object IO.StreamReader($stream)
                    try { $captureLog = $reader.ReadToEnd() } finally { $reader.Dispose() }
                    if ($captureLog -match 'CAPTURE_HANDLE:(\d+)') { break }
                }
                Start-Sleep -Milliseconds 100
            }
            if ($attempt -eq 100) { throw 'Native preview capture was not ready.' }
            $window = [IntPtr][long]$Matches[1]
            $oldDpi = [PreviewCapture]::SetThreadDpiAwarenessContext([IntPtr](-4))
            $bounds = New-Object PreviewCapture+Rect
            [void][PreviewCapture]::GetWindowRect($window, [ref]$bounds)
            $bitmap = New-Object Drawing.Bitmap(($bounds.Right-$bounds.Left), ($bounds.Bottom-$bounds.Top))
            $graphics = [Drawing.Graphics]::FromImage($bitmap)
            $dc = $graphics.GetHdc()
            try { [void][PreviewCapture]::PrintWindow($window, $dc, 0) } finally { $graphics.ReleaseHdc($dc) }
            $bitmap.Save((Join-Path $fixture "$locale.png"))
            $graphics.Dispose(); $bitmap.Dispose()
            [void][PreviewCapture]::SetThreadDpiAwarenessContext($oldDpi)
            [IO.File]::WriteAllText($captureSignal, 'done')
        }
        if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Native preview test did not finish.' }
        $result = [IO.File]::ReadAllText($log)
        if ($process.ExitCode -ne 3 -or $result -notmatch "PREVIEW_NATIVE_PASSED:$locale" -or $result -match 'PREVIEW_NATIVE_FAILED:') { throw $result }
        Write-Host "Native plugin preview passed: $locale"
    } finally { $process.Dispose() }
}
