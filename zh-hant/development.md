# 開發指南

本頁面向主倉庫貢獻者。第三方插件作者請直接閱讀[第三方插件 SDK](/zh-hant/plugin-development)；插件安裝、啓停和故障處理見[插件管理](/zh-hant/plugin-management)。

## 環境準備

| 工具 | 要求 | 用途 |
| --- | --- | --- |
| JDK | 17 | 編譯和運行 |
| Maven | 3.9+，或倉庫內的 Maven Wrapper | 構建與測試 |
| Git | 當前受支持版本 | 版本控制 |
| PowerShell | 5.1+ | Windows 打包腳本 |
| Inno Setup | 6.x，可選 | Windows 安裝包 |

所有 Java 命令都應顯式使用 UTF-8。在 Windows PowerShell 中：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

## 多模塊結構

倉庫根 `pom.xml` 是 Maven Reactor 聚合器。主要邊界如下：

| 目錄 | 職責 |
| --- | --- |
| `pixivdownload-plugin-api/` | 第三方插件的穩定擴展契約 |
| `pixivdownload-core-api/` | 穩定的宿主語義端口和值模型 |
| `pixivdownload-plugin-signature/` | 插件和倉庫清單的簽名、驗籤工具 |
| `pixivdownload-plugin-runtime/` | PF4J、Spring 子上下文和安裝生命週期 |
| `pixivdownload-plugin-*/` | 各官方外置插件 |
| `pixivdownload-plugin-douyin/` | 官方 Douyin 下載類型示例 |
| `pixivdownload-app/` | 宿主應用、適配器和可執行 Spring Boot JAR |
| `pixivdownload-official-plugins/` | 官方插件聚合與開發模式入口 |
| `plugin-templates/` | 可複製的第三方插件模板 |

插件 API 與宿主實現之間必須保持依賴方向：插件依賴 `plugin-api`，需要穩定宿主能力時再依賴 `core-api`；第三方插件不得依賴 `pixivdownload-app` 或宿主實現類。

## Fork 與分支

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/your-change upstream/master
```

提交 PR 前，將分支更新到 `upstream/master`，並向上遊 `master` 分支發起 PR。

## 構建、測試與運行

```powershell
# 構建全部模塊但跳過測試
.\mvnw.cmd package -DskipTests

# 運行完整 Maven 測試
.\mvnw.cmd test

# 運行一個模塊及它在 Reactor 中依賴的模塊
.\mvnw.cmd -pl pixivdownload-plugin-api -am test

# 運行全部 JavaScript 測試和 Web 標準檢查
npm run test:js
npm run test:web-standards

