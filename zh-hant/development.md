# 主儲存庫開發

本頁說明主程式與官方外部外掛的開發環境準備、模組邊界、建置測試、開發模式偵錯、本地打包與 PR 規範。開發獨立第三方外掛請閱讀[第三方外掛 SDK](/zh-hant/plugin-development)；外掛安裝與疑難排解見[外掛管理](/zh-hant/plugin-management)。

## 環境要求

宿主、SDK 與官方外掛統一使用 JDK 25，Java/Kotlin 編譯目標為 25；第三方外掛開發也需要 JDK 25。使用 Temurin 25 執行 ProGuard 建置時，還需下載與 JDK 完整版本一致的 JMOD 並放入其 `jmods/`；CI 透過 `actions/setup-java` 的 `java-package: jdk+jmods` 準備，保留摘要與簽章驗證。

框架基線為 Spring Boot 4.1.2、Spring Framework 7、Jackson 3 與 SDK 2.0.0。升級外掛前請完成[SDK 遷移](/zh-hant/plugin-development)，SDK 1.x 二進位檔無法直接載入此宿主。

| 工具 | 版本要求 | 用途 |
| --- | --- | --- |
| JDK | 25 | 編譯與執行 Java 模組，CI 宿主測試使用 25 |
| Maven | 3.9+ 或儲存庫內建 `mvnw` | Reactor 建置與測試 |
| Git | 目前受支援版本 | 版本控制與分支管理 |
| Node.js / npm | 與目前 CI 一致 | 前端測試、i18n 門禁與邊界守衛 |
| PowerShell | 5.1+ | Windows 本地打包腳本 |
| Inno Setup | 6.x（選用） | 建置 Windows `setup.exe` 安裝包 |

所有 Java 命令均須明確使用 UTF-8。Windows PowerShell 可設定：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

### 多 JDK 環境的選擇

根 POM 在 Maven 的 `validate` 階段要求 JDK `[25,26)`。目前 Maven JVM 已是 25 時直接重用；否則先採用 `~/.m2/toolchains.xml` 中相符的登記，再嘗試 Maven 的本機自動探索。編譯、測試、Maven 開發啟動、AOT 及 Maven 呼叫的 Gradle / ProGuard 共用此選擇；找不到 25 時建置失敗，不下載 JDK，也不退回 17 或 26。

自訂安裝目錄建議登記一次，所有 clone 共用。將以下 `jdkHome` 換成現有 JDK 25 的絕對路徑，儲存至 `~/.m2/toolchains.xml`；若檔案已存在，僅新增 `toolchain` 元素並保留其他登記。自動探索支援 `JAVA25_HOME` 等環境變數及常見安裝目錄，但不搜尋任意磁碟路徑；舊 JDK 的本地化輸出也可能導致探索失敗，此時請明確登記。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0">
  <toolchain>
    <type>jdk</type>
    <provides><version>25</version></provides>
    <configuration><jdkHome>C:/path/to/jdk-25</jdkHome></configuration>
  </toolchain>
</toolchains>
```

啟動 Maven 的 JVM 仍由終端的 `JAVA_HOME` 或 IDE Maven Runner 決定。IDE 原生 `Developer Mode` 使用專案 SDK 執行與偵錯，須在 IDE 選取已安裝的 25。直接呼叫 AOT 指令碼時，`--java-home` 優先於 `JAVA_HOME`。Compose 的 Java/Kotlin 工具鏈要求 25，禁止自動下載 JDK。

## 多模組結構與依賴方向

根目錄 `pom.xml` 是 Maven Reactor 聚合器：

| 模組或目錄 | 職責 |
| --- | --- |
| `pixivdownload-sdk-info/`、`pixivdownload-plugin-api/`、`pixivdownload-core-api/`、`pixivdownload-sdk/` | SDK 版本事實來源、外掛擴充契約、宿主穩定語意連接埠與統一編譯入口 |
| `pixivdownload-plugin-signature/` | 外掛套件、儲存庫清單與社群操作的 Ed25519 簽署與驗證工具 |
| `pixivdownload-plugin-runtime/` | PF4J 載入、Spring 子上下文、worker 處理程序與安裝交易生命週期 |
| `pixivdownload-plugin-*/` | 各官方外部外掛模組 |
| `pixivdownload-app/` | 應用程式宿主、配接器與可執行 Spring Boot JAR |
| `pixivdownload-official-plugins/` | 官方外部外掛開發聚合與 `dev-mode` 啟動入口 |
| `plugin-templates/` | 可複製的獨立第三方外掛專案範本 |

依賴方向：外掛依賴 `plugin-api`（及依需求依賴 `core-api`），嚴禁反向依賴 `pixivdownload-app` 或宿主內部實作類別。`douyin` 外掛原始碼在[獨立儲存庫](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)維護，專屬測試只由該儲存庫的 `Host compatibility` CI 對固定宿主提交執行；主儲存庫 PR 保留通用 SDK 驗收。

## Fork、建置與測試

Fork 儲存庫後，以 `upstream/master` 為基礎建立功能分支（請替換 `YOUR_USERNAME` 與分支名稱）：

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/your-change upstream/master
```

