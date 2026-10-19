type
  TInstallerProgressConsumer = procedure(const Line: String);
  TInstallerProcessInfo = record
    Size: Longword;
    Mask: Longword;
    Wnd: Longword;
    Verb: String;
    FileName: String;
    Parameters: String;
    Directory: String;
    Show: Integer;
    Instance: Longword;
    IDList: Longword;
    ClassName: String;
    ClassKey: Longword;
    HotKey: Longword;
    Icon: Longword;
    Process: Longword;
  end;

function InstallerShellExecute(var Info: TInstallerProcessInfo): Boolean;
external 'ShellExecuteExW@shell32.dll stdcall';

function InstallerWaitForProcess(Process: Longword; Milliseconds: Longword): Longword;
external 'WaitForSingleObject@kernel32.dll stdcall';

function InstallerGetExitCode(Process: Longword; var ExitCode: Longword): Boolean;
external 'GetExitCodeProcess@kernel32.dll stdcall';

function DecodeCatalogField(const Value: String): String;
begin
  Result := Value;
  StringChangeEx(Result, '%7C', '|', True);
  StringChangeEx(Result, '%25', '%', True);
end;

function ReadProgressLineUtf8(const ProgressPath: String; var ProgressText: String): Boolean;
var
  Lines: TArrayOfString;
  I: Integer;
begin
  Result := False;
  ProgressText := '';
  if not FileExists(ProgressPath) then exit;
  if not LoadStringsFromFile(ProgressPath, Lines) then exit;
  for I := GetArrayLength(Lines) - 1 downto 0 do
    if Trim(Lines[I]) <> '' then begin
      ProgressText := Lines[I];
      Result := True;
      exit;
    end;
end;

procedure RunInstallerHelper(const FileName, Parameters, ProgressPath, FailureMessage: String;
  OnProgress: TInstallerProgressConsumer);
var
  Info: TInstallerProcessInfo;
  WaitResult, ExitCode: Longword;
  ProgressText, CallbackError: String;
begin
  Info.Size := SizeOf(Info);
  { SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI }
  Info.Mask := $00000440;
  Info.Verb := 'open';
  Info.FileName := FileName;
  Info.Parameters := Parameters;
  Info.Show := SW_HIDE;
  if not InstallerShellExecute(Info) then
    RaiseException(FailureMessage + ': ' + SysErrorMessage(DLLGetLastError));
  if Info.Process = 0 then RaiseException(FailureMessage);
  try
    CallbackError := '';
    repeat
      WaitResult := InstallerWaitForProcess(Info.Process, 0);
      if WaitResult = $FFFFFFFF then
        RaiseException(FailureMessage + ': ' + SysErrorMessage(DLLGetLastError));
      if ReadProgressLineUtf8(ProgressPath, ProgressText) and (CallbackError = '') then
        try
          OnProgress(ProgressText);
        except
          CallbackError := GetExceptionMessage;
        end;
      if WaitResult <> 0 then ResponsiveSleep(200);
    until WaitResult = 0;
    if not InstallerGetExitCode(Info.Process, ExitCode) then
      RaiseException(FailureMessage + ': ' + SysErrorMessage(DLLGetLastError));
    if CallbackError <> '' then RaiseException(CallbackError);
    if Copy(ProgressText, 1, 6) = 'ERROR|' then
      RaiseException(DecodeCatalogField(Copy(ProgressText, 7, Length(ProgressText))));
    if ExitCode <> 0 then RaiseException(FailureMessage + ' (' + IntToStr(ExitCode) + ')');
    if Copy(ProgressText, 1, 5) <> 'DONE|' then RaiseException(FailureMessage);
  finally
    CloseHandle(Info.Process);
  end;
end;
