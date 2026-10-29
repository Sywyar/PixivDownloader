const
  InstallationMarkerName = '.pixivdownload-installation';
  InstallationMarkerHeader = 'PixivDownload installation 1';
  RelocationMarkerHeader = 'PixivDownload relocation 1';

var
  RelocationSource: String;
  RelocationPending: Boolean;

function InstallationFileAttributes(const FileName: String): Longword;
external 'GetFileAttributesW@kernel32.dll stdcall';

function PublishInstallationMarker(const ExistingName, NewName: String; Flags: Longword): Boolean;
external 'MoveFileExW@kernel32.dll stdcall';

function SameInstallationDirectory(const Left, Right: String): Boolean;
begin
  Result := (Left <> '') and (Right <> '') and
    (CompareText(RemoveBackslashUnlessRoot(ExpandFileName(Left)),
      RemoveBackslashUnlessRoot(ExpandFileName(Right))) = 0);
end;

function InstallationPathIsPlain(const Path: String): Boolean;
var
  Current, Parent: String;
  Attributes: Longword;
begin
  Result := False;
  Current := RemoveBackslashUnlessRoot(ExpandFileName(Path));
  while Current <> '' do
  begin
    Attributes := InstallationFileAttributes(Current);
    if (Attributes <> $FFFFFFFF) and ((Attributes and $400) <> 0) then exit;
    Parent := RemoveBackslashUnlessRoot(ExtractFileDir(Current));
    if CompareText(Parent, Current) = 0 then break;
    Current := Parent;
  end;
  Result := True;
end;

function ReadInstallationMarker(const Directory: String; var RecordedDirectory: String): Boolean;
var
  FileName: String;
  Lines: TArrayOfString;
  Size: Int64;
begin
  Result := False;
  RecordedDirectory := '';
  RelocationPending := False;
  FileName := AddBackslash(Directory) + InstallationMarkerName;
  if not FileExists(FileName) then begin Result := True; exit; end;
  if not InstallationPathIsPlain(FileName) then exit;
  if not FileSize64(FileName, Size) then exit;
  if Size > 131072 then exit;
  if not LoadStringsFromFile(FileName, Lines) then exit;
  if GetArrayLength(Lines) <> 2 then exit;
  RelocationPending := Lines[0] = RelocationMarkerHeader;
  if not RelocationPending and (Lines[0] <> InstallationMarkerHeader) then exit;
  if (Lines[1] = '') and not RelocationPending then exit;
  if (Lines[1] <> '') and ((Length(Lines[1]) < 3) or (ExtractFileDrive(Lines[1]) = '')) then exit;
  RecordedDirectory := Lines[1];
  Result := True;
end;

procedure WriteInstallationMarker(const Header, Directory: String);
var
  Marker: String;
  Lines: TArrayOfString;
