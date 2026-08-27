# 第三方插件 SDK

本指南面向要編寫、調試和發佈 PixivDownloader 外置插件的開發者。最穩妥的起點是複製官方模板，而不是從應用殼或官方插件中摘取實現類。

相關源碼：

- [第三方插件模板](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates)
- [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info)
- [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api)
- [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api)
- [Douyin 官方示例插件](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-douyin)
- [插件簽名工具](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature)

> Douyin 是完整官方實現的 SDK 示例，展示下載、配置、代理、隊列、計劃任務、私有持久化和插件自有畫廊如何組合。它只依賴公開 SDK 契約，可用於覈對完整實現。新項目仍應先複製 `plugin-templates`，避免帶入與目標站點綁定的業務代碼。

## 先理解信任邊界

外置插件與宿主運行在同一個 JVM 中，當前不是進程或 OS 級安全沙箱。插件代碼擁有與同進程代碼相同的風險級別：它可能讀取進程可訪問的文件、發起網絡請求或消耗資源。

Ed25519 簽名只證明 artifact 來自某個受信密鑰且字節未被篡改，不能證明已簽名代碼沒有惡意行爲。安裝前必須信任發佈者、源碼和倉庫運營者。Cookie、Token、代理、作品目錄和插件私有數據的合法使用也由插件作者負責。

宿主仍會執行結構、大小、路徑、版本、依賴、SHA-256、簽名和 provenance 校驗，並用同一份凍結字節完成校驗與加載。這些措施保護供應鏈完整性，不構成代碼沙箱。詳細安裝行爲見[插件管理](/zh-hant/plugin-management)。

## SDK 邊界

SDK 由 `pixivdownload-sdk-info`、`pixivdownload-plugin-api` 和 `pixivdownload-core-api` 組成，`pixivdownload-sdk-bom` 統一管理三個構件的版本。`sdk-info` 是 SDK 版本、revision 和兼容規則的唯一事實源；SDK 版本與應用發行版本獨立。`plugin-api` 提供插件入口、contribution、宿主控制面和 owner-scoped 存儲能力；`core-api` 提供穩定的業務語義端口、值模型和中性算法。依賴方向必須保持爲：

```text
第三方插件
  ├─ pixivdownload-sdk-info    必選：SDK 版本與兼容信息
  ├─ pixivdownload-plugin-api  必選：插件入口、contribution、路徑與私有數據源
  └─ pixivdownload-core-api    按需：下載設置、代理設置等穩定語義端口

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
| `schema` | 宿主與官方插件協作的共享 schema 聲明；不是第三方私有持久化入口 |
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

### 聲明式桌面 UI 邊界

桌面頁面不是由 Swing 和 Compose 各維護一份。應用殼生成完整、工具包無關的 `DesktopUiDocument`，並擁有頁面結構、狀態、配置保存、後端交互和類型化事件處理；`gui-swing` 與 `gui-compose` 只是讀取同一文檔的通用 `DesktopUiProvider`，負責各自的渲染、窗口、托盤、主題和平台適配。provider 不得按頁面 id、插件 id、字段 key 或 i18n key 編寫專用佈局。

功能插件只通過 `GuiConfigContribution` 及其 field、group、section、layout、action、preset 純數據記錄，聲明自己配置 section 的完整領域結構。宿主按可信 owner 合併、校驗和保存；插件不得返回 Swing / Compose 組件，也不得擁有頂層窗口或複製宿主頁面。新增穩定節點類型時應擴展中性 `DesktopUiNode` 契約，並讓所有 provider 通用實現，而不是只在某個 provider 中補特例。

官方 `gui-swing` 默認安裝並作爲默認 provider；`gui-compose` 按需安裝。兩者均爲 `process-restart` 插件，切換、安裝、升級、禁用或卸載後必須完整重啓。Compose 插件的 Kotlin / Compose 編譯和 JAR-with-lib 產物由 Gradle Wrapper 實際生成，Maven reactor 只負責調用 Gradle 並接入官方構建、簽名和分發流程。

## 從模板開始

### 選擇模板

| 模板 | 適用情況 | 已包含內容 |
| --- | --- | --- |
| `minimal-feature-plugin` | 頁面、API、導航、i18n 或配置 | PF4J 入口、provider、feature、顯式子上下文、controller、route/static/i18n、thin JAR 測試 |
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

模板先導入 SDK BOM，再聲明宿主提供的 SDK 構件：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>top.sywyar.lovepopup</groupId>
            <artifactId>pixivdownload-sdk-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>top.sywyar.lovepopup</groupId>
    <artifactId>pixivdownload-sdk-info</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>top.sywyar.lovepopup</groupId>
    <artifactId>pixivdownload-plugin-api</artifactId>
    <scope>provided</scope>
</dependency>
```

