type
  TPluginPreviewRow = record
    Name, CurrentVersion, BundledVersion, Reason, Source, Operation: String;
    Selected, DefaultSelected, SavedSelected, Selectable, Compatible, Required: Boolean;
  end;
var
  PluginPreviewPage: TWizardPage;
  PreviewSummary, PreviewAttention, PreviewStatus: TNewStaticText;
  PreviewEdit, PreviewDefaults, PreviewCancelEdit, PreviewOther: TNewButton;
  PreviewProgress: TNewProgressBar;
  PreviewRows: array of TPluginPreviewRow;
  PreviewDirectory, PreviewPlanFile, PreviewProjection, PreviewChoices, PreviewManifest: String;
  PreviewEditing, PreviewExpanded, PreviewLoaded, PreviewBusy, PreviewValidated: Boolean;
  PreviewPending: Boolean;

function ShouldInstallApplicationFiles: Boolean; forward;
procedure RenderPluginPreview; forward;
procedure StartPendingPluginPreview; forward;

function PreviewSetTimer(Window, Id, Interval, Callback: Longword): Longword;
external 'SetTimer@user32.dll stdcall';
function PreviewKillTimer(Window, Id: Longword): Boolean;
external 'KillTimer@user32.dll stdcall';
function PreviewTicks: Int64;
external 'GetTickCount64@kernel32.dll stdcall';

#include "PixivDownload-preview-table.iss.inc"

function PreviewRowSelectable(Index: Integer): Boolean;
begin
  Result := PreviewEditing and (Index >= 0) and (Index < GetArrayLength(PreviewRows)) and PreviewRows[Index].Selectable;
end;

function PreviewBlockedCount: Integer;
var I: Integer;
begin
  Result := 0;
  for I := 0 to GetArrayLength(PreviewRows)-1 do
    if PreviewRows[I].Required and (not PreviewRows[I].Selected) and (not PreviewRows[I].Compatible) then
      Result := Result + 1;
end;

function PreviewOutcome(Index: Integer): String;
begin
  if PreviewRows[Index].Selected then Result := CustomMessage('Preview' + PreviewRows[Index].Operation)
  else if not PreviewRows[Index].Compatible then
  begin
    if PreviewRows[Index].Required then Result := CustomMessage('PreviewResolve')
    else Result := CustomMessage('PreviewUnavailable');
  end
  else Result := CustomMessage('PreviewKeep');
end;

procedure UpdatePreviewSummary;
var I, Selected, Attention, Blocked: Integer;
begin
  Selected := 0; Attention := 0;
  for I := 0 to GetArrayLength(PreviewRows)-1 do
  begin
    if PreviewRows[I].Selected then Selected := Selected + 1;
    if PreviewRows[I].Reason <> '' then Attention := Attention + 1;
  end;
  PreviewSummary.Caption := FmtMessage(CustomMessage('PreviewSummary'), [IntToStr(Selected), IntToStr(GetArrayLength(PreviewRows)-Selected)]);
  PreviewAttention.Caption := FmtMessage(CustomMessage('PreviewAttention'), [IntToStr(Attention)]);
  if PreviewExpanded then PreviewOther.Caption := '- ' else PreviewOther.Caption := '+ ';
  PreviewOther.Caption := PreviewOther.Caption + FmtMessage(CustomMessage('PreviewOther'), [IntToStr(GetArrayLength(PreviewRows)-Attention)]);
  Blocked := PreviewBlockedCount;
  if Blocked > 0 then PreviewStatus.Caption := #215 + ' ' + FmtMessage(CustomMessage('PreviewBlocked'), [IntToStr(Blocked)])
  else PreviewStatus.Caption := '';
  WizardForm.NextButton.Enabled := PreviewLoaded and (Blocked = 0) and (not PreviewEditing) and
    (not PreviewBusy) and (not PreviewPending);
end;

