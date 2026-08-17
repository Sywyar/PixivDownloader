; Minimal Japanese overrides for Inno Setup.
; Default.isl is loaded first by PixivDownload.iss, so untranslated built-in
; messages safely fall back to English instead of requiring a full translation.

[LangOptions]
LanguageName=日本語
LanguageID=$0411
LanguageCodePage=932
DialogFontName=Yu Gothic UI
DialogFontSize=9
WelcomeFontName=Yu Gothic UI
WelcomeFontSize=12

[Messages]
SetupAppTitle=セットアップ
SetupWindowTitle=セットアップ - %1
UninstallAppTitle=アンインストール
UninstallAppFullTitle=%1 のアンインストール
InformationTitle=情報
ConfirmTitle=確認
ErrorTitle=エラー

ButtonBack=< 戻る(&B)
ButtonNext=次へ(&N) >
ButtonInstall=インストール(&I)
ButtonOK=OK
ButtonCancel=キャンセル
ButtonYes=はい(&Y)
ButtonNo=いいえ(&N)
ButtonFinish=完了
ButtonBrowse=参照(&B)...
ButtonWizardBrowse=参照(&R)...
ButtonNewFolder=新しいフォルダー(&M)

WelcomeLabel1=[name] セットアップウィザードへようこそ
WelcomeLabel2=このウィザードで [name/ver] をコンピューターにインストールします。%n%n続行する前に、ほかのアプリケーションを終了することをおすすめします。
SelectDirDesc=インストーラーは [name] を次のフォルダーにインストールします。
SelectDirLabel3=「次へ」をクリックして続行してください。別のフォルダーを選ぶ場合は「参照」をクリックしてください。
SelectDirBrowseLabel=[name] をインストールするフォルダーを選び、「OK」をクリックしてください。
SelectComponentsDesc=インストールするコンポーネントを選択
SelectComponentsLabel2=インストールするコンポーネントを選び、不要なものの選択を解除してから「次へ」をクリックしてください。
SelectTasksDesc=インストーラーで実行する追加タスクを選択
SelectTasksLabel2=[name] のインストール時に実行する追加タスクを選び、「次へ」をクリックしてください。
ReadyLabel1=コンピューターに [name] をインストールする準備ができました。
ReadyLabel2a=「インストール」をクリックして続行するか、「戻る」をクリックして設定を確認・変更してください。
ReadyMemoDir=インストール先：
ReadyMemoType=インストールの種類：
ReadyMemoComponents=選択したコンポーネント：
ReadyMemoGroup=スタートメニューフォルダー：
ReadyMemoTasks=追加タスク：
InstallingLabel=しばらくお待ちください。[name] をコンピューターにインストールしています。
FinishedHeadingLabel=[name] のインストールが完了しました
FinishedLabelNoIcons=[name] をコンピューターにインストールしました。
FinishedLabel=[name] をコンピューターにインストールしました。作成されたショートカットからアプリケーションを起動できます。
ExitSetupTitle=セットアップを終了
ExitSetupMessage=インストールは完了していません。今終了すると、プログラムはインストールされません。%n%n後でセットアップをもう一度実行してインストールを完了できます。%n%nセットアップを終了しますか？