主倉庫發佈鏈已經能夠從受信的精確源碼 SHA 構建 BOM、三個構件、source JAR、模塊 Javadoc 和覆蓋全部 SDK 類型的聚合 Javadoc 站點。約定的獨立 `PixivDownloader-Plugin-SDK` 倉庫及接收 workflow 尚未建立，因此倉庫變量 `SDK_PUBLISH_ENABLED` 當前保持關閉，也沒有可下載的獨立 SDK release。目標就緒後，接收端只按 dispatch payload 中的精確源碼 SHA 構建和發佈；除四個公開 SDK 座標外，還需發佈它們當前繼承的 `pixivdownload-parent:1.0.0` 支撐 POM，供 Maven 解析。該父 POM 不屬於插件運行時 SDK，也不應加入插件依賴。現階段從本倉源碼開發時先在根目錄安裝 SDK：

```powershell
./mvnw.cmd -pl pixivdownload-sdk-info,pixivdownload-plugin-api,pixivdownload-core-api,pixivdownload-sdk-bom -am install -DskipTests
```

只有確實需要穩定宿主語義端口時才增加 Core API，並保持 `provided`：

```xml
<dependency>
    <groupId>top.sywyar.lovepopup</groupId>
    <artifactId>pixivdownload-core-api</artifactId>
    <scope>provided</scope>
</dependency>
```

`plugin.requires` 只聲明 SDK `major.minor`。同 major 且宿主 minor 不低於插件要求時兼容，patch 和 revision 不參與運行時兼容判定。公開契約變更必須提升 SDK 語義版本；僅模板、文檔或發佈包修正可在語義版本不變時提升 revision。質量門禁會拒絕未同步提升發佈標識的 SDK 表面變更；只有 SDK 元數據改變才觸發獨立倉庫發佈，應用發行不會自動製造新 SDK。

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
| `plugin.requires=1.0` | 兼容的 SDK major.minor |
| `plugin.provider=Example Developer` | 發佈者名稱 |

還要同步修改路由、靜態路徑、前端常量、私有數據表名、測試和兩種語言的 i18n 文案。只改 `plugin.properties` 會造成 descriptor、feature 與運行時 publication 身份不一致，宿主會拒絕接入。

## 插件包和入口

### `plugin.properties`

文件必須位於 JAR 根部。基礎示例：