procedure UpdatePreviewTableRow(Row: Integer);
var I, Image, State: Integer; Name, Note, CurrentVersion, BundledVersion: String;
begin
  I := PreviewVisibleRows[Row];
  Name := PreviewRows[I].Name;
  Note := '';
  if PreviewRows[I].Required and (PreviewRows[I].Reason = 'incompatible') then Note := CustomMessage('PreviewRequired')
  else if PreviewRows[I].Reason = 'source' then
  begin
    if PreviewRows[I].Source = 'COMMUNITY_CATALOG' then Note := CustomMessage('PreviewCommunity')
    else if PreviewRows[I].Source = 'LOCAL_UPLOAD' then Note := CustomMessage('PreviewLocal')
    else Note := CustomMessage('PreviewUnknownSource');
  end
  else if PreviewRows[I].Reason <> '' then Note := CustomMessage('PreviewReason' + PreviewRows[I].Reason);
  State := 0;
  if PreviewRowSelectable(I) then
  begin
    State := $1000;
    if PreviewRows[I].Selected then State := $2000;
  end;
  Image := 2;
  if PreviewRows[I].Selected then
  begin
    Image := 3;
    if PreviewRows[I].Operation = 'downgrade' then Image := 4;
  end else if not PreviewRows[I].Compatible then Image := 0
  else if PreviewRows[I].Reason <> '' then Image := 1;
  CurrentVersion := PreviewRows[I].CurrentVersion;
  BundledVersion := PreviewRows[I].BundledVersion;
  if CurrentVersion = '' then CurrentVersion := #8212;
  if BundledVersion = '' then BundledVersion := #8212;
  PreviewTableUpdating := True;
  try
    PreviewTableCell(Row, 0, Name, Image, State);
    PreviewTableCell(Row, 1, CurrentVersion, 0, 0);
    PreviewTableCell(Row, 2, BundledVersion, 0, 0);
    PreviewTableCell(Row, 3, Note, 0, 0);
    PreviewTableCell(Row, 4, PreviewOutcome(I), 0, 0);
  finally PreviewTableUpdating := False; end;
end;

procedure PreviewToggleRow(Index: Integer; Checked: Boolean);
var I: Integer;
begin
  if not PreviewRowSelectable(Index) then exit;
  PreviewRows[Index].Selected := Checked;
  PreviewValidated := False;
  for I := 0 to GetArrayLength(PreviewVisibleRows)-1 do
    if PreviewVisibleRows[I] = Index then UpdatePreviewTableRow(I);
  UpdatePreviewSummary;
end;

procedure RenderPluginPreview;
var I: Integer;
begin
  PreviewTableHost.Visible := True; PreviewAttention.Visible := True;
  PreviewEdit.Visible := True; PreviewOther.Visible := True;
  PreviewTableUpdating := True;
  try
    SendMessage(PreviewTable, $000B, 0, 0);
    SendMessage(PreviewTable, $1009, 0, 0);
    SetArrayLength(PreviewVisibleRows, 0);
    for I := 0 to GetArrayLength(PreviewRows)-1 do
      if PreviewExpanded or (PreviewRows[I].Reason <> '') then PreviewTableAddRow(I);
  finally PreviewTableUpdating := False; end;
  for I := 0 to GetArrayLength(PreviewVisibleRows)-1 do UpdatePreviewTableRow(I);
  SendMessage(PreviewTable, $000B, 1, 0);
  PreviewEdit.Caption := CustomMessage('PreviewStart');
  if PreviewEditing then PreviewEdit.Caption := CustomMessage('PreviewDone');
  PreviewDefaults.Visible := PreviewEditing; PreviewCancelEdit.Visible := PreviewEditing;
  UpdatePreviewSummary;
end;

procedure PreviewHelperProgress(const Line: String);
begin
  { 辅助进程报告完成状态，本地扫描不显示估算百分比。 }
end;

procedure SetPreviewBusy(Value: Boolean);
begin
  PreviewBusy := Value;
  WizardForm.BackButton.Enabled := not Value; WizardForm.CancelButton.Enabled := not Value;
  PreviewEdit.Enabled := not Value; PreviewOther.Enabled := (not Value) and PreviewLoaded;
  if Value then WizardForm.NextButton.Enabled := False
  else if PreviewLoaded and (WizardForm.CurPageID = PluginPreviewPage.ID) then UpdatePreviewSummary;
end;

procedure RunPreviewHelper(const Action: String);
var Parameters, ProgressPath, Language: String; WasBusy: Boolean;
begin
  Language := ActiveLanguage;
  if Language = 'zhcn' then Language := 'zh';
  if Language = 'zhhant' then Language := 'zh-Hant';
  ProgressPath := PreviewPlanFile + '.progress';
  DeleteFile(ProgressPath);
  Parameters := '-NoProfile -ExecutionPolicy Bypass -File ' + QuoteArg(ExpandConstant('{tmp}\installer-plugin-plan.ps1')) +
    ' -Action ' + Action + ' -ManifestFile ' + QuoteArg(PreviewManifest) +
    ' -InstallDir ' + QuoteArg(WizardDirValue) + ' -PlanFile ' + QuoteArg(PreviewPlanFile) +
    ' -ProjectionFile ' + QuoteArg(PreviewProjection) + ' -ChoicesFile ' + QuoteArg(PreviewChoices) +
    ' -SdkVersion ' + QuoteArg('{#SdkVersion}') + ' -AppVersion ' + QuoteArg('{#AppVersion}') +
    ' -Language ' + Language;
  WasBusy := PreviewBusy;
  SetPreviewBusy(True);
  try
    RunInstallerHelper(ExpandConstant('{sysnative}\WindowsPowerShell\v1.0\powershell.exe'), Parameters,
      ProgressPath, CustomMessage('PreviewReadFailed'), @PreviewHelperProgress);
  finally
    SetPreviewBusy(WasBusy);
  end;