begin
  Marker := ExpandConstant('{app}\') + InstallationMarkerName;
  if not InstallationPathIsPlain(Marker) or not InstallationPathIsPlain(Marker + '.tmp') then
    RaiseException(CustomMessage('RelocationFailed'));
  SetArrayLength(Lines, 2);
  Lines[0] := Header;
  Lines[1] := Directory;
  if not SaveStringsToUTF8File(Marker + '.tmp', Lines, False) then
    RaiseException(CustomMessage('RelocationFailed'));
  if not PublishInstallationMarker(Marker + '.tmp', Marker, $9) then
    RaiseException(CustomMessage('RelocationFailed'));
end;

function IsInstallationUninstallerName(const Name: String): Boolean;
var
  I: Integer;
  Extension: String;
begin
  Result := False;
  if Length(Name) <> 12 then exit;
  if CompareText(Copy(Name, 1, 5), 'unins') <> 0 then exit;
  for I := 6 to 8 do
    if (Name[I] < '0') or (Name[I] > '9') then exit;
  Extension := Lowercase(Copy(Name, 9, 4));
  Result := (Extension = '.exe') or (Extension = '.dat') or (Extension = '.msg');
end;

function PrepareInstallationRelocation: String;
var
  Target, Recorded, Registered, BackupRoot, Candidate, FileName: String;
  Records: TArrayOfString;
  FindRec: TFindRec;
  I, Count, Visited: Integer;
  Relocated: Boolean;
  Lines: TArrayOfString;
begin
  Result := CustomMessage('RelocationFailed');
  Target := ExpandConstant('{app}');
  if not InstallationPathIsPlain(Target) then exit;
  if not ReadInstallationMarker(Target, Recorded) then exit;
  Registered := '';
  QueryMachineStringValue(InnoUninstallRegistryKey, 'InstallLocation', Registered);
  if Registered = '' then QueryMachineStringValue(AppRegistryKey, 'InstallLocation', Registered);
  Registered := Unquote(Registered);
  RelocationSource := Recorded;
  if RelocationSource = '' then RelocationSource := Registered;

  Count := 0;
  Visited := 0;
  if FindFirst(AddBackslash(Target) + 'unins*', FindRec) then
  try
    repeat
      Visited := Visited + 1;
      if Visited > 4000 then exit;
      if IsInstallationUninstallerName(FindRec.Name) then
      begin
        if (FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0 then exit;
        FileName := AddBackslash(Target) + FindRec.Name;
        if not InstallationPathIsPlain(FileName) then exit;
        SetArrayLength(Records, Count + 1);
        Records[Count] := FindRec.Name;
        Count := Count + 1;
        if Count > 3000 then exit;
      end;
    until not FindNext(FindRec);
  finally
    FindClose(FindRec);
  end;

  Relocated := RelocationPending or ((Recorded <> '') and not SameInstallationDirectory(Recorded, Target)) or
    ((Registered <> '') and not SameInstallationDirectory(Registered, Target)) or
    ((Recorded = '') and (Registered = '') and (Count > 0));
  if Relocated then
  begin
    if LegacyMsiProductCode <> '' then
    begin Result := CustomMessage('RelocationLegacyUnsupported'); exit; end;
    { 静默安装必须显式声明迁移；交互安装默认取消，避免复制来的卸载记录继续指向旧目录。 }
    if WizardSilent then
    begin
      if (ExpandConstant('{param:RELOCATE|0}') <> '1') or (ExpandConstant('{param:DIR|}') = '') then
      begin Result := CustomMessage('RelocationConfirmationRequired'); exit; end;
    end
    else if MsgBox(FmtMessage(CustomMessage('RelocationConfirm'), [RelocationSource, Target]),
      mbConfirmation, MB_YESNO or MB_DEFBUTTON2) <> IDYES then
    begin Result := CustomMessage('RelocationConfirmationRequired'); exit; end;

    BackupRoot := AddBackslash(Target) + 'state\installation-backups';
    if not InstallationPathIsPlain(BackupRoot) then exit;
    Candidate := '';
    for I := 0 to 99 do
    begin
      Candidate := AddBackslash(BackupRoot) + GetDateTimeString('yyyymmdd-hhnnss', '-', ':') + '-' + IntToStr(I);
      if not DirExists(Candidate) and not FileExists(Candidate) then break;
      Candidate := '';
    end;
    if Candidate = '' then exit;
    if not ForceDirectories(Candidate) then exit;
    SetArrayLength(Lines, 2);
    Lines[0] := RelocationSource;
    Lines[1] := Target;
    if not SaveStringsToUTF8File(AddBackslash(Candidate) + 'locations.txt', Lines, False) then exit;
    if FileExists(AddBackslash(Target) + InstallationMarkerName) then
      if not FileCopy(AddBackslash(Target) + InstallationMarkerName,
        AddBackslash(Candidate) + 'installation-marker.bak', True) then exit;
    WriteInstallationMarker(RelocationMarkerHeader, RelocationSource);
    for I := 0 to Count - 1 do
      if not RenameFile(AddBackslash(Target) + Records[I],
        AddBackslash(Candidate) + Records[I] + '.bak') then exit;
  end;
  Result := '';
end;

function LoadInstallationShortcut(const ShortcutPath: String): Variant;
var
  Shell, Folder, Item: Variant;
begin
  Shell := CreateOleObject('Shell.Application');
  Folder := Shell.NameSpace(ExtractFileDir(ShortcutPath));
  Item := Folder.ParseName(ExtractFileName(ShortcutPath));
  Result := Item.GetLink;
end;

procedure FinishInstallationRegistration;
var
  ShortcutPath, OldTarget: String;
  Shortcut: Variant;
begin
  ShortcutPath := ExpandConstant('{userstartup}\{#AppName}.lnk');
  if (RelocationSource <> '') and FileExists(ShortcutPath) then
  begin
    if not InstallationPathIsPlain(ShortcutPath) then RaiseException(CustomMessage('RelocationFailed'));
    Shortcut := LoadInstallationShortcut(ShortcutPath);
    OldTarget := Shortcut.Path;
    if SameInstallationDirectory(ExtractFileDir(OldTarget), RelocationSource) and
      (CompareText(ExtractFileName(OldTarget), '{#AppExeName}') = 0) then
    begin
      Shortcut.Path := ExpandConstant('{app}\{#AppExeName}');
      Shortcut.WorkingDirectory := ExpandConstant('{app}');
      Shortcut.SetIconLocation(ExpandConstant('{app}\{#AppExeName}'), 0);
      Shortcut.Save(ShortcutPath);
    end;
  end;
  WriteInstallationMarker(InstallationMarkerHeader, ExpandConstant('{app}'));
end;

procedure RemoveInstallationStartupShortcut;
var
  ShortcutPath, Target: String;
  Shortcut: Variant;
begin
  ShortcutPath := ExpandConstant('{userstartup}\{#AppName}.lnk');
  if not FileExists(ShortcutPath) then exit;
  if not InstallationPathIsPlain(ShortcutPath) then
    RaiseException(CustomMessage('UninstallStartupFailed'));
  Shortcut := LoadInstallationShortcut(ShortcutPath);
  Target := Shortcut.Path;
  if SameInstallationDirectory(ExtractFileDir(Target), ExpandConstant('{app}')) and
    (CompareText(ExtractFileName(Target), '{#AppExeName}') = 0) then
    if not DeleteFile(ShortcutPath) then RaiseException(CustomMessage('UninstallStartupFailed'));
end;