```properties
plugin.id=example-download
plugin.version=0.1.0
plugin.requires=1.0
plugin.class=com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin
plugin.provider=Example Developer
plugin.description=Example download type.
pixiv.display-namespace=example-download
pixiv.display-name-key=plugin.name
pixiv.description-key=plugin.summary
pixiv.icon-key=download
pixiv.color-token=green
pixiv.lifecycle-policy=hot-reload
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
| `pixiv.lifecycle-policy` | `hot-reload`、`backend-restart` 或 `process-restart`；區分大小寫，缺省爲 `hot-reload` |

SDK 當前以 `1.0.0` 爲初始契約基線。兼容判斷使用 `requiredMajor == hostMajor && requiredMinor <= hostMinor`，PATCH 不參與准入判斷。首次公開發布後，破壞性契約變更升 MAJOR，向後兼容新增升 MINOR，兼容修復升 PATCH。

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

`state/{pluginId}` 用於可重建運行狀態，`data/{pluginId}` 用於插件管理的數據和緩存。作品文件不能寫進這兩個目錄。需要持久化時直接注入 `PluginDataSource`；它是宿主管理生命週期的 `javax.sql.DataSource`，只連接 `data/{pluginId}/plugin.db`。插件自行管理該私有 SQLite 的 schema 和遷移，不得關閉、unwrap 或借它訪問宿主主庫。

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

第三方獨立項目的基線流程：

1. `mvn clean verify`；
2. 在 JAR 中確認根 `plugin.properties`、類和資源；
3. 正式運行時通過已配置的自定義倉庫安裝；本地上傳只接受內置官方信任根簽發的 JAR 與對應 `.sig`；
4. 顯式插件開發模式可省略本地上傳簽名，產生的來源會保持爲未驗證開發 artifact；
5. 對 `hot-reload` 插件執行事務替換和即時激活；
6. 刷新頁面，驗證 controller、route、static、i18n 和下載類型都屬於當前 generation；
7. 修改後重新構建、上傳並使用 `reload`，不要在運行時手工覆蓋 JAR。

```powershell
jar tf target/example-download-plugin-0.1.0.jar
```

也可以在應用停止時把 JAR 放入工作目錄 `plugins/`，再啓動應用。`plugins/runtime/` 是宿主私有凍結工作區，不是安裝目錄或調試輸出目錄。

倉庫中的官方插件使用專用開發模式，它先編譯官方插件並從各模塊當前 `target/classes` 加載：

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

該入口適合貢獻官方插件或覈對 Douyin 示例，不是倉庫外第三方項目的自動發現器。IDE 可使用倉庫提交的 IntelliJ IDEA、VS Code 或 Eclipse `Developer Mode` 共享配置。

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

## 簽名和發佈

### 生成 artifact 簽名

私鑰必須是 Ed25519 PKCS#8 PEM，保存在倉庫和構建輸出之外；倉庫配置使用 Base64 編碼的 X.509 SubjectPublicKeyInfo 公鑰。構建簽名工具後，CLI 通過 classpath 主類調用：

```powershell
java -cp <signature-tool.jar> `
  top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool `
  artifact `
  --artifact <plugin.jar> `
  --plugin-id example-download `
  --version 0.1.0 `
  --key-id example-2026 `
  --private-key <ed25519-pkcs8.pem> `
  --out <plugin.jar.sig>
```

輸出是結構化 JSON：`formatVersion`、`algorithm=Ed25519`、`keyId` 和 `value`。同時記錄 artifact 的精確字節數和 SHA-256：

```powershell
$artifact = Get-Item -LiteralPath <plugin.jar>
$artifact.Length
(Get-FileHash -Algorithm SHA256 -LiteralPath $artifact.FullName).Hash.ToLowerInvariant()
```

### catalog manifest

倉庫清單 schema version 1 的頂層字段爲 `schemaVersion`、`generatedTime` 和 `entries`。最小可發佈條目：

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
          "requiredSdk": "1.0",
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
java -cp <signature-tool.jar> `
  top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool `
  manifest `
  --manifest <manifest.json> `
  --repository-id example `
  --key-id example-2026 `
  --private-key <ed25519-pkcs8.pem> `
  --out <manifest.json.sig>