# 運行打包後的應用
java -Dfile.encoding=UTF-8 -jar pixivdownload-app/target/PixivDownload-*-boot.jar
```

如果使用 `-Dtest=...` 對帶 `-am` 的 Reactor 做聚焦測試，請同時添加 `-Dsurefire.failIfNoSpecifiedTests=false`，避免沒有該測試類的上游模塊誤報失敗。

## 官方外置插件開發模式

從倉庫根目錄運行：

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

IDE 調試請使用倉庫提交的共享配置：IntelliJ IDEA 的 `.run/Developer Mode.run.xml`、VS Code 的 `.vscode/launch.json` 或 Eclipse 的 `eclipse/Developer Mode.launch`。這些入口會先編譯所需 Reactor 模塊，再啓動 `GuiLauncher`。

缺失必裝插件的恢復模式可使用相應的 `Missing Required Plugin` 共享配置，或運行：

```powershell
mvn -pl pixivdownload-app -am -Precovery-mode process-classes -Dexec.skip=true
```

## 用戶腳本資源

`pixiv-batch.html` 通過 `/api/scripts` 讀取已經物化的腳本目錄。獨立 `*.user.js` 與 `scripts/build-userscript-bundle.ps1` 生成的整合腳本會在 Maven `generate-resources` 階段複製到應用資源。因此修改用戶腳本後至少運行一次 Maven 生命週期，不能只依賴 IDE 的舊輸出目錄。

## i18n 國際化工作流

`i18n/locales.json` 是語言清單；中文（`zh-CN`）是開發源語言，英文（`en-US`）是全局回退語言。常用命令：

```bash
npm run setup:hooks
npm run doctor:hooks
npm run i18n:check
npm run i18n:generate-static
npm run test:i18n
```

新增中文文案必須同時提交英文翻譯。靜態資源發生變化時，重新生成並提交 `pixivdownload-app/src/main/resources/static/i18n-static`。基線接受和 hooks 細節見[倉庫 i18n 工作流](https://github.com/Sywyar/PixivDownloader/blob/master/docs/i18n-workflow.md)。

## 本地 Windows 打包

`scripts/package-local.ps1` 會構建應用殼、官方插件輸入、在線/離線便攜包及可選的 Inno Setup 安裝包。正式產物要求每個官方插件都有可複驗的簽名：可以傳入已經帶 `.sig` sidecar 的 `-PrebuiltPluginsDir`，或對本地模塊產物傳入 `-OfficialKeyId`、倉庫外的 `-PrivateKeyFile` 和可選的 `-SignatureToolJar`。產物默認寫入 `build/out/`。

```powershell
# 本機運行驗收：從當前源碼構建隔離的 unsigned 測試安裝器
.\scripts\package-installer-with-plugins.ps1 -Version 0.0.1-local -PluginSource Local -AllowUnsignedLocalPlugins

# 恢復/開發用 core-shell-only 便攜包，不含任何插件
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipPlugins -SkipInstaller

# 使用已經簽名並複驗過的官方插件輸入構建正式產物
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -PrebuiltPluginsDir C:\path\to\signed-plugin-inputs -SignatureToolJar C:\path\to\signature-tool.jar
```

常用參數包括 `-PrebuiltJar`、`-PrebuiltPluginsDir`、`-SkipPlugins`、`-RunTests`、`-SkipPortable`、`-SkipOfflinePortable`、`-SkipInstaller` 和 `-RedownloadFfmpeg`。`-SkipPlugins` 產物只能進入恢復/補齊流程。unsigned 測試安裝器寫入隔離的 `build/out-local-unsigned/`，不得分發；私鑰也不得放入倉庫、構建輸出或日誌。

## 提交與 PR

提交前應按改動風險運行聚焦測試，再擴大到受影響模塊或完整門禁。至少檢查：

```bash
git diff --check
git diff --staged
```

PR 描述應說明動機、邊界、驗證命令和實際結果。涉及 UI 時附截圖；涉及打包時說明驗證過的產物。不要提交 `target/`、`build/`、運行期配置、憑據或下載數據。

## CI 與發佈

- 普通變更由質量門禁工作流檢查 Java、JavaScript、i18n、依賴和分發邊界。
- `v*` 標籤觸發官方插件發佈、應用殼構建、Java 分發包、Windows 安裝器和 GitHub Release。
- `workflow_dispatch` 用於在質量門禁通過後創建指定標籤的草稿 Release。
- 文檔站點由獨立的 `gh-pages` 分支維護，不是應用靜態資源的標籤預覽。

工作流會持續演進；執行發佈操作前，以倉庫當前 `.github/workflows/` 與 `scripts/` 爲準。

## 代碼邊界

- 公共字符串進入 i18n 流水線；網頁遵循現有暗色模式、CSS 變量和 HTML/CSS/JavaScript 分離規則。
- 公共 HTTP API 使用顯式 DTO，並複用項目既有鑑權和異常映射。
- 修改數據庫建表語句時同步更新受管 schema 規格和遷移測試。
- 插件私有配置、憑據、狀態、數據和依賴只歸對應插件所有；不得重新耦合進應用殼。
- 新增或修改第三方擴展契約時，先更新 `plugin-api`/`core-api` 的契約與守衛，再更新模板、示例和 SDK 文檔。
