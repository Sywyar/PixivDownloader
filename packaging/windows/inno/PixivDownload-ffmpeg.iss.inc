procedure SetFfmpegControlsVisible(Visible: Boolean);
begin
  FfmpegTitleLabel.Visible := Visible;
  FfmpegDetailLabel.Visible := Visible;
  FfmpegProgressBar.Visible := Visible;
  LayoutInstallProgressControls;
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


procedure ExtractFfmpegInstallerSupportFiles;
begin
  ExtractTemporaryFile(PluginSignatureToolName);
  ExtractTemporaryFile(FfmpegDownloadScriptName);
  ExtractTemporaryFile(FfmpegIntegrityScriptName);
end;


function ShouldShowOptionalFeaturesPage: Boolean;
begin
  ResolveExistingInstallation;
  Result :=
    (not ExistingInstallationFound) or
    (MaintenanceMode = MaintenanceChangeMode);
end;


function IsFfmpegRequested: Boolean;
begin
  Result := Assigned(FfmpegCheckBox) and FfmpegCheckBox.Checked and ShouldShowOptionalFeaturesPage;
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
  ExtractFfmpegInstallerSupportFiles;
  ScriptPath := ExpandConstant('{tmp}\' + FfmpegDownloadScriptName);
  ProgressPath := ExpandConstant('{tmp}\pixivdownload-ffmpeg-download.progress');
  PowerShellPath := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');

  DeleteFile(ProgressPath);

  Params :=
    '-NoProfile -ExecutionPolicy Bypass -File ' + DoubleQuote(ScriptPath) +
    ' -ReleaseBaseUrl ' + DoubleQuote('{#FfmpegReleaseBaseUrl}') +
    ' -AssetName ' + DoubleQuote('{#FfmpegAssetName}') +
    ' -OutFile ' + DoubleQuote(ArchivePath) +
    ' -ProgressFile ' + DoubleQuote(ProgressPath) +
    ' -SignatureToolJar ' + DoubleQuote(SignatureToolTempPath) +
    ' -JavaPath ' + DoubleQuote(ExpandConstant('{app}\runtime\bin\java.exe')) +
    ' -IntegrityScript ' + DoubleQuote(ExpandConstant('{tmp}\' + FfmpegIntegrityScriptName)) +
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
  SourceFfmpegLicense: String;
  SourceLibwebpLicense: String;
  SourceLibwebpPatents: String;
  TargetDir: String;
  LicenseDir: String;
begin
  SourceFfmpeg := '';
  SourceFfprobe := '';
  SourceFfmpegLicense := '';
  SourceLibwebpLicense := '';
  SourceLibwebpPatents := '';
  if (not FindFileRecursive(ExtractDir, 'ffmpeg.exe', SourceFfmpeg)) or
     (not FindFileRecursive(ExtractDir, 'ffprobe.exe', SourceFfprobe)) or
     (not FindFileRecursive(ExtractDir, FfmpegLicenseName, SourceFfmpegLicense)) or
     (not FindFileRecursive(ExtractDir, LibwebpLicenseName, SourceLibwebpLicense)) or
     (not FindFileRecursive(ExtractDir, LibwebpPatentsName, SourceLibwebpPatents)) then
    RaiseException(CustomMessage('FfmpegArchiveInvalid'));

  SetFfmpegProgress(CustomMessage('FfmpegInstallingFiles'), '', 100);

  TargetDir := ExpandConstant('{app}\tools\ffmpeg');
  LicenseDir := TargetDir + '\licenses';
  ForceDirectories(TargetDir);
  ForceDirectories(LicenseDir);

  if (not CopyFile(SourceFfmpeg, TargetDir + '\ffmpeg.exe', False)) or
     (not CopyFile(SourceFfprobe, TargetDir + '\ffprobe.exe', False)) then
    RaiseException(CustomMessage('FfmpegCopyFailed'));

  if (not CopyFile(SourceFfmpegLicense, LicenseDir + '\' + FfmpegLicenseName, False)) or
     (not CopyFile(SourceLibwebpLicense, LicenseDir + '\' + LibwebpLicenseName, False)) or
     (not CopyFile(SourceLibwebpPatents, LicenseDir + '\' + LibwebpPatentsName, False)) then
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