常用建置與驗證命令：

```powershell
# 略過測試，打包全部模組
.\mvnw.cmd package -DskipTests

# 執行完整 Maven 測試
.\mvnw.cmd test

# 測試單一模組及其 Reactor 上游相依模組（搭配 -Dtest=... 時請加 -Dsurefire.failIfNoSpecifiedTests=false）
.\mvnw.cmd -pl pixivdownload-plugin-api -am test

# 執行前端測試與 Web 標準守衛
npm run test:js
npm run test:web-standards
```

建置結果以 `BUILD SUCCESS` 和結束代碼 `0` 為準；略過測試的打包不代表測試通過。單獨執行 `pixivdownload-app/target/PixivDownload-*-boot.jar` 僅啟動不含外部外掛的核心殼，完整功能整合偵錯請使用下方開發模式。

## 官方外部外掛開發模式與復原模式

在儲存庫根目錄透過明確的 `dev-mode` profile 啟動完整開發環境（不要在 IDE Maven 面板中全域勾選此 profile）：

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

`dev-mode` 會同時探索各官方外掛模組的 `target/classes` 與 `plugins/` 目錄中已核驗簽章的安裝包；若同一外掛 ID 在兩處同時存在，將觸發 `plugin-conflict` 拒絕載入，並在復原頁提示移除衝突副本後重新啟動。IDE 偵錯請直接使用儲存庫附帶的共用設定（IntelliJ IDEA `.run/Developer Mode.run.xml`、VS Code `.vscode/launch.json`、Eclipse `eclipse/Developer Mode.launch`）。

驗證缺少必要外掛或復原模式時，使用 `Missing Required Plugin` 共用設定或執行：

```powershell
mvn -pl pixivdownload-app -am -Precovery-mode process-classes -Dexec.skip=true
```

## 使用者腳本同步與 i18n 工作流程

- **使用者腳本**：根目錄 `*.user.js` 與 `scripts/build-userscript-bundle.ps1` 產生的 All-in-One，會在 Maven `generate-resources` 階段複製至 `download-workbench` 資源目錄。修改共用片段或腳本後，請執行 `./scripts/sync-shared-snippets.ps1`、`./scripts/build-userscript-bundle.ps1` 並執行一次 Maven 建置。
- **i18n 國際化**：唯一語言目錄為 `pixivdownload-app/src/main/resources/i18n/locales.json`（來源語言 `zh-CN`，全域回退 `en-US`，包含 `zh-Hant`、`ja-JP`、`ko-KR`）。新增或修改文案後，依序執行：

```bash
npm run setup:hooks
npm run doctor:hooks
npm run i18n:check
npm run i18n:generate-static
npm run test:i18n
```

## 可選的 JDK 25 AOT Cache

儲存庫的 `scripts/aot-cache.mjs` 為最終 boot JAR 準備並使用 JVM 啟動快取，需要 Node.js 與現有 JDK 25。它不下載 JDK，也不啟用 Spring AOT。安裝程式保留原有啟動方式；CI 的產物驗證會執行實際快取訓練與 strict 啟動檢查。僅對可信 JAR 執行訓練；訓練會執行應用程式，但使用隔離的暫存設定，不讀取目前執行個體的使用者資料。

直接依序執行共用設定 `AOT Prepare` 與 `AOT Packaged Run`，無須先手動打包。IntelliJ IDEA 使用 `.run`，VS Code 使用同名工作，Eclipse 匯入 `eclipse/` 下的同名 launch 設定。Prepare 使用 Maven Toolchains 選定的 JDK 25，自動建置主程式與官方外掛、組裝獨立執行目錄，再準備快取。Windows 使用系統 PowerShell；Linux / macOS 的 PATH 須包含 PowerShell 7（`pwsh`）。對應命令如下：

```powershell
.\mvnw.cmd -N -Paot '-Daot.command=prepare' validate
if ($LASTEXITCODE -ne 0) { throw 'AOT preparation failed' }
.\mvnw.cmd -N -Paot validate
```

預設執行目錄為 `target/aot-runtime`，可用 `-Daot.runtime.directory=...` 指定新目錄。`artifacts/` 保存主程式與完整官方分發外掛集合（含選用外掛，不含復原測試探針），`cache/` 保存依 JAR 與 JDK 內容區分的快取，`instance/` 是應用程式的工作目錄，保存獨立設定與資料。`mvn clean` 會刪除 `target/` 下的預設執行目錄；需要長期保留資料時，請指定 `target/` 以外的目錄，並在 Prepare 與 Run 使用相同值。首次 Run 按正常流程初始化，不會讀取或覆寫儲存庫根目錄的既有執行個體。程式碼變更後再次執行 Prepare，成功後才切換新產物，設定與資料保留。相同產物驗證後重用；僅外掛變更而主程式與 JDK 不變時，可重用主程式快取。 單一執行個體鎖位於此執行目錄的 `instance/ipc/`，與其他開發或正式執行個體隔離；同時執行仍需設定不同服務連接埠。

