procedure SetPluginInstallControlsVisible(Visible: Boolean);
begin
  PluginInstallTitleLabel.Visible := Visible;
  PluginInstallDetailLabel.Visible := Visible;
  PluginInstallProgressBar.Visible := Visible;
  LayoutInstallProgressControls;
end;

procedure SetPluginInstallProgress(const Title: String; const Detail: String; const Percent: Integer);
var
  SafePercent: Integer;
begin
  SafePercent := Percent;
  if SafePercent < 0 then
    SafePercent := 0;
  if SafePercent > 100 then
    SafePercent := 100;
  if PluginInstallProgressTitle <> Title then
  begin
    PluginInstallProgressTitle := Title;
    PluginInstallProgressLastPercent := -1;
  end;
  if SafePercent < PluginInstallProgressLastPercent then
    SafePercent := PluginInstallProgressLastPercent;
  PluginInstallProgressLastPercent := SafePercent;

  PluginInstallTitleLabel.Caption := Title;
  PluginInstallDetailLabel.Caption := Detail;
  PluginInstallProgressBar.Position := SafePercent;
  WizardForm.StatusLabel.Caption := Title;
  ProcessInstallerMessages;
end;


function DecodeCatalogField(const Value: String): String;
begin
  Result := Value;
  StringChangeEx(Result, '%7C', '|', True);
  StringChangeEx(Result, '%25', '%', True);
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
  SetArrayLength(PluginVersions, 0);
end;

procedure AddPluginCatalogItem(const PluginId, Version, DisplayName, Summary: String);
var
  Index: Integer;
  Caption: String;
begin
  Index := GetArrayLength(PluginIds);
  SetArrayLength(PluginIds, Index + 1);
  SetArrayLength(PluginVersions, Index + 1);
  PluginIds[Index] := PluginId;
  PluginVersions[Index] := Version;
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

function ReadProgressLineUtf8(const ProgressPath: String; var ProgressText: String): Boolean;
var
  Lines: TArrayOfString;
  I: Integer;
begin
  Result := False;
  ProgressText := '';
  if not FileExists(ProgressPath) then
    exit;
  if not LoadStringsFromFile(ProgressPath, Lines) then
    exit;
  for I := GetArrayLength(Lines) - 1 downto 0 do
  begin
    if Trim(Lines[I]) <> '' then
    begin
      ProgressText := Lines[I];
      Result := True;
      exit;
    end;
  end;
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
  if GetArrayLength(Parts) = 0 then
    exit;
  if Parts[0] = 'PLUGIN' then
  begin
    Detail := '';
    if GetArrayLength(Parts) >= 4 then
      Detail := Parts[1] + ' (' + Parts[2] + ' / ' + Parts[3] + ')';
    SetPluginInstallProgress(CustomMessage('PluginInstalling'), Detail, 0);
  end
  else if Parts[0] = 'PROGRESS' then
  begin
    Percent := 0;
    if GetArrayLength(Parts) >= 4 then
      Percent := StrToIntDef(Parts[3], 0);
    SetPluginInstallProgress(CustomMessage('PluginInstalling'), Parts[1] + ' / ' + Parts[2] + ' bytes', Percent);
  end;
end;

procedure DownloadAndInstallSelectedPlugins;
var
  ScriptPath: String;
  ProgressPath: String;
  Params: String;
  ResultCode: Integer;
  ProgressText: String;
  Parts: TArrayOfString;
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
  if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
    '-NoProfile -ExecutionPolicy Bypass -File ' + QuoteArg(ScriptPath) + ' ' + Params,
    '', SW_HIDE, ewNoWait, ResultCode) then
    RaiseException('Could not start optional plugin installer: ' + SysErrorMessage(ResultCode));

  while True do
  begin
    if ReadProgressLineUtf8(ProgressPath, ProgressText) then
    begin
      ApplyPluginInstallProgress(ProgressText);
      Parts := StringSplit(ProgressText, ['|'], stAll);
      if GetArrayLength(Parts) > 0 then
      begin
        if Parts[0] = 'DONE' then
        begin
          SetPluginInstallProgress(CustomMessage('PluginCompleted'), '', 100);
          PluginInstalled := True;
          exit;
        end
        else if Parts[0] = 'ERROR' then
        begin
          if GetArrayLength(Parts) >= 2 then
            RaiseException(DecodeCatalogField(Parts[1]))
          else
            RaiseException('Optional plugin installation failed.');
        end;
      end;
    end;
    ResponsiveSleep(200);
  end;
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
