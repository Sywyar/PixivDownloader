; Minimal Korean overrides for Inno Setup.
; Default.isl is loaded first by PixivDownload.iss, so untranslated built-in
; messages safely fall back to English instead of requiring a full translation.

[LangOptions]
LanguageName=한국어
LanguageID=$0412
LanguageCodePage=949
DialogFontName=Malgun Gothic
DialogFontSize=9
WelcomeFontName=Malgun Gothic
WelcomeFontSize=12

[Messages]
SetupAppTitle=설치
SetupWindowTitle=설치 - %1
UninstallAppTitle=제거
UninstallAppFullTitle=%1 제거
InformationTitle=정보
ConfirmTitle=확인
ErrorTitle=오류

ButtonBack=< 뒤로(&B)
ButtonNext=다음(&N) >
ButtonInstall=설치(&I)
ButtonOK=확인
ButtonCancel=취소
ButtonYes=예(&Y)
ButtonNo=아니요(&N)
ButtonFinish=마침
ButtonBrowse=찾아보기(&B)...
ButtonWizardBrowse=찾아보기(&R)...
ButtonNewFolder=새 폴더(&M)

WelcomeLabel1=[name] 설치 마법사에 오신 것을 환영합니다
WelcomeLabel2=이 마법사는 컴퓨터에 [name/ver]을(를) 설치합니다.%n%n계속하기 전에 다른 애플리케이션을 종료하는 것이 좋습니다.
SelectDirDesc=설치 프로그램은 다음 폴더에 [name]을(를) 설치합니다.
SelectDirLabel3=계속하려면 「다음」을 클릭하세요. 다른 폴더를 선택하려면 「찾아보기」를 클릭하세요.
SelectDirBrowseLabel=[name]을(를) 설치할 폴더를 선택하고 「확인」을 클릭하세요.
SelectComponentsDesc=설치할 구성 요소 선택
SelectComponentsLabel2=설치할 구성 요소를 선택하고 필요하지 않은 항목을 선택 취소한 후 「다음」을 클릭하세요.
SelectTasksDesc=설치 프로그램에서 실행할 추가 작업 선택
SelectTasksLabel2=[name] 설치 시 실행할 추가 작업을 선택한 후 「다음」을 클릭하세요.
ReadyLabel1=컴퓨터에 [name]을(를) 설치할 준비가 되었습니다.
ReadyLabel2a=계속하려면 「설치」를 클릭하고 설정을 확인하거나 변경하려면 「뒤로」를 클릭하세요.
ReadyMemoDir=설치 위치:
ReadyMemoType=설치 유형:
ReadyMemoComponents=선택한 구성 요소:
ReadyMemoGroup=시작 메뉴 폴더:
ReadyMemoTasks=추가 작업:
InstallingLabel=잠시 기다리세요. 컴퓨터에 [name]을(를) 설치하는 중입니다.
FinishedHeadingLabel=[name] 설치 완료
FinishedLabelNoIcons=컴퓨터에 [name]을(를) 설치했습니다.
FinishedLabel=컴퓨터에 [name]을(를) 설치했습니다. 생성된 바로 가기로 애플리케이션을 실행할 수 있습니다.
ExitSetupTitle=설치 종료
ExitSetupMessage=설치가 완료되지 않았습니다. 지금 종료하면 프로그램이 설치되지 않습니다.%n%n나중에 설치 프로그램을 다시 실행하여 설치를 완료할 수 있습니다.%n%n설치를 종료하시겠습니까?
