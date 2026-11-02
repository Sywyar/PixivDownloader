# 主儲存庫開發

本頁說明主程式與官方外部外掛的開發環境準備、模組邊界、建置測試、開發模式偵錯、本地打包與 PR 規範。開發獨立第三方外掛請閱讀[第三方外掛 SDK](/zh-hant/plugin-development)；外掛安裝與疑難排解見[外掛管理](/zh-hant/plugin-management)。

## 環境要求

| 工具 | 版本要求 | 用途 |
| --- | --- | --- |
| JDK | 17 | 編譯與執行 Java 模組 |
| Maven | 3.9+ 或儲存庫內建 `mvnw` | Reactor 建置與測試 |
| Git | 目前受支援版本 | 版本控制與分支管理 |
| Node.js / npm | 與目前 CI 一致 | 前端測試、i18n 門禁與邊界守衛 |
| PowerShell | 5.1+ | Windows 本地打包腳本 |
| Inno Setup | 6.x（選用） | 建置 Windows `setup.exe` 安裝包 |

所有 Java 命令均須明確使用 UTF-8。Windows PowerShell 可設定：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

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

依賴方向：外掛依賴 `plugin-api`（及依需求依賴 `core-api`），嚴禁反向依賴 `pixivdownload-app` 或宿主內部實作類別。`douyin` 外掛原始碼在[獨立儲存庫](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)維護，透過固定提交參與 SDK 隔離消費者相容性驗收。

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