Prepare 的 Maven 建置會略過單元測試，測試與 CI 門禁仍另行執行。外掛沿用本機未簽章來源規則組裝，僅供本機開發，不可當作官方簽章發行物分發。Run 不重新編譯；同一目錄正用於準備或執行時，會拒絕並行操作。失敗會保留上一份可執行記錄，損壞內容不予重用；可選擇新執行目錄重新準備。舊產物與快取不自動清理，會佔用磁碟；停止相關程序後可刪除不再使用的內容，保留 `instance/` 中需要的資料。

`AOT Prepare` 與 `AOT Packaged Run` 可直接使用一般 Run。即使透過 IDE Debug 啟動，入口也只在 AOT 子程序的環境中移除 JDWP 與 IDEA 偵錯代理參數，保留堆積大小、Proxy、憑證等其他參數；應用程式的斷點偵錯仍使用 `Developer Mode`。在 Windows 下停止 Prepare 或其啟動器，會一併結束這次建置與訓練的子程序並釋放鎖，不影響其他應用程式執行個體。遇到鎖占用時，先停止同一執行目錄的 Prepare 或 Run 工作，再重試，不要刪除 `.lock` 檔案。其他鎖錯誤請依輸出的底層原因排查。

`Developer Mode` 仍用於原始碼執行與中斷點偵錯。JDK 25 AOT Cache 無法以非空編譯目錄建立快取，目前的連結快取也無法用於 JDWP 偵錯。單元測試、JaCoCo、Maven 與 Gradle 建置程序不使用應用程式 AOT 快取；訓練成本應依實際重複啟動次數評估。

以下底層 CLI 處理既有 JAR，也供 CI 直接重用，不自動建置或組裝外掛。請從儲存庫根目錄執行，替換 `<version>`、JDK 路徑及已初始化的執行個體目錄。新快取使用尚未建立的目錄，既有有效快取可重複驗證重用。執行時使用所選執行個體的設定、外掛與資料；沒有外掛的核心 JAR 僅提供復原模式。

```powershell
$repo = (Get-Location).Path
$jar = (Resolve-Path 'pixivdownload-app/target/PixivDownload-<version>-boot.jar').Path
$jdk = 'C:/path/to/jdk-25'
$cache = Join-Path $repo 'target/aot-cache'
node "$repo/scripts/aot-cache.mjs" prepare --jar $jar --java-home $jdk --cache-dir $cache
if ($LASTEXITCODE -ne 0) { throw 'AOT preparation failed' }

Set-Location 'C:/path/to/initialized-instance'
node "$repo/scripts/aot-cache.mjs" run --jar $jar --java-home $jdk --cache-dir $cache -- --no-gui
if ($LASTEXITCODE -ne 0) { throw 'AOT application failed' }
```

底層首次準備成功會輸出 `AOT prepare: ready`，重用有效快取時輸出 `AOT prepare: reusing verified cache`。啟動前核對原始 JAR、解開的依賴、JDK 與快取的 SHA-256；損壞或不完整的快取會保留原目錄，請用 `--cache-dir` 指定新目錄。訓練資料於結束時清理。清單僅檢查本機一致性，不能取代發行簽章或外掛准入。

正常啟動保留目前環境，快取不相容時由 JVM 回退。驗證命中時，在 `run` 的 `--` 之前加入 `--strict`，並檢查 JVM 的 `Using AOT-linked classes: true` 診斷；程序成功結束本身不代表命中。訓練成本需由多次啟動節省的時間抵消，部署前應使用相同 JAR、JDK、外掛、資料與啟動目標測量。機制詳見 [Spring Boot AOT Cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html)。

## 本地 Windows 打包

- **本機未簽署安裝程式驗收**（從目前原始碼建置外掛，輸出至隔離的 `build/out-local-unsigned/`，嚴禁對外散布）：

  ```powershell
  .\scripts\package-installer-with-plugins.ps1 -Version 0.0.1-local -PluginSource Local -AllowUnsignedLocalPlugins
  ```

- **不含外掛的核心殼可攜式套件**（僅用於復原模式/開發驗證）：

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipPlugins -SkipInstaller
  ```

- **使用已簽署外掛輸入建置正式套件**（輸出至 `build/out/`，嚴禁將私鑰放入儲存庫、建置目錄或日誌）：

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -PrebuiltPluginsDir C:\path\to\signed-plugin-inputs -SignatureToolJar C:\path\to\signature-tool.jar
  ```

## 提交與 PR 規範

1. 依改動風險執行聚焦測試、模組回歸與前端/i18n 門禁，提交前檢查 `git diff --check` 與 `git diff --staged`，不提交 `target/`、`build/`、執行期設定、憑證或測試資料。
2. 保持使用者可見字串接入 i18n、執行日誌固定使用英文、HTTP API 使用明確 DTO 並沿用既有驗證授權與例外對應、外掛私有設定/憑證/資料歸各自外掛所有。
3. 將分支同步至最新 `upstream/master` 後，向 `master` 發起 PR，在描述中說明改動原因、邊界及實際執行的驗證命令與結果。線上文件由獨立 `gh-pages` 分支維護。
