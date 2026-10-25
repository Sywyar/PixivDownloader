[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$CompilerPath)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
. (Join-Path $repo 'scripts/plugin-distribution-common.ps1')
$sdkVersion = Get-PixivDownloadSdkVersion -ProjectRoot $repo
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixture = Join-Path $tempRoot ('pixiv-installer-helper-' + [Guid]::NewGuid().ToString('N'))
$encoding = [Text.UTF8Encoding]::new($true)
[IO.Directory]::CreateDirectory($fixture) | Out-Null
try {
    Copy-Item -LiteralPath (Join-Path $repo 'packaging/windows/inno/PixivDownload-process.iss.inc') -Destination $fixture
    Copy-Item -LiteralPath (Join-Path $repo 'packaging/windows/inno/installer-progress.ps1') -Destination $fixture
    & {
        . (Join-Path $fixture 'installer-progress.ps1')
        $ProgressFile = Join-Path $fixture ('progress-' + [char]0x4e2d + '/state.txt')
        $detail = [string][char]0x4e2d + [char]0x6587 + '|100%' + "`r`n"
        $expected = 'ERROR|' + [char]0x4e2d + [char]0x6587 + '%7C100%25  ' + "`n"
        Write-State ('PROGRESS|' + ('x' * 200))
        $reader = [IO.File]::Open($ProgressFile, 'Open', 'Read', 'ReadWrite')
        try { Write-State ('ERROR|' + (Escape-StateField $detail)) }
        finally { $reader.Dispose() }
        if (([IO.File]::ReadAllBytes($ProgressFile) -join ',') -ne ([Text.Encoding]::UTF8.GetBytes($expected) -join ',')) {
            throw 'Progress did not preserve UTF-8 fields or replace the previous state.'
        }
        $exclusive = [IO.File]::Open($ProgressFile, 'Open', 'ReadWrite', 'None')
        try {
            $rejected = $false
            try { Write-State 'DONE|1' } catch [IO.IOException] { $rejected = $true }
            if (-not $rejected) { throw 'Locked progress output was accepted.' }
        } finally { $exclusive.Dispose() }
        Write-State 'DONE|1'
        if ([IO.File]::ReadAllText($ProgressFile, [Text.Encoding]::UTF8) -cne "DONE|1`n") {
            throw 'Progress output did not recover after the lock was released.'
        }
    }
    $downloadScript = Join-Path $repo 'packaging/windows/inno/installer-ffmpeg-download.ps1'
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($downloadScript, [ref]$null, [ref]$parseErrors)
    if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
    $lastFunction = @($ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.FunctionDefinitionAst] })[-1]
    $downloadFailure = @'

function Invoke-Download { throw ([string][char]0x4e2d + [char]0x6587 + '|100%') }

'@
    $driver = Join-Path $fixture 'ffmpeg-with-download-failure.ps1'
    [IO.File]::WriteAllText($driver, ([IO.File]::ReadAllText($downloadScript).Insert($lastFunction.Extent.EndOffset, $downloadFailure)), $encoding)
    $progress = Join-Path $fixture 'download-failure.progress'
    & (Get-Process -Id $PID).Path -NoProfile -File $driver -ReleaseBaseUrl 'https://example.invalid/' -AssetName fixture.zip -OutFile (Join-Path $fixture 'output.zip') -ProgressFile $progress -SignatureToolJar unused -JavaPath unused -IntegrityScript (Join-Path $repo 'scripts/ffmpeg-release-integrity.ps1')
    if ($LASTEXITCODE -ne 1 -or [IO.File]::ReadAllText($progress, [Text.Encoding]::UTF8) -notlike ('ERROR|*' + [char]0x4e2d + [char]0x6587 + '%7C100%25*')) {
        throw 'FFmpeg helper did not propagate the Unicode download failure.'
    }
    $appImage = Join-Path $fixture 'app-image'
    [IO.Directory]::CreateDirectory($appImage) | Out-Null
    [IO.File]::WriteAllText((Join-Path $appImage 'fixture.txt'), 'compile input')
    $signatureTool = Join-Path $fixture 'signature.jar'
    [IO.File]::WriteAllText($signatureTool, 'compile input')
    & $CompilerPath '/Q' '/O-' "/DAppImageDir=$appImage" "/DSignatureToolJar=$signatureTool" "/DSdkVersion=$sdkVersion" (Join-Path $repo 'packaging/windows/inno/PixivDownload.iss')
    if ($LASTEXITCODE -ne 0) { throw "Installer entry compilation failed: $LASTEXITCODE" }
    $cases = [ordered]@{
        success = 'Write-State "PROGRESS|1|2|50"; Start-Sleep -Milliseconds 400; Write-State "DONE|2"'
        failed = 'exit 7'
        incomplete = 'exit 0'
        reported = 'Write-State "ERROR|detail%7C100%25"; exit 1'
        false_success = 'Write-State "DONE|2"; exit 7'
        drain = 'Write-State "DONE|2"; Start-Sleep -Milliseconds 600; Write-Finished'
        callback = 'Write-State "PROGRESS|1|2|50"; Start-Sleep -Milliseconds 600; Write-Finished; Write-State "DONE|2"'
        progress_only = 'Write-State "PROGRESS|1|2|50"'
        unicode = 'Write-State ("ERROR|" + (Escape-StateField ([string][char]0x4e2d + [char]0x6587 + "|100%"))); exit 1'
        partial = @'
Write-State 'PROGRESS'
Start-Sleep -Milliseconds 600
Write-State 'PROGRESS|40|80|50'
Start-Sleep -Milliseconds 600
Write-State 'DONE|1'
'@
    }
    $childPrefix = @'
