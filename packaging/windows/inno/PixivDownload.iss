#define AppName "PixivDownload"
#define AppPublisher "sywyar"
#define AppExeName "PixivDownload.exe"
#define FfmpegArchiveUrl "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip"

#ifndef AppVersion
#define AppVersion "0.0.1-local"
#endif

#ifndef InstallerVersion
#define InstallerVersion "0.0.1"
#endif

#ifndef AppImageDir
#define AppImageDir "..\..\..\build\app-image-online\PixivDownload"
#endif

#ifndef OutputDir
#define OutputDir "..\..\..\build\out"
#endif

[Setup]
AppId={{4D4F3566-C6C0-4D24-9242-86059B2A84A5}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={code:GetDefaultInstallDir}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename={#AppName}-{#AppVersion}-win-x64-setup
SetupIconFile=..\..\..\src\main\resources\static\favicon.ico
UninstallDisplayIcon={app}\{#AppExeName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
DisablePrecompiledFileVerifications=setupldr
UsePreviousAppDir=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
ArchiveExtraction=full
VersionInfoCompany={#AppPublisher}
VersionInfoDescription={#AppName} Setup
VersionInfoProductName={#AppName}
VersionInfoProductVersion={#InstallerVersion}
VersionInfoVersion={#InstallerVersion}

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "zhcn"; MessagesFile: "compiler:Default.isl,ChineseSimplified.isl"

[CustomMessages]
en.OptionalTasksGroup=Optional setup tasks:
zhcn.OptionalTasksGroup=可选安装任务：
en.TaskDownloadFfmpeg=Download and install FFmpeg after PixivDownload is installed
zhcn.TaskDownloadFfmpeg=安装 PixivDownload 后下载并安装 FFmpeg
en.FfmpegWaiting=FFmpeg download is waiting for application installation to finish.
zhcn.FfmpegWaiting=FFmpeg 下载正在等待应用安装完成。
en.FfmpegDownloading=Downloading FFmpeg...
zhcn.FfmpegDownloading=正在下载 FFmpeg...
en.FfmpegProxyDetected=Using system proxy
zhcn.FfmpegProxyDetected=使用系统代理
en.FfmpegDirectDownload=No enabled system proxy was found; downloading directly.
zhcn.FfmpegDirectDownload=未检测到已启用且有效的系统代理，正在直连下载。
en.FfmpegExtracting=Extracting FFmpeg...
zhcn.FfmpegExtracting=正在解压 FFmpeg...
en.FfmpegInstallingFiles=Installing FFmpeg files...
zhcn.FfmpegInstallingFiles=正在安装 FFmpeg 文件...
en.FfmpegCompleted=FFmpeg has been installed.
zhcn.FfmpegCompleted=FFmpeg 已安装完成。
en.FfmpegFailed=FFmpeg installation failed. PixivDownload was installed, and you can retry from the Status page later.
zhcn.FfmpegFailed=FFmpeg 安装失败。PixivDownload 已安装，稍后可在“状态”页重试。
en.FfmpegArchiveInvalid=The FFmpeg archive did not contain ffmpeg.exe or ffprobe.exe.
zhcn.FfmpegArchiveInvalid=FFmpeg 压缩包中未找到 ffmpeg.exe 或 ffprobe.exe。
en.FfmpegCopyFailed=Could not copy FFmpeg files to the application tools directory.
zhcn.FfmpegCopyFailed=无法将 FFmpeg 文件复制到应用工具目录。
en.FfmpegLicenseWriteFailed=Could not write the FFmpeg license notice.
zhcn.FfmpegLicenseWriteFailed=无法写入 FFmpeg 许可证说明。
en.FfmpegFinishedSuccess=FFmpeg was downloaded and installed during setup.
zhcn.FfmpegFinishedSuccess=FFmpeg 已在安装过程中下载并安装。
en.FfmpegFinishedFailed=FFmpeg was not installed during setup. Open the Status page in PixivDownload to retry.
zhcn.FfmpegFinishedFailed=FFmpeg 未能在安装过程中安装。可打开 PixivDownload 的“状态”页重试。
en.MaintenanceTitle=PixivDownload is already installed
zhcn.MaintenanceTitle=PixivDownload 已安装
en.MaintenanceDescription=Choose an operation for the existing installation.
zhcn.MaintenanceDescription=请选择要对现有安装执行的操作。
en.MaintenanceRepairButton=&Repair
zhcn.MaintenanceRepairButton=修复(&R)
en.MaintenanceRepairHint=Reinstall PixivDownload files in the current installation folder.
zhcn.MaintenanceRepairHint=在当前安装目录重新安装 PixivDownload 文件。
en.MaintenanceChangeButton=&Change
zhcn.MaintenanceChangeButton=更改(&C)
en.MaintenanceChangeHint=Change optional setup tasks, such as downloading FFmpeg.
zhcn.MaintenanceChangeHint=更改可选安装任务，例如下载 FFmpeg。
en.MaintenanceUninstallButton=&Uninstall
zhcn.MaintenanceUninstallButton=卸载(&U)
en.MaintenanceUninstallHint=Remove the existing PixivDownload installation.
zhcn.MaintenanceUninstallHint=移除现有 PixivDownload 安装。
en.MaintenanceUninstallConfirm=This will start the existing PixivDownload uninstaller. Continue?
zhcn.MaintenanceUninstallConfirm=即将启动现有 PixivDownload 卸载程序。是否继续？
en.MaintenanceUninstallMissing=Could not find the existing uninstaller.
zhcn.MaintenanceUninstallMissing=未能找到现有卸载程序。
en.MaintenanceUninstallFailed=The existing uninstaller failed.
zhcn.MaintenanceUninstallFailed=现有卸载程序执行失败。
en.MaintenanceRemovingLegacyMsi=Removing the previous MSI installation...
zhcn.MaintenanceRemovingLegacyMsi=正在移除旧 MSI 安装...
en.MaintenanceLegacyMsiRemoveFailed=Could not remove the previous MSI installation.
zhcn.MaintenanceLegacyMsiRemoveFailed=未能移除旧 MSI 安装。

[Tasks]
Name: "downloadffmpeg"; Description: "{cm:TaskDownloadFfmpeg}"; GroupDescription: "{cm:OptionalTasksGroup}"; Flags: unchecked

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Check: ShouldInstallApplicationFiles

[Registry]
Root: HKLM64; Subkey: "Software\sywyar\PixivDownload"; ValueType: string; ValueName: "InstallLocation"; ValueData: "{app}"; Flags: uninsdeletekeyifempty; Check: ShouldInstallApplicationFiles
Root: HKLM64; Subkey: "Software\sywyar\PixivDownload"; ValueType: dword; ValueName: "installed"; ValueData: "1"; Flags: uninsdeletevalue; Check: ShouldInstallApplicationFiles

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\{#AppExeName}"; Check: ShouldInstallApplicationFiles
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\{#AppExeName}"; Check: ShouldInstallApplicationFiles

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(AppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent; Check: ShouldInstallApplicationFiles

[UninstallDelete]
Type: filesandordirs; Name: "{app}\tools"

[Code]
type
  TMsg = record
    Hwnd: Longword;
    Message: Longword;
    WParam: Longword;
    LParam: Longword;
    Time: Longword;
    PtX: Longint;
    PtY: Longint;
  end;

const
  PM_REMOVE = 1;
  FfmpegArchiveName = 'ffmpeg.zip';
  FfmpegLicenseName = 'ffmpeg-LGPL.txt';
  AppRegistryKey = 'Software\sywyar\PixivDownload';
  UninstallRegistryKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall';
  InnoUninstallRegistryKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{4D4F3566-C6C0-4D24-9242-86059B2A84A5}_is1';
  WindowsInternetSettingsKey = 'Software\Microsoft\Windows\CurrentVersion\Internet Settings';
  MaintenanceRepairMode = 'repair';
  MaintenanceChangeMode = 'change';
  MaintenanceUninstallMode = 'uninstall';
  FfmpegLicenseNotice =
    'FFmpeg is licensed under the LGPL v2.1.'#13#10 +
    'Source code: https://ffmpeg.org'#13#10 +
    'Build: BtbN FFmpeg Builds (https://github.com/BtbN/FFmpeg-Builds)'#13#10 +
    'LGPL License: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html'#13#10;

function PeekMessage(var Msg: TMsg; Hwnd: Longword; MsgFilterMin, MsgFilterMax, RemoveMsg: Longword): Boolean;
external 'PeekMessageW@user32.dll stdcall';

function TranslateMessage(var Msg: TMsg): Boolean;
external 'TranslateMessage@user32.dll stdcall';

function DispatchMessage(var Msg: TMsg): Longint;
external 'DispatchMessageW@user32.dll stdcall';

var
  MaintenancePage: TWizardPage;
  FfmpegTitleLabel: TNewStaticText;
  FfmpegDetailLabel: TNewStaticText;
  FfmpegProgressBar: TNewProgressBar;
  FfmpegProgressTitle: String;
  FfmpegProgressLastPercent: Integer;
  FfmpegInstalled: Boolean;
  FfmpegFailed: Boolean;
  SystemProxyUrl: String;
  ExistingInstallationResolved: Boolean;
  ExistingInstallationFound: Boolean;
  ExistingInstallDir: String;
  ExistingUninstallCommand: String;
  LegacyMsiProductCode: String;
  MaintenanceMode: String;
  MaintenanceClosingAfterUninstall: Boolean;

procedure ProcessInstallerMessages;
var
  Msg: TMsg;
begin
  while PeekMessage(Msg, 0, 0, 0, PM_REMOVE) do
  begin
    TranslateMessage(Msg);
    DispatchMessage(Msg);
  end;
end;

procedure ResponsiveSleep(const Milliseconds: Integer);
var
  Elapsed: Integer;
  Step: Integer;
begin
  Elapsed := 0;
  while Elapsed < Milliseconds do
  begin
    ProcessInstallerMessages;
    Step := Milliseconds - Elapsed;
    if Step > 25 then
      Step := 25;
    Sleep(Step);
    Elapsed := Elapsed + Step;
  end;
  ProcessInstallerMessages;
end;

function PathWithTrailingBackslash(const Path: String): String;
begin
  Result := Path;
  if (Result <> '') and (Copy(Result, Length(Result), 1) <> '\') then
    Result := Result + '\';
end;

function Unquote(const Value: String): String;
begin
  Result := Trim(Value);
  if (Length(Result) >= 2) and (Copy(Result, 1, 1) = '"') and (Copy(Result, Length(Result), 1) = '"') then
    Result := Copy(Result, 2, Length(Result) - 2);
end;

function IsUsableInstallDir(const Path: String): Boolean;
var
  Candidate: String;
begin
  Candidate := Unquote(Path);
  Result :=
    (Candidate <> '') and
    (DirExists(Candidate) or FileExists(PathWithTrailingBackslash(Candidate) + '{#AppExeName}'));
end;

function QueryMachineStringValue(const SubKeyName, ValueName: String; var Value: String): Boolean;
begin
  Result := False;
  if IsWin64 then
    Result := RegQueryStringValue(HKLM64, SubKeyName, ValueName, Value);
  if not Result then
    Result := RegQueryStringValue(HKLM, SubKeyName, ValueName, Value);
end;

function FindLegacyMsiProductCodeInRoot(const RootKey: Integer; var ProductCode: String): Boolean;
var
  Names: TArrayOfString;
  I: Integer;
  KeyName: String;
  DisplayName: String;
  Publisher: String;
  WindowsInstaller: Cardinal;
begin
  Result := False;
  if not RegGetSubkeyNames(RootKey, UninstallRegistryKey, Names) then
    exit;

  for I := 0 to GetArrayLength(Names) - 1 do
  begin
    KeyName := UninstallRegistryKey + '\' + Names[I];
    Publisher := '';
    RegQueryStringValue(RootKey, KeyName, 'Publisher', Publisher);
    if RegQueryStringValue(RootKey, KeyName, 'DisplayName', DisplayName) and
       (CompareText(Trim(DisplayName), '{#AppName}') = 0) and
       ((Trim(Publisher) = '') or (CompareText(Trim(Publisher), '{#AppPublisher}') = 0)) and
       RegQueryDWordValue(RootKey, KeyName, 'WindowsInstaller', WindowsInstaller) and
       (WindowsInstaller = 1) then
    begin
      ProductCode := Names[I];
      Result := True;
      exit;
    end;
  end;
end;

function FindLegacyMsiProductCode(var ProductCode: String): Boolean;
begin
  Result := False;
  if IsWin64 then
    Result := FindLegacyMsiProductCodeInRoot(HKLM64, ProductCode);
  if not Result then
    Result := FindLegacyMsiProductCodeInRoot(HKLM, ProductCode);
end;

procedure ResolveExistingInstallation;
var
  InstallLocation: String;
begin
  if ExistingInstallationResolved then
    exit;

  ExistingInstallationResolved := True;
  ExistingInstallationFound := False;
  ExistingInstallDir := '';
  ExistingUninstallCommand := '';
  LegacyMsiProductCode := '';

  QueryMachineStringValue(InnoUninstallRegistryKey, 'InstallLocation', ExistingInstallDir);
  QueryMachineStringValue(InnoUninstallRegistryKey, 'UninstallString', ExistingUninstallCommand);

  if not IsUsableInstallDir(ExistingInstallDir) then
  begin
    InstallLocation := '';
    if QueryMachineStringValue(AppRegistryKey, 'InstallLocation', InstallLocation) and IsUsableInstallDir(InstallLocation) then
      ExistingInstallDir := Unquote(InstallLocation)
    else
      ExistingInstallDir := '';
  end
  else
    ExistingInstallDir := Unquote(ExistingInstallDir);

  FindLegacyMsiProductCode(LegacyMsiProductCode);
  ExistingInstallationFound :=
    (ExistingInstallDir <> '') or
    (ExistingUninstallCommand <> '') or
    (LegacyMsiProductCode <> '');
end;

function GetDefaultInstallDir(Param: String): String;
begin
  ResolveExistingInstallation;
  if ExistingInstallDir <> '' then
    Result := ExistingInstallDir
  else
    Result := ExpandConstant('{autopf}\{#AppName}');
end;

function SplitCommandLine(const CommandLine: String; var FileName, Params: String): Boolean;
var
  S: String;
  I: Integer;
  SpacePos: Integer;
begin
  Result := False;
  S := Trim(CommandLine);
  FileName := '';
  Params := '';
  if S = '' then
    exit;

  if Copy(S, 1, 1) = '"' then
  begin
    for I := 2 to Length(S) do
    begin
      if Copy(S, I, 1) = '"' then
      begin
        FileName := Copy(S, 2, I - 2);
        Params := Trim(Copy(S, I + 1, Length(S)));
        Result := FileName <> '';
        exit;
      end;
    end;
  end
  else
  begin
    SpacePos := Pos(' ', S);
    if SpacePos > 0 then
    begin
      FileName := Copy(S, 1, SpacePos - 1);
      Params := Trim(Copy(S, SpacePos + 1, Length(S)));
    end
    else
      FileName := S;
    Result := FileName <> '';
  end;
end;

function RunExistingUninstaller: Boolean;
var
  FileName: String;
  Params: String;
  ResultCode: Integer;
begin
  Result := False;
  ResolveExistingInstallation;

  if LegacyMsiProductCode <> '' then
  begin
    FileName := ExpandConstant('{sys}\msiexec.exe');
    Params := '/x ' + LegacyMsiProductCode;
  end
  else if ExistingUninstallCommand <> '' then
  begin
    if not SplitCommandLine(ExistingUninstallCommand, FileName, Params) then
    begin
      SuppressibleMsgBox(CustomMessage('MaintenanceUninstallMissing'), mbError, MB_OK, IDOK);
      exit;
    end;
  end
  else
  begin
    SuppressibleMsgBox(CustomMessage('MaintenanceUninstallMissing'), mbError, MB_OK, IDOK);
    exit;
  end;

  if not Exec(FileName, Params, '', SW_SHOWNORMAL, ewWaitUntilTerminated, ResultCode) then
  begin
    SuppressibleMsgBox(CustomMessage('MaintenanceUninstallFailed') + #13#10#13#10 + SysErrorMessage(ResultCode), mbError, MB_OK, IDOK);
    exit;
  end;

  if (ResultCode <> 0) and (ResultCode <> 3010) and (ResultCode <> 1605) then
  begin
    SuppressibleMsgBox(CustomMessage('MaintenanceUninstallFailed') + ' Exit code: ' + IntToStr(ResultCode), mbError, MB_OK, IDOK);
    exit;
  end;

  Result := True;
end;

function RemoveLegacyMsiSilently(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Result := '';
  ResolveExistingInstallation;
  if LegacyMsiProductCode = '' then
    exit;

  WizardForm.StatusLabel.Caption := CustomMessage('MaintenanceRemovingLegacyMsi');
  if not Exec(ExpandConstant('{sys}\msiexec.exe'), '/x ' + LegacyMsiProductCode + ' /qn /norestart', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
  begin
    Result := CustomMessage('MaintenanceLegacyMsiRemoveFailed') + ' ' + SysErrorMessage(ResultCode);
    exit;
  end;

  if ResultCode = 3010 then
    NeedsRestart := True
  else if (ResultCode <> 0) and (ResultCode <> 1605) then
    Result := CustomMessage('MaintenanceLegacyMsiRemoveFailed') + ' Exit code: ' + IntToStr(ResultCode);
end;

procedure ContinueFromMaintenance(const Mode: String);
begin
  MaintenanceMode := Mode;
  WizardForm.NextButton.OnClick(WizardForm.NextButton);
end;

procedure MaintenanceRepairButtonClick(Sender: TObject);
begin
  ContinueFromMaintenance(MaintenanceRepairMode);
end;

procedure MaintenanceChangeButtonClick(Sender: TObject);
begin
  ContinueFromMaintenance(MaintenanceChangeMode);
end;

procedure MaintenanceUninstallButtonClick(Sender: TObject);
begin
  ContinueFromMaintenance(MaintenanceUninstallMode);
end;

procedure AddMaintenanceButton(var Button: TNewButton; const Caption, Hint: String; const Top: Integer; const Mode: String);
begin
  Button := TNewButton.Create(MaintenancePage);
  Button.Parent := MaintenancePage.Surface;
  Button.Style := bsCommandLink;
  Button.Caption := Caption;
  Button.CommandLinkHint := Hint;
  Button.Left := 0;
  Button.Top := Top;
  Button.Width := MaintenancePage.SurfaceWidth;
  Button.Height := ScaleY(48);
  if Mode = MaintenanceRepairMode then
    Button.OnClick := @MaintenanceRepairButtonClick
  else if Mode = MaintenanceChangeMode then
    Button.OnClick := @MaintenanceChangeButtonClick
  else
    Button.OnClick := @MaintenanceUninstallButtonClick;
  Button.AdjustHeightIfCommandLink;
end;

procedure SetFfmpegControlsVisible(Visible: Boolean);
begin
  FfmpegTitleLabel.Visible := Visible;
  FfmpegDetailLabel.Visible := Visible;
  FfmpegProgressBar.Visible := Visible;
end;

procedure SetFfmpegProgress(const Title: String; const Detail: String; const Percent: Integer);
var
  SafePercent: Integer;
begin
  SafePercent := Percent;
  if SafePercent < 0 then
    SafePercent := 0;
  if SafePercent > 100 then
    SafePercent := 100;
  if FfmpegProgressTitle <> Title then
  begin
    FfmpegProgressTitle := Title;
    FfmpegProgressLastPercent := -1;
  end;
  if SafePercent < FfmpegProgressLastPercent then
    SafePercent := FfmpegProgressLastPercent;
  FfmpegProgressLastPercent := SafePercent;

  FfmpegTitleLabel.Caption := Title;
  FfmpegDetailLabel.Caption := Detail;
  FfmpegProgressBar.Position := SafePercent;
  WizardForm.StatusLabel.Caption := Title;
  ProcessInstallerMessages;
end;

procedure InitializeWizard;
var
  RepairButton: TNewButton;
  ChangeButton: TNewButton;
  UninstallButton: TNewButton;
  ButtonTop: Integer;
begin
  ResolveExistingInstallation;
  MaintenanceMode := MaintenanceRepairMode;
  if ExistingInstallDir <> '' then
    WizardForm.DirEdit.Text := ExistingInstallDir;

  if ExistingInstallationFound then
  begin
    MaintenancePage := CreateCustomPage(
      wpWelcome,
      CustomMessage('MaintenanceTitle'),
      CustomMessage('MaintenanceDescription'));

    ButtonTop := 0;
    AddMaintenanceButton(
      RepairButton,
      CustomMessage('MaintenanceRepairButton'),
      CustomMessage('MaintenanceRepairHint'),
      ButtonTop,
      MaintenanceRepairMode);
    ButtonTop := RepairButton.Top + RepairButton.Height + ScaleY(8);

    AddMaintenanceButton(
      ChangeButton,
      CustomMessage('MaintenanceChangeButton'),
      CustomMessage('MaintenanceChangeHint'),
      ButtonTop,
      MaintenanceChangeMode);
    ButtonTop := ChangeButton.Top + ChangeButton.Height + ScaleY(8);

    AddMaintenanceButton(
      UninstallButton,
      CustomMessage('MaintenanceUninstallButton'),
      CustomMessage('MaintenanceUninstallHint'),
      ButtonTop,
      MaintenanceUninstallMode);
  end;

  FfmpegTitleLabel := TNewStaticText.Create(WizardForm);
  FfmpegTitleLabel.Parent := WizardForm.ProgressGauge.Parent;
  FfmpegTitleLabel.Left := WizardForm.ProgressGauge.Left;
  FfmpegTitleLabel.Top := WizardForm.ProgressGauge.Top + WizardForm.ProgressGauge.Height + ScaleY(18);
  FfmpegTitleLabel.Width := WizardForm.ProgressGauge.Width;
  FfmpegTitleLabel.Caption := CustomMessage('FfmpegWaiting');
  FfmpegTitleLabel.Visible := False;

  FfmpegProgressBar := TNewProgressBar.Create(WizardForm);
  FfmpegProgressBar.Parent := WizardForm.ProgressGauge.Parent;
  FfmpegProgressBar.Left := WizardForm.ProgressGauge.Left;
  FfmpegProgressBar.Top := FfmpegTitleLabel.Top + FfmpegTitleLabel.Height + ScaleY(6);
  FfmpegProgressBar.Width := WizardForm.ProgressGauge.Width;
  FfmpegProgressBar.Height := WizardForm.ProgressGauge.Height;
  FfmpegProgressBar.Max := 100;
  FfmpegProgressBar.Position := 0;
  FfmpegProgressBar.Visible := False;
  FfmpegProgressTitle := '';
  FfmpegProgressLastPercent := -1;

  FfmpegDetailLabel := TNewStaticText.Create(WizardForm);
  FfmpegDetailLabel.Parent := WizardForm.ProgressGauge.Parent;
  FfmpegDetailLabel.Left := WizardForm.ProgressGauge.Left;
  FfmpegDetailLabel.Top := FfmpegProgressBar.Top + FfmpegProgressBar.Height + ScaleY(6);
  FfmpegDetailLabel.Width := WizardForm.ProgressGauge.Width;
  FfmpegDetailLabel.Caption := '';
  FfmpegDetailLabel.Visible := False;
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if ExistingInstallationFound then
  begin
    if (MaintenanceMode = MaintenanceRepairMode) and
       ((PageID = wpSelectDir) or (PageID = wpSelectProgramGroup) or (PageID = wpSelectTasks)) then
      Result := True
    else if (MaintenanceMode = MaintenanceChangeMode) and
            ((PageID = wpSelectDir) or (PageID = wpSelectProgramGroup)) then
      Result := True;
  end;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if ExistingInstallationFound then
  begin
    if CurPageID = MaintenancePage.ID then
    begin
      if MaintenanceMode = MaintenanceUninstallMode then
      begin
        Result := False;
        if SuppressibleMsgBox(CustomMessage('MaintenanceUninstallConfirm'), mbConfirmation, MB_YESNO, IDNO) = IDYES then
        begin
          if RunExistingUninstaller then
          begin
            MaintenanceClosingAfterUninstall := True;
            WizardForm.Close;
          end;
        end;
      end;
    end;
  end;
end;

procedure CancelButtonClick(CurPageID: Integer; var Cancel, Confirm: Boolean);
begin
  if MaintenanceClosingAfterUninstall then
  begin
    Cancel := True;
    Confirm := False;
  end;
end;

function IsTaskOnlyMaintenanceChange: Boolean;
begin
  ResolveExistingInstallation;
  Result :=
    ExistingInstallationFound and
    (MaintenanceMode = MaintenanceChangeMode) and
    (LegacyMsiProductCode = '');
end;

function ShouldInstallApplicationFiles: Boolean;
begin
  Result := not IsTaskOnlyMaintenanceChange;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpInstalling then
  begin
    SetFfmpegControlsVisible(WizardIsTaskSelected('downloadffmpeg'));
    if WizardIsTaskSelected('downloadffmpeg') then
      SetFfmpegProgress(CustomMessage('FfmpegWaiting'), '', 0);
  end
  else if CurPageID = wpFinished then
  begin
    if FfmpegInstalled then
      WizardForm.FinishedLabel.Caption := WizardForm.FinishedLabel.Caption + #13#10#13#10 + CustomMessage('FfmpegFinishedSuccess')
    else if FfmpegFailed then
      WizardForm.FinishedLabel.Caption := WizardForm.FinishedLabel.Caption + #13#10#13#10 + CustomMessage('FfmpegFinishedFailed');
  end;
end;

function OnFfmpegDownloadProgress(const Url, FileName: String; const Progress, ProgressMax: Int64): Boolean;
var
  Percent: Integer;
  Detail: String;
begin
  if ProgressMax > 0 then
  begin
    Percent := Progress * 100 div ProgressMax;
    Detail := IntToStr(Progress) + ' / ' + IntToStr(ProgressMax) + ' bytes';
  end
  else
  begin
    Percent := 0;
    Detail := IntToStr(Progress) + ' bytes';
  end;

  SetFfmpegProgress(CustomMessage('FfmpegDownloading'), Detail, Percent);
  Result := True;
end;

function OnFfmpegExtractionProgress(const ArchiveName, FileName: String; const Progress, ProgressMax: Int64): Boolean;
var
  Percent: Integer;
begin
  if ProgressMax > 0 then
    Percent := Progress * 100 div ProgressMax
  else
    Percent := 100;

  SetFfmpegProgress(CustomMessage('FfmpegExtracting'), FileName, Percent);
  ProcessInstallerMessages;
  Result := True;
end;

function StartsWithText(const Value, Prefix: String): Boolean;
begin
  Result := Copy(Lowercase(Value), 1, Length(Prefix)) = Lowercase(Prefix);
end;

function StripProxyScheme(const Value: String): String;
begin
  Result := Trim(Value);
  if StartsWithText(Result, 'http://') then
    Result := Copy(Result, 8, Length(Result))
  else if StartsWithText(Result, 'https://') then
    Result := Copy(Result, 9, Length(Result));
end;

function NormalizeHttpProxyEntry(const Entry: String): String;
var
  Candidate: String;
  SlashPos: Integer;
  Parts: TArrayOfString;
  Host: String;
  Port: Integer;
begin
  Result := '';
  Candidate := StripProxyScheme(Entry);
  SlashPos := Pos('/', Candidate);
  if SlashPos > 0 then
    Candidate := Copy(Candidate, 1, SlashPos - 1);

  Parts := StringSplit(Candidate, [':'], stAll);
  if GetArrayLength(Parts) < 2 then
    exit;

  Host := Trim(Parts[0]);
  Port := StrToIntDef(Trim(Parts[1]), 0);
  if (Host = '') or (Port <= 0) or (Port > 65535) then
    exit;

  Result := 'http://' + Host + ':' + IntToStr(Port);
end;

function FindNamedSystemProxy(const ProxyServer, Scheme: String): String;
var
  Segments: TArrayOfString;
  I: Integer;
  Segment: String;
  Prefix: String;
begin
  Result := '';
  Prefix := Lowercase(Scheme) + '=';
  Segments := StringSplit(ProxyServer, [';'], stExcludeEmpty);
  for I := 0 to GetArrayLength(Segments) - 1 do
  begin
    Segment := Trim(Segments[I]);
    if StartsWithText(Segment, Prefix) then
    begin
      Result := NormalizeHttpProxyEntry(Copy(Segment, Length(Prefix) + 1, Length(Segment)));
      if Result <> '' then
        exit;
    end;
  end;
end;

function FindGenericSystemProxy(const ProxyServer: String): String;
var
  Segments: TArrayOfString;
  I: Integer;
  Segment: String;
begin
  Result := '';
  Segments := StringSplit(ProxyServer, [';'], stExcludeEmpty);
  for I := 0 to GetArrayLength(Segments) - 1 do
  begin
    Segment := Trim(Segments[I]);
    if Pos('=', Segment) = 0 then
    begin
      Result := NormalizeHttpProxyEntry(Segment);
      if Result <> '' then
        exit;
    end;
  end;
end;

function ResolveSystemProxyUrl: String;
var
  ProxyEnable: Cardinal;
  ProxyServer: String;
begin
  Result := '';
  if not RegQueryDWordValue(HKEY_CURRENT_USER, WindowsInternetSettingsKey, 'ProxyEnable', ProxyEnable) then
    exit;
  if ProxyEnable = 0 then
    exit;
  if not RegQueryStringValue(HKEY_CURRENT_USER, WindowsInternetSettingsKey, 'ProxyServer', ProxyServer) then
    exit;

  ProxyServer := Trim(ProxyServer);
  if ProxyServer = '' then
    exit;

  Result := FindNamedSystemProxy(ProxyServer, 'https');
  if Result <> '' then
    exit;

  Result := FindNamedSystemProxy(ProxyServer, 'http');
  if Result <> '' then
    exit;

  Result := FindGenericSystemProxy(ProxyServer);
end;

function DoubleQuote(const Value: String): String;
begin
  Result := '"' + Value + '"';
end;

function BuildFfmpegDownloadScript: String;
begin
  Result :=
    'param('#13#10 +
    '  [string]$Url,'#13#10 +
    '  [string]$OutFile,'#13#10 +
    '  [string]$ProgressFile,'#13#10 +
    '  [string]$ProxyUrl'#13#10 +
    ')'#13#10 +
    '$ErrorActionPreference = "Stop"'#13#10 +
    'function Write-State([string]$Value) {'#13#10 +
    '  $directory = [System.IO.Path]::GetDirectoryName($ProgressFile)'#13#10 +
    '  if (-not [System.String]::IsNullOrWhiteSpace($directory)) {'#13#10 +
    '    [System.IO.Directory]::CreateDirectory($directory) | Out-Null'#13#10 +
    '  }'#13#10 +
    '  $bytes = [System.Text.Encoding]::ASCII.GetBytes($Value)'#13#10 +
    '  for ($attempt = 0; $attempt -lt 40; $attempt++) {'#13#10 +
    '    try {'#13#10 +
    '      $stream = [System.IO.File]::Open($ProgressFile, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)'#13#10 +
    '      try {'#13#10 +
    '        $stream.Write($bytes, 0, $bytes.Length)'#13#10 +
    '        $stream.SetLength($bytes.Length)'#13#10 +
    '      } finally {'#13#10 +
    '        $stream.Dispose()'#13#10 +
    '      }'#13#10 +
    '      return'#13#10 +
    '    } catch {'#13#10 +
    '      if ($attempt -eq 39) { throw }'#13#10 +
    '      Start-Sleep -Milliseconds 50'#13#10 +
    '    }'#13#10 +
    '  }'#13#10 +
    '}'#13#10 +
    'function Format-Error([System.Exception]$ErrorValue) {'#13#10 +
    '  $message = $ErrorValue.GetType().FullName + ": " + $ErrorValue.Message'#13#10 +
    '  return [System.Text.RegularExpressions.Regex]::Replace($message, "[^ -~]", "?")'#13#10 +
    '}'#13#10 +
    'try {'#13#10 +
    '  [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12'#13#10 +
    '  $request = [System.Net.HttpWebRequest][System.Net.WebRequest]::Create($Url)'#13#10 +
    '  $request.Method = "GET"'#13#10 +
    '  $request.UserAgent = "PixivDownload/setup-ffmpeg"'#13#10 +
    '  $request.AllowAutoRedirect = $true'#13#10 +
    '  $request.Timeout = 600000'#13#10 +
    '  $request.ReadWriteTimeout = 600000'#13#10 +
    '  if ([string]::IsNullOrWhiteSpace($ProxyUrl)) {'#13#10 +
    '    $request.Proxy = $null'#13#10 +
    '    Write-State "PROXY|DIRECT"'#13#10 +
    '  } else {'#13#10 +
    '    $proxy = New-Object System.Net.WebProxy($ProxyUrl, $true)'#13#10 +
    '    $proxy.Credentials = [System.Net.CredentialCache]::DefaultNetworkCredentials'#13#10 +
    '    $request.Proxy = $proxy'#13#10 +
    '    Write-State ("PROXY|" + $ProxyUrl)'#13#10 +
    '  }'#13#10 +
    '  [int64]$downloaded = 0'#13#10 +
    '  [int64]$total = -1'#13#10 +
    '  $response = $request.GetResponse()'#13#10 +
    '  try {'#13#10 +
    '    $total = $response.ContentLength'#13#10 +
    '    $inputStream = $response.GetResponseStream()'#13#10 +
    '    $outputStream = [System.IO.File]::Open($OutFile, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)'#13#10 +
    '    try {'#13#10 +
    '      $buffer = New-Object byte[] 65536'#13#10 +
    '      while (($read = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {'#13#10 +
    '        $outputStream.Write($buffer, 0, $read)'#13#10 +
    '        $downloaded += $read'#13#10 +
    '        if ($total -gt 0) {'#13#10 +
    '          $percent = [int][Math]::Floor(($downloaded / [double]$total) * 100)'#13#10 +
    '          if ($percent -lt 0) { $percent = 0 }'#13#10 +
    '          if ($percent -gt 100) { $percent = 100 }'#13#10 +
    '        } else {'#13#10 +
    '          $percent = 0'#13#10 +
    '        }'#13#10 +
    '        Write-State ("PROGRESS|{0}|{1}|{2}" -f $downloaded, $total, $percent)'#13#10 +
    '      }'#13#10 +
    '    } finally {'#13#10 +
    '      $outputStream.Dispose()'#13#10 +
    '      $inputStream.Dispose()'#13#10 +
    '    }'#13#10 +
    '  } finally {'#13#10 +
    '    $response.Close()'#13#10 +
    '  }'#13#10 +
    '  Write-State ("DONE|{0}|{1}|100" -f $downloaded, $total)'#13#10 +
    '  exit 0'#13#10 +
    '} catch {'#13#10 +
    '  Write-State ("ERROR|" + (Format-Error $_.Exception))'#13#10 +
    '  exit 1'#13#10 +
    '}'#13#10;
end;

procedure ApplyPowerShellDownloadProgress(const Line: String);
var
  CleanLine: String;
  Parts: TArrayOfString;
  CurrentText: String;
  TotalText: String;
  Percent: Integer;
  Detail: String;
begin
  CleanLine := Line;
  StringChangeEx(CleanLine, #13, '', True);
  StringChangeEx(CleanLine, #10, '', True);
  Parts := StringSplit(CleanLine, ['|'], stAll);

  if GetArrayLength(Parts) = 0 then
    exit;

  if Parts[0] = 'PROXY' then
  begin
    if (GetArrayLength(Parts) >= 2) and (Parts[1] <> 'DIRECT') then
      SetFfmpegProgress(CustomMessage('FfmpegDownloading'), CustomMessage('FfmpegProxyDetected') + ': ' + Parts[1], 0)
    else
      SetFfmpegProgress(CustomMessage('FfmpegDownloading'), CustomMessage('FfmpegDirectDownload'), 0);
    exit;
  end;

  if Parts[0] = 'PROGRESS' then
  begin
    CurrentText := '0';
    TotalText := '-1';
    Percent := 0;
    if GetArrayLength(Parts) >= 3 then
    begin
      CurrentText := Parts[1];
      TotalText := Parts[2];
    end;
    if GetArrayLength(Parts) >= 4 then
      Percent := StrToIntDef(Parts[3], 0);

    if (TotalText <> '') and (TotalText <> '-1') then
      Detail := CurrentText + ' / ' + TotalText + ' bytes'
    else
      Detail := CurrentText + ' bytes';
    SetFfmpegProgress(CustomMessage('FfmpegDownloading'), Detail, Percent);
  end;
end;

procedure DownloadFfmpegArchive(const ArchivePath: String);
var
  ScriptPath: String;
  ProgressPath: String;
  PowerShellPath: String;
  Params: String;
  ResultCode: Integer;
  ProgressLine: AnsiString;
  ProgressText: String;
  Parts: TArrayOfString;
begin
  ScriptPath := ExpandConstant('{tmp}\pixivdownload-ffmpeg-download.ps1');
  ProgressPath := ExpandConstant('{tmp}\pixivdownload-ffmpeg-download.progress');
  PowerShellPath := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');

  DeleteFile(ProgressPath);
  if not SaveStringToFile(ScriptPath, AnsiString(BuildFfmpegDownloadScript), False) then
    RaiseException('Could not prepare the FFmpeg downloader script.');

  Params :=
    '-NoProfile -ExecutionPolicy Bypass -File ' + DoubleQuote(ScriptPath) +
    ' -Url ' + DoubleQuote('{#FfmpegArchiveUrl}') +
    ' -OutFile ' + DoubleQuote(ArchivePath) +
    ' -ProgressFile ' + DoubleQuote(ProgressPath) +
    ' -ProxyUrl ' + DoubleQuote(SystemProxyUrl);

  Log('Starting FFmpeg download. Proxy URL: ' + SystemProxyUrl);
  if not Exec(PowerShellPath, Params, '', SW_HIDE, ewNoWait, ResultCode) then
    RaiseException('Could not start PowerShell downloader: ' + SysErrorMessage(ResultCode));

  while True do
  begin
    if FileExists(ProgressPath) and LoadStringFromFile(ProgressPath, ProgressLine) then
    begin
      ProgressText := String(ProgressLine);
      ApplyPowerShellDownloadProgress(ProgressText);
      Parts := StringSplit(ProgressText, ['|'], stAll);
      if GetArrayLength(Parts) > 0 then
      begin
        if Parts[0] = 'DONE' then
          exit
        else if Parts[0] = 'ERROR' then
        begin
          if GetArrayLength(Parts) >= 2 then
            RaiseException(Parts[1])
          else
            RaiseException('FFmpeg download failed.');
        end;
      end;
    end;

    ResponsiveSleep(200);
  end;
end;

function FindFileRecursive(const RootDir: String; const TargetName: String; var FoundPath: String): Boolean;
var
  FindRec: TFindRec;
  Candidate: String;
begin
  Result := False;
  if not FindFirst(RootDir + '\*', FindRec) then
    exit;

  try
    repeat
      if (FindRec.Name <> '.') and (FindRec.Name <> '..') then
      begin
        Candidate := RootDir + '\' + FindRec.Name;
        if DirExists(Candidate) then
        begin
          if FindFileRecursive(Candidate, TargetName, FoundPath) then
          begin
            Result := True;
            exit;
          end;
        end
        else if CompareText(FindRec.Name, TargetName) = 0 then
        begin
          FoundPath := Candidate;
          Result := True;
          exit;
        end;
      end;
    until not FindNext(FindRec);
  finally
    FindClose(FindRec);
  end;
end;

procedure InstallFfmpegFiles(const ExtractDir: String);
var
  SourceFfmpeg: String;
  SourceFfprobe: String;
  TargetDir: String;
  LicenseDir: String;
begin
  SourceFfmpeg := '';
  SourceFfprobe := '';
  if (not FindFileRecursive(ExtractDir, 'ffmpeg.exe', SourceFfmpeg)) or
     (not FindFileRecursive(ExtractDir, 'ffprobe.exe', SourceFfprobe)) then
    RaiseException(CustomMessage('FfmpegArchiveInvalid'));

  SetFfmpegProgress(CustomMessage('FfmpegInstallingFiles'), '', 100);

  TargetDir := ExpandConstant('{app}\tools\ffmpeg');
  LicenseDir := TargetDir + '\licenses';
  ForceDirectories(TargetDir);
  ForceDirectories(LicenseDir);

  if (not CopyFile(SourceFfmpeg, TargetDir + '\ffmpeg.exe', False)) or
     (not CopyFile(SourceFfprobe, TargetDir + '\ffprobe.exe', False)) then
    RaiseException(CustomMessage('FfmpegCopyFailed'));

  if not SaveStringToFile(LicenseDir + '\' + FfmpegLicenseName, FfmpegLicenseNotice, False) then
    RaiseException(CustomMessage('FfmpegLicenseWriteFailed'));
end;

procedure DownloadAndInstallFfmpeg;
var
  ArchivePath: String;
  ExtractDir: String;
begin
  ArchivePath := ExpandConstant('{tmp}\' + FfmpegArchiveName);
  ExtractDir := ExpandConstant('{tmp}\ffmpeg-extract');

  if DirExists(ExtractDir) then
    DelTree(ExtractDir, True, True, True);
  ForceDirectories(ExtractDir);

  SystemProxyUrl := ResolveSystemProxyUrl;
  SetFfmpegProgress(CustomMessage('FfmpegDownloading'), '', 0);
  DownloadFfmpegArchive(ArchivePath);

  try
    SetFfmpegProgress(CustomMessage('FfmpegExtracting'), '', 0);
    ExtractArchive(ArchivePath, ExtractDir, '', True, @OnFfmpegExtractionProgress);
    InstallFfmpegFiles(ExtractDir);
    SetFfmpegProgress(CustomMessage('FfmpegCompleted'), '', 100);
    FfmpegInstalled := True;
  finally
    if DirExists(ExtractDir) then
      DelTree(ExtractDir, True, True, True);
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if ShouldInstallApplicationFiles then
    Result := RemoveLegacyMsiSilently(NeedsRestart);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ErrorMessage: String;
begin
  if (CurStep = ssPostInstall) and WizardIsTaskSelected('downloadffmpeg') then
  begin
    try
      DownloadAndInstallFfmpeg;
    except
      ErrorMessage := GetExceptionMessage;
      FfmpegFailed := True;
      Log('FFmpeg installation failed: ' + ErrorMessage);
      SetFfmpegProgress(CustomMessage('FfmpegFailed'), ErrorMessage, 0);
      SuppressibleMsgBox(CustomMessage('FfmpegFailed') + #13#10#13#10 + ErrorMessage, mbError, MB_OK, IDOK);
    end;
  end;
end;
