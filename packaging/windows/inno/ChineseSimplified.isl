; Minimal Simplified Chinese overrides for Inno Setup.
; Default.isl is loaded first by PixivDownload.iss, so untranslated built-in
; messages safely fall back to English instead of requiring an external
; ChineseSimplified.isl file on the build machine.

[LangOptions]
LanguageName=简体中文
LanguageID=$0804
LanguageCodePage=936
DialogFontName=Microsoft YaHei UI
DialogFontSize=9
WelcomeFontName=Microsoft YaHei UI
WelcomeFontSize=12

[Messages]
SetupAppTitle=安装
SetupWindowTitle=安装 - %1
UninstallAppTitle=卸载
UninstallAppFullTitle=%1 卸载
InformationTitle=信息
ConfirmTitle=确认
ErrorTitle=错误

ButtonBack=< 上一步(&B)
ButtonNext=下一步(&N) >
ButtonInstall=安装(&I)
ButtonOK=确定
ButtonCancel=取消
ButtonYes=是(&Y)
ButtonNo=否(&N)
ButtonFinish=完成
ButtonBrowse=浏览(&B)...
ButtonWizardBrowse=浏览(&R)...
ButtonNewFolder=新建文件夹(&M)

WelcomeLabel1=欢迎使用 [name] 安装向导
WelcomeLabel2=这将在你的计算机上安装 [name/ver]。%n%n建议继续前关闭其他应用程序。
SelectDirDesc=安装程序会将 [name] 安装到以下文件夹。
SelectDirLabel3=单击“下一步”继续。如需选择其他文件夹，请单击“浏览”。
SelectDirBrowseLabel=选择要安装 [name] 的文件夹，然后单击“确定”。
SelectComponentsDesc=选择要安装的组件。
SelectComponentsLabel2=请选择要安装的组件，清除不需要安装的组件，然后单击“下一步”。
SelectTasksDesc=选择安装程序要执行的附加任务。
SelectTasksLabel2=请选择安装 [name] 时要执行的附加任务，然后单击“下一步”。
ReadyLabel1=安装程序已准备好开始在你的计算机上安装 [name]。
ReadyLabel2a=单击“安装”继续安装，或单击“上一步”查看或更改设置。
ReadyMemoDir=目标位置：
ReadyMemoType=安装类型：
ReadyMemoComponents=选定组件：
ReadyMemoGroup=开始菜单文件夹：
ReadyMemoTasks=附加任务：
InstallingLabel=请稍候，安装程序正在你的计算机上安装 [name]。
FinishedHeadingLabel=[name] 安装完成
FinishedLabelNoIcons=安装程序已在你的计算机上安装 [name]。
FinishedLabel=安装程序已在你的计算机上安装 [name]。可以通过选择已安装的快捷方式运行该应用程序。
ExitSetupTitle=退出安装程序
ExitSetupMessage=安装尚未完成。如果现在退出，程序将不会安装。%n%n你可以稍后再次运行安装程序完成安装。%n%n确定要退出安装程序吗？
