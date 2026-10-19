procedure SetPluginInstallControlsVisible(Visible: Boolean);
begin
  PluginInstallTitleLabel.Visible := Visible;
  PluginInstallDetailLabel.Visible := Visible;
  PluginInstallProgressBar.Visible := Visible;
  LayoutInstallProgressControls;
end;

procedure SetPluginInstallProgress(const Title: String; const Detail: String; const Percent: Integer);
begin
  SetInstallerProgress(
    PluginInstallTitleLabel,
    PluginInstallDetailLabel,
    PluginInstallProgressBar,
    Title,
    Detail,
    Percent
  );
end;


function IsInstallerPluginCatalogEnabled: Boolean;
begin
#if InstallerPluginCatalogEnabled == "1"
  Result := True;
#else
  Result := False;
#endif
end;

function PackagedPluginCatalogManifestPath: String;
begin
  Result := ExpandConstant('{tmp}\installer-plugin-catalog.json');
end;

procedure ExtractPluginInstallerSupportFiles;
begin
  ExtractTemporaryFile(PluginInstallScriptName);
  ExtractTemporaryFile('installer-progress.ps1');
#if InstallerPluginCatalogEnabled == "1"
  ExtractTemporaryFile('installer-plugin-catalog.json');
  ExtractTemporaryFile('installer-plugin-catalog.json.sig');
  ExtractTemporaryFile(PluginSignatureToolName);
#endif
end;