$ErrorActionPreference = 'Stop'
$ProgressFile = $args[0]
. (Join-Path $PSScriptRoot 'installer-progress.ps1')
function Write-Finished { [IO.File]::WriteAllText("$ProgressFile.finished", 'finished') }
[IO.File]::WriteAllText("$ProgressFile.bitness", [string][Environment]::Is64BitProcess)
'@
    foreach ($case in $cases.GetEnumerator()) {
        [IO.File]::WriteAllText((Join-Path $fixture ($case.Key + '.ps1')), $childPrefix + "`n" + $case.Value, $encoding)
    }
    . (Join-Path $repo 'scripts/package-local-installer-catalog.ps1')
    $catalog = @'
{"entries":[
  {"pluginId":"optional","market":{"displayName":{"en":"Reader's | 100%","zh":"\u4e2d\u6587"},"summary":{"en":"line\nnext","zh":"\u6458\u8981"}},"packages":[{"version":"2.3.4","packageUrl":"https://example.invalid/optional.jar","sha256":"fixture","expectedSizeBytes":123,"signature":{},"requiredSdk":"__SDK__"}]},
  {"pluginId":"fallback","market":{"displayName":{"en":"Fallback"}},"packages":[{"version":"3.2.1","packageUrl":"https://example.invalid/fallback.jar","sha256":"fixture","expectedSizeBytes":123,"signature":{},"requiredSdk":"__SDK__"}]},
  {"pluginId":"required","market":{"officialRequired":true},"packages":[]},
  {"pluginId":"default","market":{"defaultInstalled":true},"packages":[]},
  {"pluginId":"incompatible","market":{},"packages":[{"version":"4.5.6","packageUrl":"https://example.invalid/incompatible.jar","sha256":"fixture","expectedSizeBytes":123,"signature":{},"requiredSdk":"__INCOMPATIBLE__"}]}
]}
'@
    $catalog = $catalog.Replace('__SDK__', $sdkVersion).Replace('__INCOMPATIBLE__', ([string]([int]$sdkVersion.Split('.')[0] + 1) + '.0'))
    $manifest = Join-Path $fixture 'manifest.json'
    $catalogInclude = Join-Path $fixture 'catalog.iss.inc'
    $catalog = $catalog | ConvertFrom-Json
    $catalog.entries[2].packages = $catalog.entries[0].packages
    $catalog.entries[3].packages = $catalog.entries[0].packages
    [IO.File]::WriteAllText($manifest, ($catalog | ConvertTo-Json -Depth 10), $encoding)
    Write-InstallerPluginCatalogInclude -ManifestPath $manifest -OutputPath $catalogInclude -SdkVersion $sdkVersion
    $installerSource = [IO.File]::ReadAllText((Join-Path $repo 'packaging/windows/inno/PixivDownload.iss'))
    $progressSource = [regex]::Match($installerSource, '(?s)\[Code\](.*?)(?=function CreateToolhelp32Snapshot)').Groups[1].Value
    $progressSource += [regex]::Match($installerSource, '(?s)\nvar\s+MaintenancePage:.*?(?=procedure ProcessInstallerMessages;)').Value
    foreach ($sourceName in @('PixivDownload-lifecycle.iss.inc', 'PixivDownload.iss', 'PixivDownload-plugins.iss.inc', 'PixivDownload-ffmpeg.iss.inc')) {
        $content = [IO.File]::ReadAllText((Join-Path $repo "packaging/windows/inno/$sourceName"))
        foreach ($routine in [regex]::Matches($content, '(?ms)^procedure (?:ProcessInstallerMessages|SetInstallerProgress|SetPluginInstallProgress|SetFfmpegProgress|ApplyPluginInstallProgress|ClearPluginCatalogItems|AddPluginCatalogItem)\b(?!;\r?\nforward;).*?^end;')) {
            $progressSource += $routine.Value + "`n"
        }
    }
    $program = @'
