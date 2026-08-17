; Minimal Traditional Chinese overrides for Inno Setup.
; Default.isl is loaded first by PixivDownload.iss, so untranslated built-in
; messages safely fall back to English instead of requiring a full translation.

[LangOptions]
LanguageName=繁體中文
LanguageID=$7C04
LanguageCodePage=950
DialogFontName=Microsoft JhengHei UI
DialogFontSize=9
WelcomeFontName=Microsoft JhengHei UI
WelcomeFontSize=12

[Messages]
SetupAppTitle=安裝
SetupWindowTitle=安裝 - %1
UninstallAppTitle=解除安裝
UninstallAppFullTitle=%1 解除安裝
InformationTitle=資訊
ConfirmTitle=確認
ErrorTitle=錯誤

ButtonBack=< 上一步(&B)
ButtonNext=下一步(&N) >
ButtonInstall=安裝(&I)
ButtonOK=確定
ButtonCancel=取消
ButtonYes=是(&Y)
ButtonNo=否(&N)
ButtonFinish=完成
ButtonBrowse=瀏覽(&B)...
ButtonWizardBrowse=瀏覽(&R)...
ButtonNewFolder=新增資料夾(&M)

WelcomeLabel1=歡迎使用 [name] 安裝精靈
WelcomeLabel2=這將在您的電腦上安裝 [name/ver]。%n%n建議繼續前關閉其他應用程式。
SelectDirDesc=安裝程式會將 [name] 安裝到以下資料夾。
SelectDirLabel3=按一下「下一步」繼續。如需選擇其他資料夾，請按一下「瀏覽」。
SelectDirBrowseLabel=選擇要安裝 [name] 的資料夾，然後按一下「確定」。
SelectComponentsDesc=選擇要安裝的元件。
SelectComponentsLabel2=請選擇要安裝的元件，清除不需要安裝的元件，然後按一下「下一步」。
SelectTasksDesc=選擇安裝程式要執行的其他工作。
SelectTasksLabel2=請選擇安裝 [name] 時要執行的其他工作，然後按一下「下一步」。
ReadyLabel1=安裝程式已準備好開始在您的電腦上安裝 [name]。
ReadyLabel2a=按一下「安裝」繼續安裝，或按一下「上一步」檢視或變更設定。
ReadyMemoDir=目的地位置：
ReadyMemoType=安裝類型：
ReadyMemoComponents=選取的元件：
ReadyMemoGroup=開始功能表資料夾：
ReadyMemoTasks=其他工作：
InstallingLabel=請稍候，安裝程式正在您的電腦上安裝 [name]。
FinishedHeadingLabel=[name] 安裝完成
FinishedLabelNoIcons=安裝程式已在您的電腦上安裝 [name]。
FinishedLabel=安裝程式已在您的電腦上安裝 [name]。您可以使用已建立的捷徑執行此應用程式。
ExitSetupTitle=結束安裝程式
ExitSetupMessage=安裝尚未完成。如果現在結束，程式將不會安裝。%n%n您可以稍後再次執行安裝程式完成安裝。%n%n確定要結束安裝程式嗎？
