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


procedure LayoutInstallProgressControls;
var
  NextTop: Integer;
begin
  NextTop := WizardForm.ProgressGauge.Top + WizardForm.ProgressGauge.Height + ScaleY(18);

  if Assigned(PluginInstallTitleLabel) then
  begin
    if PluginInstallTitleLabel.Visible then
    begin
      PluginInstallTitleLabel.Top := NextTop;
      PluginInstallProgressBar.Top := PluginInstallTitleLabel.Top + PluginInstallTitleLabel.Height + ScaleY(6);
      PluginInstallDetailLabel.Top := PluginInstallProgressBar.Top + PluginInstallProgressBar.Height + ScaleY(6);
      NextTop := PluginInstallDetailLabel.Top + PluginInstallDetailLabel.Height + ScaleY(14);
    end;
  end;

  if Assigned(FfmpegTitleLabel) then
  begin
    if FfmpegTitleLabel.Visible then
    begin
      FfmpegTitleLabel.Top := NextTop;
      FfmpegProgressBar.Top := FfmpegTitleLabel.Top + FfmpegTitleLabel.Height + ScaleY(6);
      FfmpegDetailLabel.Top := FfmpegProgressBar.Top + FfmpegProgressBar.Height + ScaleY(6);
    end;
  end;
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

  if ShowMaintenancePage then
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

  OptionalFeaturesPage := CreateCustomPage(
    wpSelectDir,
    CustomMessage('OptionalFeaturesTitle'),
    CustomMessage('OptionalFeaturesDescription'));

  FfmpegCheckBox := TNewCheckBox.Create(OptionalFeaturesPage);
  FfmpegCheckBox.Parent := OptionalFeaturesPage.Surface;
  FfmpegCheckBox.Left := 0;
  FfmpegCheckBox.Top := ScaleY(4);
  FfmpegCheckBox.Width := OptionalFeaturesPage.SurfaceWidth;
  FfmpegCheckBox.Height := ScaleY(24);
  FfmpegCheckBox.Caption := CustomMessage('TaskDownloadFfmpeg');
  FfmpegCheckBox.Checked := False;

  OptionalPluginsPage := CreateCustomPage(
    OptionalFeaturesPage.ID,
    CustomMessage('OptionalPluginsTitle'),
    CustomMessage('OptionalPluginsDescription'));

  PluginHintLabel := TNewStaticText.Create(OptionalPluginsPage);
  PluginHintLabel.Parent := OptionalPluginsPage.Surface;
  PluginHintLabel.Left := 0;
  PluginHintLabel.Top := 0;
  PluginHintLabel.Width := OptionalPluginsPage.SurfaceWidth;
  PluginHintLabel.Height := ScaleY(24);
  PluginHintLabel.Caption := CustomMessage('PluginListHint');

  PluginStatusLabel := TNewStaticText.Create(OptionalPluginsPage);
  PluginStatusLabel.Parent := OptionalPluginsPage.Surface;
  PluginStatusLabel.Left := 0;
  PluginStatusLabel.Top := PluginHintLabel.Top + PluginHintLabel.Height + ScaleY(8);
  PluginStatusLabel.Width := OptionalPluginsPage.SurfaceWidth;
  PluginStatusLabel.Height := ScaleY(20);
  PluginStatusLabel.Caption := CustomMessage('PluginCatalogLoading');

  PluginCheckList := TNewCheckListBox.Create(OptionalPluginsPage);
  PluginCheckList.Parent := OptionalPluginsPage.Surface;
  PluginCheckList.Left := 0;
  PluginCheckList.Top := PluginStatusLabel.Top + PluginStatusLabel.Height + ScaleY(8);
  PluginCheckList.Width := OptionalPluginsPage.SurfaceWidth;
  PluginCheckList.Height := OptionalPluginsPage.SurfaceHeight - PluginCheckList.Top - ScaleY(6);

  PluginInstallTitleLabel := TNewStaticText.Create(WizardForm);
  PluginInstallTitleLabel.Parent := WizardForm.ProgressGauge.Parent;
  PluginInstallTitleLabel.Left := WizardForm.ProgressGauge.Left;
  PluginInstallTitleLabel.Top := WizardForm.ProgressGauge.Top + WizardForm.ProgressGauge.Height + ScaleY(18);
  PluginInstallTitleLabel.Width := WizardForm.ProgressGauge.Width;
  PluginInstallTitleLabel.Caption := CustomMessage('PluginWaiting');
  PluginInstallTitleLabel.Visible := False;

  PluginInstallProgressBar := TNewProgressBar.Create(WizardForm);
  PluginInstallProgressBar.Parent := WizardForm.ProgressGauge.Parent;
  PluginInstallProgressBar.Left := WizardForm.ProgressGauge.Left;
  PluginInstallProgressBar.Top := PluginInstallTitleLabel.Top + PluginInstallTitleLabel.Height + ScaleY(6);
  PluginInstallProgressBar.Width := WizardForm.ProgressGauge.Width;
  PluginInstallProgressBar.Height := WizardForm.ProgressGauge.Height;
  PluginInstallProgressBar.Max := 100;
  PluginInstallProgressBar.Position := 0;
  PluginInstallProgressBar.Visible := False;
  PluginInstallProgressTitle := '';
  PluginInstallProgressLastPercent := -1;

  PluginInstallDetailLabel := TNewStaticText.Create(WizardForm);
  PluginInstallDetailLabel.Parent := WizardForm.ProgressGauge.Parent;
  PluginInstallDetailLabel.Left := WizardForm.ProgressGauge.Left;
  PluginInstallDetailLabel.Top := PluginInstallProgressBar.Top + PluginInstallProgressBar.Height + ScaleY(6);
  PluginInstallDetailLabel.Width := WizardForm.ProgressGauge.Width;
  PluginInstallDetailLabel.Caption := '';
  PluginInstallDetailLabel.Visible := False;

  FfmpegTitleLabel := TNewStaticText.Create(WizardForm);
  FfmpegTitleLabel.Parent := WizardForm.ProgressGauge.Parent;
  FfmpegTitleLabel.Left := WizardForm.ProgressGauge.Left;
  FfmpegTitleLabel.Top := PluginInstallDetailLabel.Top + PluginInstallDetailLabel.Height + ScaleY(14);
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
  if ShowMaintenancePage then
  begin
    if (MaintenanceMode = MaintenanceRepairMode) and
       ((PageID = wpSelectDir) or (PageID = wpSelectProgramGroup) or
        (Assigned(OptionalFeaturesPage) and (PageID = OptionalFeaturesPage.ID)) or
        (Assigned(OptionalPluginsPage) and (PageID = OptionalPluginsPage.ID))) then
      Result := True
    else if (MaintenanceMode = MaintenanceChangeMode) and
            ((PageID = wpSelectDir) or (PageID = wpSelectProgramGroup)) then
      Result := True;
  end;
  if Assigned(OptionalFeaturesPage) and (PageID = OptionalFeaturesPage.ID) and (not ShouldShowOptionalFeaturesPage) then
    Result := True;
  if Assigned(OptionalPluginsPage) and (PageID = OptionalPluginsPage.ID) and (not ShouldShowOptionalPluginsPage) then
    Result := True;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if ShowMaintenancePage then
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
var
  FfmpegRequested: Boolean;
  PluginRequested: Boolean;