end;

procedure LoadPluginPreview;
forward;

procedure CancelPendingPluginPreview;
begin
  if not PreviewPending then exit;
  PreviewPending := False;
  PreviewKillTimer(PreviewTableHost.Handle, 1);
end;

procedure ShowPreviewLoading;
begin
  PreviewLoaded := False; PreviewValidated := False; PreviewEditing := False;
  PreviewTableHost.Visible := False; PreviewAttention.Visible := False;
  PreviewEdit.Visible := False; PreviewOther.Visible := False;
  PreviewDefaults.Visible := False; PreviewCancelEdit.Visible := False;
  PreviewSummary.Caption := CustomMessage('PreviewLoading'); PreviewStatus.Caption := '';
  PreviewProgress.Style := npbstMarquee; PreviewProgress.Visible := True;
  WizardForm.NextButton.Enabled := False;
end;

procedure ShowPreviewFailure;
begin
  PreviewProgress.Visible := False; PreviewProgress.Style := npbstNormal;
  PreviewSummary.Caption := CustomMessage('PreviewReadFailed');
  PreviewEdit.Caption := CustomMessage('PreviewRetry'); PreviewEdit.Visible := True;
  PreviewOther.Enabled := False;
  WizardForm.NextButton.Enabled := False;
end;

procedure SchedulePluginPreview;
begin
  if PreviewBusy or PreviewPending then exit;
  if WizardSilent then begin LoadPluginPreview; exit; end;
  if PreviewLoaded and SameInstallationDirectory(PreviewDirectory, WizardDirValue) then
  begin RenderPluginPreview; exit; end;
  ShowPreviewLoading;
  { 首屏的 CurPageChanged 可能早于窗口显示；由窗口消息循环延后启动。 }
  PreviewPending := PreviewSetTimer(PreviewTableHost.Handle, 1, 25, 0) <> 0;
  if not PreviewPending then
  begin
    Log('Plugin preview scheduling failed: ' + SysErrorMessage(DLLGetLastError));
    ShowPreviewFailure;
  end;
end;

procedure StartPendingPluginPreview;
begin
  { KillTimer 不移除已入队的消息；过期通知不得重新启动检查。 }
  if not PreviewPending then exit;
  if WizardForm.CurPageID <> PluginPreviewPage.ID then
  begin CancelPendingPluginPreview; exit; end;
  if not WizardForm.Showing then exit;
  CancelPendingPluginPreview;
  LoadPluginPreview;
end;

procedure LoadPluginPreview;
var I, Count: Integer; Section: String; Started: Int64;
begin
  if PreviewBusy then exit;
  CancelPendingPluginPreview;
  if PreviewLoaded and SameInstallationDirectory(PreviewDirectory, WizardDirValue) then
  begin RenderPluginPreview; exit; end;
  ShowPreviewLoading;
  SetArrayLength(PreviewRows, 0);
  Started := PreviewTicks;
  SetPreviewBusy(True);
  Log('Plugin preview started.');
  try
    if WizardForm.Showing then WizardForm.Repaint;
    try
      ExtractTemporaryFile('installer-plugin-plan.ps1');
      ExtractTemporaryFile('plugin-package-metadata.ps1');
      ExtractTemporaryFile('locales.json');
      ExtractTemporaryFile('installer-plugin-install.ps1');
      ExtractTemporaryFile('installer-progress.ps1');
#if FileExists(AddBackslash(AppImageDir) + "plugins\plugins-manifest.json")
      ExtractTemporaryFile('bundled-plugins.json');
#else
      SaveStringToFile(PreviewManifest, '[]', False);
