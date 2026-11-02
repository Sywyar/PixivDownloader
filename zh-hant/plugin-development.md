# 第三方外掛 SDK

第三方外掛從 SDK 開發包的獨立專案範本起步，只需 JDK 17、Node.js 及套件內附的建置工具與執行清單，無需複製主儲存庫。

| 開發任務 | 對應章節 |
| --- | --- |
| 建立並在本地偵錯第一個外掛 | [從範本開始](#從範本開始) · [本地開發](#本地開發) |
| 新增下載作品類型或獨立畫廊 | [新增下載類型的完整流程](#新增下載類型的完整流程) · [新增外掛自有畫廊](#新增外掛自有畫廊) |
| 管理設定、加密憑證、檔案與私有資料庫 | [設定、憑證和檔案](#設定、憑證和檔案) · [不依賴 GUI 的憑證與通用操作驗證](#不依賴-gui-的憑證與通用操作驗證) |
| 宣告跨外掛服務契約或頁面編輯器元件 | [獨立服務契約與組合](#獨立服務契約與組合) · [一般插槽與頁面元件](#一般插槽與頁面元件) |
| 升級 SDK、投稿社群市場或自建儲存庫散布 | [維護外部專案與 SDK 升級](#維護外部專案與-sdk-升級) · [投稿市場資料](#投稿市場資料) · [簽署和發布](#簽署和發布) |

下文中的 `SDK_VERSION`、套件名稱、外掛 ID、路徑、SHA-256 與簽章均為預留位置，使用時請替換為實際值。程式碼片段需放入對應範本上下文，並補齊 import 與業務實作，介面可用性以所選 [SDK Release](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) 及[版本化 Javadoc](https://sywyar.github.io/PixivDownloader-Plugin-SDK/) 為準。

參考原始碼：[外掛範本](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates) · [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info) · [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api) · [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api) · [簽署工具](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature) · [Douyin 完整範例外掛](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)（展示下載、設定、代理、佇列、排程工作、私有資料庫與獨立畫廊，但不屬於官方發行集合；新專案請從 `plugin-templates` 開始）。

## 外掛授權與發布管道

PixivDownloader 及官方外掛採用 [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)，並為隨附[外掛連結例外](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)的版本提供額外授權：僅透過公開 SDK / API 開發且符合例外條件的獨立第三方外掛，可選擇 MIT 等授權條款（複製修改專案範本時，須保留上游著作權聲明與例外文字；宿主、SDK 與官方外掛仍適用 AGPL-3.0）。

[官方社群外掛儲存庫](https://github.com/Sywyar/PixivDownloader-community-plugins)僅收錄開放原始碼外掛，要求公開完整 GitHub 原始碼、開放原始碼授權條款、可重現建置並通過人工審核。

## 信任與 SDK 架構邊界

- **執行模式與信任邊界**：`declarative-process` 在獨立 worker JVM 中執行（隔離資源與協定），`host-process-full-trust` 在宿主 JVM 中執行；兩者均共用目前 OS 帳號，不構成作業系統層級安全沙箱。Ed25519 簽章僅證明發布者身分與位元組完整性，安裝前仍需信任作者與原始碼（詳見[外掛管理](/zh-hant/plugin-management)）。
- **編譯依賴方向**：第三方外掛宣告單一 `pixivdownload-sdk`（`provided`）相依項目，由其傳遞 `pixivdownload-sdk-info`、`pixivdownload-plugin-api`、`pixivdownload-core-api` 及宿主提供的 PF4J、Spring、Servlet、Jackson 編譯相依項目（三個 API 模組與 BOM 也可單獨使用）。**嚴禁依賴 `pixivdownload-app`、宿主內部實作類別、官方外掛私有類別、宿主 `DataSource` 或私有前端全域變數**。
- **能力發布與缺少能力時的降級**：外掛透過 `PixivFeaturePlugin` 貢獻能力，透過 `PixivPluginProvider.configurationClasses()` 宣告獨立 Spring 子上下文設定類別。外掛停用、解除安裝或換代時，宿主依 publication 撤回路由、靜態資源、i18n、導覽、下載類型、佇列與排程能力；呼叫端必須處理能力缺少的情況，不能產生空指標或未完整完成的任務。
- **桌面 UI 邊界**：宿主透過不依賴工具套件的純資料契約（`GuiConfigContribution` 等）彙整設定、引導與狀態，由 `gui-compose`（預設）和 `gui-swing`（備用）各自渲染介面；功能外掛不回傳 Swing / Compose 元件。

### `PixivFeaturePlugin` 主要貢獻入口

| 方法 | 貢獻內容 |
| --- | --- |
| `id`、`displayName`、`description`、`displayNamespace`、`iconKey`、`colorToken`、`kind` | 外掛身分、i18n 顯示鍵與受控圖示/顏色/分類 token |
| `start`、`stop` | 外掛自有本地資源生命週期（`stop` 必須具冪等性） |
| `privateSchema`、`privateStorageMigrations` | 目前 owner 的權威私有資料表結構與依序執行的資料遷移宣告 |
| `schema`、`sharedMainStorage` | 參與宿主主資料庫協作的受管資料表與公開儲存域申請（不可存取其他 owner 資料表） |
| `routes`、`staticResources`、`i18n` | Web 路由存取策略、classpath 靜態資源對應、前端 i18n namespace |
| `navigation`、`startupRoutes`、`landings`、`pageSections`、`uiSlots`、`drilldowns` | 導覽項目、啟動落點、頁面區塊、Web UI 插槽與下鑽連結 |
| `guiConfigContributions`、`guiOnboardingSteps`、`guiThemes` | 桌面設定欄位/群組/動作/預設組合、引導步驟與啟動期主題 |
| `userscripts`、`scheduledSourceDescriptors`、`downloadTypes` | 使用者腳本資源、排程工作來源描述檔、下載工作台作品類型描述檔 |

## 從範本開始

| 範本 | 適用情境 | 已包含內容 |
| --- | --- | --- |
| `minimal-feature-plugin` | 宣告式頁面、導覽與 i18n | `declarative-process` worker、PF4J 入口、route/static/i18n 與 thin JAR 測試（無 Spring 子上下文） |
| `download-type-plugin` | 新增可下載作品類型與畫廊 | `host-process-full-trust`、下載類型描述檔、5 種取得模式、佇列、排程來源、Vue 插槽、獨立畫廊與前後端測試 |
| `typed-service-plugin` | 跨外掛型別化服務與遠端呼叫 | 獨立 `api` / `contract` / `provider` / `consumer` 多模組專案與 Schema/用戶端產生 |

複製至儲存庫外後，範本是不繼承主儲存庫 parent 的獨立 Maven 專案，直接執行 `mvn clean verify` 即可驗證（在主儲存庫內可用 `mvn -f plugin-templates/pom.xml clean verify` 驗證）。

Maven 相依宣告（Java 控制器若使用未明確命名的 `@PathVariable` / `@RequestParam`，須設定 `<maven.compiler.parameters>true</maven.compiler.parameters>` 或 `-parameters`）：

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>SDK_VERSION</version>
    <scope>provided</scope>
</dependency>
```

Gradle 使用 `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:SDK_VERSION")`，sbt 使用 `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "SDK_VERSION" % Provided`。複製範本後，請統一替換 Maven `artifactId`、全域唯一外掛 ID、Java 套件名稱與類別名稱前綴、版本號、路由前綴、靜態資源目錄、私有資料表名稱和 i18n namespace。

## 外掛套件描述檔與子上下文組裝

JAR 根目錄必須包含 `plugin.properties`：

```properties
plugin.id=example-download
plugin.version=0.1.0
plugin.requires=1.3
plugin.class=com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin
plugin.provider=Example Developer
plugin.description=Example download type.
pixiv.display-namespace=example-download
pixiv.display-name-key=plugin.name
pixiv.description-key=plugin.summary
pixiv.icon-key=download
pixiv.color-token=green
pixiv.execution-mode=host-process-full-trust
pixiv.lifecycle-policy=process-restart
```

- `plugin.id` 必須與 `PixivFeaturePlugin.id()` 完全一致；`plugin.requires` 宣告所需 SDK 版本（穩定版依 `requiredMajor == hostMajor && requiredMinor <= hostMinor` 比對，預先發行版採用 `alpha.N` / `beta.N` / `rc.N` 並要求精確符合）。
- `pixiv.execution-mode` 必填（`declarative-process` 或 `host-process-full-trust`）；`pixiv.lifecycle-policy` 可選 `hot-reload`（預設）、`backend-restart` 或 `process-restart`；`pixiv.risk-signals` 可宣告風險訊號 token。
- 外部入口實作 `PixivPluginProvider`，回傳唯一的 `PixivFeaturePlugin` 與明確 `@Bean` 設定類別（禁止依賴宿主套件掃描）：

```java
public final class ExampleDownloadPf4jPlugin
        extends org.pf4j.Plugin implements PixivPluginProvider {
    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new ExampleDownloadPlugin();
    }
    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(ExampleDownloadConfiguration.class);
    }
}
```

### Web 路由、靜態資源、i18n、通知範本與問卷插槽

每個 Controller 路徑、靜態資源和 HTML 頁面，均須由同一外掛在 `routes()` 中宣告 `AccessPolicy`（支援 `publicRoute`、`visitor`、`visitorAndInvitedGuest`、`invitedGuest`、`admin`、`local`、`gui`；未宣告的 `path + method` 回傳 404）：

```java
@Override
public List<WebRouteContribution> routes() {
    return List.of(
            WebRouteContribution.admin("/example-download-gallery.html"),
            WebRouteContribution.admin("/example-download-gallery/**"),
            WebRouteContribution.admin("/api/example-download/gallery"));
}
```

- **業務通知範本**：情境 owner 在子上下文註冊 `NotificationTemplateContributor` Bean，回傳純值 `NotificationTemplateContribution(scenarioId, medium, locale, titleTemplate, bodyTemplate)`（單一標題上限 16 KiB、正文 1 MiB，單一外掛最多 256 項 / 8 MiB；填入不可信值前必須進行 HTML 跳脫）。
- **PostHog 調查與站內信**：Web 外掛可宣告 `plugin.dependencies=posthog?@1.0`，載入 `/pixiv-posthog/pixiv-posthog.js` 並呼叫 `window.PixivPostHog.createSurveyClient` 與 `captureSurveyWithAck`；需要常駐管理員站內信時，使用 `new SurveyInboxMessage(...).toUiSlotContribution()` 貢獻 `notification.inbox` 插槽。

## 新增下載類型的完整流程

下載類型由同一外掛協同貢獻：

1. **宣告 `DownloadTypeDescriptor`**：在 `PixivFeaturePlugin.downloadTypes()` 中回傳 descriptor（`contractVersion = DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION`、全域唯一 `type`、i18n 鍵、圖示與顏色 token、同源行為模組 `moduleUrl`、支援的取得模式 `single-import` / `user` / `series` / `search` / `quick`、`cancelSupported`、篩選與設定白名單）。
2. **實作前端行為模組**：在 `moduleUrl` 中呼叫 `PixivBatch.queueTypes.registerModule(initializer)`，實作 `process(item, context)`（必填，透過 `context.updateItem(patch)` 更新狀態，使用 `DownloadTasks` 時透過 `context.bindTask(taskId)` 綁定執行 UUID）及已宣告的 `import`、`acquisition.*`、`filters`、`settings`。非同步回呼寫回前，須檢查 `AbortSignal` / `isActive()`，並在 `onCleanup()` 釋放資源。
3. **後端解析可信身分**：Controller 注入父上下文提供的 `RequestOwnerIdentityResolver` 以解析目前請求身分（嚴禁信任請求主體或參數自行宣稱的 owner UUID），並在檔案持久寫入磁碟且紀錄提交後，才標示完成。
4. **實作 `QueueOperations` 與確實排空（Quiesce / Drain）**：`workKey` 為不透明字串（由宿主透過 `POST /api/download/queue/{queueType}/cancel` 的 JSON 請求主體傳遞）。凡有非同步或背景任務的佇列，必須覆寫 `prepareQuiesce` 以停止接納新請求，並回傳具有正世代編號的真實 `QueueDrain`，待進行中任務歸零後完成 drain。
5. **UI 插槽與排程工作能力（選用）**：透過 `uiSlots()` 貢獻 `WebUiSlotContribution`（使用宿主 `PixivVue.mountUiSlot`，不打包 Vue runtime）；透過 `scheduledSourceDescriptors()` 及子上下文 `ScheduledSourceExecutor` / `ScheduledWorkExecutor` Bean 貢獻排程工作支援（宿主負責排程、鎖、憑證與進度水位提交）。

## 新增外掛自有畫廊

每個下載類型外掛完整擁有自己的畫廊 HTML、CSS/JS、路由、i18n 與 Controller（`/pixiv-gallery.html` 與 `/api/gallery/**` 僅歸官方 Pixiv 畫廊外掛所有，不存在通用畫廊聚合 API）。

要接入畫廊頂部的跨類型切換列，在自有畫廊 HTML 頂部放入：

```html
<nav data-nav-slot="gallery.type-switch"></nav>
```

並在 `navigation()` 中貢獻 `NavigationPlacements.GALLERY_TYPE_SWITCH` 導覽項目（側邊欄入口則使用 `gallery.sidebar`、`gallery.sidebar.tools`、`novel.sidebar`、`novel.sidebar.tools`）：

```java
new NavigationContribution(
        "example-gallery-type-switch",
        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
        "example-download", "nav.gallery",
        "/example-download-gallery.html", "images",
        AccessPolicy.ADMIN, 50)
```

## 設定、憑證和檔案

| 資料類別 | 磁碟路徑 | 外掛存取方式 |
| --- | --- | --- |
| 宿主設定與外掛開關 | `config/config.yaml` | 經 Core API 穩定連接埠（如 `DownloadSettings`、`OutboundProxySettings`）以唯讀方式取得 |
| 外掛業務設定 | `config/plugins/{pluginId}.properties` | 子上下文 `Environment`（建議 `Binder.get(environment).bind(...)`）與 `GuiConfigContribution` |
| 外掛敏感憑證 | `config/credentials/{pluginId}.properties` | 宿主加密儲存，僅將本 owner 已宣告的敏感鍵注入子上下文 `Environment` 或 `PluginCredentials` |
| 外掛狀態與持久資料 | `state/{pluginId}/`、`data/{pluginId}/` | 注入 `RuntimePathProvider`，呼叫 `stateDirectory()`、`dataDirectory()` |
| 下載作品檔案 | `{rootFolder}/{pluginId}/` 或外掛自訂目錄 | 由 `DownloadSettings.getRootFolder()` 衍生，不得寫入 `state/` 或 `data/` |

- **資料庫存取**：新外掛注入 `PluginDatabase`，透過 `privateSchema()` 與 `privateStorageMigrations()` 宣告結構，使用 `read(work)` / `transaction(work)` 執行與方言無關的操作（相容 SQLite 與 PostgreSQL）；參與主資料庫協作時，注入 `PluginMainDatabase` 存取本 owner 受管資料表或已申請的 `sharedMainStorage()`。舊 `PluginDataSource` 僅支援 SQLite。
- **出站 HTTP / WebSocket**：注入 `OutboundHttpClientFactory` / `OutboundWebSocketClientFactory`，建立由外掛 Bean 擁有並在銷毀時 `close()` 的用戶端（`exchangeStream` 回傳的回應串流必須在所有分支關閉）；禁止自行建立 `HttpClient`、`ProxySelector` 或依賴宿主 HTTP 設定類別。

## 獨立服務契約與組合

SDK 1.4+ 支援跨外掛型別化服務（參考 `typed-service-plugin` 範本）：

- 契約載體外掛透過 `pixiv.contract-api` 與 `pixiv.contract-sha256` 宣告原始純 JDK API JAR（採用 `process-restart`）；提供者在 `serviceExports()` 匯出實作，消費者在 `serviceImports()` 宣告相依性並注入 `PluginServices`。
- 呼叫 `find(key)`、`require(key)`、`findAll(key)` 或生命週期綁定 `bind(keys, activation)` / `bindAll(keys, activation)`，取得綁定精確 publication 的代理。
- 使用 `sdk-tools.jar` 產生 JSON Schema、遠端呼叫配接器與檢查二進位相容性：

```text
java -jar sdk-tools.jar schema <api-module> <classpath>
java -jar sdk-tools.jar remote <consumer-module> <classpath> example.one.Service example.two.Service
java -jar sdk-tools.jar contract-evolution <old-api.jar> <new-api.jar> <new-report.json>
```

大整數在 record 欄位標註 `@ValueProperty(encoding = ValueEncoding.DECIMAL_STRING)`；`UUID`、`Instant`、`Duration`、`float`、`double` 使用 SDK 固定的無損字串編碼。

## 不依賴 GUI 的憑證與通用操作驗證

- **`PluginCredentials`**：外掛可在 `credentialKeys()` 宣告本 owner 前綴下的敏感鍵（最多 128 項，單值 64 KiB、單批 1 MiB；同名 GUI 欄位必須標為敏感），注入 `PluginCredentials` 執行原子 `read`、`update`、`delete`。
- **`OperationVerification` 與 `OperationGuard`**：驗證外掛提供 `OperationVerification` Bean；業務外掛在 `serviceImports()` 宣告 `OperationVerification.IMPORT` 並注入 `OperationGuard`，在準備階段呼叫 `prepare(operationId, subject, context)`，在提交階段完成驗證授權與限流後，呼叫 `execute(operationId, subject, context, token, proofs, action)`。詳見[頁面編輯器](/zh-hant/page-editor#表單驗證與資源授權)與[操作驗證碼](/zh-hant/captcha)（完整實作見 [`examples/captcha-plugin`](https://github.com/Sywyar/PixivDownloader/tree/master/examples/captcha-plugin)）。

## 一般插槽與頁面元件

一般頁面透過 `/js/pixiv-ui-slots.js` 的 `PixivUiSlots.connect(...)` 掛載 `data-ui-slot`，外掛模組呼叫 `registerModule(moduleUrl, mount)`，並在 `onCleanup` 中釋放 DOM、監聽器與請求。頁面編輯器元件與可編輯頁面的接入方式見[頁面編輯器與外掛元件](/zh-hant/page-editor#開發第三方外掛元件與接入頁面)。

## 維護外部專案與 SDK 升級

### 本地開發

SDK 開發包支援在 IntelliJ IDEA（執行 `Developer Mode`）、VS Code（`Run Plugin` / `Debug Plugin`）、Eclipse（`eclipse/Run Plugin.launch`）或命令列中，直接建置並啟動配套宿主與完整官方外掛，執行資料隔離於專案 `.dev/` 目錄（首次執行會自動下載並核驗固定執行包至 `~/.cache/pixivdownloader-sdk/`）：

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd verify exec:exec@sdk-debug
.\mvnw.cmd exec:exec@sdk-stop
```

打包形式預設為根目錄含 `plugin.properties`、不含宿主共用類別的 thin PF4J `.jar`；含外掛私有第三方相依項目時，可使用 PF4J JAR-with-lib（私有相依項目置於 `lib/*.jar`）。升級 SDK 時，請將新開發包解壓縮至獨立目錄，對照 Javadoc 更新契約與執行清單，並在外掛管理頁驗證啟停、reload 與任務排空。

## 投稿市場資料

社群投稿精靈支援附加外掛圖示、截圖、外部連結清單，以及多語言 `README`、本次更新說明和 `CHANGELOG.md`（可依版本標題如 `## [v1.2.0]` 自動擷取）。所有文件與圖片隨同一版本 Release 以簽章鎖定，用戶端以淨化後的靜態隔離文件顯示，詳見[社群儲存庫投稿說明](https://github.com/Sywyar/PixivDownloader-community-plugins#投稿与版本管理)。

## 簽署和發布

在主儲存庫建置 `pixivdownload-plugin-signature` 後，使用 `PluginSignatureTool` 管理 Ed25519 金鑰與簽章（私鑰須儲存在儲存庫與建置目錄之外）：

```powershell
# 1. 產生金鑰對與匯出公鑰指紋
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool keygen --directory "C:\secure\keys"
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool public-key --public-key "C:\secure\keys\public-key.pem" --key-id example-2026 --out "public.json"

# 2. 簽署外掛套件與儲存庫 manifest.json
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool artifact --artifact "plugin.jar" --plugin-id example-download --version 0.1.0 --key-id example-2026 --private-key "C:\secure\keys\private-key.pem" --out "plugin.jar.sig"
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool manifest --manifest "manifest.json" --repository-id example.plugins --key-id example-2026 --private-key "C:\secure\keys\private-key.pem" --out "manifest.json.sig"
```

自建外掛儲存庫可發布 `manifest.json` + `manifest.json.sig`（`manifest-v1` 協定）或實作 `paged-v2` 分頁端點，並提供最大 64 KiB 的公網 HTTPS `repository.json` 描述檔，供使用者在桌面設定中一鍵匯入（支援 `DIRECT_STRICT` 或 `GITHUB_RELEASES` 網路策略，以及 `repository-update` 輪替證明和 `plugin-revocations` 撤銷清單）。

需要向主儲存庫貢獻 SDK 契約或官方外掛修復時，請先閱讀[主儲存庫開發](/zh-hant/development)。
