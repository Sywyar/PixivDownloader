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
  InnoInstallDir: String;
  FallbackLocation: String;
  InstalledVersion: String;
begin
  if ExistingInstallationResolved then
    exit;

  ExistingInstallationResolved := True;
  ExistingInstallationFound := False;
  ShowMaintenancePage := False;
  ExistingInstallDir := '';
  ExistingUninstallCommand := '';
  LegacyMsiProductCode := '';

  InnoInstallDir := '';
  QueryMachineStringValue(InnoUninstallRegistryKey, 'InstallLocation', InnoInstallDir);
  QueryMachineStringValue(InnoUninstallRegistryKey, 'UninstallString', ExistingUninstallCommand);

  if IsUsableInstallDir(InnoInstallDir) then
    ExistingInstallDir := Unquote(InnoInstallDir);

  FindLegacyMsiProductCode(LegacyMsiProductCode);

  ExistingInstallationFound :=
    (ExistingUninstallCommand <> '') or
    (LegacyMsiProductCode <> '');

  if ExistingInstallationFound and (ExistingInstallDir = '') then
  begin
    FallbackLocation := '';
    if QueryMachineStringValue(AppRegistryKey, 'InstallLocation', FallbackLocation) and IsUsableInstallDir(FallbackLocation) then
      ExistingInstallDir := Unquote(FallbackLocation);
  end;

  if ExistingUninstallCommand <> '' then
  begin
    InstalledVersion := '';
    if QueryMachineStringValue(InnoUninstallRegistryKey, 'DisplayVersion', InstalledVersion) then
      ShowMaintenancePage := CompareText(Trim(InstalledVersion), '{#AppVersion}') = 0;
  end;
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