#endif
      Log('Plugin preview helpers extracted: elapsed_ms=' + IntToStr(PreviewTicks - Started));
      RunPreviewHelper('Scan');
      Count := StrToIntDef(GetIniString('plan', 'count', '', PreviewProjection), -1);
      if (Count < 0) or (Count > 1024) then RaiseException('Invalid plugin preview projection.');
      SetArrayLength(PreviewRows, Count);
      for I := 0 to Count-1 do
      begin
        Section := 'row' + IntToStr(I);
        PreviewRows[I].Name := GetIniString(Section, 'name', '', PreviewProjection);
        PreviewRows[I].CurrentVersion := GetIniString(Section, 'current', '', PreviewProjection);
        PreviewRows[I].BundledVersion := GetIniString(Section, 'bundled', '', PreviewProjection);
        PreviewRows[I].Reason := GetIniString(Section, 'reason', '', PreviewProjection);
        PreviewRows[I].Source := GetIniString(Section, 'source', '', PreviewProjection);
        PreviewRows[I].Operation := GetIniString(Section, 'operation', '', PreviewProjection);
        PreviewRows[I].Selected := GetIniBool(Section, 'selected', False, PreviewProjection);
        PreviewRows[I].DefaultSelected := PreviewRows[I].Selected;
        PreviewRows[I].Selectable := GetIniBool(Section, 'selectable', False, PreviewProjection);
        PreviewRows[I].Compatible := GetIniBool(Section, 'compatible', False, PreviewProjection);
        PreviewRows[I].Required := GetIniBool(Section, 'required', False, PreviewProjection);
      end;
      PreviewDirectory := WizardDirValue; PreviewLoaded := True;
      RenderPluginPreview;
      Log('Plugin preview loaded: packages=' + IntToStr(Count) + ', elapsed_ms=' + IntToStr(PreviewTicks - Started));
    except
      Log('Plugin preview failed: ' + GetExceptionMessage);
      PreviewLoaded := False;
      ShowPreviewFailure;
    end;
  finally
    PreviewProgress.Visible := False; PreviewProgress.Style := npbstNormal;
    SetPreviewBusy(False);
  end;
end;

procedure PreviewEditClick(Sender: TObject);
var I: Integer;
begin
  if not PreviewLoaded then begin SchedulePluginPreview; exit; end;
  if not PreviewEditing then
    for I := 0 to GetArrayLength(PreviewRows)-1 do PreviewRows[I].SavedSelected := PreviewRows[I].Selected;
  PreviewEditing := not PreviewEditing;
  RenderPluginPreview;
end;

procedure PreviewDefaultsClick(Sender: TObject);
var I: Integer;
begin
  for I := 0 to GetArrayLength(PreviewRows)-1 do PreviewRows[I].Selected := PreviewRows[I].DefaultSelected;
  PreviewValidated := False; RenderPluginPreview;
end;

procedure PreviewCancelClick(Sender: TObject);
var I: Integer;
begin
  for I := 0 to GetArrayLength(PreviewRows)-1 do PreviewRows[I].Selected := PreviewRows[I].SavedSelected;
  PreviewEditing := False; PreviewValidated := False; RenderPluginPreview;
end;

procedure PreviewOtherClick(Sender: TObject);
begin
  if not PreviewLoaded then exit;
  PreviewExpanded := not PreviewExpanded; RenderPluginPreview;
end;

function PreviewButton(const Caption: String; Left, Top, Width: Integer; Click: TNotifyEvent): TNewButton;
begin
  Result := TNewButton.Create(PluginPreviewPage); Result.Parent := PluginPreviewPage.Surface;
  Result.SetBounds(Left, Top, Width, ScaleY(25)); Result.Caption := Caption; Result.OnClick := Click;
end;