begin
  if (CurPageID <> wpInstalling) and
     ((not Assigned(OptionalPluginsPage)) or (CurPageID <> OptionalPluginsPage.ID)) then
    WizardForm.NextButton.Enabled := True;

  if Assigned(OptionalPluginsPage) and (CurPageID = OptionalPluginsPage.ID) then
  begin
    LoadPackagedInstallerPluginCatalog;
  end
  else if CurPageID = wpInstalling then
  begin
    FfmpegRequested := IsFfmpegRequested;
    PluginRequested := AnyPluginSelected;
    SetPluginInstallControlsVisible(PluginRequested);
    if PluginRequested then
      SetPluginInstallProgress(CustomMessage('PluginWaiting'), '', 0);
    SetFfmpegControlsVisible(FfmpegRequested);
    if FfmpegRequested then
      SetFfmpegProgress(CustomMessage('FfmpegWaiting'), '', 0);
  end
  else if CurPageID = wpFinished then
  begin
    if PluginInstalled then
      WizardForm.FinishedLabel.Caption := WizardForm.FinishedLabel.Caption + #13#10#13#10 + CustomMessage('PluginFinishedSuccess')
    else if PluginFailed then
      WizardForm.FinishedLabel.Caption := WizardForm.FinishedLabel.Caption + #13#10#13#10 + CustomMessage('PluginFinishedFailed');
    if FfmpegInstalled then
      WizardForm.FinishedLabel.Caption := WizardForm.FinishedLabel.Caption + #13#10#13#10 + CustomMessage('FfmpegFinishedSuccess')
    else if FfmpegFailed then
      WizardForm.FinishedLabel.Caption := WizardForm.FinishedLabel.Caption + #13#10#13#10 + CustomMessage('FfmpegFinishedFailed');
  end;
end;


function GetProcessExeName(var Entry: PROCESSENTRY32): String;
var
  I: Integer;
begin
  Result := '';
  for I := 0 to 259 do
  begin
    if Entry.szExeFile[I] = #0 then
      break;
    Result := Result + Entry.szExeFile[I];
  end;
end;

function IsApplicationRunning: Boolean;
var
  Snapshot: Longword;
  Entry: PROCESSENTRY32;
begin
  Result := False;
  Snapshot := CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
  if Snapshot = $FFFFFFFF then
    exit;
  try
    Entry.dwSize := SizeOf(Entry);
    if Process32First(Snapshot, Entry) then
    begin
      repeat
        if CompareText(GetProcessExeName(Entry), '{#AppExeName}') = 0 then
        begin
          Result := True;
          exit;
        end;
      until not Process32Next(Snapshot, Entry);
    end;
  finally
    CloseHandle(Snapshot);
  end;
end;

function EnsureApplicationClosed: String;
begin
  Result := '';
  if not ShouldInstallApplicationFiles then
    exit;
  while IsApplicationRunning do
  begin
    if SuppressibleMsgBox(CustomMessage('AppRunningError'), mbError, MB_RETRYCANCEL, IDCANCEL) <> IDRETRY then
    begin
      Result := CustomMessage('AppRunningAbort');
      exit;
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := EnsureApplicationClosed;
  if Result <> '' then
    exit;
  if ShouldInstallApplicationFiles then
    Result := RemoveLegacyMsiSilently(NeedsRestart);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ErrorMessage: String;
begin
  if (CurStep = ssPostInstall) and ShouldInstallApplicationFiles then
    ReconcileBundledDefaultPlugins;

  if (CurStep = ssPostInstall) and AnyPluginSelected then
  begin
    try
      DownloadAndInstallSelectedPlugins;
    except
      ErrorMessage := GetExceptionMessage;
      PluginFailed := True;
      Log('Optional plugin installation failed: ' + ErrorMessage);
      SetPluginInstallProgress(CustomMessage('PluginFailed'), ErrorMessage, 0);
      SuppressibleMsgBox(CustomMessage('PluginFailed') + #13#10#13#10 + ErrorMessage, mbError, MB_OK, IDOK);
    end;
  end;

  if (CurStep = ssPostInstall) and IsFfmpegRequested then
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