```

發佈 `manifest.json`、同地址追加 `.sig` 的 `manifest.json.sig`、artifact 和可選的 artifact detached signature。不要在簽名後格式化或重寫 manifest。

驗籤命令：

```text
verify-manifest --manifest <manifest.json> --signature <manifest.json.sig> --repository-id <id> [--policy official|custom]
verify-artifact --artifact <jar> --signature <sig.json> --plugin-id <id> --version <version> --expected-size <bytes> --sha256 <hex> [--policy official|custom]
```

驗證自定義 root 時再傳 `--trusted-key-id`、`--trusted-public-key`；可選字段有 `--trusted-algorithm`、`--trusted-state`、`--trusted-publisher`、`--trusted-label` 和 `--trusted-official`。

### 讓用戶添加自定義倉庫

發佈一個最大 64 KiB、嚴格 UTF-8 JSON 的 `repository.json`，使用者只需在外掛程式市集填寫它的公網 HTTPS 位址：

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

`networkProfile` 只接受 `DIRECT_STRICT` 與 `GITHUB_RELEASES`。小型儲存庫可繼續使用上文已簽章的 `manifest-v1`；大型儲存庫可改用 `paged-v2` 並實作 `{endpoint}/plugins`、`{endpoint}/plugins/{pluginId}` 和 `{endpoint}/plugins/{pluginId}/versions/{version}`。每頁預設 24、最多 100 項，回應帶有 `generation`，安裝前宿主仍會重新解析版本並核對凍結套件的大小、SHA-256、簽章和套件內 descriptor。

已信任儲存庫可讓舊受信 key 簽署單調序號的 `repository-update-v1`；安全撤銷使用 `revocations-v1`。兩者的簽章檔都在 JSON URL 後追加 `.sig`，並使用現有 CLI 的 `repository-update` 或 `plugin-revocations` 命令生成。撤銷範圍支援 `PACKAGE_SHA256`、`PLUGIN_VERSION`、`SIGNING_KEY`、`PUBLISHER`；`YANKED` 只阻止新安裝/更新，`REVOKED` 還會在載入前阻斷已安裝的相符位元組。外掛程式仍與主程式在同一 JVM 執行，沒有程式碼沙箱。

## 向項目貢獻

編寫私有或社區插件通常不需要修改宿主。以下內容適合向主倉庫貢獻：

- 修復 Plugin API、Core API、插件運行時或模板中的真實缺陷；
- 爲多個插件都需要的中性語義新增穩定端口；
- 完善模板、SDK 文檔、邊界測試和失敗診斷；
- 貢獻或修復官方外置插件；
- 改進簽名、安裝事務、生命週期和能力缺席降級；
- 修正文檔與當前實現的偏移。

如果現有 SDK 缺少能力，不要先依賴 app 私有類。先提出一個不認識具體站點或插件 id 的中性契約，並同時說明真實消費者、所有權、生命週期、錯誤/缺席語義和測試。公共契約變更需要同步更新 SDK 版本與 revision、BOM、Javadoc、模板、Douyin 示例和本文。

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

## 發佈前檢查表

- [ ] 導入 SDK BOM，只依賴 SDK Info、Plugin API 和確有需要的 Core API 穩定端口，全部共享依賴爲 `provided`
- [ ] `plugin.properties` 位於 JAR 根，id/version/requires/class 與代碼一致
- [ ] feature 只返回一個，子上下文只顯式裝配自己的 Bean
- [ ] 每個 controller、頁面和靜態目錄都有正確 `AccessPolicy` 路由聲明
- [ ] i18n、錯誤碼和狀態不會泄露憑據或異常細節
- [ ] 下載完成只在文件和成功事實耐久落地後報告
- [ ] owner 來自 `RequestOwnerIdentityResolver`，`workKey` 保持不透明字符串
- [ ] 異步隊列、任務、客戶端、executor 和 scheduler 可真實 quiesce/drain/close
- [ ] 插件自持畫廊頁面/API/static/i18n，只向 `gallery.type-switch` 註冊自己的切換入口
- [ ] 配置、憑據、state/data、`PluginDataSource` 和作品目錄符合 owner 與目錄邊界
- [ ] `mvn clean verify`、前端行爲測試和 JAR 結構檢查通過
- [ ] 發佈 artifact 的大小、SHA-256、簽名和 manifest 完全對應同一份字節
- [ ] 私鑰不在源碼、構建輸出、日誌、插件包或倉庫服務器公開目錄中