procedure InitializePluginPreview;
var W, H: Integer;
begin
  PluginPreviewPage := CreateCustomPage(OptionalPluginsPage.ID, CustomMessage('PreviewTitle'), 'SDK {#SdkVersion}');
  W := PluginPreviewPage.SurfaceWidth; H := PluginPreviewPage.SurfaceHeight;
  PreviewPlanFile := ExpandConstant('{tmp}\plugin-plan.json'); PreviewProjection := PreviewPlanFile + '.ini';
  PreviewChoices := PreviewPlanFile + '.choices'; PreviewManifest := ExpandConstant('{tmp}\bundled-plugins.json');
  PreviewSummary := TNewStaticText.Create(PluginPreviewPage); PreviewSummary.Parent := PluginPreviewPage.Surface;
  PreviewSummary.SetBounds(0, 0, W, ScaleY(22));
  PreviewProgress := TNewProgressBar.Create(PluginPreviewPage);
  PreviewProgress.Parent := PluginPreviewPage.Surface;
  PreviewProgress.TabStop := False;
  PreviewProgress.SetBounds(0, ScaleY(35), W, WizardForm.ProgressGauge.Height);
  PreviewProgress.Visible := False;
  PreviewAttention := TNewStaticText.Create(PluginPreviewPage); PreviewAttention.Parent := PluginPreviewPage.Surface;
  PreviewAttention.SetBounds(0, ScaleY(35), W div 3, ScaleY(22)); PreviewAttention.Font.Style := [fsBold];
  PreviewEdit := PreviewButton(CustomMessage('PreviewStart'), W-ScaleX(105), ScaleY(28), ScaleX(105), @PreviewEditClick);
  PreviewCancelEdit := PreviewButton(CustomMessage('PreviewCancel'), W-ScaleX(215), ScaleY(28), ScaleX(105), @PreviewCancelClick);
  PreviewDefaults := PreviewButton(CustomMessage('PreviewDefaults'), W-ScaleX(325), ScaleY(28), ScaleX(105), @PreviewDefaultsClick);
  PreviewCancelEdit.Visible := False; PreviewDefaults.Visible := False;
  CreatePreviewTable(PluginPreviewPage, ScaleY(64), H-ScaleY(103));
  PreviewOther := PreviewButton('', 0, H-ScaleY(30), ScaleX(180), @PreviewOtherClick);
  PreviewStatus := TNewStaticText.Create(PluginPreviewPage); PreviewStatus.Parent := PluginPreviewPage.Surface;
  PreviewStatus.SetBounds(ScaleX(190), H-ScaleY(24), W-ScaleX(190), ScaleY(22));
end;

function ValidatePluginPreview: String;
var I: Integer;
begin
  Result := '';
  LoadPluginPreview;
  if not PreviewLoaded then begin Result := CustomMessage('PreviewReadFailed'); exit; end;
  if PreviewBlockedCount > 0 then begin Result := FmtMessage(CustomMessage('PreviewBlocked'), [IntToStr(PreviewBlockedCount)]); exit; end;
  try
    if not SaveStringToFile(PreviewChoices, '[choices]' + #13#10, False) then
      RaiseException('Cannot write plugin selection.');
    for I := 0 to GetArrayLength(PreviewRows)-1 do
      if not SetIniString('choices', 'row' + IntToStr(I), IntToStr(Ord(PreviewRows[I].Selected)), PreviewChoices) then
        RaiseException('Cannot write plugin selection.');
    RunPreviewHelper('Validate'); PreviewValidated := True;
  except
    Log('Plugin preview validation failed: ' + GetExceptionMessage);
    PreviewLoaded := False; PreviewValidated := False;
    Result := CustomMessage('PreviewChanged');
  end;
end;

function ShouldInstallPlannedFile: Boolean;
var Path, Root, Relative: String;
begin
  Result := ShouldInstallApplicationFiles;
  if not Result then exit;
  Path := ExpandConstant(CurrentFilename); Root := AddBackslash(ExpandConstant('{app}'));
  if CompareText(Copy(Path, 1, Length(Root)), Root) <> 0 then begin Result := False; exit; end;
  Relative := Copy(Path, Length(Root)+1, Length(Path));
  if CompareText(Relative, 'plugins\LOCAL-UNSIGNED-BUILD.txt') = 0 then exit;
  if CompareText(Copy(Relative, 1, 8), 'plugins\') = 0 then
    Result := PreviewValidated and GetIniBool('files', Relative, False, PreviewPlanFile + '.files.ini');
end;

procedure FinalizePluginPreview;
begin
  if not PreviewValidated then RaiseException('Plugin selection was not validated.');
  RunPreviewHelper('Finalize');
end;

<event('DeinitializeSetup')>
procedure ReleasePluginPreview;
begin
  CancelPendingPluginPreview;
  if (PreviewCallback <> 0) and Assigned(PluginPreviewPage) then
  begin
    PreviewRemoveSubclass(PreviewTableHost.Handle, PreviewCallback, 1);
    PreviewCallback := 0;
  end;
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo,
  MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo: String): String;
var Sections: TArrayOfString; I: Integer; Version: String;
begin
  Result := '';
  Sections := [MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo, MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo];
  for I := 0 to GetArrayLength(Sections)-1 do
    if Sections[I] <> '' then Result := Result + Sections[I] + NewLine + NewLine;
  if ShouldInstallApplicationFiles and PreviewLoaded then
  begin
    Result := Result + CustomMessage('PreviewTitle') + ':' + NewLine;
    for I := 0 to GetArrayLength(PreviewRows)-1 do
    begin
      Version := PreviewRows[I].CurrentVersion;
      if PreviewRows[I].Selected then Version := PreviewRows[I].BundledVersion;
      Result := Result + Space + PreviewRows[I].Name + ' ' + Version + '  ' + PreviewOutcome(I) + NewLine;
    end;
  end;
end;
