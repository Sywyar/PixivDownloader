#define AppName "PixivDownload"
#define AppPublisher "sywyar"
#define AppExeName "PixivDownload.exe"
#define FfmpegReleaseBaseUrl "https://github.com/Sywyar/PixivDownloader-Remote-Content/releases/download/ffmpeg-stable/"
#define FfmpegAssetName "ffmpeg-windows-x64.zip"
#ifndef SdkVersion
#error SdkVersion must be supplied from pixivdownload-sdk-info metadata.
#endif

#ifndef AppVersion
#define AppVersion "0.0.1-local"
#endif

#ifndef InstallerVersion
#define InstallerVersion "0.0.1"
#endif

#ifndef AppImageDir
#define AppImageDir "..\..\..\build\app-image-online\PixivDownload"
#endif

#ifndef OutputDir
#define OutputDir "..\..\..\build\out"
#endif

#ifndef SignatureToolJar
#define SignatureToolJar ""
#endif

#ifndef InstallerPluginCatalogEnabled
#define InstallerPluginCatalogEnabled "0"
#endif

#if Len(SignatureToolJar) == 0
#error SignatureToolJar must be defined for FFmpeg release verification.
#endif

[Setup]
AppId={{4D4F3566-C6C0-4D24-9242-86059B2A84A5}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={code:GetDefaultInstallDir}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename={#AppName}-{#AppVersion}-win-x64-setup
SetupIconFile=..\..\..\pixivdownload-app\src\main\resources\static\favicon.ico
UninstallDisplayIcon={app}\{#AppExeName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
DisablePrecompiledFileVerifications=setupldr
UsePreviousAppDir=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
ArchiveExtraction=full
VersionInfoCompany={#AppPublisher}
VersionInfoDescription={#AppName} Setup
VersionInfoProductName={#AppName}
VersionInfoProductVersion={#InstallerVersion}
VersionInfoVersion={#InstallerVersion}

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "zhcn"; MessagesFile: "compiler:Default.isl,ChineseSimplified.isl"
Name: "zhhant"; MessagesFile: "compiler:Default.isl,ChineseTraditional.isl"
Name: "ja"; MessagesFile: "compiler:Default.isl,Japanese.isl"
Name: "ko"; MessagesFile: "compiler:Default.isl,Korean.isl"

[CustomMessages]
en.OptionalTasksGroup=Optional setup tasks:
zhcn.OptionalTasksGroup=可选安装任务：
en.TaskDownloadFfmpeg=Download and install FFmpeg after PixivDownload is installed
zhcn.TaskDownloadFfmpeg=安装 PixivDownload 后下载并安装 FFmpeg
en.OptionalFeaturesTitle=Optional features
zhcn.OptionalFeaturesTitle=附加功能
en.OptionalFeaturesDescription=Choose extra components to install and enable.
zhcn.OptionalFeaturesDescription=选择需要安装并启用的附加组件。
en.OptionalPluginsTitle=Official optional plugins
zhcn.OptionalPluginsTitle=官方可选插件
en.OptionalPluginsDescription=Choose official plugins to install and enable.
zhcn.OptionalPluginsDescription=选择需要安装并启用的官方插件。
en.PluginCatalogLoading=Loading the packaged signed plugin catalog...
zhcn.PluginCatalogLoading=正在读取安装包内置的签名插件清单...
en.PluginCatalogPackaged=Using the signed plugin catalog packaged with this setup.
zhcn.PluginCatalogPackaged=正在使用安装包内置的签名插件清单。
en.PluginCatalogUnavailable=No signed plugin catalog is available.
zhcn.PluginCatalogUnavailable=未找到可用的签名插件清单。
en.PluginListHint=Optional plugins are installed from the signed official catalog and take effect after restart.
zhcn.PluginListHint=可选插件会从签名官方清单安装，重启应用后生效。
en.PluginWaiting=Optional plugin installation is waiting for application installation to finish.
zhcn.PluginWaiting=可选插件安装正在等待应用安装完成。
en.PluginInstalling=Installing optional plugins...
zhcn.PluginInstalling=正在安装可选插件...
en.PluginCompleted=Optional plugins have been installed and enabled.
zhcn.PluginCompleted=可选插件已安装并启用。
en.PluginFailed=Optional plugin installation failed. PixivDownload was installed; retry from Plugin Market later.
zhcn.PluginFailed=可选插件安装失败。PixivDownload 已安装，稍后可在插件市场重试。
en.PluginFinishedSuccess=Selected optional plugins were installed and enabled.
zhcn.PluginFinishedSuccess=已选可选插件已安装并启用。
en.PluginFinishedFailed=部分可选插件未安装。可打开 PixivDownload 的插件市场重试。
zhcn.PluginFinishedFailed=部分可选插件未能安装。可打开 PixivDownload 的插件市场重试。
en.FfmpegWaiting=FFmpeg download is waiting for application installation to finish.
zhcn.FfmpegWaiting=FFmpeg 下载正在等待应用安装完成。
en.FfmpegDownloading=Downloading FFmpeg...
zhcn.FfmpegDownloading=正在下载 FFmpeg...
en.FfmpegProxyDetected=Using system proxy
zhcn.FfmpegProxyDetected=使用系统代理
en.FfmpegDirectDownload=No enabled system proxy was found; downloading directly.
zhcn.FfmpegDirectDownload=未检测到已启用且有效的系统代理，正在直连下载。
en.FfmpegExtracting=Extracting FFmpeg...
zhcn.FfmpegExtracting=正在解压 FFmpeg...
en.FfmpegInstallingFiles=Installing FFmpeg files...
zhcn.FfmpegInstallingFiles=正在安装 FFmpeg 文件...
en.FfmpegCompleted=FFmpeg has been installed.
zhcn.FfmpegCompleted=FFmpeg 已安装完成。
en.FfmpegFailed=FFmpeg installation failed. PixivDownload was installed, and you can retry from the Status page later.
zhcn.FfmpegFailed=FFmpeg 安装失败。PixivDownload 已安装，稍后可在“状态”页重试。
en.FfmpegArchiveInvalid=The FFmpeg archive did not contain ffmpeg.exe or ffprobe.exe.
zhcn.FfmpegArchiveInvalid=FFmpeg 压缩包中未找到 ffmpeg.exe 或 ffprobe.exe。
en.FfmpegCopyFailed=Could not copy FFmpeg files to the application tools directory.
zhcn.FfmpegCopyFailed=无法将 FFmpeg 文件复制到应用工具目录。
en.FfmpegLicenseWriteFailed=Could not write the FFmpeg license notice.
zhcn.FfmpegLicenseWriteFailed=无法写入 FFmpeg 许可证说明。
en.FfmpegFinishedSuccess=FFmpeg was downloaded and installed during setup.
zhcn.FfmpegFinishedSuccess=FFmpeg 已在安装过程中下载并安装。
en.FfmpegFinishedFailed=FFmpeg was not installed during setup. Open the Status page in PixivDownload to retry.
zhcn.FfmpegFinishedFailed=FFmpeg 未能在安装过程中安装。可打开 PixivDownload 的“状态”页重试。
en.MaintenanceTitle=PixivDownload is already installed
zhcn.MaintenanceTitle=PixivDownload 已安装
en.MaintenanceDescription=Choose an operation for the existing installation.
zhcn.MaintenanceDescription=请选择要对现有安装执行的操作。
en.MaintenanceRepairButton=&Repair
zhcn.MaintenanceRepairButton=修复(&R)
en.MaintenanceRepairHint=Reinstall PixivDownload files in the current installation folder.
zhcn.MaintenanceRepairHint=在当前安装目录重新安装 PixivDownload 文件。
en.MaintenanceChangeButton=&Change
zhcn.MaintenanceChangeButton=更改(&C)
en.MaintenanceChangeHint=Change optional setup tasks, such as downloading FFmpeg.
zhcn.MaintenanceChangeHint=更改可选安装任务，例如下载 FFmpeg。
en.MaintenanceUninstallButton=&Uninstall
zhcn.MaintenanceUninstallButton=卸载(&U)
en.MaintenanceUninstallHint=Remove the existing PixivDownload installation.
zhcn.MaintenanceUninstallHint=移除现有 PixivDownload 安装。
en.MaintenanceUninstallConfirm=This will start the existing PixivDownload uninstaller. Continue?
zhcn.MaintenanceUninstallConfirm=即将启动现有 PixivDownload 卸载程序。是否继续？
en.MaintenanceUninstallMissing=Could not find the existing uninstaller.
zhcn.MaintenanceUninstallMissing=未能找到现有卸载程序。
en.MaintenanceUninstallFailed=The existing uninstaller failed.
zhcn.MaintenanceUninstallFailed=现有卸载程序执行失败。
en.MaintenanceRemovingLegacyMsi=Removing the previous MSI installation...
zhcn.MaintenanceRemovingLegacyMsi=正在移除旧 MSI 安装...
en.MaintenanceLegacyMsiRemoveFailed=Could not remove the previous MSI installation.
zhcn.MaintenanceLegacyMsiRemoveFailed=未能移除旧 MSI 安装。
en.AppRunningError=PixivDownload is currently running. Please close it completely, then click Retry to continue.
zhcn.AppRunningError=检测到 PixivDownload 正在运行。请完全关闭它后点击“重试”继续安装。
en.AppRunningAbort=Setup cannot continue while PixivDownload is running. Installation was cancelled.
zhcn.AppRunningAbort=PixivDownload 正在运行，安装无法继续，已取消安装。
zhhant.OptionalTasksGroup=可選安裝任務：
zhhant.TaskDownloadFfmpeg=安裝 PixivDownload 後下載並安裝 FFmpeg
zhhant.OptionalFeaturesTitle=附加功能
zhhant.OptionalFeaturesDescription=選擇需要安裝並啟用的附加組件。
zhhant.OptionalPluginsTitle=官方可選插件
zhhant.OptionalPluginsDescription=選擇需要安裝並啟用的官方插件。
zhhant.PluginCatalogLoading=正在讀取安裝包內置的簽名插件清單...
zhhant.PluginCatalogPackaged=正在使用安裝包內置的簽名插件清單。
zhhant.PluginCatalogUnavailable=未找到可用的簽名插件清單。
zhhant.PluginListHint=可選插件會從簽名官方清單安裝，重啟應用後生效。
zhhant.PluginWaiting=可選插件安裝正在等待應用安裝完成。
zhhant.PluginInstalling=正在安裝可選插件...
zhhant.PluginCompleted=可選插件已安裝並啟用。
zhhant.PluginFailed=可選插件安裝失敗。PixivDownload 已安裝，稍後可在插件市場重試。
zhhant.PluginFinishedSuccess=已選可選插件已安裝並啟用。
zhhant.PluginFinishedFailed=部分可選插件未能安裝。可打開 PixivDownload 的插件市場重試。
zhhant.FfmpegWaiting=FFmpeg 下載正在等待應用安裝完成。
zhhant.FfmpegDownloading=正在下載 FFmpeg...
zhhant.FfmpegProxyDetected=使用系統代理
zhhant.FfmpegDirectDownload=未檢測到已啟用且有效的系統代理，正在直連下載。
zhhant.FfmpegExtracting=正在解壓 FFmpeg...
zhhant.FfmpegInstallingFiles=正在安裝 FFmpeg 文件...
zhhant.FfmpegCompleted=FFmpeg 已安裝完成。
zhhant.FfmpegFailed=FFmpeg 安裝失敗。PixivDownload 已安裝，稍後可在「狀態」頁重試。
zhhant.FfmpegArchiveInvalid=FFmpeg 壓縮包中未找到 ffmpeg.exe 或 ffprobe.exe。
zhhant.FfmpegCopyFailed=無法將 FFmpeg 文件複製到應用工具目錄。
zhhant.FfmpegLicenseWriteFailed=無法寫入 FFmpeg 許可證說明。
zhhant.FfmpegFinishedSuccess=FFmpeg 已在安裝過程中下載並安裝。
zhhant.FfmpegFinishedFailed=FFmpeg 未能在安裝過程中安裝。可打開 PixivDownload 的「狀態」頁重試。
zhhant.MaintenanceTitle=PixivDownload 已安裝
zhhant.MaintenanceDescription=請選擇要對現有安裝執行的操作。
zhhant.MaintenanceRepairButton=修復(&R)
zhhant.MaintenanceRepairHint=在當前安裝目錄重新安裝 PixivDownload 文件。
zhhant.MaintenanceChangeButton=更改(&C)
zhhant.MaintenanceChangeHint=更改可選安裝任務，例如下載 FFmpeg。
zhhant.MaintenanceUninstallButton=卸載(&U)
zhhant.MaintenanceUninstallHint=移除現有 PixivDownload 安裝。
zhhant.MaintenanceUninstallConfirm=即將啟動現有 PixivDownload 卸載程序。是否繼續？
zhhant.MaintenanceUninstallMissing=未能找到現有卸載程序。
zhhant.MaintenanceUninstallFailed=現有卸載程序執行失敗。
zhhant.MaintenanceRemovingLegacyMsi=正在移除舊 MSI 安裝...
zhhant.MaintenanceLegacyMsiRemoveFailed=未能移除舊 MSI 安裝。
zhhant.AppRunningError=檢測到 PixivDownload 正在運行。請完全關閉它後點擊「重試」繼續安裝。
zhhant.AppRunningAbort=PixivDownload 正在運行，安裝無法繼續，已取消安裝。
ja.OptionalTasksGroup=追加セットアップタスク：
ja.TaskDownloadFfmpeg=PixivDownload のインストール後に FFmpeg をダウンロードしてインストール
ja.OptionalFeaturesTitle=追加機能
ja.OptionalFeaturesDescription=インストールして有効にする追加コンポーネントを選択してください。
ja.OptionalPluginsTitle=公式オプションプラグイン
ja.OptionalPluginsDescription=インストールして有効にする公式プラグインを選択してください。
ja.PluginCatalogLoading=パッケージ化された署名済みプラグインカタログを読み込み中...
ja.PluginCatalogPackaged=このセットアップに同梱された署名済みプラグインカタログを使用しています。
ja.PluginCatalogUnavailable=利用可能な署名済みプラグインカタログがありません。
ja.PluginListHint=オプションプラグインは署名済みの公式カタログからインストールされ、再起動後に有効になります。
ja.PluginWaiting=オプションプラグインのインストールは、アプリケーションのインストール完了を待っています。
ja.PluginInstalling=オプションプラグインをインストール中...
ja.PluginCompleted=オプションプラグインをインストールして有効にしました。
ja.PluginFailed=オプションプラグインのインストールに失敗しました。PixivDownload はインストール済みです。後でプラグインマーケットから再試行してください。
ja.PluginFinishedSuccess=選択したオプションプラグインをインストールして有効にしました。
ja.PluginFinishedFailed=一部のオプションプラグインをインストールできませんでした。PixivDownload のプラグインマーケットから再試行できます。
ja.FfmpegWaiting=FFmpeg のダウンロードは、アプリケーションのインストール完了を待っています。
ja.FfmpegDownloading=FFmpeg をダウンロード中...
ja.FfmpegProxyDetected=システムプロキシを使用
ja.FfmpegDirectDownload=有効なシステムプロキシが見つからないため、直接ダウンロードします。
ja.FfmpegExtracting=FFmpeg を展開中...
ja.FfmpegInstallingFiles=FFmpeg ファイルをインストール中...
ja.FfmpegCompleted=FFmpeg のインストールが完了しました。
ja.FfmpegFailed=FFmpeg のインストールに失敗しました。PixivDownload はインストール済みです。後でステータスページから再試行できます。
ja.FfmpegArchiveInvalid=FFmpeg アーカイブに ffmpeg.exe または ffprobe.exe が含まれていません。
ja.FfmpegCopyFailed=FFmpeg ファイルをアプリケーションの tools ディレクトリにコピーできませんでした。
ja.FfmpegLicenseWriteFailed=FFmpeg のライセンス通知を書き込めませんでした。
ja.FfmpegFinishedSuccess=セットアップ中に FFmpeg をダウンロードしてインストールしました。
ja.FfmpegFinishedFailed=セットアップ中に FFmpeg をインストールできませんでした。PixivDownload のステータスページから再試行してください。
ja.MaintenanceTitle=PixivDownload はすでにインストールされています
ja.MaintenanceDescription=既存のインストールに対する操作を選択してください。
ja.MaintenanceRepairButton=修復(&R)
ja.MaintenanceRepairHint=現在のインストールフォルダーに PixivDownload のファイルを再インストールします。
ja.MaintenanceChangeButton=変更(&C)
ja.MaintenanceChangeHint=FFmpeg のダウンロードなど、追加セットアップタスクを変更します。
ja.MaintenanceUninstallButton=アンインストール(&U)
ja.MaintenanceUninstallHint=既存の PixivDownload を削除します。
ja.MaintenanceUninstallConfirm=既存の PixivDownload アンインストーラーを起動します。続行しますか？
ja.MaintenanceUninstallMissing=既存のアンインストーラーが見つかりません。
ja.MaintenanceUninstallFailed=既存のアンインストーラーが失敗しました。
ja.MaintenanceRemovingLegacyMsi=以前の MSI インストールを削除中...
ja.MaintenanceLegacyMsiRemoveFailed=以前の MSI インストールを削除できませんでした。
ja.AppRunningError=PixivDownload は現在実行中です。完全に終了してから「再試行」をクリックして続行してください。
ja.AppRunningAbort=PixivDownload の実行中はセットアップを続行できません。インストールをキャンセルしました。
ko.OptionalTasksGroup=선택적 설치 작업:
ko.TaskDownloadFfmpeg=PixivDownload 설치 후 FFmpeg 다운로드 및 설치
ko.OptionalFeaturesTitle=선택적 기능
ko.OptionalFeaturesDescription=설치하고 활성화할 추가 구성 요소를 선택하세요.
ko.OptionalPluginsTitle=공식 선택적 플러그인
ko.OptionalPluginsDescription=설치하고 활성화할 공식 플러그인을 선택하세요.
ko.PluginCatalogLoading=패키지에 포함된 서명된 플러그인 카탈로그를 로드하는 중...
ko.PluginCatalogPackaged=이 설치 프로그램에 포함된 서명된 플러그인 카탈로그를 사용합니다.
ko.PluginCatalogUnavailable=사용 가능한 서명된 플러그인 카탈로그가 없습니다.
ko.PluginListHint=선택적 플러그인은 서명된 공식 카탈로그에서 설치되며 재시작 후 적용됩니다.
ko.PluginWaiting=선택적 플러그인 설치가 애플리케이션 설치 완료를 기다리는 중입니다.
ko.PluginInstalling=선택적 플러그인을 설치하는 중...
ko.PluginCompleted=선택적 플러그인을 설치하고 활성화했습니다.
ko.PluginFailed=선택적 플러그인 설치에 실패했습니다. PixivDownload는 설치되었으므로 나중에 플러그인 마켓에서 다시 시도하세요.
ko.PluginFinishedSuccess=선택한 선택적 플러그인을 설치하고 활성화했습니다.
ko.PluginFinishedFailed=일부 선택적 플러그인을 설치하지 못했습니다. PixivDownload 플러그인 마켓에서 다시 시도할 수 있습니다.
ko.FfmpegWaiting=FFmpeg 다운로드가 애플리케이션 설치 완료를 기다리는 중입니다.
ko.FfmpegDownloading=FFmpeg 다운로드 중...
ko.FfmpegProxyDetected=시스템 프록시 사용
ko.FfmpegDirectDownload=활성화된 시스템 프록시를 찾지 못해 직접 다운로드합니다.
ko.FfmpegExtracting=FFmpeg 압축 해제 중...
ko.FfmpegInstallingFiles=FFmpeg 파일 설치 중...
ko.FfmpegCompleted=FFmpeg를 설치했습니다.
ko.FfmpegFailed=FFmpeg 설치에 실패했습니다. PixivDownload는 설치되었으므로 나중에 상태 페이지에서 다시 시도할 수 있습니다.
ko.FfmpegArchiveInvalid=FFmpeg 압축 패키지에 ffmpeg.exe 또는 ffprobe.exe가 없습니다.
ko.FfmpegCopyFailed=FFmpeg 파일을 애플리케이션 도구 디렉터리에 복사할 수 없습니다.
ko.FfmpegLicenseWriteFailed=FFmpeg 라이선스 안내를 기록할 수 없습니다.
ko.FfmpegFinishedSuccess=설치 중 FFmpeg를 다운로드하고 설치했습니다.
ko.FfmpegFinishedFailed=설치 중 FFmpeg를 설치하지 못했습니다. PixivDownload의 상태 페이지에서 다시 시도하세요.
ko.MaintenanceTitle=PixivDownload가 이미 설치되어 있습니다
ko.MaintenanceDescription=기존 설치에 수행할 작업을 선택하세요.
ko.MaintenanceRepairButton=복구(&R)
ko.MaintenanceRepairHint=현재 설치 폴더에 PixivDownload 파일을 다시 설치합니다.
ko.MaintenanceChangeButton=변경(&C)
ko.MaintenanceChangeHint=FFmpeg 다운로드와 같은 선택적 설치 작업을 변경합니다.
ko.MaintenanceUninstallButton=제거(&U)
ko.MaintenanceUninstallHint=기존 PixivDownload 설치를 제거합니다.
ko.MaintenanceUninstallConfirm=기존 PixivDownload 제거 프로그램을 시작합니다. 계속하시겠습니까?
ko.MaintenanceUninstallMissing=기존 제거 프로그램을 찾을 수 없습니다.
ko.MaintenanceUninstallFailed=기존 제거 프로그램이 실패했습니다.
ko.MaintenanceRemovingLegacyMsi=이전 MSI 설치를 제거하는 중...
ko.MaintenanceLegacyMsiRemoveFailed=이전 MSI 설치를 제거할 수 없습니다.
ko.AppRunningError=PixivDownload가 현재 실행 중입니다. 완전히 닫은 후 「재시도」를 클릭하여 계속하세요.
ko.AppRunningAbort=PixivDownload가 실행 중이어서 설치를 계속할 수 없습니다. 설치를 취소했습니다.

[InstallDelete]
; jpackage 把版本号写进主 jar 文件名（PixivDownload-<version>.jar），升级时新旧 jar 会同时
; 残留在 {app}\app 下。安装文件复制前先清空该目录，避免旧版本 jar 堆积。
; 用户数据（config.yaml、pixiv-download\ 等）位于 {app} 根目录而非 {app}\app，不受影响。
; 故意不清空 {app}\plugins：官方默认安装插件以稳定文件名（<module>.jar）随 app-image 携带，[Files] 的
; ignoreversion 会就地覆盖同名文件、不留旧版本残留；用户自行安装的第三方插件（不同文件名）不在安装器
; 文件清单内，升级时既不复制也不删除，得以保留。插件启用 / 禁用状态存放在 {app}\config\config.yaml
; （plugins.<id>.enabled），同样位于 {app} 根目录、升级时不受影响。
Type: filesandordirs; Name: "{app}\app"; Check: ShouldInstallApplicationFiles
; 本地 unsigned 测试包会由后续 [Files] 重新复制此标记；正式包没有该文件，故升级时保持删除，
; 避免曾安装测试包的目录永久显示为 unsigned。
Type: files; Name: "{app}\plugins\LOCAL-UNSIGNED-BUILD.txt"; Check: ShouldInstallApplicationFiles

[Files]
; app-image 根目录已含 plugins\（package-local.ps1 预置的官方默认安装插件 jar + 校验文件 + manifest）；
; 此处递归复制即把 plugins\ 一并装入 {app}\plugins。
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Check: ShouldInstallApplicationFiles
#if InstallerPluginCatalogEnabled == "1"
Source: "{#AppImageDir}\installer-catalog\manifest.json"; DestDir: "{tmp}"; DestName: "installer-plugin-catalog.json"; Flags: dontcopy
Source: "{#AppImageDir}\installer-catalog\manifest.json.sig"; DestDir: "{tmp}"; DestName: "installer-plugin-catalog.json.sig"; Flags: dontcopy
#endif
Source: "{#SignatureToolJar}"; DestDir: "{tmp}"; DestName: "pixivdownload-plugin-signature-tool.jar"; Flags: dontcopy
Source: "..\..\..\scripts\ffmpeg-release-integrity.ps1"; DestDir: "{tmp}"; Flags: dontcopy
Source: "installer-ffmpeg-download.ps1"; DestDir: "{tmp}"; Flags: dontcopy
Source: "installer-plugin-install.ps1"; DestDir: "{tmp}"; Flags: dontcopy

[Registry]
Root: HKLM64; Subkey: "Software\sywyar\PixivDownload"; ValueType: string; ValueName: "InstallLocation"; ValueData: "{app}"; Flags: uninsdeletevalue uninsdeletekeyifempty; Check: ShouldInstallApplicationFiles
Root: HKLM64; Subkey: "Software\sywyar\PixivDownload"; ValueType: dword; ValueName: "installed"; ValueData: "1"; Flags: uninsdeletevalue uninsdeletekeyifempty; Check: ShouldInstallApplicationFiles

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\{#AppExeName}"; Check: ShouldInstallApplicationFiles
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; IconFilename: "{app}\{#AppExeName}"; Check: ShouldInstallApplicationFiles

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(AppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent runascurrentuser; Check: ShouldInstallApplicationFiles

[UninstallDelete]
Type: filesandordirs; Name: "{app}\tools"

[Code]
type
  TMsg = record
    Hwnd: Longword;
    Message: Longword;
    WParam: Longword;
    LParam: Longword;
    Time: Longword;
    PtX: Longint;
    PtY: Longint;
  end;

  PROCESSENTRY32 = record
    dwSize: Longword;
    cntUsage: Longword;
    th32ProcessID: Longword;
    th32DefaultHeapID: Longword;
    th32ModuleID: Longword;
    cntThreads: Longword;
    th32ParentProcessID: Longword;
    pcPriClassBase: Longint;
    dwFlags: Longword;
    szExeFile: array[0..259] of Char;
  end;

const
  PM_REMOVE = 1;
  TH32CS_SNAPPROCESS = $00000002;
  FfmpegArchiveName = 'ffmpeg.zip';
  FfmpegLicenseName = 'ffmpeg-LGPLv2.1.txt';
  LibwebpLicenseName = 'libwebp-COPYING.txt';
  LibwebpPatentsName = 'libwebp-PATENTS.txt';
  AppRegistryKey = 'Software\sywyar\PixivDownload';
  UninstallRegistryKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall';
  InnoUninstallRegistryKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{4D4F3566-C6C0-4D24-9242-86059B2A84A5}_is1';
  WindowsInternetSettingsKey = 'Software\Microsoft\Windows\CurrentVersion\Internet Settings';
  MaintenanceRepairMode = 'repair';
  MaintenanceChangeMode = 'change';
  MaintenanceUninstallMode = 'uninstall';
  PluginInstallScriptName = 'installer-plugin-install.ps1';
  PluginSignatureToolName = 'pixivdownload-plugin-signature-tool.jar';
  FfmpegDownloadScriptName = 'installer-ffmpeg-download.ps1';
  FfmpegIntegrityScriptName = 'ffmpeg-release-integrity.ps1';

function PeekMessage(var Msg: TMsg; Hwnd: Longword; MsgFilterMin, MsgFilterMax, RemoveMsg: Longword): Boolean;
external 'PeekMessageW@user32.dll stdcall';

function TranslateMessage(var Msg: TMsg): Boolean;
external 'TranslateMessage@user32.dll stdcall';

function DispatchMessage(var Msg: TMsg): Longint;
external 'DispatchMessageW@user32.dll stdcall';

function CreateToolhelp32Snapshot(dwFlags, th32ProcessID: Longword): Longword;
external 'CreateToolhelp32Snapshot@kernel32.dll stdcall';

function Process32First(hSnapshot: Longword; var lppe: PROCESSENTRY32): Boolean;
external 'Process32FirstW@kernel32.dll stdcall';

function Process32Next(hSnapshot: Longword; var lppe: PROCESSENTRY32): Boolean;
external 'Process32NextW@kernel32.dll stdcall';

function CloseHandle(hObject: Longword): Boolean;
external 'CloseHandle@kernel32.dll stdcall';

function ResolveSystemProxyUrl: String;
forward;

var
  MaintenancePage: TWizardPage;
  OptionalFeaturesPage: TWizardPage;
  OptionalPluginsPage: TWizardPage;
  FfmpegCheckBox: TNewCheckBox;
  PluginStatusLabel: TNewStaticText;
  PluginHintLabel: TNewStaticText;
  PluginCheckList: TNewCheckListBox;
  PluginCatalogLoaded: Boolean;
  PluginCatalogPath: String;
  PluginIds: TArrayOfString;
  PluginVersions: TArrayOfString;
  PluginInstallTitleLabel: TNewStaticText;
  PluginInstallDetailLabel: TNewStaticText;
  PluginInstallProgressBar: TNewProgressBar;
  PluginInstallProgressTitle: String;
  PluginInstallProgressLastPercent: Integer;
  FfmpegTitleLabel: TNewStaticText;
  FfmpegDetailLabel: TNewStaticText;
  FfmpegProgressBar: TNewProgressBar;
  FfmpegProgressTitle: String;
  FfmpegProgressLastPercent: Integer;
  FfmpegInstalled: Boolean;
  FfmpegFailed: Boolean;
  PluginInstalled: Boolean;
  PluginFailed: Boolean;
  SystemProxyUrl: String;
  ExistingInstallationResolved: Boolean;
  ExistingInstallationFound: Boolean;
  ExistingInstallDir: String;
  ExistingUninstallCommand: String;
  LegacyMsiProductCode: String;
  ShowMaintenancePage: Boolean;
  MaintenanceMode: String;
  MaintenanceClosingAfterUninstall: Boolean;

procedure ProcessInstallerMessages;
forward;

procedure ResponsiveSleep(const Milliseconds: Integer);
forward;

procedure LayoutInstallProgressControls;
forward;

function ShouldShowOptionalFeaturesPage: Boolean;
forward;

#include "PixivDownload-maintenance.iss.inc"
#include "PixivDownload-plugins.iss.inc"
#include "PixivDownload-ffmpeg.iss.inc"
#include "PixivDownload-lifecycle.iss.inc"
