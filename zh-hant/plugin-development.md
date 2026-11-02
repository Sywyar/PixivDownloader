# 第三方插件 SDK

從 SDK 開發包中的獨立工程開始，構建並運行示例後，再加入自己的頁面、下載類型或業務邏輯。需要 JDK 17、Node.js，以及所選 SDK 包附帶的構建工具和運行清單。

| 要完成的事 | 從這裏開始 |
| --- | --- |
| 創建第一個插件 | [選擇模板](#從模板開始)，再用 [Run / Debug](#本地開發) 驗證 |
| 增加下載類型或畫廊 | [下載類型流程](#新增下載類型的完整流程)、[獨立畫廊](#新增插件自有畫廊) |
| 保存配置、憑據或數據 | [所有權和存儲契約](#配置、憑據和文件) |
| 升級現有工程 | [SDK 升級](#外部工程維護與-sdk-升級) |
| 分發插件 | [市場資料](#投稿市場資料)、[簽名和發佈](#簽名和發佈) |

示例中的 `SDK_VERSION`、文件路徑、ID、摘要和簽名均須替換。Java / JavaScript 片段用於說明接口，需放入模板上下文並補齊導入和業務實現；它們不是獨立可運行程序。接口是否可用以所選版本的 Javadoc 和包內模板爲準。

相關源碼：

- [第三方插件模板](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates)
- [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info)
- [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api)
- [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api)
- [Douyin 第三方示例外掛](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)
- [插件簽名工具](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature)
- [SDK 下載與版本記錄](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases)
- [版本化 SDK Javadoc](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)

> Douyin 是完整的第三方 SDK 示例，展示下載、配置、代理、隊列、計劃任務、私有持久化和插件自有畫廊如何組合。它只依賴公開 SDK 契約，不屬於官方分發集合。新項目從 `plugin-templates` 開始，可避免帶入與目標站點綁定的業務代碼。

## 插件許可證與發佈渠道

PixivDownloader 及其官方插件採用 AGPL-3.0，並爲隨附[插件鏈接例外](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)的版本提供額外授權。通過公開 SDK/API 或文檔化擴展接口開發、符合例外條件的獨立第三方插件，可選擇 MIT 許可證。

例外允許複製和修改項目自有插件模板用於獨立第三方插件，分發時須保留相關版權聲明及例外文本。宿主、SDK/API、運行時和官方插件實現及其修改仍須遵守適用的 AGPL 義務；第三方依賴仍須遵守各自許可證。

帶此例外的 SDK 開發包將上游許可放在 `licenses/pixivdownloader/`，Maven SDK/API JAR 的 `META-INF/` 也包含許可文本。請爲自己的插件選擇許可證並在工程中聲明；複製示例另建工程時保留上游聲明。覈對實際選用的宿主和 SDK 版本，未隨附例外的舊發行不會因文檔更新而自動獲得額外授權。

[官方社區倉庫](https://github.com/Sywyar/PixivDownloader-community-plugins)只收錄開源插件：要求公開 GitHub 完整源碼、開源許可證、可復現構建和人工審核。只有公網產物或有效 SPDX 表達式不夠。插件許可與社區收錄分別適用；安裝時仍須通過既有包校驗和信任確認。

## 先理解信任邊界

`declarative-process` 插件在獨立 worker JVM 中執行，具有資源與協議隔離；`host-process-full-trust` 插件在宿主 JVM 中執行。兩者都不是 OS 級安全沙箱，插件仍可能訪問當前賬戶可讀的文件、發起網絡請求或消耗資源。

Ed25519 簽名只證明 artifact 來自某個受信密鑰且字節未被篡改，不能證明已簽名代碼沒有惡意行爲。安裝前必須信任發佈者、源碼和倉庫運營者。Cookie、Token、代理、作品目錄和插件私有數據的合法使用也由插件作者負責。

宿主仍會執行結構、大小、路徑、版本、依賴、SHA-256、簽名和 provenance 校驗，並用同一份凍結字節完成校驗與加載。這些措施保護供應鏈完整性，不構成代碼沙箱。詳細安裝行爲見[插件管理](/zh-hant/plugin-management)。

## SDK 邊界

統一薄 JAR 入口 `pixivdownload-sdk` 通過標準 POM 傳遞三個 API 模塊及宿主提供的編譯依賴；`pixivdownload-sdk-info`、`pixivdownload-plugin-api`、`pixivdownload-core-api` 和 BOM 仍可獨立使用。`sdk-info` 是完整 SDK 版本、預發佈身份和兼容規則的唯一事實源；SDK 版本與應用發行版本獨立。`plugin-api` 提供插件入口、contribution、宿主控制面和 owner-scoped 存儲能力；`core-api` 提供穩定的業務語義端口、值模型和中性算法。依賴方向必須保持爲：

```text
第三方插件
  └─ pixivdownload-sdk        provided：統一編譯入口
      ├─ pixivdownload-sdk-info    SDK 版本與兼容信息
      ├─ pixivdownload-plugin-api  插件入口、contribution、路徑與私有數據源
      ├─ pixivdownload-core-api    下載設置、代理設置等穩定語義端口
      └─ PF4J、Spring、Servlet、Jackson 等宿主編譯依賴

禁止依賴：pixivdownload-app、宿主實現類、plugin-runtime/installer/signature internal、
官方插件私有 service/mapper/controller、宿主 DataSource 或私有前端全局
```

插件通過描述符和 contribution 聲明能力，宿主按可信的插件身份、包身份、generation 和 publication 註冊能力。宿主不應出現按第三方插件 id、包名或作品類型寫的特判。插件停用、卸載、損壞或不兼容時，它的路由、靜態資源、i18n、導航、下載類型、隊列和計劃能力會撤回；消費者必須按“能力缺席”降級，不能產生白屏、空指針或半完成任務。

### 可以貢獻什麼

`PixivFeaturePlugin` 當前提供以下入口。未使用的入口保持默認空列表即可：

| 方法 | 能力 |
| --- | --- |
| `id`、`displayName`、`description`、`displayNamespace` | 插件身份和 i18n 展示鍵 |
| `iconKey`、`colorToken`、`kind` | 受控圖標、顏色和類別 token |
| `start`、`stop` | 插件直接擁有的本地資源生命週期；`stop` 必須冪等 |
| `schema` | 參與穩定宿主協作的受管表、補列和路徑列聲明；不是普通私庫入口 |
| `sharedMainStorage` | 請求宿主公開的邏輯主庫存儲域；不能請求其它 owner 的表 |
| `privateSchema` | 當前 owner 全部權威私有表的結構聲明 |
| `privateStorageMigrations` | 按發佈順序執行的私有數據轉換，啓動和停機遷移均使用 |
| `routes` | 頁面、API 和靜態路徑的訪問策略 |
| `staticResources` | 插件 classpath 靜態資源到 URL 的映射 |
| `i18n` | 插件自有 Web i18n namespace |
| `navigation` | 導航項和中性 placement |
| `startupRoutes`、`landings` | 默認啓動落點和身份相關業務落點 |
| `pageSections`、`uiSlots` | 頁面區塊與受控 Web UI 掛載點 |
| `guiThemes` | 啓動期 GUI 主題；通常需要 `process-restart` |
| `guiConfigContributions` | GUI 配置字段、分組、section、動作與預設 |
| `guiOnboardingSteps` | GUI 引導步驟 |
| `drilldowns` | 按語義 placement 解析的下鑽鏈接 |
| `userscripts` | 穩定腳本 id 與精確 classpath 資源 |
| `scheduledSourceDescriptors` | 計劃來源的純數據描述符 |
| `downloadTypes` | 下載工作臺作品類型描述符 |

Spring Bean 不從 `PixivFeaturePlugin` 返回。外置入口通過 `PixivPluginProvider.configurationClasses()` 聲明配置類，宿主爲活動插件創建獨立的子 `ApplicationContext`。

### 桌面 UI 邊界

應用殼只通過穩定、工具包無關的宿主契約提供業務狀態、配置語義和動作；`gui-swing` 與 `gui-compose` 分別完整擁有自己的頁面樹、佈局、組件、交互狀態、窗口、托盤、主題和平台適配。穩定 SDK 不提供 `DesktopUiDocument`、`DesktopUiNode` 或其它跨工具包視圖 AST，也不要求兩個 provider 共享頁面實現。

功能插件只通過 `GuiConfigContribution` 等純數據契約貢獻配置字段、分組、section、動作、預設及其它明確業務語義，由宿主按可信 owner 合併、校驗和保存，再由各 GUI provider 按自己的界面實現呈現。功能插件不得返回 Swing / Compose 組件或直接擁有宿主頂層窗口；宿主也不得按 provider id 選擇頁面結構。

官方發行包同時提供 `gui-compose` 和 `gui-swing`，Compose 爲默認 provider，Swing 爲後備。兩者均爲 `process-restart` 插件，切換、安裝、升級、禁用或卸載後必須完整重啓。Compose 插件的 Kotlin / Compose 編譯和 JAR-with-lib 產物由 Gradle Wrapper 實際生成，Maven reactor 只負責調用 Gradle 並接入官方構建、簽名和分發流程。

## 從模板開始

### 選擇模板

| 模板 | 適用情況 | 已包含內容 |
| --- | --- | --- |
| `minimal-feature-plugin` | 聲明式頁面、導航和 i18n | 獨立 worker、PF4J 入口、feature、route/static/i18n、thin JAR 測試；無 Spring 子上下文 |
| `download-type-plugin` | 新增一種可下載作品類型 | 下載描述符、五類取得模式、隊列、計劃來源、Vue 槽位、獨立畫廊、前後端測試 |

倉庫內驗證兩個模板：

```powershell
mvn -f plugin-templates/pom.xml clean verify
mvn -f plugin-templates/pom.xml -pl minimal-feature-plugin -am verify
mvn -f plugin-templates/pom.xml -pl download-type-plugin -am verify
```

複製到倉庫外後，每個模板都是不繼承 PixivDownloader 根 parent 的獨立 Maven 項目。在模板目錄執行：

```powershell
mvn clean verify
```

### 獲取 SDK artifact

SDK 開發包提供單一 `pixivdownload-sdk` 編譯依賴、獨立 Maven 工程及 Gradle / sbt 範例。帶有固定執行清單的開發包可透過隨附的 Run / Debug 入口建置目前外掛，自動準備配套主程式和完整官方外掛；執行資料保存在各工程的 `.dev/`。可下載版本及具體用法以對應 [SDK Release](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) 和包內 README 為準。

模板使用單一 `pixivdownload-sdk` 編譯依賴：

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>SDK_VERSION</version>
    <scope>provided</scope>
</dependency>
```

Java 控制器使用未顯式命名的 `@PathVariable` 或 `@RequestParam` 時，編譯必須保留參數名。Maven 工程在 `<properties>` 中設置 `<maven.compiler.parameters>true</maven.compiler.parameters>`；其它構建工具向 Java 編譯器傳入 `-parameters`。已有工程也需檢查此設置，並通過宿主實際調用打包後的接口，不能只測試控制器方法。

Gradle 使用 `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:SDK_VERSION")`，sbt 使用 `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "SDK_VERSION" % Provided`。標準 Ivy 可映射編譯配置：

```xml
<dependency org="io.github.sywyar.pixivdownloader" name="pixivdownload-sdk"
            rev="SDK_VERSION" conf="compile->default"/>
```

Ivy 的運行配置不要繼承此編譯配置。標準 Maven 元數據傳遞公開 API 及 PF4J、Spring、Servlet、Jackson 編譯依賴；產物仍是 thin PF4J JAR，不將這些宿主提供類打包。測試框架自行聲明，三個 API 模塊和 BOM 仍可單獨消費。

請將 `SDK_VERSION` 換成 [SDK Releases](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) 中包含統一入口的已發布版本。公開座標共五個：統一入口、三個 API 模組與 BOM。含 `developmentRuntime` 中繼資料的開發包附帶固定宿主，歷史版本以各包 README 為準。公開 Java API 或 Maven 消費契約變更須使用新的 SDK 發行身分，已發布座標與附件不可覆寫。

穩定版的 `plugin.requires` 宣告 SDK `major.minor`：主版本相同且宿主次版本不低於要求時相容，修訂版本不參與准入判斷。預發布開發包須按完整 SDK 版本匹配。僅模板、文件或工具修正不強制發布新 SDK；需要交付新開發包時，提升修訂版本或預發布序號，建立新的不可變發行。

PF4J、Spring、Jackson、Servlet API 等由宿主父 classloader 提供的依賴也必須是 `provided`。不要把共享契約或框架類複製進插件 JAR，否則同名類會因 classloader 不同而無法轉換。

### 複製後必須統一改名

以下載類型模板爲例，至少同時替換：

| 模板值 | 替換內容 |
| --- | --- |
| `example-download-plugin` | Maven `artifactId` |
| `example-download` | 全局唯一插件 id、隊列類型、URL 前綴和 i18n namespace |
| `com.example.pixivdownload.downloadtype` | Java 包及對應目錄 |
| `ExampleDownload` | Java 類型名前綴 |
| `0.1.0` | artifact 和 `plugin.version` |
| `plugin.requires=1.3` | 兼容的 SDK major.minor |
| `plugin.provider=Example Developer` | 發佈者名稱 |

還要同步修改路由、靜態路徑、前端常量、私有數據表名、測試和兩種語言的 i18n 文案。只改 `plugin.properties` 會造成 descriptor、feature 與運行時 publication 身份不一致，宿主會拒絕接入。

## 插件包和入口

### `plugin.properties`

文件必須位於 JAR 根部。以下示例與當前源碼模板的 SDK 1.3 要求一致；使用其它開發包時，按該包的真實最低契約調整編譯版本與 `plugin.requires`，不要僅改聲明繞過檢查。

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

字段規則：

| 字段 | 規則 |
| --- | --- |
| `plugin.id` | 全局唯一、小寫短橫線 token；必須與 `PixivFeaturePlugin.id()` 相同 |
| `plugin.version` | 插件 artifact 版本 |
| `plugin.requires` | 所需 SDK `major.minor`，不是應用發行版本 |
| `plugin.class` | PF4J 主類，實現 `PixivPluginProvider` |
| `plugin.provider`、`plugin.description` | 發佈者和 descriptor 說明 |
| `plugin.dependencies` | 可選 PF4J 插件依賴表達式 |
| `pixiv.*` 展示字段 | i18n namespace/key 和受控展示 token |
| `pixiv.replaces` | 可選的被替換插件身份 |
| `pixiv.execution-mode` | 必填：`host-process-full-trust` 或 `declarative-process`；缺失、空白及未知值均拒絕 |
| `pixiv.lifecycle-policy` | `hot-reload`、`backend-restart` 或 `process-restart`；區分大小寫，缺省爲 `hot-reload` |

新 SDK 預發佈版本採用 `alpha.N`、`beta.N`、`rc.N`，序號從 1 開始且不補零；歷史緊連後綴繼續按原始發行身份使用。SDK 按渠道和數字序號排序，例如 `rc2 < rc.10`；`rc2` 與 `rc.2` 優先級相同，不能僅換拼寫重新發佈。依賴座標、Tag、下載路徑及運行清單須與所選 Release 完全一致。已發佈的舊 SDK 工具按包內說明使用，新格式支持隨新的開發包交付。

穩定版的兼容判斷使用 `requiredMajor == hostMajor && requiredMinor <= hostMinor`，PATCH 不參與准入判斷；預發佈開發包按完整 SDK 版本匹配。破壞性契約變更升 MAJOR，向後兼容新增升 MINOR，兼容修復升 PATCH。

### 複用 PostHog 瀏覽器客戶端

需要發佈 PostHog 調查的 Web 插件可以依賴官方 `posthog` 插件，並在自己的頁面先加載 `/pixiv-posthog/pixiv-posthog.js`。這不是中性調查抽象：調查發佈插件仍擁有 Survey ID、問題結構、觸發、狀態、文案、隱私過濾以及全部四個 PostHog 項目參數。

```properties
plugin.dependencies=posthog?@1.0
```

```js
const posthog = Object.freeze({
  projectToken: 'phc_...',
  surveyId: '...',
  apiHost: 'https://example.invalid',
  uiHost: 'https://us.posthog.com'
});

const client = await window.PixivPostHog?.createSurveyClient({
  ownerKey: 'example-plugin.feedback',
  posthog,
  distinctId: '',
  beforeSend(event) {
    return allowedSurveyEvent(event) ? event : null;
  }
});
if (!client) return; // 依賴缺失、參數非法、SDK 加載失敗或配置衝突時靜默關閉

await window.PixivPostHog.captureSurveyWithAck(
  'example-plugin.feedback',
  'survey sent',
  surveyProperties,
  submissionId
);
```

`ownerKey` 必須全局穩定；同一頁面內同一 owner 以相同四參數、`distinctId` 和同一個 `beforeSend` 函數重複調用會複用客戶端，任一項變化則 fail-closed。不同 owner 可以使用不同項目參數。`submissionId` 必須是發佈插件根據 Survey ID、campaign 版本和調查作用域匿名身份派生的穩定 UUID；同一答卷的重試必須複用，campaign 版本提升時才生成新值。適配器會把該 UUID 作爲事件頂層 `uuid` 發送，並只在固定接收端返回 2xx 後完成 Promise；缺失或非法 UUID 會在發出請求前拒絕。適配器固定並加載 vendored SDK、關閉默認採集並創建隔離的命名實例，但不會替插件選擇調查、生成身份或決定回答字段。若運行時停用 `posthog`，已打開頁面中已經加載的 JavaScript 不會被撤銷；刷新後資源缺席，調用方必須按客戶端不可用降級。

若調查需要長期出現在站內信並直接填寫，發佈插件可以額外貢獻一個不加載槽位模塊的 `notification.inbox` 槽位：

```java
new SurveyInboxMessage(
        "example-plugin.feedback-survey",
        "campaign-v1",
        "/example-plugin/survey.html",
        "example-plugin",
        "survey.inbox-title",
        "survey.inbox-body",
        100
).toUiSlotContribution()
```

`messageKey` 必須穩定且全局唯一；`instanceKey` 只在發佈新一輪調查時變化。正文 URL 必須是插件自有的同源絕對路徑，namespace 與標題、摘要 i18n key 也由該插件發佈。該路由仍需聲明訪問級別；僅從管理員站內信展示的問卷應聲明 `ADMIN`。HTML 保留在插件自有頁面中，不以原始正文、Bean 或 ClassLoader 通過穩定契約傳遞。命中問卷目標但字段非法的貢獻會被拒絕並記錄診斷；不應手工拼裝 metadata 代替該封裝。

該貢獻是可選的 best-effort 展示能力。通知插件缺席不影響問卷頁面或 PostHog 提交主流程。宿主保存純值活動快照，quiesce 不會回調發布插件。宿主在啓動同步或槽位變化後的下一次站內信請求中冪等保存消息；同一實例複用既有已讀狀態和不可用墓碑，實例鍵變化後創建新的未讀消息。貢獻缺席、插件停用或卸載、publication 換代只會隱藏活動消息，保留的狀態可在同一實例恢復時繼續使用。

宿主會給內嵌 URL 附加 `notificationId` 與 `lang` 查詢參數；頁面可用 `pixiv-content-height` 消息報告高度。只有在已確認遠端調查永久關閉 / 刪除後，頁面才應向同源父頁面發送 `{type: 'pixiv-survey-unavailable', notificationId}`；暫時網絡失敗不能發送，否則會留下關閉標記，並在同一實例再次發佈時保持關閉。

### PF4J provider 與 Spring 子上下文

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

每個外置包必須返回且只返回一個非空 feature，其 id 與 descriptor 相同。配置類用 `@Bean` 顯式裝配插件 Bean：

```java
@Configuration(proxyBeanMethods = false)
public class ExampleDownloadConfiguration {

    @Bean
    ExampleDownloadPlugin exampleDownloadPlugin() {
        return new ExampleDownloadPlugin();
    }

    @Bean
    ExampleDownloadController controller(
            ExampleDownloadQueue queue,
            RequestOwnerIdentityResolver ownerResolver) {
        return new ExampleDownloadController(queue, ownerResolver);
    }
}
```

不要依賴宿主根包掃描，也不要從插件包掃描任意類。子上下文可以注入父上下文明確提供的 Plugin API、Core API、JDK 類型和規範依賴，但不能注入 app 實現類。

## 貢獻通知模板

通知模板歸擁有業務場景的插件所有，不歸 mail 或 push 傳輸插件所有。場景所有者應在自己的子上下文中提供 `top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContributor` Bean；每個 `NotificationTemplateContribution` 都只是包含 `scenarioId`、`medium`、`locale`、`titleTemplate` 和 `bodyTemplate` 的純值：

```java
@Bean
NotificationTemplateContributor notificationTemplates() {
    return () -> List.of(new NotificationTemplateContribution(
            "example.completed",
            "mail",
            Locale.US,
            "示例已完成",
            """
            <!doctype html>
            <html><body><p>{{summary}}</p></body></html>
            """));
}
```

發佈模板數據不會註冊新的通知場景，也不會觸發發送。只能使用對應穩定場景 / dispatcher 契約已經接納的場景 id，且不能覆蓋其它插件擁有的 tuple。mail 和 push 插件只消費宿主提供的只讀 `NotificationTemplateCatalog`；配置、渲染檢查、發送和失敗處理仍由各自介質插件擁有。

宿主在準備插件 publication 時調用 contributor，把 record 複製爲不可變快照；插件 stop、reload 或 unload 時撤回對應的精確 publication。重複的 `(scenarioId, medium, locale)` 會立即失敗。查找先匹配精確 locale，再按確定順序回退到同語言模板。

HTML 不會通過網絡或進程邊界在插件間傳輸。它在同一個 JVM 內作爲有界 `String` 純值傳遞，並按 UTF-8 字節計量：標題上限 16 KiB，正文上限 1 MiB；單個插件一次 publication 最多 256 份模板、貢獻數據合計最多 8 MiB。不要通過本契約傳遞 `InputStream`、`Path`、Spring `Resource`、`ClassLoader`、Bean 或延遲迴調，否則會把插件生命週期或 I/O 狀態泄漏到邊界外。把不可信值填入 HTML 佔位符前必須轉義。

如果真實需求超過這些上限或需要二進制數據，應另行提出具有明確生命週期和配額的宿主所有 streaming/blob handle 契約；不要擴大模板契約，也不要讓插件通過臨時文件互相交換數據。

## Web 路由、靜態資源和 i18n

每個 controller 映射、靜態目錄和頂層 HTML 都必須由所屬插件在 `routes()` 中聲明。controller 只能由同一插件 owner 的聲明覆蓋，不能借用其它插件的寬前綴；未聲明的 `path + HTTP method` 會返回 404。前端隱藏入口不構成鑑權。

常用命名工廠：

| 工廠 | 實際訪問面 |
| --- | --- |
| `publicRoute` | 無需鑑權，solo/multi 一致 |
| `visitor` | multi 遊客可達，solo 需要會話，受邀訪客不可達 |
| `visitorAndInvitedGuest` | multi 遊客與受邀訪客均可讀 |
| `invitedGuest` | 管理員和受邀訪客可達，同時受 monitor 保護 |
| `admin` | 僅管理員 |
| `local` | 本機流程特例 |
| `gui` | 本機可信請求和 GUI token 雙重校驗 |

需要限制 HTTP 方法時使用 `WebRouteContribution` 標準構造器並顯式傳 `HttpMethod` 集合。HTTP 方法集合重疊時，不同插件的可匹配路徑必須使用相同 `AccessPolicy`；不同策略會在註冊階段 fail-fast，並報告雙方插件與路徑模式。相同策略可以共享命名空間；同一插件仍可用更具體的窄聲明覆蓋自己的寬前綴。

獨立管理頁的完整聲明示例：

```java
@Override
public List<WebRouteContribution> routes() {
    return List.of(
            WebRouteContribution.admin("/example-download-gallery.html"),
            WebRouteContribution.admin("/example-download-gallery/**"),
            WebRouteContribution.admin("/api/example-download/gallery"));
}

@Override
public List<StaticResourceContribution> staticResources() {
    return List.of(
            new StaticResourceContribution(
                    "classpath:/static/", "/example-download-gallery.html", true),
            new StaticResourceContribution(
                    "classpath:/static/example-download-gallery/",
                    "/example-download-gallery/"));
}

@Override
public List<I18nContribution> i18n() {
    return List.of(new I18nContribution(
            "example-download", "i18n.web.example-download"));
}
```

頁面、CSS 和 JavaScript 分文件存放；用戶可見文案進入插件 namespace。渲染外部數據時使用 DOM API 和 `textContent`，不要把未知文本拼進 `innerHTML`。

## 新增下載類型的完整流程

目前原始碼的下載工作契約支援外掛註冊提交處理器、選項擴充及事件觀察者，提供請求去重、精確取消和重新連線快照，無須繼承 SDK 抽象基底類別。[下載類型範本](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates/download-type-plugin)示範這些介面的用法。請選用包含相同契約的 SDK 與主程式；目前原始碼中的介面不代表舊發行包也已提供。

一個下載類型不是單個 Java 類，而是一組由同一插件擁有、能一起發佈和撤回的能力：

```text
plugin.properties + provider
        ↓
DownloadTypeDescriptor ──→ 下載工作臺發現類型和取得模式
        ↓
同源行爲模塊 ───────────→ 導入、發現、入隊、狀態更新、篩選和設置
        ↓
插件 controller/service ─→ 解析請求並完成真實領域工作
        ↓
QueueOperations ─────────→ 取消、清空和生命週期 drain
        ├─ WebUiSlotContribution（可選）
        ├─ ScheduledSourceDescriptor + executor（可選）
        └─ 插件自有獨立畫廊（可選）
```

### 1. 聲明 `DownloadTypeDescriptor`

當前沒有 `QueueTypeContribution`、`independentPage`、gallery capability bag 或 descriptor 內的 `uiSlots` 字段。下載類型、隊列、UI 槽位、計劃來源和獨立頁面各走自己的穩定契約。

```java
@Override
public List<DownloadTypeDescriptor> downloadTypes() {
    return List.of(new DownloadTypeDescriptor(
            DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
            "example-download",
            "example-download",
            "batch.kind",
            900,
            "download",
            "green",
            "/example-download/example-download-type.js",
            List.of(
                    DownloadAcquisitionMode.SINGLE_IMPORT,
                    DownloadAcquisitionMode.USER_PROFILE,
                    DownloadAcquisitionMode.SERIES_COLLECTION,
                    DownloadAcquisitionMode.SEARCH,
                    DownloadAcquisitionMode.QUICK),
            true,
            List.of("example-ready-filter"),
            List.of("example-output-setting"),
            "example-download"));
}
```

字段含義：

| 字段 | 要求 |
| --- | --- |
| `contractVersion` | 當前必須爲 `DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION`，值爲 1 |
| `type` | 全局唯一作品類型；通常與 `QueueOperations.queueType()` 一致，但註冊中心不假定二者必然對應 |
| `displayNamespace`、`displayI18nKey` | 類型名稱的 namespace 和純 key |
| `order` | 穩定排序值 |
| `iconKey`、`colorToken` | 宿主白名單內的受控 token，不是 URL、HTML 或任意 CSS |
| `moduleUrl` | 必填的同源絕對 `.js` 路徑，必須由同一插件靜態資源貢獻擁有 |
| `acquisitionModes` | `single-import`、`user`、`series`、`search`、`quick` 的聲明集合 |
| `cancelSupported` | 是否提供單項取消；爲 `true` 時隊列項必須有頂層 `cancelWorkKey` |
| `filters`、`settings` | 行爲模塊中對應契約 id 的白名單 |
| `i18nNamespace` | 行爲模塊狀態和錯誤文案 namespace |

### 2. 實現前端行爲模塊

模塊在宿主創建的真實 `<script>` 求值窗口中調用 `PixivBatch.queueTypes.registerModule(initializer)`。不要複製 Vue，也不要直接讀寫宿主的 `state`、`saveQueue`、`renderQueue`、`updateStats` 或私有 DOM id。

管理員下載頁會把 `DownloadTasks` 的執行快照合併到現有佇列。類型模組在 `process(item, context)` 中取得後端實際執行的 UUID 後，呼叫 `context.bindTask(taskId)`，即可關聯原項目並按執行編號取消；不可用作品 ID 代替執行編號。從其他入口提交的工作會標示為「其他入口」，網頁不會重新派發。終態快照僅保留 5 分鐘，已儲存的佇列記錄仍會保留；無法確認的執行會顯示為待確認，重新整理頁面不會自動重送。

contract version 1 的主要入口：

| 入口 | 職責 | 缺席語義 |
| --- | --- | --- |
| `process(item, context)` | 把一個隊列項交給插件 API，並用 `context.updateItem(patch)` 提交白名單狀態 | 必選；缺失則類型不應激活 |
| `import` | URL 匹配、構建單作品隊列項和 `cancelWorkKey` | 未聲明 `single-import` 時省略 |
| `acquisition.user` | 用戶輸入、分頁發現、渲染和隊列元數據 | 未聲明 `user` 時省略 |
| `acquisition.series` | 系列 URL、分頁、順序和隊列元數據 | 未聲明 `series` 時省略 |
| `acquisition.search` | 搜索請求、範圍請求、渲染和隊列同步 | 未聲明 `search` 時省略 |
| `acquisition.quick` | 快捷動作和作品發佈；結果通過 `context.publishWorks(payload)` 提交 | 未聲明 `quick` 時省略 |
| `filters` | 只實現 descriptor 已列出的篩選 id | 空列表表示沒有額外篩選 |
| `settings` | 只實現 descriptor 已列出的設置 id | 空列表表示沒有額外設置 |
| `slots` | 類型模塊同源的聲明式片段 | 沒有片段時省略；獨立動態槽位走 `WebUiSlotContribution` |

initializer 會收到 `AbortSignal`、`isActive()`、`assertActive()` 和 `onCleanup()`。所有異步結果在寫回前必須確認 publication 仍然有效；清理監聽器、定時器和已掛載組件。

### 3. 後端解析可信 owner

HTTP controller 只從父上下文注入 `RequestOwnerIdentityResolver`，由當前請求解析管理員/用戶 owner：

```java
RequestOwnerIdentity identity = ownerResolver.resolve(request);
queue.submit(command, identity);
```

不能信任 JSON、query 或自定義 header 中的 owner UUID。descriptor 的 `pluginId/packageId/generation/publicationId` 只證明下載類型 publication 的 currentness，也不是用戶鑑權身份。

真實下載器必須在文件已經耐久寫入、歷史或來源關係等成功事實已經落地後，才把任務標記爲 completed。模板的內存完成響應只是確定性測試夾具，不能直接用於生產插件。

### 4. 實現 `QueueOperations`

```java
public final class ExampleQueue implements QueueOperations {
    @Override public String queueType() { return "example-download"; }
    @Override public void cancel(String workKey, String ownerUuid, boolean admin) { /* ... */ }
    @Override public int clearAll() { /* ... */ }
    @Override public int clearForOwner(String ownerUuid) { /* ... */ }
}
```

`workKey` 是該 queue type 內的不透明穩定字符串，不要求是數字，也不能放進 URL path segment。宿主使用 `POST /api/download/queue/{queueType}/cancel`，在 JSON 中傳原始 `workKey` 和 descriptor publication 身份；插件前端應調用宿主橋接，不自行構造控制請求。

嚴格同步且沒有後臺任務的實現可以使用默認 generation 0 completed drain。只要存在排隊、執行器 handoff、延遲迴調或任何越過當前調用棧的工作，就必須：

1. `prepareQuiesce(registeredQueueType)` 原子停止接納新任務，返回正 generation 的真實 `QueueDrain`；
2. 宿主保存 drain 後，`cancelQuiescedTasks()` 才發送協作式取消；
3. 所有活動任務歸零後 drain 才完成；
4. 重複 prepare 返回相同的 `queueType + generation`，新插件實例使用新 generation。

不能用 completed 哨兵僞造異步隊列已經退出。插件自己的 executor、scheduler、HTTP/WebSocket client 必須由子上下文擁有並在關閉時釋放。

### 5. 增加 UI 槽位

槽位獨立發佈，不放回下載 descriptor：

```java
@Override
public List<WebUiSlotContribution> uiSlots() {
    return List.of(
            new WebUiSlotContribution(
                    "example-download.settings-card",
                    "settings-card",
                    "/example-download/example-download-type.js",
                    900),
            new WebUiSlotContribution(
                    "example-download.quick-actions",
                    "quick-actions-mine",
                    "/example-download/example-download-ui-slot.js",
                    900));
}
```

動態槽位模塊通過宿主 `PixivVue.mountUiSlot` 掛載，並且只用 owner-scoped `context.supports(type, mode)`、`context.dispatchQuickAction(action)` 和 `context.onCleanup(...)`。不要隨插件打包 Vue runtime。

### 6. 增加計劃任務能力

計劃任務是可選能力。feature 只貢獻純數據 `ScheduledSourceDescriptor`，`ScheduledSourceExecutor` 和 `ScheduledWorkExecutor` 作爲插件子上下文 Bean 提供。

宿主負責排程 tick、持久化狀態、並行控制、管理 API 及關閉時等待執行結束。來源與作品執行器由各自外掛發布及撤回，無須依賴下載工作台。憑據管理入口使用 `plugin.api.schedule.management.ScheduledTaskCredentials`，不引用宿主的 `ScheduleService`；管理頁面可透過 `NavigationPlacements.DESKTOP_SCHEDULE_MANAGE` 提供桌面入口。

瀏覽器來源模塊負責：

- `capture`：把當前取得輸入序列化成插件擁有、帶 schema/version 的 definition；
- `restore`：把保存的 definition 回灌編輯器；
- `summary`：生成受控的展示結構。

瀏覽器只通過 publication-scoped `context.acquisitionInput(mode)` 和 `context.restoreAcquisition(mode, value)` 訪問宿主允許的輸入。當前第三方中性 adapter 只開放 `single-import`，不要讀取宿主 DOM 或調用私有 mode global 模擬其它模式。

後端來源執行器擁有 definition schema、發現和 checkpoint；作品執行器擁有 payload schema 和同步作品執行。宿主繼續擁有 claim、lease、credential、Guard、pending、取消和 checkpoint CAS。`ScheduledWorkExecutor.execute` 只有在作品文件和成功事實都已耐久提交後才能返回 `COMPLETED` 或 `ALREADY_COMPLETED`。插件或執行器缺席時，任務數據保留並掛起，不應刪除或提前推進 checkpoint。

## 新增插件自有畫廊

“獨立頁面”是一個設計模式，不是名爲 `independentPage` 的 API 或 descriptor 字段。它表示插件完整擁有：

- 頂層 HTML 和頁面專屬 CSS/JavaScript；
- 自己的 route、static 和 i18n contribution；
- 自己的 controller/API、可見性檢查和數據模型；
- 自己的導航或類型切換入口（如需要）。

因此，新增第三方畫廊的標準做法是按前文的獨立管理頁示例新增頁面和 API。頁面是否存在自然跟隨插件 publication；插件停止後路由和資源撤回，不需要宿主按類型寫分支。

下載類型插件還應在自己畫廊頁頂部聲明空槽位：

```html
<nav data-nav-slot="gallery.type-switch"></nav>
```

並通過 `navigation()` 註冊自己的類型切換入口：

```java
new NavigationContribution(
        "example-gallery-type-switch",
        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
        "example-download", "nav.gallery",
        "/example-download-gallery.html", "images",
        AccessPolicy.ADMIN, 50)
```

每個插件只聲明自己的入口；頁面不硬編碼 Pixiv、Douyin 或其它插件 id。宿主根據當前活動 publication 聚合槽位，熱啓停時入口同步增減。

邊界必須明確：

- `/pixiv-gallery.html` 是長期維護的官方 Pixiv 主畫廊，不是第三方下載類型的通用掛載殼；
- 通用畫廊 provider/registry/broker、`/api/gallery/unified/**` 和 `unifiedGallery` ABI/wire 字段已移除，不存在可供插件消費的兼容面；
- 第三方頁面不得複製 gallery/novel 私有實現、直連宿主數據庫或導入 app 實現類；
- 資產 serving、刪除、可見性、搜索、收藏和統計由該插件自己的 API 與私有數據模型實現；需要宿主協作能力而 SDK 尚未提供時，應先提出中性公共契約，不要從 app 實現繞過。

Douyin 的 `/pixiv-douyin-gallery.html`、詳情頁、`/api/douyin/gallery/**` 和 `gallery.type-switch` 貢獻是該模式的完整 SDK 案例；第三方項目仍以 `download-type-plugin` 的獨立畫廊爲可複製基線。

## 畫廊導覽槽位

外掛程式透過 `NavigationContribution.placements` 聲明入口。`gallery.sidebar` 承載外掛程式、下載、畫廊等主要頁面，`gallery.sidebar.tools` 承載邀請碼管理、統計、疑似重複等工具頁面；小說畫廊對應 `novel.sidebar` 和 `novel.sidebar.tools`。兩組獨立排序，按請求身分過濾；貢獻撤回後入口消失，空組隱藏。頁面只聲明 `data-nav-slot`，不按外掛程式 ID 分類。

## 配置、憑據和文件

### 三類配置所有權

| 內容 | 路徑 | 插件如何取得 |
| --- | --- | --- |
| 宿主設置和啓停狀態 | `config/config.yaml` | 只經 SDK 的只讀語義端口讀取需要的最小值；不直接讀寫文件 |
| 插件業務配置 | `config/plugins/{pluginId}.properties` | 子上下文 `Environment`、`@Value`、`@ConfigurationProperties`；需要直接管理文件時用 `RuntimePathProvider` |
| 插件憑據 | `config/credentials/{pluginId}.properties` | 宿主加密維護，只把當前 owner 已聲明的解密值注入該插件子上下文 |

插件業務鍵應使用自己的 id 前綴，例如：

```properties
example-download.download.directory=
example-download.proxy.mode=inherit
example-download.download.include-cover=false
```

普通讀取示例：

```java
@Bean
ExampleSettings settings(Environment environment) {
    return new ExampleSettings(
            environment.getProperty("example-download.download.directory", ""),
            environment.getProperty("example-download.proxy.mode", "inherit"));
}
```

GUI 字段通過 `GuiConfigContribution` 聲明，宿主按可信 owner 保存。敏感字段或 `PASSWORD` 字段不會寫入普通 properties；插件只讀取注入後的值，不能讀取、解密或重寫 credential envelope。owner-scoped 目錄和加密憑據仍不構成惡意同 JVM 代碼的硬隔離。

### 穩定路徑和作品目錄

宿主在每個插件子上下文注入已綁定 owner 的 Plugin API `RuntimePathProvider`；調用方不傳入插件 id：

```java
Path config = runtimePathProvider.configFile("properties");
Path state = runtimePathProvider.stateDirectory();
Path data = runtimePathProvider.dataDirectory();
```

`state/{pluginId}` 用於可重建運行狀態，`data/{pluginId}` 用於插件管理的數據和緩存。作品文件不能寫進這兩個目錄。私有數據庫使用當前進程所選 provider，不應從數據目錄推斷固定的數據庫文件。

#### 私有數據庫與主庫存儲域

新插件需要支持可切換數據庫時，注入 owner 綁定的 `PluginDatabase`，通過 `privateSchema()` 聲明全部權威私有表，並用 `privateStorageMigrations()` 聲明按發佈順序的數據轉換。provider 在子上下文啓動前建表、安全補列並覈驗結構；同一聲明也用於停機遷移。空聲明不表示可以忽略已有私庫。

| 入口 | 契約 |
| --- | --- |
| `PluginDatabase.read(work)` | 同線程只讀回調，拒絕寫入；回調返回後會話失效，只返回已物化結果 |
| `PluginDatabase.transaction(work)` | 只對當前 owner 私庫原子寫入，不加入宿主 Spring 事務；任一操作失敗後只能回滾 |
| `available()` | 表示當前可操作性；不可用不是空數據庫，也不能當作寫入成功 |
| `databaseId()` | 診斷標識，不用於業務代碼選擇 SQL 方言 |

操作使用中性關係型契約，provider 負責方言、連接與憑據。能力缺失或故障時操作拋出 `SQLException`（SQLState `08003`）；查詢應明確標記不可用，寫入不得繼續後續副作用。插件自帶驅動或 SQLite 專用 SQL 不會自動轉換。

參與主庫存儲協作的插件使用 `PluginMainDatabase`：只可訪問本 owner 的受管主庫表和 `sharedMainStorage()` 明確申請的宿主公開域，不能訪問其它 owner 的表或私庫；未知域會拒絕裝配。`read` / `transaction` 都是同線程同步回調，寫入加入宿主事務，可與文件發佈共同提交。缺庫、上下文關閉或聲明失效時拒絕操作，任一失敗使事務不可提交。

舊 `PluginDataSource` 仍是 SQLite 專用兼容入口，連接 `data/{owner}/plugin.db`；切到其它方言時返回 SQLState `0A000`，不會另建 SQLite。使用它的插件負責自己的 JDBC 兼容性，不得關閉、unwrap 數據源或訪問主庫。跨引擎遷移還需完整的私庫結構與數據轉換聲明；先驗證遷移報告，不能只檢查安裝包替換成功。

下載作品默認從 `DownloadSettings.getRootFolder()` 繼承宿主作品根，並由插件自主管理自己的子目錄：

```java
Path defaultOutput = Path.of(downloadSettings.getRootFolder())
        .resolve("example-download")
        .normalize();
```

Douyin 當前使用同一規則得到 `{rootFolder}/douyin`，再按 owner 管理作品；用戶在 `config/plugins/douyin.properties` 設置保存位置後改用插件自己的覆蓋目錄。第三方插件可以採用相同模式，但具體子目錄、文件名和遷移邏輯歸插件所有。

不要依賴 app 的 `RuntimeFiles`、`DownloadConfig`、`ProxyConfig`、宿主 mapper、`SqlSessionFactory`、主 `DataSource` 或具體線程池 Bean。路徑和私有數據源使用 Plugin API，下載和代理語義使用 Core API 的 `DownloadSettings`、`OutboundProxySettings` 等端口；SDK 沒有覆蓋的宿主實現不是隱式公共 API。

完整路徑說明見[存儲原理](/zh-hant/storage)，配置鍵說明見[配置參考](/zh-hant/configuration)。

## 出站 HTTP 與 WebSocket

插件網絡訪問使用純 JDK 穩定工廠：

```java
@Bean(destroyMethod = "close")
OutboundHttpClient exampleHttpClient(OutboundHttpClientFactory factory) {
    return factory.open(OutboundHttpClientProfile.standard(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            OutboundHttpRoute.inherit()));
}
```

`OutboundHttpClient.exchangeStream` 返回的 live response 每條路徑都必須關閉；`exchange` 會完整緩衝並自動關閉響應。非 2xx 狀態仍是普通響應，業務調用方負責解釋。

WebSocket 使用 `OutboundWebSocketClientFactory.open(profile)`，客戶端同樣由插件 Bean 擁有並在子上下文關閉時 `close()`。插件聲明超時、redirect、cookie、連接池和中性 route profile；宿主擁有實際傳輸、全局/任務代理解析和 ProxySelector。

不要自行創建 `java.net.http.HttpClient`、`ProxySelector`，不要依賴 Apache 類型或 app 的 HTTP 配置。鑑權頭、站點請求頭和協議消息屬於插件業務，不能塞進通用傳輸層。Douyin 已使用 `OutboundHttpClient` 作爲完整參考實現。

## 獨立服務契約與組合

以下介面要求 SDK 1.4.0。使用 `typed-service-plugin` 範本建立獨立 API、契約載體、提供者與消費者模組；業務版本由契約套件管理。載體透過 `pixiv.contract-api` 與 `pixiv.contract-sha256` 指定原始 API JAR，採用 `process-restart`。提供者和消費者宣告對載體的依賴，不各自封裝一份 API 類別。

在 `PixivFeaturePlugin.serviceImports()` 宣告需要呼叫的 key，在 `serviceExports()` 宣告實作 Bean，並向子 context 注入 `PluginServices`。建構時可以保留存取器或登記繫結，但自身 publication 啟用前不能呼叫業務。介面與 record/enum 必須來自同一契約類別空間和程式碼來源。契約套件不支援依賴其他契約套件，跨邊界 DTO 應明確轉換。

`find(key)` 缺席時傳回空，`require(key)` 回報 `UNAVAILABLE`；兩者遇到多個實作都回報 `AMBIGUOUS`。`findAll(key)` 傳回不可變的 `List<ServiceProvider<T>>`，空集合合法，每次最多 128 個成員。`service()` 是固定消費者與提供者精確 publication 的代理，`publication()` 是可信的純值身分。發布順序不代表業務優先級，選擇與排序由消費者負責。舊代理撤回後失敗，不轉接替代實例。

需要隨依賴變化建立資源時，使用 `bind(keys, activation)` 或 `bindAll(keys, activation)`。前者等待每項唯一實作，後者接受空集合；回呼分別透過 `scope.require(key)`、`scope.findAll(key)` 取得本輪代理，以 `scope.acquire(...)` 和 `scope.guard(...)` 管理資源與輔助回呼。成員變化先關閉舊輪准入，等待在途工作退出並清理，再啟用新輪。啟用回呼不得重播業務命令。`FAILED`、`CLOSE_FAILED` 可明確 `retry()`；只有 `CLOSED` 證明關閉完成。

簡單廣播可由獨立介面 `EventSink.onEvent(record)` 實作：發布者遍歷 `findAll`，逐個呼叫，並依業務契約處理 `ServiceAccessException`。只有非權威的輔助副作用適合盡力傳送。必要驗證必須在寫入前完成，必要實作缺席或驗證失敗應拒絕寫入。集合介面不提供訊息持久化、自動重試、背壓或送達保證。

長任務使用業務契約自己的 `start → jobId → query/cancel/result` 純值協定。提供者透過 `PluginRuntimeTaskRegistrar` 登記工作，以 `PluginResources` 管理執行器與資源，並自行回應取消。取消控制代碼不等於任務退出；宿主等待真實 drain 才關閉 context。跨重啟恢復、checkpoint 與冪等性由資料 owner 透過私有資料庫定義，不把 Future、執行緒或回呼當作服務值。

`binding.diagnostic()` 傳回有界的純值原因、狀態、依賴與 publication。管理頁的服務關係來自同一執行期事實來源，停用影響包含宣告匯入及仍在退出的繫結。診斷區分等待、歧義、啟用失敗、排程拒絕與關閉失敗；`truncated` 表示顯示不完整，不代表沒有依賴。讀取診斷不重試業務，也不改變生命週期。

## 不依賴 GUI 的憑證與通用操作驗證

透過 `credentialKeys()` 宣告自身前綴的鍵，例如 `my-plugin.token`，並注入 `PluginCredentials`。`read(key)` 傳回 `Optional<String>`；`update(Map)` 原子合併，省略鍵保留、空白值刪除；`delete(key)` 冪等刪除。最多宣告和更新 128 項，單值 UTF-8 最多 64 KiB，單批最多 1 MiB。未宣告鍵與舊 generation 控制代碼會被拒絕，不能傳入其他 owner。

憑據鍵不能同時宣告為普通 GUI 欄位：同鍵必須是敏感欄位，否則宿主拒絕該 owner 的憑據與桌面設定快照，並繼續遮蔽已發現的敏感鍵。GUI 儲存的憑據快照、寫入與失敗回復共用一次 owner 鎖；載入和重新載入時，憑據讀取與遷移決策也在同一次鎖內完成，避免舊快照或舊明文覆蓋已成功的程式化輪換。環境匯出選擇包含憑據時，也包含沒有 GUI 的已宣告鍵，封存必須加密；排除憑據時不匯出這些值，還原仍檢查 owner 與安裝代際。

儲存沿用宿主加密與檔案鎖，既有 GUI 敏感欄位仍納入相同宣告及遮罩範圍。儲存不會自動重綁已有 `@Value` 或設定物件；業務應按需讀取或遵守既有重啟策略。不得把明文放入服務 DTO、例外、日誌或診斷。

策略在自己的子 context 提供 `OperationVerification` Bean。`policyId()` 是穩定策略鍵；`policy()` 傳回無副作用的規則修訂、上下文 schema、互動協定及結構化資源 profile。規則或資源變更須更新修訂並重新發布頁面；`generation()` 表示憑證代際，使舊挑戰失效。`verify()` 只接收必要純值與不透明 proof，傳回 `APPROVED`、`REJECTED` 或 `CHALLENGE`，不取得密碼、Cookie、完整請求或業務回呼。

業務 owner 在 `serviceImports()` 宣告 `OperationVerification.IMPORT`，注入本 owner 的 `OperationGuard`，對於已有身分的業務 API，完成身分、權限、CSRF、限流及輸入檢查後呼叫 `execute(operationId, subject, context, token, proofs, action)`。操作身分必須屬於該 owner；一般業務的主體從可信身分解析器取得；登入準備使用格式校驗後的使用者名稱，不代表已認證。全部必要策略通過後，才在 owner 本機執行一次 action；租約持續到業務動作實際結束。缺席、逾時及換代均拒絕，業務失敗不自動重試。不要以輔助回呼的 `ServiceBindingScope.guard()` 承接使用者命令。

準備入口呼叫 `OperationGuard.prepare(operationId, subject, context)`，不執行業務；提交入口繼續使用 `execute` 複核。瀏覽器 `createOperationTarget` 的第三個參數是表單 owner 的準備請求回呼，接收取消訊號；只有 `ready()` 為 true 才能使用 `request()` 取得提交資料。準備與提交須綁定相同主體及上下文。輸入變更呼叫 `reset()`，元件失效呼叫 `invalidate(id)`，取消準備並清除證明；不自動重送業務。操作步驟見[頁面編輯器指南](page-editor.md)。

頁面 owner 在 `EditablePage.targets` 宣告 `PageInteraction.Target`，元件透過 `PageComponent` 的參與宣告指定策略與協定。唯一相容目標會自動綁定；有歧義須選擇，無匹配只可儲存草稿。元件先呼叫 `context.interaction.prepare()`，再透過 `context.interaction.onState()` 接收自己的挑戰，以 `respond(id, proof)` 回應；狀態清空或實例銷毀時須結束 Worker、請求與監聽器。表單 owner 使用 `/js/pixiv-pages.mjs` 的 `createOperationTarget`，經頁面掛載傳入目標；型別見 SDK 的 `page-interaction.d.ts`、`operation-verification.d.ts` 與 `page-components.d.mts`。一般 `PagePort` 連線仍為 DAG。

登入提交依序執行限流、驗證、密碼檢查及建立工作階段。`setup.login-verification-policies` 是最多 16 項的靜態必要策略，空值只清除靜態要求，頁面發布不能抹除非空靜態要求。`POST /api/auth/login/prepare` 只提交使用者名稱與 rememberMe，沿用登入限流，準備成功以 200 回傳 `token`、`revision` 與依策略鍵索引的 `challenges`。全部證明齊備後，登入請求才提交 `operationToken`、`operationProofs` 及登入憑證；直接提交但缺少證明時仍回傳 409 挑戰。拒絕為 403，必要能力不可用為 503，舊挑戰為 409，失敗不建立 Cookie。挑戰綁定主體、操作、上下文、規則、憑證、provider 及正式修訂，五分鐘有效且單次消費；單項挑戰/proof 為 UTF-8 16 KiB，累計 64 KiB，最多 256 個待回應挑戰。

固定 `login.verification` UiSlot 也宣告相同參與協定，接收實例互動通道；用戶端成功事件不授予權限。正式頁面、綁定、操作要求及資源授權由 `PagePublications` 持有同一修訂；預檢不呼叫廠商，提交時重新檢查並等待受影響操作結束。資源來源須由管理員明確核准；未綁定頁面與自訂元件沙箱不繼承這些權限。綁定及復原方式見[頁面編輯器指南](page-editor.md)；[獨立驗證碼範例](https://github.com/Sywyar/PixivDownloader/tree/master/examples/captcha-plugin)包含 Turnstile、ALTCHA SHA-256 與 PBKDF2 適配。

## 普通槽位與頁面元件

普通頁面載入 `/js/pixiv-ui-slots.js`、建立 `data-ui-slot` 掛載點，呼叫 `PixivUiSlots.connect({targetPrefix, properties, regions, onEvent, i18n})` 後 `refresh()`。預設清單仍受既有路由權限保護；公開頁面須使用限定目標的公開清單。模組在腳本求值時呼叫 `registerModule(moduleUrl, mount)`，傳回可選的 `update(properties)` 與 `destroy()`。

`mount(root, context)` 使用純值屬性、事件及明確 region，不讀取其他頁面的私有全域變數。`context.fetch` 綁定來源 publication，目標 API 繼續鑑權；`signal`、`on`、`interval`、`onCleanup`、`loadScript`、`loadStyle` 隨實例撤回清理。活動清單每兩秒更新，隱藏頁面暫停新更新。同一模組可供多個實例共用，單一實例退出不撤銷其他實例。非同步返回後應檢查 signal，遲到結果不得重建舊 DOM。離頁釋放實例、監聽器、計時器與請求。介面見 `META-INF/pixivdownload/web/ui-slots.d.ts`。

同一 target 依清單的 `order` 排列；重新啟用或加入貢獻時，新節點直接插到正確位置，保留未變實例及其焦點與選取範圍。既有節點需要重排時，執行階段檢查 `moveBefore` 支援，並保留輸入框與可編輯文字的焦點、選取範圍；不支援該 API 的瀏覽器使用相容路徑。遲到清單不得撤銷較新的實例。模組在 `root` 外建立節點後，須立即用 `onCleanup` 登記對該節點參照的清理；控制器建立後另行登記銷毀，確保腳本載入失敗或期間撤回也能釋放資源。

下載工作台的佇列槽位繼續使用專用 Vue/佇列協定。普通槽位不是跨 Swing/Compose 的桌面檢視協定。小說和系列頁透過 properties、regions、事件向 AI/TTS 槽位提供內容，業務模型由雙方約定。

編輯器元件用 `PagePort(name, contractId, schema)` 宣告端口。兩端必須有相同的版本化契約身分及解析後 Schema，且使用 SDK 產生器的受控子集。未知端口、重複邊及有向循環拒絕儲存，執行期再次校驗值。掛載期間事件在全部實例掛載後依佇列轉發，支援 A→B→C，不向後來加入的實例重播歷史。單頁佇列最多 256 項、16 MiB，連續鏈最多 4096 次交付，超限明確失敗。元件只持久化專案資料、純值和受控樣式；業務請求仍使用產生的用戶端與實例 fetch。

## 多服務產生、值編碼與契約演進

先在獨立 API 模組產生 Schema，再為消費模組一次列出完整服務集合。以下目錄、classpath 與類別名稱需換成自己的值；Windows classpath 用分號，Unix 用冒號。

```text
java -jar sdk-tools.jar schema <api-module> <classpath>
java -jar sdk-tools.jar remote <consumer-module> <classpath> example.one.Service example.two.Service
java -jar sdk-tools.jar contract-evolution <old-api.jar> <new-api.jar> <new-report.json>
```

同簡單名稱的介面依完整套件名稱區分 Java 適配器與 `web/generated/<完整類別名稱>.mjs`、`.d.mts`。每次 `remote` 接收最多 128 個服務，空清單撤回全部產生物；移除服務也會清理舊 class、JS 與宣告。失敗撤回受管輸出和完成標記，封裝必須檢查 `target/remote-generation.complete`。完整批次編譯後才發布標記，但檔案寫入不是多檔案系統交易。輸出累計最多 16 MiB，單檔最多 1 MiB。

普通 JSON 整數仍受 Java 範圍和 JS 安全整數範圍交集限制。完整 long 的 record 屬性明確使用 `@ValueProperty(encoding = ValueEncoding.DECIMAL_STRING)`。UUID、Instant、Duration、float、double 採固定無損字串：小寫 UUID、`秒:九位奈秒`、IEEE 754 小寫定長十六進位。拒絕非正規表示、越界和非有限浮點，保留負零及奈秒，不以 JS Date 截斷精度。產生的用戶端透過 `wireValues.int64/uuid/instant/duration/float32/float64` 建立對應型別值。其他型別應在獨立契約中轉換為支援的 DTO，不註冊任意 serializer。

`contract-evolution` 只讀 classfile、不載入類別，分別報告原始碼相容、二進位連結、新宿主呼叫舊實作三個維度；業務行為始終為 `NOT_ASSESSED`。新增介面抽象方法可能保留舊二進位連結，但新呼叫遇到未實作的方法。報告不能代替重編、原始位元組舊外掛載入與實際呼叫；結束碼 0 也不證明行為相容。輸出檔案必須尚未存在。`verify-sdk-usage` 繼續檢查最低中央 SDK 宣告，不能代替獨立契約演進或舊 JAR 執行驗收。


## 外部工程維護與 SDK 升級

預發布 SDK 使用精確契約，properties 寫為 `plugin.requires==7.2.3-rc.4`（示例）；穩定版使用 `major.minor`。編譯相依、描述檔與執行套件須一致，不同 RC、RC 與穩定版均不預設相容。舊包若只聲明主次版本，無法據此推斷編譯時使用的 RC；須驗證後發布新的插件版本，不覆寫舊附件。保留原工程與 `.dev/`，把新 SDK 解壓到另一目錄，成套更新工具、契約資源與執行清單，再合併建置及 IDE 設定。插件負責私有資料遷移；未知格式或失敗時保留舊資料並停止功能啟動，包交易不回復業務資料。

獨立工程執行 `mvnw.cmd clean verify`（Windows）或 `./mvnw clean verify`，再以同一 Wrapper 執行 `verify exec:exec@sdk-run`。透過管理員頁面驗證啟動、停止、再次啟動及策略允許的 reload，核對路由撤回、舊 publication 失效、真實任務排空，以及資料與未完成任務保留。`process-restart` 須完整退出後重新啟動並核對版本。停止開發實例使用 `exec:exec@sdk-stop`。開發目錄的 full-trust 執行不能取代正式 worker 套件驗收。

`DownloadObserver` 是同步盡力通知，不提供持久重播；`DownloadAdmissionPolicy` 在副作用前允許或拒絕，不改寫請求。兩者是宿主管理的可選 full-trust Bean。`WorkFileImporter` 登記唯讀原檔參照，類型 owner 缺席時不可回報成功。請查閱所選 Release 的 Javadoc，確認實際提供的 API。

投稿排障先定位步驟：摘要不符時重取同一發行物，無候選時核對預設分支目前 CI 與 `tools/candidate-projects.json`，登入或 push 失敗時核對目前帳號與權限。審核事實、所有權改變或回應遺失時先查詢原請求，不自動重複投稿。報告保留 ID、版本、來源 commit、SDK 與錯誤碼，分享前移除憑據、私鑰及個人路徑。

## 構建、測試、調試和安裝

### 必要測試

至少保留模板已有的檢查：

- descriptor、provider、feature id 和 contribution 一致性；
- 子上下文顯式裝配及 controller 註冊；
- route/static/i18n/schema 或下載 publication；
- Queue owner、opaque work key、清空和 drain；
- 前端模塊 `node --check` 及實際行爲測試；
- JAR 根 `plugin.properties` 與 thin JAR 邊界。

從倉庫根驗證模板的標準命令是：

```powershell
mvn -f plugin-templates/pom.xml clean verify
```

若插件同時貢獻後端和前端，不要只用 Java 測試搜索腳本文本；前端 contract 應由 Node 實際執行。

### 本地開發

安裝 JDK 17 和 Node.js，讓 `java`、`node` 可從命令行調用。IDE 導入只解析工程。顯式 Run / Debug 才編譯當前插件、準備固定運行包並啓動完整應用；構建失敗會中止啓動。Maven Wrapper 會取得固定版本的 Maven，無需克隆宿主倉庫或手工複製宿主和官方插件。首次應用配置使用宿主自己的 setup 流程。

| IDE | 導入 | 運行 | 調試 |
| --- | --- | --- | --- |
| IntelliJ IDEA | 打開根 `pom.xml` | 選擇 `Developer Mode`，點擊 Run | 選擇同一個 `Developer Mode`，點擊 Debug |
| VS Code | 打開本目錄，安裝推薦的 Java Extension Pack | `Tasks: Run Task > Run Plugin` | `Run and Debug > Debug Plugin` |
| Eclipse | `Import > Existing Maven Projects` | `eclipse/Run Plugin.launch` | `eclipse/Debug Plugin.launch` 啓動組 |

IntelliJ 的 `Developer Mode` 是原生 Application 配置，啓動前由 Maven 執行 `test-compile exec:exec@sdk-prepare`。IDE 直接啓動 JVM，在同一進程中運行宿主、配套官方插件和當前工程的 `target/classes`。在 `ExampleMinimalPlugin.java` 的 `routes()` 內設置斷點，再點擊 Debug 即可，無需遠程連接或固定調試端口。入口 `src/test/java/sdk/DevelopmentLauncher.java` 不進入插件 JAR；運行配置把隨包工具加入啓動 classpath，以便解析 Spring Boot 嵌套 JAR。

此配置顯式啓用插件開發模式：當前源碼按 `host-process-full-trust` 執行，運行狀態如實顯示該模式，源描述符保持原樣。重新 Run / Debug 會重新編譯並加載源碼。結束時使用 `Stop Plugin`，或停止 Application 會話。

VS Code、Eclipse 和下述命令行任務使用打包驗證流程，安裝當前 JAR 並保留其聲明的執行模式。它們的遠程調試地址默認爲 `127.0.0.1:5005`；`declarative-process` 連接插件 worker，`host-process-full-trust` 連接宿主。使用 `Stop Plugin` 或停止整個組合來結束應用，單獨斷開遠程調試連接不會停止應用。

#### 命令行

Windows：

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd verify exec:exec@sdk-debug
.\mvnw.cmd exec:exec@sdk-stop
```

Linux / macOS：

```bash
./mvnw verify exec:exec@sdk-run
./mvnw verify exec:exec@sdk-debug
./mvnw exec:exec@sdk-stop
```

`sdk-debug` 等待 IDE 附加，不自行打開調試器。只驗證插件使用 `clean verify`，只準備運行包使用 `exec:exec@sdk-prepare`。默認產物爲 `target/example-minimal-plugin-0.1.0.jar`。

已成功構建本次產物後，也可直接調用隨包工具：

```text
java -jar tools/sdk-tools.jar run <工程絕對路徑> <本次插件JAR絕對路徑> --no-gui
java -jar tools/sdk-tools.jar debug <工程絕對路徑> <本次插件JAR絕對路徑> --debug-port=5005
java -jar tools/sdk-tools.jar stop <工程絕對路徑>
```

`--debug-connect` 用於連接已監聽的 IDE。`run` / `debug` 只接受工程內、`.dev/` 外的產物，並保留描述符中的執行模式，使用本次 JAR 的 SHA-256 完成本地安裝確認。官方插件在開發和打包驗證時均驗證原簽名及 provenance。請使用唯一插件 ID，避免與配套插件衝突。

#### 運行包、緩存和工程數據

`sdk-project.json` 與發行附件 `sdk-release.json` 記錄同一套 SDK、宿主、官方插件清單及完整運行 ZIP 的固定身份、大小與 SHA-256。運行 ZIP 是 SDK Release 的獨立附件，首次顯式準備時下載，後續複用 `~/.cache/pixivdownloader-sdk/` 中的已校驗緩存。清空緩存後仍取得相同字節；資源不可用或摘要不符會失敗。

每次啓動創建獨立運行副本，並覈對宿主及逐個官方插件。緩存不承載應用狀態。工程 `.dev/` 保存配置、數據庫、日誌、下載和運行副本；配置及狀態按運行包摘要隔離。此環境關閉宿主與官方插件自動更新，日常安裝的應用數據不參與這條啓動鏈。

停止應用後，可刪除本工程的 `.dev/` 重置開發數據，這也會刪除其中的下載文件。直接調用工具時，可通過 JVM 屬性 `-Dpixivdownload.sdk.cache-dir=<目錄>` 指定緩存位置；工程運行目錄仍獨立。

離線使用需要提前完成運行包準備和構建工具依賴緩存，兩者互不替代。Maven 使用 `-o`，Gradle 使用 `--offline`；緩存不完整會失敗。更新 SDK 時使用新版本開發包及配套清單，再遷入自己的源碼。

IDE 導入、編譯和完整運行是不同檢查：請在實際使用的 IDE 中驗證啓動、源碼斷點和正常停止；Maven 編譯通過不能代替 VS Code 或 Eclipse 的圖形操作驗收。打包驗證時，目錄過深仍可能觸及 Windows 創建 worker 時的工作目錄長度限制，遇到錯誤 267 時請移到較短路徑。運行包聲明的其它平台需在對應操作系統上驗收，不能以 Windows 結果代替。

Gradle / sbt 示例通過 `runPlugin`、`debugPlugin` 調用相同工具。Gradle 用 `stopPlugin` 停止；sbt 運行期間會佔用構建進程，請從另一終端在示例目錄執行 `java -jar ../../tools/sdk-tools.jar stop .`。

倉庫中的官方插件使用專用開發模式，它先編譯官方插件並從各模塊當前 `target/classes` 加載：

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

該入口用於主倉庫官方插件。Douyin 在獨立源碼倉庫開發，使用它自己的 SDK 工程；這裏不會自動發現倉庫外第三方項目。IDE 可使用倉庫提交的 IntelliJ IDEA、VS Code 或 Eclipse `Developer Mode` 共享配置。

調試時檢查：

- `/plugin-manage.html` 中狀態爲 `STARTED`，generation 與本次替換一致；
- 插件頁面/API 能訪問，stop 後變爲未聲明，start/reload 後恢復；
- 頁面腳本、CSS 和 i18n 的改動確實來自新 artifact，而不是瀏覽器緩存或舊 `plugins/` 包；
- `log/` 沒有 route 衝突、重複 id、子上下文裝配、版本、簽名或 drain 診斷；
- 異步任務停止後不再寫文件、回調頁面或持有舊 classloader。

### artifact 形態

模板默認生成 thin PF4J JAR：

- JAR 根有 `plugin.properties`；
- 沒有 Spring Boot `BOOT-INF/`；
- 沒有 `lib/*.jar`；
- 不包含 plugin-api、core-api、PF4J、Spring、Jackson、Servlet API 或宿主類副本。

宿主也支持 PF4J JAR-with-lib，用於插件私有第三方庫：根部仍是 descriptor、插件類和資源，私有依賴放 `lib/*.jar`。不要 shade 或私帶共享契約。選擇 JAR-with-lib 時應增加包結構和獨立 classloader 加載測試；官方默認交付格式仍是 `.jar`，不是 ZIP。

## 投稿市場資料

支持文檔投稿的 SDK 工具可從候選綁定的源碼提交讀取 `CHANGELOG.md`，按插件版本精確提取 `## [v8.2.6] - 2031.3.2` 這樣的二級標題，保留 `### Features`、`### Bug Fixes` 等分類。找不到唯一版本時，嚮導要求重選、手工輸入或明確跳過。可選 README 支持 UTF-8 Markdown、HTML、倉庫內文件、本地文件或多行輸入；完整 CHANGELOG 與本次說明分別發佈。

文檔按默認語言收錄，預覽確認後凍結字節。相對圖片只能取自選定目錄；外部圖片須明確確認下載。鏈接是可增減列表，支持預設用途、自定義本地化標題及顯式空列表。具體輸入與恢復方式見[社區投稿說明](https://github.com/Sywyar/PixivDownloader-community-plugins#投稿与版本管理)。

版本投稿的可選 `content` 包含 `readme`、`changelog`、`releaseNotes` 語言映射；文檔記錄 `format`、`asset`、可選 `sourcePath`、`sourceUrl` 與圖片 `resources`。倉庫文檔的 `sourceUrl` 綁定凍結源碼提交，相對鏈接按它解析；本地文件、直接輸入和缺少該字段的舊記錄不會猜測來源。安全 HTML 錨點和 Markdown 標題支持頁內導航，外部鏈接在新窗口打開。附件記錄 `name`、`url`、`mediaType`、`size`、`sha256`。市場元數據的 `links` 缺失時兼容 `homepageUrl`，空列表表示不提供鏈接。完整字段與約束以同一 SDK 開發包的社區 Schema 爲準。

社區把文檔、圖片和插件包放在同一個版本 Release，目錄保存受簽名保護的引用。客戶端按需讀取並複驗大小與摘要，HTML 以淨化後的隔離靜態文檔顯示。文檔簽名邊界屬於審核引用和目錄，不能把 JAR 簽名當作文檔證明。社區須先驗證新 SDK 的公開發行字節再升級固定工具；宿主也須包含對應的展示支持。只增加市場資料不要求舊插件提高 `plugin.requires`，SDK 1.1 宿主仍兼容 `requires=1.0`。

## 簽名和發佈

### 發布者金鑰與社群操作簽名

發布者簽名 CLI 位於 `pixivdownload-plugin-signature`，執行僅需 JDK 17。在原始碼儲存庫根目錄建置工具，再透過該模組產生的 JAR 呼叫：

```text
./mvnw -pl pixivdownload-plugin-signature package
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool <command>
```

- `keygen --directory <new-directory>`：產生 `private-key.pem`（PKCS#8）與配套 `public-key.pem`（SPKI），目錄及私鑰僅允許目前使用者存取。請選擇原始碼儲存庫與暫存目錄以外的位置，備份這兩個檔案；命令不覆寫既有目錄。
- `public-key --public-key <public-key.pem> --key-id <id> --out <public.json>`：匯出標準化 SPKI Base64 與 SHA-256 公鑰指紋。此命令讀取配套公鑰檔案，不從私鑰推導公鑰。
- `community-operation --operation <PUBLISHER_KEY_ROTATION|VERSION_STATUS_REQUEST|OWNERSHIP_TRANSFER> --canonical-body <canonical-body> --request-id <SHA-256> --key-id <id> --private-key <private-key.pem> --out <sig.json>`：簽署固定版本 SDK 產生的 JCS 正文字節。正文摘要必須與 requestId 一致；輸出僅含 detached 簽名，不含私鑰。

公鑰匯出與社群操作簽名均拒絕覆寫既有輸出。外掛套件使用 `artifact` 命令簽名，`--help` 列出完整參數。私鑰不得提交至 Git 或放入投稿附件。

### 生成 artifact 簽名

以下簽名命令中的文件名是佔位路徑，請替換爲實際路徑並保留引號。私鑰必須是 Ed25519 PKCS#8 PEM，保存在倉庫和構建輸出之外；倉庫配置使用 Base64 編碼的 X.509 SubjectPublicKeyInfo 公鑰。構建簽名工具後，CLI 通過 classpath 主類調用：

```powershell
java -cp "signature-tool.jar" `
  top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool `
  artifact `
  --artifact "plugin.jar" `
  --plugin-id example-download `
  --version 0.1.0 `
  --key-id example-2026 `
  --private-key "ed25519-pkcs8.pem" `
  --out "plugin.jar.sig"
```

輸出是結構化 JSON：`formatVersion`、`algorithm=Ed25519`、`keyId` 和 `value`。同時記錄 artifact 的精確字節數和 SHA-256：

```powershell
$artifact = Get-Item -LiteralPath "plugin.jar"
$artifact.Length
(Get-FileHash -Algorithm SHA256 -LiteralPath $artifact.FullName).Hash.ToLowerInvariant()
```

### catalog manifest

倉庫清單 schema version 1 的頂層字段爲 `schemaVersion`、`generatedTime` 和 `entries`。以下是結構示例，域名、大小、SHA-256、簽名和時間必須替換爲同一真實產物的值後才可發佈：

```json
{
  "schemaVersion": "1",
  "generatedTime": "2026-08-10T00:00:00Z",
  "entries": [
    {
      "pluginId": "example-download",
      "displayNamespace": "example-download",
      "displayNameKey": "plugin.name",
      "descriptionKey": "plugin.summary",
      "market": {
        "displayName": {"zh": "示例下載", "en": "Example download"},
        "summary": {"zh": "示例下載類型", "en": "Example download type"},
        "description": {"zh": "插件詳細說明", "en": "Plugin description"},
        "author": "Example Developer",
        "sourceType": "community",
        "category": "download",
        "tags": ["download"],
        "homepageUrl": "https://example.com/plugin",
        "license": "MIT",
        "latestVersion": "0.1.0",
        "updatedTime": "2026-08-10T00:00:00Z",
        "iconToken": "download",
        "colorToken": "green",
        "recommended": false,
        "officialRequired": false,
        "defaultInstalled": false
      },
      "packages": [
        {
          "version": "0.1.0",
          "packageUrl": "https://plugins.example.com/example-download-0.1.0.jar",
          "expectedSizeBytes": 12345,
          "sha256": "LOWERCASE_SHA256_HEX",
          "signature": {
            "formatVersion": 1,
            "algorithm": "Ed25519",
            "keyId": "example-2026",
            "value": "BASE64_SIGNATURE"
          },
          "signatureUrl": "https://plugins.example.com/example-download-0.1.0.jar.sig",
          "requiredSdk": "1.3",
          "dependencies": [],
          "releasedTime": "2026-08-10T00:00:00Z",
          "changeNotes": ["Initial release"],
          "channel": "stable",
          "deprecated": false
        }
      ]
    }
  ]
}
```

`market` 只用於展示、搜索和排序，不參與安裝安全決策。`packageUrl` 和 manifest URL 必須是 HTTPS；安裝仍以 artifact 的大小、SHA-256、結構化簽名和內部 descriptor 爲權威。

對 manifest 原始字節生成 detached 簽名：

```powershell
java -cp "signature-tool.jar" `
  top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool `
  manifest `
  --manifest "manifest.json" `
  --repository-id example `
  --key-id example-2026 `
  --private-key "ed25519-pkcs8.pem" `
  --out "manifest.json.sig"
```

發佈 `manifest.json`、同地址追加 `.sig` 的 `manifest.json.sig`、artifact 和可選的 artifact detached signature。不要在簽名後格式化或重寫 manifest。

驗籤命令：

```text
verify-manifest --manifest "manifest.json" --signature "manifest.json.sig" --repository-id <id> [--policy official|custom]
verify-artifact --artifact <jar> --signature <sig.json> --plugin-id <id> --version <version> --expected-size <bytes> --sha256 <hex> [--policy official|custom]
```

驗證自定義 root 時再傳 `--trusted-key-id`、`--trusted-public-key`；可選字段有 `--trusted-algorithm`、`--trusted-state`、`--trusted-publisher`、`--trusted-label` 和 `--trusted-official`。

### 讓用戶添加自定義倉庫

發佈一個最大 64 KiB、嚴格 UTF-8 JSON 的 `repository.json`，使用者在 GUI 的「設定 → 外掛程式市集設定 → 新增 → 使用儲存庫描述檔」中填寫它的公網 HTTPS 位址：

```json
{
  "schemaVersion": 1,
  "repositoryId": "example.plugins",
  "displayName": "Example Plugins",
  "publisher": {"id": "example", "displayName": "Example Publisher", "homepageUrl": "https://example.com/plugins"},
  "catalog": {"protocol": "manifest-v1", "endpoint": "https://plugins.example.com/manifest.json"},
  "networkProfile": "DIRECT_STRICT",
  "revocationsUrl": "https://plugins.example.com/revocations.json",
  "updateProofUrl": "https://plugins.example.com/repository-update.json",
  "trustedKeys": [{
    "keyId": "example-2026",
    "algorithm": "Ed25519",
    "publicKeySpkiBase64": "BASE64_X509_SUBJECT_PUBLIC_KEY_INFO",
    "state": "ACTIVE",
    "publisher": "Example Publisher",
    "trustLabel": "Example release key"
  }]
}
```

首次匯入不發佈也不驗證 `repository.json.sig`：用描述符裡的新公開金鑰簽署描述符本身只是自我證明。軟體會顯示描述符摘要、發佈者文字、全部連線主機和每把公開金鑰完整的 `SHA-256(SPKI DER)` 指紋；確認時會重新下載並要求摘要逐位元組一致，保存的設定在重新啟動後生效。自訂儲存庫不繼承官方 trust root；`official`、`configured`、`community` 是保留 ID。

`trustedKeys` 必須包含 1 至 4 把 Ed25519 SPKI 格式公開金鑰，`keyId` 不可重複。`networkProfile` 只允許 `DIRECT_STRICT` 和 `GITHUB_RELEASES`。前者只訪問描述符聲明的公網 HTTPS 主機且不跟隨重定向；後者只允許 GitHub 固定主機邊界內的一次重定向。描述符不能關閉 SSRF 檢查、允許私網或提供任意代理/重定向白名單。

小倉庫繼續使用上文的 `manifest-v1` 和 `manifest.json.sig`。大型倉庫可把 `catalog.protocol` 改爲 `paged-v2`，並把 endpoint 指向 API 基地址，實現：

```text
GET {endpoint}/plugins?cursor=&limit=&query=&category=&publisher=&channel=
GET {endpoint}/plugins/{pluginId}?cursor=&limit=
GET {endpoint}/plugins/{pluginId}/versions/{version}
```

列表默認每頁 24、最多 100 項，響應必須包含 `generation`，cursor 不透明；列表和詳情響應分別不得超過 512 KiB 與 256 KiB。安裝請求仍只提交 repository ID、plugin ID 和 version，宿主會重新讀取版本端點，並把遠端元數據與凍結後的包內 descriptor、大小、SHA-256 和發佈者簽名逐項覈對。

已信任倉庫輪換描述符或密鑰時，可用舊受信 key 簽署 `repository-update-v1`；安全撤銷使用更高 sequence 的 `revocations-v1`。兩個文件的簽名都放在原 URL 後追加 `.sig`，並用現有 CLI 的獨立簽名域生成：

```text
repository-update --document repository-update.json --repository-id example.plugins --sequence 2 --key-id example-2026 --private-key <pem> --out repository-update.json.sig
plugin-revocations --document revocations.json --repository-id example.plugins --sequence 1 --key-id example-2026 --private-key <pem> --out revocations.json.sig
```

撤銷範圍支持 `PACKAGE_SHA256`、`PLUGIN_VERSION`、`SIGNING_KEY`、`PUBLISHER`；`YANKED` 只阻止新安裝/更新，`REVOKED` 還會在下次啓動加載前阻斷已安裝的匹配字節。`host-process-full-trust` 與宿主共用 JVM，`declarative-process` 使用獨立 worker；兩者都使用當前操作系統賬戶，不構成 OS 沙箱。簽名和撤銷不能證明代碼無惡意。

## 向項目貢獻

編寫私有或社區插件通常不需要修改宿主。以下內容適合向主倉庫貢獻：

- 修復 Plugin API、Core API、插件運行時或模板中的真實缺陷；
- 爲多個插件都需要的中性語義新增穩定端口；
- 完善模板、SDK 文檔、邊界測試和失敗診斷；
- 貢獻或修復官方外置插件；
- 改進簽名、安裝事務、生命週期和能力缺席降級；
- 修正文檔與當前實現的偏移。

如果現有 SDK 缺少能力，不要先依賴 app 私有類。先提出一個不認識具體站點或插件 id 的中性契約，並同時說明真實消費者、所有權、生命週期、錯誤/缺席語義和測試。公共契約變更需要同步提升 SDK 身份，並更新 BOM、Javadoc、模板、Douyin 示例和本文；已發佈候選不可覆蓋。

基本流程：

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/plugin-api/your-capability upstream/master
```

提交前：

1. 運行直接相關的模塊測試，再運行受影響的模板和邊界測試；
2. 保持插件 id、descriptor、feature、route、靜態資源、i18n 和測試一致；
3. 不提交 `target/`、`build/`、運行配置、憑據、私鑰或下載數據；
4. PR 說明動機、穩定邊界、失敗/缺席行爲以及實際執行的驗證命令；
5. 代碼、模板和核心開發文檔向 `master` 提交 PR；在線站點內容位於獨立 `gh-pages` 分支，通常從其專用 worktree 直接提交併推送。

### 官方倉庫的版本與發佈檢查

插件交付內容變化時，應將 `plugin.version` 提升到高於已發佈版本；升版後可以先合入源碼、稍後發佈，同一未發佈版本可繼續修改。只有實際使用 SDK 新增內容時才提高 `plugin.requires`，並同步提升插件版本；SDK 更新本身不要求所有插件聯動升版。官方 PR 門禁檢查交付內容與已發佈版本，所有質量門禁構建檢查產物對所聲明最低 SDK 的類型、成員引用和回調覆蓋。發佈重跑不重複判定源碼是否需要升版。反射、內聯常量及行爲契約變化仍需代碼評審。

插件發佈不要求當前源碼 SDK 已公開。新的 SDK 正式版由主程序正式 Release 觸發；手動發佈需要 `-f`，但仍不能覆蓋不可變 SDK 發行物。Beta 主程序可以複用已經公開的 SDK 正式版，不能發佈新的 SDK 正式版。插件手動 `-f` 則保留同版本產物覆蓋修復功能，主程序 Release 不自動傳入該參數。

版本檢查覆蓋插件源碼、資源、描述符、私有依賴、發行文檔，以及共享 Maven 構建配置和引用的構建文件；測試專用配置不要求升版。門禁檢查版本是否遞增，major、minor、patch 的選擇仍按公共契約變化評審。官方市場的舊客戶端支持集合固定爲截至 2026-10-07 已公開的正式 SDK `1.0.0`、`1.1.0`，在 `scripts/market-curation.json` 的 `_legacySdkVersions` 中維護，不隨新 SDK 自動擴大。渠道與歷史保留規則見[插件管理](/zh-hant/plugin-management?id=更新提示與歷史版本)。

## 發佈前檢查表

- [ ] 聲明單個 `pixivdownload-sdk` provided 依賴或正確的舊 BOM 組合，插件不包含宿主提供類
- [ ] `plugin.properties` 位於 JAR 根，id/version/requires/class 與代碼一致
- [ ] feature 只返回一個，子上下文只顯式裝配自己的 Bean
- [ ] 每個 controller、頁面和靜態目錄都有正確 `AccessPolicy` 路由聲明
- [ ] i18n、錯誤碼和狀態不會泄露憑據或異常細節
- [ ] 下載完成只在文件和成功事實耐久落地後報告
- [ ] owner 來自 `RequestOwnerIdentityResolver`，`workKey` 保持不透明字符串
- [ ] 異步隊列、任務、客戶端、executor 和 scheduler 可真實 quiesce/drain/close
- [ ] 插件自持畫廊頁面/API/static/i18n，只向 `gallery.type-switch` 註冊自己的切換入口
- [ ] 配置、憑據、state/data、作品目錄及 `PluginDatabase` / `PluginMainDatabase` 符合 owner 邊界；舊 `PluginDataSource` 僅作 SQLite 兼容入口
- [ ] `mvn clean verify`、前端行爲測試和 JAR 結構檢查通過
- [ ] 發佈 artifact 的大小、SHA-256、簽名和 manifest 完全對應同一份字節
- [ ] 私鑰不在源碼、構建輸出、日誌、插件包或倉庫服務器公開目錄中