function SignatureToolTempPath: String;
begin
  Result := ExpandConstant('{tmp}\' + PluginSignatureToolName);
end;


function QuoteArg(const Value: String): String;
begin
  Result := '"' + Value + '"';
end;


function ShouldShowOptionalPluginsPage: Boolean;
begin
  Result := ShouldShowOptionalFeaturesPage and IsInstallerPluginCatalogEnabled;
end;


function AnyPluginSelected: Boolean;
var
  I: Integer;
begin
  Result := False;
  if not IsInstallerPluginCatalogEnabled then
    exit;
  if not Assigned(PluginCheckList) then
    exit;
  for I := 0 to PluginCheckList.Items.Count - 1 do
  begin
    if PluginCheckList.Checked[I] then
    begin
      Result := True;
      exit;
    end;
  end;
end;

function SelectedPluginIds: String;
var
  I: Integer;
begin
  Result := '';
  if not IsInstallerPluginCatalogEnabled then
    exit;
  if not Assigned(PluginCheckList) then
    exit;
  for I := 0 to PluginCheckList.Items.Count - 1 do
  begin
    if PluginCheckList.Checked[I] then
    begin
      if Result <> '' then
        Result := Result + ',';
      Result := Result + PluginIds[I];
    end;
  end;
end;

procedure ClearPluginCatalogItems;
begin
  if Assigned(PluginCheckList) then
    PluginCheckList.Items.Clear;
  SetArrayLength(PluginIds, 0);
end;

procedure AddPluginCatalogItem(const PluginId, Version, DisplayName, Summary: String);
var
  Index: Integer;
  Caption: String;
begin
  Index := GetArrayLength(PluginIds);
  SetArrayLength(PluginIds, Index + 1);
  PluginIds[Index] := PluginId;
  Caption := DisplayName + '  v' + Version;
  if Summary <> '' then
    Caption := Caption + ' - ' + Summary;
  PluginCheckList.AddCheckBox(Caption, '', 0, False, True, False, True, nil);
end;

#if InstallerPluginCatalogEnabled == "1"
#include "..\..\..\build\installer-plugin-catalog-items.iss.inc"
#endif

procedure LoadPackagedInstallerPluginCatalog;
begin
  if PluginCatalogLoaded then
  begin
    if Assigned(PluginCheckList) then
      PluginCheckList.Enabled := True;
    WizardForm.NextButton.Enabled := True;
    exit;
  end;

  ClearPluginCatalogItems;
  if not IsInstallerPluginCatalogEnabled then
  begin
    PluginCatalogLoaded := True;
    if Assigned(PluginCheckList) then
      PluginCheckList.Enabled := True;
    if Assigned(PluginStatusLabel) then
      PluginStatusLabel.Caption := CustomMessage('PluginCatalogUnavailable');
    WizardForm.NextButton.Enabled := True;
    exit;
  end;

  if Assigned(PluginStatusLabel) then
    PluginStatusLabel.Caption := CustomMessage('PluginCatalogLoading');
  try
#if InstallerPluginCatalogEnabled == "1"
    PluginCatalogPath := PackagedPluginCatalogManifestPath;
    LoadCompiledInstallerPluginCatalogItems;
    if Assigned(PluginStatusLabel) then
      PluginStatusLabel.Caption := CustomMessage('PluginCatalogPackaged');
#else
    PluginCatalogPath := '';
    if Assigned(PluginStatusLabel) then
      PluginStatusLabel.Caption := CustomMessage('PluginCatalogUnavailable');
#endif
  except
    ClearPluginCatalogItems;
    PluginCatalogPath := '';
    if Assigned(PluginStatusLabel) then
      PluginStatusLabel.Caption := CustomMessage('PluginCatalogUnavailable');
  end;
  PluginCatalogLoaded := True;
  if Assigned(PluginCheckList) then
    PluginCheckList.Enabled := True;
  WizardForm.NextButton.Enabled := True;
end;

procedure ApplyPluginInstallProgress(const Line: String);
var
  CleanLine: String;
  Parts: TArrayOfString;
  Percent: Integer;
  Detail: String;
begin
  CleanLine := Line;
  StringChangeEx(CleanLine, #13, '', True);
  StringChangeEx(CleanLine, #10, '', True);
  Parts := StringSplit(CleanLine, ['|'], stAll);
  if GetArrayLength(Parts) < 4 then
    exit;
  if Parts[0] = 'PLUGIN' then
  begin
    Detail := Parts[1] + ' (' + Parts[2] + ' / ' + Parts[3] + ')';
    SetPluginInstallProgress(CustomMessage('PluginInstalling'), Detail, 0);
  end
  else if Parts[0] = 'PROGRESS' then
  begin
    Percent := StrToIntDef(Parts[3], 0);
    SetPluginInstallProgress(CustomMessage('PluginInstalling'), Parts[1] + ' / ' + Parts[2] + ' bytes', Percent);
  end;
end;

procedure DownloadAndInstallSelectedPlugins;
var
  ScriptPath: String;
  ProgressPath: String;
  Params: String;
begin
  if not IsInstallerPluginCatalogEnabled then
    exit;
  if (SelectedPluginIds = '') or (PluginCatalogPath = '') then
    exit;
  ExtractPluginInstallerSupportFiles;
  ScriptPath := ExpandConstant('{tmp}\' + PluginInstallScriptName);
  ProgressPath := ExpandConstant('{tmp}\pixivdownload-plugin-install.progress');
  DeleteFile(ProgressPath);
  SystemProxyUrl := ResolveSystemProxyUrl;

  Params :=
    '-ManifestFile ' + QuoteArg(PluginCatalogPath) +
    ' -PluginIds ' + QuoteArg(SelectedPluginIds) +
    ' -InstallDir ' + QuoteArg(ExpandConstant('{app}')) +
    ' -ProgressFile ' + QuoteArg(ProgressPath) +
    ' -SignatureToolJar ' + QuoteArg(SignatureToolTempPath) +
    ' -JavaPath ' + QuoteArg(ExpandConstant('{app}\runtime\bin\java.exe')) +
    ' -ProxyUrl ' + QuoteArg(SystemProxyUrl) +
    ' -SdkVersion ' + QuoteArg('{#SdkVersion}');

  SetPluginInstallProgress(CustomMessage('PluginInstalling'), '', 0);
  RunInstallerHelper(ExpandConstant('{sysnative}\WindowsPowerShell\v1.0\powershell.exe'),
    '-NoProfile -ExecutionPolicy Bypass -File ' + QuoteArg(ScriptPath) + ' ' + Params,
    ProgressPath, CustomMessage('PluginFailed'), @ApplyPluginInstallProgress);
  SetPluginInstallProgress(CustomMessage('PluginCompleted'), '', 100);
  PluginInstalled := True;
end;

procedure ReconcileBundledDefaultPlugins;
var
  ScriptPath: String;
  ManifestPath: String;
  Params: String;
  ResultCode: Integer;
begin
  ManifestPath := ExpandConstant('{app}\plugins\plugins-manifest.json');
  if not FileExists(ManifestPath) then
  begin
    Log('No bundled plugin manifest found; skipping default-plugin reconciliation.');
    exit;
  end;
  ExtractPluginInstallerSupportFiles;
  ScriptPath := ExpandConstant('{tmp}\' + PluginInstallScriptName);
  Params :=
    '-ReconcileBundledDefaults' +
    ' -ManifestFile ' + QuoteArg(ManifestPath) +
    ' -InstallDir ' + QuoteArg(ExpandConstant('{app}'));
  if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
    '-NoProfile -ExecutionPolicy Bypass -File ' + QuoteArg(ScriptPath) + ' ' + Params,
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
    RaiseException('Could not start bundled plugin reconciliation: ' + SysErrorMessage(ResultCode));
  if ResultCode <> 0 then
    RaiseException('Bundled plugin reconciliation failed with exit code ' + IntToStr(ResultCode) + '.');
end;