[Setup]
AppName=Installer helper verification
AppVersion=1.0
DefaultDirName={tmp}\unused-installer-helper
CreateAppDir=no
Uninstallable=no
CreateUninstallRegKey=no
PrivilegesRequired=lowest
DisableStartupPrompt=yes
OutputDir=.
OutputBaseFilename=helper-test
Compression=none
[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "zhcn"; MessagesFile: "compiler:Default.isl"
[CustomMessages]
PluginInstalling=Plugin transfer
FfmpegDownloading=FFmpeg transfer
FfmpegProxyDetected=Proxy
FfmpegDirectDownload=Direct
[Code]
var
  CurrentCase: String;
  LastClosedHandle: Longword;
  CloseCount, CallbackCount: Integer;
  SawPartial, SawComplete: Boolean;

function NativeCloseHandle(Handle: Longword): Boolean;
external 'CloseHandle@kernel32.dll stdcall';
function GetHandleInformation(Handle: Longword; var Flags: Longword): Boolean;
external 'GetHandleInformation@kernel32.dll stdcall';

function CloseHandle(Handle: Longword): Boolean;
begin
  LastClosedHandle := Handle;
  CloseCount := CloseCount + 1;
  Result := NativeCloseHandle(Handle);
end;

procedure ResponsiveSleep(const Milliseconds: Integer);
begin
  Sleep(Milliseconds);
end;

#include "PixivDownload-process.iss.inc"

__PROGRESS_SOURCE__

__CATALOG_SOURCE__

procedure CheckCatalog;
var
  Expected: String;
begin
  PluginCheckList := TNewCheckListBox.Create(WizardForm);
  PluginCheckList.Parent := WizardForm.InstallingPage;
  LoadCompiledInstallerPluginCatalogItems;
  ClearPluginCatalogItems;
  LoadCompiledInstallerPluginCatalogItems;
  if (GetArrayLength(PluginIds) <> 2) or (PluginCheckList.Items.Count <> 2) then
    RaiseException('Unexpected catalog entries');
  if (PluginIds[0] <> 'optional') or (PluginIds[1] <> 'fallback') then
    RaiseException('Unexpected catalog identity or order');
  Expected := 'Reader''s | 100%  v2.3.4 - line next';
  if ActiveLanguage = 'zhcn' then Expected := #20013#25991 + '  v2.3.4 - ' + #25688#35201;
  if (PluginCheckList.Items[0] <> Expected) or
     (PluginCheckList.Items[1] <> 'Fallback  v3.2.1') then
    RaiseException('Catalog text did not survive generation and rendering');
  Log('CATALOG_OK:' + ActiveLanguage);
end;

procedure CheckProgress(const Name, Title, Detail: String; Percent: Integer);
begin
  if (PluginInstallTitleLabel.Caption <> Title) or
     (PluginInstallDetailLabel.Caption <> Detail) or
     (PluginInstallProgressBar.Position <> Percent) or
     (WizardForm.StatusLabel.Caption <> Title) then
    RaiseException(Name + ': unexpected plugin progress');
end;

procedure CheckProgressControls;
var
  I: Integer;
  Line, Title: String;
begin
  PluginInstallTitleLabel := TNewStaticText.Create(WizardForm);
  PluginInstallTitleLabel.Parent := WizardForm.InstallingPage;
  PluginInstallDetailLabel := TNewStaticText.Create(WizardForm);
  PluginInstallDetailLabel.Parent := WizardForm.InstallingPage;
  PluginInstallProgressBar := TNewProgressBar.Create(WizardForm);
  PluginInstallProgressBar.Parent := WizardForm.InstallingPage;
  PluginInstallProgressBar.Max := 100;
  FfmpegTitleLabel := TNewStaticText.Create(WizardForm);
  FfmpegTitleLabel.Parent := WizardForm.InstallingPage;
  FfmpegDetailLabel := TNewStaticText.Create(WizardForm);
  FfmpegDetailLabel.Parent := WizardForm.InstallingPage;
  FfmpegProgressBar := TNewProgressBar.Create(WizardForm);
  FfmpegProgressBar.Parent := WizardForm.InstallingPage;
  FfmpegProgressBar.Max := 100;
  Title := #20013#25991;
  SetPluginInstallProgress(Title, 'first', -5);
  CheckProgress('negative', Title, 'first', 0);
  SetPluginInstallProgress(Title, 'next', 60);
  SetPluginInstallProgress(Title, 'stale', 20);
  CheckProgress('monotonic', Title, 'stale', 60);
  SetFfmpegProgress(Title, 'other', 25);
  if (FfmpegProgressBar.Position <> 25) or (FfmpegTitleLabel.Caption <> Title) or
     (FfmpegDetailLabel.Caption <> 'other') then RaiseException('FFmpeg progress mismatch');
  SetFfmpegProgress(Title, 'stale', 10);
  if FfmpegProgressBar.Position <> 25 then RaiseException('FFmpeg progress regressed');
  CheckProgress('independent', Title, 'stale', 60);
  SetPluginInstallProgress(Title, 'clamped', 200);
  CheckProgress('upper bound', Title, 'clamped', 100);
  SetPluginInstallProgress('next task', 'reset', 5);
  CheckProgress('changed title', 'next task', 'reset', 5);
  SetFfmpegProgress('extract', 'reset', -1);
  if FfmpegProgressBar.Position <> 0 then RaiseException('FFmpeg title did not reset progress');
  ApplyPluginInstallProgress('PLUGIN|sample|1|2');
  CheckProgress('plugin', CustomMessage('PluginInstalling'), 'sample (1 / 2)', 0);
  ApplyPluginInstallProgress('PROGRESS|40|80|50');
  CheckProgress('download', CustomMessage('PluginInstalling'), '40 / 80 bytes', 50);
  Log('PROGRESS_COMPLETE_RECORDS_OK');
  Line := 'PROGRESS|40|80|';
  for I := 0 to Length(Line) - 1 do begin
    try
      ApplyPluginInstallProgress(Copy(Line, 1, I));
    except
      RaiseException('progress prefix ' + IntToStr(I) + ': ' + GetExceptionMessage);
    end;
    CheckProgress('partial progress', CustomMessage('PluginInstalling'), '40 / 80 bytes', 50);
  end;
  Line := 'PLUGIN|sample|1|';
  for I := 0 to Length(Line) - 1 do begin
    ApplyPluginInstallProgress(Copy(Line, 1, I));
    CheckProgress('partial plugin', CustomMessage('PluginInstalling'), '40 / 80 bytes', 50);
  end;
  ApplyPluginInstallProgress('PROGRESS|60|80|75');
  CheckProgress('resumed', CustomMessage('PluginInstalling'), '60 / 80 bytes', 75);
  Log('PROGRESS_CONTROLS_OK');
end;

procedure ObserveProgress(const Line: String);
begin
  CallbackCount := CallbackCount + 1;
  if CurrentCase = 'callback' then RaiseException('callback failure');
  if CurrentCase = 'partial' then begin
    if Line = 'PROGRESS' then SawPartial := True;
    if Line = 'PROGRESS|40|80|50' then SawComplete := True;
    ApplyPluginInstallProgress(Line);
  end;
end;

procedure CheckCase(const Name, ExpectedError: String);
var
  ProgramPath, ProgressPath, MessageText: String;
  Flags: Longword;
  BeforeClose: Integer;
  Bitness: AnsiString;
begin
  CurrentCase := Name;
  CallbackCount := 0;
  SawPartial := False;
  SawComplete := False;
  BeforeClose := CloseCount;
  ProgressPath := ExpandConstant('{src}\' + Name + '.progress');
  ProgramPath := ExpandConstant('{sysnative}\WindowsPowerShell\v1.0\powershell.exe');
  if Name = 'missing' then ProgramPath := ExpandConstant('{src}\missing.exe');
  MessageText := '';
  try
    RunInstallerHelper(ProgramPath,
      '-NoProfile -ExecutionPolicy Bypass -File "' + ExpandConstant('{src}\' + Name + '.ps1') + '" "' + ProgressPath + '"',
      ProgressPath, 'fixture failure', @ObserveProgress);
  except
    MessageText := GetExceptionMessage;
  end;
  if ((ExpectedError = '') and (MessageText <> '')) or
     ((ExpectedError <> '') and (Pos(ExpectedError, MessageText) = 0)) then
    RaiseException(Name + ': unexpected result: ' + MessageText);
  if Name = 'missing' then begin
    if CloseCount <> BeforeClose then RaiseException('Closed an unowned handle');
  end else begin
    if CloseCount <> BeforeClose + 1 then RaiseException(Name + ': missing process handle release');
    if GetHandleInformation(LastClosedHandle, Flags) then RaiseException(Name + ': process handle remained open');
    if not LoadStringFromFile(ProgressPath + '.bitness', Bitness) or (Bitness <> 'True') then
      RaiseException(Name + ': native PowerShell was not used');
  end;
  if ((Name = 'drain') or (Name = 'callback')) and not FileExists(ProgressPath + '.finished') then
    RaiseException(Name + ': returned before helper finished');
  if (Name = 'success') and (CallbackCount = 0) then RaiseException('No progress was delivered');
  if (Name = 'partial') and not (SawPartial and SawComplete) then
    RaiseException('Partial and complete records were not both observed');
  Log('CASE_OK:' + Name);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep <> ssInstall then exit;
  try
    CheckCatalog;
    CheckProgressControls;
    CheckCase('success', '');
    CheckCase('failed', '(7)');
    CheckCase('incomplete', 'fixture failure');
    CheckCase('reported', 'detail|100%');
    CheckCase('false_success', '(7)');
    CheckCase('drain', '');
    CheckCase('callback', 'callback failure');
    CheckCase('progress_only', 'fixture failure');
    CheckCase('unicode', #20013#25991 + '|100%');
    CheckCase('partial', '');
    CheckCase('missing', 'fixture failure');
    Log('ALL_CASES_PASSED');
  except
    Log('TEST_FAILED:' + GetExceptionMessage);
  end;
  Abort;
end;
'@
    $program = $program.Replace('__PROGRESS_SOURCE__', $progressSource)
    $program = $program.Replace('__CATALOG_SOURCE__', [IO.File]::ReadAllText($catalogInclude))
    $source = Join-Path $fixture 'helper-test.iss'
    [IO.File]::WriteAllText($source, $program, $encoding)
    & $CompilerPath '/Q' $source
    if ($LASTEXITCODE -ne 0) { throw "Inno compilation failed: $LASTEXITCODE" }
    foreach ($language in @('en', 'zhcn')) {
        $log = Join-Path $fixture "$language.log"
        $process = Start-Process -FilePath (Join-Path $fixture 'helper-test.exe') -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/LANG=$language", ('/LOG="' + $log + '"')) -WindowStyle Hidden -PassThru
        try {
            if (-not $process.WaitForExit(60000)) {
                $process.Kill()
                $process.WaitForExit()
                throw 'Installer helper verification did not terminate.'
            }
            $lines = Get-Content -LiteralPath $log -Encoding UTF8
            $lines | Where-Object { $_ -match 'CASE_OK:|TEST_FAILED:|ALL_CASES_PASSED|PROGRESS_.*_OK|CATALOG_OK:' } | Write-Output
            if ($process.ExitCode -ne 3 -or @($lines | Where-Object { $_ -match 'CASE_OK:' }).Count -ne $cases.Count + 1 -or
                -not ($lines -match "CATALOG_OK:$language") -or -not ($lines -match 'ALL_CASES_PASSED') -or ($lines -match 'TEST_FAILED:')) {
                throw "Installer helper verification failed (exit $($process.ExitCode))."
            }
        } finally { $process.Dispose() }
    }
} finally {
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    if (-not $resolvedFixture.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        (Get-Item -LiteralPath $resolvedFixture).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Unsafe fixture cleanup path.'
    }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
