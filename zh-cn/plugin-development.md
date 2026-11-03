# 第三方插件 SDK

第三方插件从 SDK 开发包的独立工程模板起步，只需 JDK 17、Node.js 及包内自带的构建工具与运行清单，无需克隆主仓库。

| 开发任务 | 对应章节 |
| --- | --- |
| 创建并本地调试第一个插件 | [从模板开始](#从模板开始) · [本地开发](#本地开发) |
| 新增下载作品类型或独立画廊 | [新增下载类型的完整流程](#新增下载类型的完整流程) · [新增插件自有画廊](#新增插件自有画廊) |
| 管理配置、加密凭据、文件与私有数据库 | [配置、凭据和文件](#配置、凭据和文件) · [不依赖 GUI 的凭据与通用操作验证](#不依赖-gui-的凭据与通用操作验证) |
| 声明跨插件服务契约或页面编辑器组件 | [独立服务契约与组合](#独立服务契约与组合) · [普通槽位与页面组件](#普通槽位与页面组件) |
| 升级 SDK、投稿社区市场或自建仓库分发 | [维护外部工程与 SDK 升级](#维护外部工程与-sdk-升级) · [投稿市场资料](#投稿市场资料) · [签名和发布](#签名和发布) |

下文中的 `SDK_VERSION`、包名、插件 ID、路径、SHA-256 与签名均为占位符，使用时请替换为实际值。代码片段需放入对应模板上下文并补齐 import 与业务实现，接口可用性以所选 [SDK Release](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) 及[版本化 Javadoc](https://sywyar.github.io/PixivDownloader-Plugin-SDK/) 为准。

参考源码：[插件模板](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates) · [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info) · [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api) · [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api) · [签名工具](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature) · [Douyin 完整示例插件](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)（展示下载、配置、代理、队列、计划任务、私库与独立画廊，但不属于官方分发集合；新项目请从 `plugin-templates` 开始）。

## 插件许可证与发布渠道

PixivDownloader 及官方插件采用 [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)，并为随附[插件链接例外](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)的版本提供额外授权：仅通过公开 SDK / API 开发且满足例外条件的独立第三方插件，可选择 MIT 等许可证（复制修改项目模板时须保留上游版权声明与例外文本；宿主、SDK 与官方插件仍适用 AGPL-3.0）。

[官方社区插件仓库](https://github.com/Sywyar/PixivDownloader-community-plugins)仅收录开源插件，要求公开完整 GitHub 源码、开源许可证、可复现构建并通过人工审核。

## 信任与 SDK 架构边界

- **执行模式与信任边界**：`declarative-process` 运行在独立 worker JVM（隔离资源与协议），`host-process-full-trust` 运行在宿主 JVM；两者均共享当前 OS 账户，不构成操作系统级安全沙箱。Ed25519 签名仅证明发布者身份与字节完整性，安装前仍需信任作者与源码（详见[插件管理](/zh-cn/plugin-management)）。
- **编译依赖方向**：第三方插件声明单个 `pixivdownload-sdk`（`provided`）依赖，由其传递 `pixivdownload-sdk-info`、`pixivdownload-plugin-api`、`pixivdownload-core-api` 及宿主提供的 PF4J、Spring、Servlet、Jackson 编译依赖（三个 API 模块与 BOM 也可单独使用）。**严禁依赖 `pixivdownload-app`、宿主内部实现类、官方插件私有类、宿主 `DataSource` 或私有前端全局变量**。
- **能力发布与缺席降级**：插件通过 `PixivFeaturePlugin` 贡献能力，通过 `PixivPluginProvider.configurationClasses()` 声明独立 Spring 子上下文配置类。插件停用、卸载或换代时，宿主按 publication 撤回路由、静态资源、i18n、导航、下载类型、队列与计划能力；调用方必须处理能力缺席，不能产生空指针或半完成任务。
- **桌面 UI 边界**：宿主通过工具包无关的纯数据契约（`GuiConfigContribution` 等）聚合配置、引导与状态，由 `gui-compose`（默认）和 `gui-swing`（后备）各自渲染界面；功能插件不返回 Swing / Compose 组件。

### `PixivFeaturePlugin` 主要贡献入口

| 方法 | 贡献内容 |
| --- | --- |
| `id`、`displayName`、`description`、`displayNamespace`、`iconKey`、`colorToken`、`kind` | 插件身份、i18n 展示键与受控图标/颜色/分类 token |
| `start`、`stop` | 插件自有本地资源生命周期（`stop` 必须幂等） |
| `privateSchema`、`privateStorageMigrations` | 当前 owner 权威私有表结构与按序数据迁移声明 |
| `schema`、`sharedMainStorage` | 参与宿主主库协作的受管表与公开存储域申请（不可访问其他 owner 表） |
| `routes`、`staticResources`、`i18n` | Web 路由访问策略、classpath 静态资源映射、前端 i18n namespace |
| `navigation`、`startupRoutes`、`landings`、`pageSections`、`uiSlots`、`drilldowns` | 导航项、启动落点、页面区块、Web UI 槽位与下钻链接 |
| `guiConfigContributions`、`guiOnboardingSteps`、`guiThemes` | 桌面配置字段/分组/动作/预设、引导步骤与启动期主题 |
| `userscripts`、`scheduledSourceDescriptors`、`downloadTypes` | 油猴脚本资源、计划任务来源描述符、下载工作台作品类型描述符 |

## 从模板开始

| 模板 | 适用场景 | 已包含内容 |
| --- | --- | --- |
| `minimal-feature-plugin` | 声明式页面、导航与 i18n | `declarative-process` worker、PF4J 入口、route/static/i18n 与 thin JAR 测试（无 Spring 子上下文） |
| `download-type-plugin` | 新增可下载作品类型与画廊 | `host-process-full-trust`、下载类型描述符、5 种取得模式、队列、计划来源、Vue 槽位、独立画廊与前后端测试 |
| `typed-service-plugin` | 跨插件类型化服务与远程调用 | 独立 `api` / `contract` / `provider` / `consumer` 多模块工程与 Schema/客户端生成 |

复制到仓库外后，模板是不继承主仓库 parent 的独立 Maven 工程，直接运行 `mvn clean verify` 即可验证（在主仓库内可用 `mvn -f plugin-templates/pom.xml clean verify` 验证）。

Maven 依赖声明（Java 控制器若使用未显式命名的 `@PathVariable` / `@RequestParam`，须设置 `<maven.compiler.parameters>true</maven.compiler.parameters>` 或 `-parameters`）：

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>SDK_VERSION</version>
    <scope>provided</scope>
</dependency>
```

Gradle 使用 `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:SDK_VERSION")`，sbt 使用 `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "SDK_VERSION" % Provided`。复制模板后，请统一替换 Maven `artifactId`、全局唯一插件 ID、Java 包名与类名前缀、版本号、路由前缀、静态资源目录、私有表名和 i18n namespace。

## 插件包描述符与子上下文装配

JAR 根目录必须包含 `plugin.properties`：

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

- `plugin.id` 必须与 `PixivFeaturePlugin.id()` 完全一致；`plugin.requires` 声明所需 SDK 版本（稳定版按 `requiredMajor == hostMajor && requiredMinor <= hostMinor` 匹配，预发布版采用 `alpha.N` / `beta.N` / `rc.N` 并要求精确匹配）。
- `pixiv.execution-mode` 必填（`declarative-process` 或 `host-process-full-trust`）；`pixiv.lifecycle-policy` 可选 `hot-reload`（默认）、`backend-restart` 或 `process-restart`；`pixiv.risk-signals` 可声明风险信号 token。
- 外置入口实现 `PixivPluginProvider`，返回唯一 `PixivFeaturePlugin` 与显式 `@Bean` 配置类（禁止依赖宿主包扫描）：

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

### Web 路由、静态资源、i18n、通知模板与问卷槽位

每个 Controller 路径、静态资源和 HTML 页面均须由同一插件在 `routes()` 中声明 `AccessPolicy`（支持 `publicRoute`、`visitor`、`visitorAndInvitedGuest`、`invitedGuest`、`admin`、`local`、`gui`；未声明的 `path + method` 返回 404）：

```java
@Override
public List<WebRouteContribution> routes() {
    return List.of(
            WebRouteContribution.admin("/example-download-gallery.html"),
            WebRouteContribution.admin("/example-download-gallery/**"),
            WebRouteContribution.admin("/api/example-download/gallery"));
}
```

- **业务通知模板**：场景 owner 在子上下文注册 `NotificationTemplateContributor` Bean，返回纯值 `NotificationTemplateContribution(scenarioId, medium, locale, titleTemplate, bodyTemplate)`（单标题上限 16 KiB、正文 1 MiB，单插件最多 256 项 / 8 MiB；填入不可信值前必须做 HTML 转义）。
- **PostHog 调查与站内信**：Web 插件可声明 `plugin.dependencies=posthog?@1.0`，加载 `/pixiv-posthog/pixiv-posthog.js` 并调用 `window.PixivPostHog.createSurveyClient` 与 `captureSurveyWithAck`；需常驻管理员站内信时，使用 `new SurveyInboxMessage(...).toUiSlotContribution()` 贡献 `notification.inbox` 槽位。

## 新增下载类型的完整流程

下载类型由同一插件协同贡献：

1. **声明 `DownloadTypeDescriptor`**：在 `PixivFeaturePlugin.downloadTypes()` 中返回 descriptor（`contractVersion = DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION`、全局唯一 `type`、i18n 键、图标与颜色 token、同源行为模块 `moduleUrl`、支持的取得模式 `single-import` / `user` / `series` / `search` / `quick`、`cancelSupported`、筛选与设置白名单）。
2. **实现前端行为模块**：在 `moduleUrl` 中调用 `PixivBatch.queueTypes.registerModule(initializer)`，实现 `process(item, context)`（必填，通过 `context.updateItem(patch)` 更新状态，使用 `DownloadTasks` 时通过 `context.bindTask(taskId)` 绑定执行 UUID）及已声明的 `import`、`acquisition.*`、`filters`、`settings`。异步回调写回前须检查 `AbortSignal` / `isActive()`，并在 `onCleanup()` 释放资源。
3. **后端解析可信身份**：Controller 注入父上下文提供的 `RequestOwnerIdentityResolver` 解析当前请求身份（严禁信任请求体或参数自报的 owner UUID），并在文件耐久落盘且记录提交后才标记完成。
4. **实现 `QueueOperations` 与真实排空（Quiesce / Drain）**：`workKey` 为不透明字符串（由宿主通过 `POST /api/download/queue/{queueType}/cancel` 的 JSON 请求体传递）。凡有异步或后台任务的队列，必须覆写 `prepareQuiesce` 停止新接纳并返回正代际的真实 `QueueDrain`，待在途任务归零后完成 drain。
5. **UI 槽位与计划任务能力（可选）**：通过 `uiSlots()` 贡献 `WebUiSlotContribution`（使用宿主 `PixivVue.mountUiSlot`，不打包 Vue runtime）；通过 `scheduledSourceDescriptors()` 及子上下文 `ScheduledSourceExecutor` / `ScheduledWorkExecutor` Bean 贡献计划任务支持（宿主负责调度、锁、凭据与水位线提交）。

## 新增插件自有画廊

每个下载类型插件完整拥有自己的画廊 HTML、CSS/JS、路由、i18n 与 Controller（`/pixiv-gallery.html` 与 `/api/gallery/**` 仅归官方 Pixiv 画廊插件所有，不存在通用画廊聚合 API）。

要接入画廊顶部的跨类型切换栏，在自有画廊 HTML 顶部放入：

```html
<nav data-nav-slot="gallery.type-switch"></nav>
```

并在 `navigation()` 中贡献 `NavigationPlacements.GALLERY_TYPE_SWITCH` 导航项（侧边栏入口则使用 `gallery.sidebar`、`gallery.sidebar.tools`、`novel.sidebar`、`novel.sidebar.tools`）：

```java
new NavigationContribution(
        "example-gallery-type-switch",
        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
        "example-download", "nav.gallery",
        "/example-download-gallery.html", "images",
        AccessPolicy.ADMIN, 50)
```

## 配置、凭据和文件

| 数据类别 | 磁盘路径 | 插件访问方式 |
| --- | --- | --- |
| 宿主配置与插件开关 | `config/config.yaml` | 经 Core API 稳定端口（如 `DownloadSettings`、`OutboundProxySettings`）只读获取 |
| 插件业务配置 | `config/plugins/{pluginId}.properties` | 子上下文 `Environment`（推荐 `Binder.get(environment).bind(...)`）与 `GuiConfigContribution` |
| 插件敏感凭据 | `config/credentials/{pluginId}.properties` | 宿主加密存储，仅将本 owner 已声明的敏感键注入子上下文 `Environment` 或 `PluginCredentials` |
| 插件状态与持久数据 | `state/{pluginId}/`、`data/{pluginId}/` | 注入 `RuntimePathProvider` 调用 `stateDirectory()`、`dataDirectory()` |
| 下载作品文件 | `{rootFolder}/{pluginId}/` 或插件自定义目录 | 由 `DownloadSettings.getRootFolder()` 派生，不得写入 `state/` 或 `data/` |

- **数据库访问**：新插件注入 `PluginDatabase`，通过 `privateSchema()` 与 `privateStorageMigrations()` 声明结构，使用 `read(work)` / `transaction(work)` 执行方言无关操作（兼容 SQLite 与 PostgreSQL）；参与主库协作时注入 `PluginMainDatabase` 访问本 owner 受管表或已申请的 `sharedMainStorage()`。旧 `PluginDataSource` 仅支持 SQLite。
- **出站 HTTP / WebSocket**：注入 `OutboundHttpClientFactory` / `OutboundWebSocketClientFactory` 创建由插件 Bean 拥有并在销毁时 `close()` 的客户端（`exchangeStream` 返回的响应流必须在所有分支关闭）；禁止自行创建 `HttpClient`、`ProxySelector` 或依赖宿主 HTTP 配置类。

## 独立服务契约与组合

SDK 1.4+ 支持跨插件类型化服务（参考 `typed-service-plugin` 模板）：

- 契约载体插件通过 `pixiv.contract-api` 与 `pixiv.contract-sha256` 声明原始纯 JDK API JAR（采用 `process-restart`）；提供者在 `serviceExports()` 导出实现，消费者在 `serviceImports()` 声明依赖并注入 `PluginServices`。
- 调用 `find(key)`、`require(key)`、`findAll(key)` 或生命周期绑定 `bind(keys, activation)` / `bindAll(keys, activation)` 获取绑定精确 publication 的代理。
- 使用 `sdk-tools.jar` 生成 JSON Schema、远程调用适配器与检查二进制兼容性：

```text
java -jar sdk-tools.jar schema <api-module> <classpath>
java -jar sdk-tools.jar remote <consumer-module> <classpath> example.one.Service example.two.Service
java -jar sdk-tools.jar contract-evolution <old-api.jar> <new-api.jar> <new-report.json>
```

大整数在 record 字段标注 `@ValueProperty(encoding = ValueEncoding.DECIMAL_STRING)`；`UUID`、`Instant`、`Duration`、`float`、`double` 使用 SDK 固定无损字符串编码。

## 不依赖 GUI 的凭据与通用操作验证

- **`PluginCredentials`**：插件可在 `credentialKeys()` 声明本 owner 前缀下的敏感键（最多 128 项，单值 64 KiB、单批 1 MiB；同名 GUI 字段必须标为敏感），注入 `PluginCredentials` 执行原子 `read`、`update`、`delete`。
- **HTTP Hooks**：提供 `OperationHook` Bean，实现 `id()`、`definition()`、`generation()` 和 `invoke(...)`。宿主从 Spring 的真实映射发现接口，包含 owner、方法、路径模板和匹配条件；普通控制器与动态外置控制器都无需 Hook 注解或 Guard 包装。正式绑定可选择单个 endpoint 或显式配置的接口组，`OperationEndpoint` 只用于可选的操作分组。
- **绑定与查询**：管理员读取 `/api/web/operation-hooks/catalog`、`definitions`、`configuration`；保留当前配置后，将 `{revision, settings}` 提交到同前缀的 `prepare`，审阅目标、受影响页面和权限，再以 `{plan, approvals}` 提交到 `publish`。配置含 `groups` 和 `bindings`；绑定指定 `id/hook/operation/endpoint/phase/order/completion/configuration/fields`，`operation` 与 `endpoint` 二选一。`resolveTargets` 与 `getFormEndpoints(pageId, formId, actionId)` 共用解析器；未声明、歧义、越权或不可用必须处理，发现接口不授予调用或绑定权限。
- **执行与数据**：`BEFORE_INVOKE` 在宿主鉴权、CSRF 和准入之后、controller 之前返回 `CONTINUE/REJECT/CHALLENGE`。默认只传元数据；正文、查询参数和结果字段按正式绑定的 `fields` 白名单有界提取，禁止凭据字段。提供者只拿纯值，不能取得请求对象或业务 action。`AFTER_COMPLETION` 可筛选 `SUCCEEDED/FAILED/BLOCKED/TIMED_OUT/CANCELLED` 或 `ALL`；成功表示 HTTP 状态小于 400，不推断正文中的业务成功、事务提交或后台任务完成。SSE、下载和异步请求观察完成元数据，不缓存整条流。
- **挑战与生命周期**：宿主 `POST /api/operation-hooks/prepare` 与统一证明头 `X-Pixiv-Hook-Ticket` 负责挑战准备、正式修订核对和一次消费，业务 DTO 不增加证明字段；框架不会盲目重放请求。必需前置提供者缺席、故障或超时会拒绝执行。后置失败保留原 HTTP 结果，不保证持久送达；超时不代表 Java 工作已停止，精确代际租约在实际退出后释放。每页最多 16 个绑定、128 个表单关系，每个绑定最多 16 个获批字段；HTTP 执行最多 64 个在途请求、8 个工作线程、64 个排队任务，前置累计等待及后置等待各 10 秒。
- **诊断与本地调用**：默认英文日志记录 correlationId、单次调用、可信插件及 generation/publication、注册回调入口、绑定来源、目标 handler/方法/路由模板、阶段和结果，不记录证明或请求正文。`OperationVerification` 适配可交互前置 Hook；`OperationGuard` 保留给显式的本地同步操作，不在同一 HTTP 入口重复调用。验证组件见[操作验证码](/zh-cn/captcha)。

### 用注解声明固定 HTTP 目标

SDK 1.4+ 可在插件回调方法上使用 `@HttpHook`。把下例的 `StatusObserver` 通过插件子上下文的 `@Bean` 注册，宿主会发现声明；目标控制器不需要修改。这个例子统计成功的本机健康探针请求，`/api/download/status` 不表示一次作品下载完成。

```java
import java.util.concurrent.atomic.AtomicLong;
import top.sywyar.pixivdownload.plugin.api.operation.HttpHook;
import top.sywyar.pixivdownload.plugin.api.operation.OperationHook;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

public final class StatusObserver {
    private final AtomicLong completed = new AtomicLong();

    @HttpHook(id = "example-status-observer", targetOwner = "core",
            method = HttpMethod.GET, path = "/api/download/status",
            phase = OperationHook.Phase.AFTER_COMPLETION,
            completion = OperationHook.Completion.SUCCEEDED)
    public void onStatus(OperationHook.Invocation invocation) {
        completed.incrementAndGet();
    }

    public long count() { return completed.get(); }
}
```

管理员在「插件管理 → 插件详情 → HTTP Hook 声明」选择「预览并批准」，核对目标、时机、字段权限及受影响页面，再「确认并发布」。相同声明在插件重新启用后自动恢复；声明、数据合同或目标映射变化时需重新批准。只批准当前选择，不自动更新其它声明。停用或卸载不会删除已批准的前置要求；要解除保护，管理员须显式预览撤销并发布。

方法必须是单例 Bean 的 public 同步实例方法，唯一参数为 `OperationHook.Invocation`。前置返回 `Decision`，后置可返回 void；不支持 `@Async` 或提前返回后继续工作的回调。宿主日志记录实际回调类与方法。每个插件合计最多 16 个普通或注解 Hook。

`targetOwner/method/path` 选择真实目录中的 handler；同一路径有多个候选时，用 `params/headers/consumes/produces` 指定目录中的完整条件。非空条件列表按集合精确匹配，空列表不筛选该条件；未找到或仍歧义时不能批准。同一 handler 的多个路由别名共享绑定。

普通方法仅接收元数据，配置为 `{}`。需要字段、配置 schema、资源或交互挑战时，实现 `OperationHook` 并在其 `invoke` 方法上加注解，保持 `id()` 与注解 id 相同；`fields` 声明仍须批准并通过敏感字段和预算检查。动态配置和页面编辑器绑定继续使用原接口，不依赖注解。

管理 API 为 `GET /api/web/operation-hooks/declarations?provider=<pluginId>`，以及 `POST /api/web/operation-hooks/declarations/prepare`，后者接收 `{revision, provider, id, fingerprint, remove}`。返回的计划仍提交到原 `publish`；不要持有旧计划跨越目录、插件或正式修订变化。


## 普通槽位与页面组件

普通页面通过 `/js/pixiv-ui-slots.js` 的 `PixivUiSlots.connect(...)` 挂载 `data-ui-slot`，插件模块调用 `registerModule(moduleUrl, mount)` 并在 `onCleanup` 中释放 DOM、监听器与请求。页面编辑器组件与可编辑页面的接入方式见[页面编辑器与插件组件](/zh-cn/page-editor#开发第三方插件组件与接入页面)。

## 维护外部工程与 SDK 升级

### 本地开发

SDK 开发包支持在 IntelliJ IDEA（运行 `Developer Mode`）、VS Code（`Run Plugin` / `Debug Plugin`）、Eclipse（`eclipse/Run Plugin.launch`）或命令行中直接构建并启动配套宿主与完整官方插件，运行数据隔离在工程 `.dev/` 目录（首次运行自动下载并校验固定运行包到 `~/.cache/pixivdownloader-sdk/`）：

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd verify exec:exec@sdk-debug
.\mvnw.cmd exec:exec@sdk-stop
```

打包形态默认为根部含 `plugin.properties`、不含宿主共享类的 thin PF4J `.jar`；含插件私有第三方依赖时可使用 PF4J JAR-with-lib（私有依赖置于 `lib/*.jar`）。升级 SDK 时请将新开发包解压到独立目录，对照 Javadoc 更新契约与运行清单，并在插件管理页验证启停、reload 与任务排空。

## 投稿市场资料

社区投稿向导支持附加插件图标、截图、外链列表以及多语言 `README`、本次更新说明和 `CHANGELOG.md`（可按版本标题如 `## [v1.2.0]` 自动提取）。所有文档与图片随同一版本 Release 签名锁定，客户端以净化后的静态隔离文档展示，详见[社区仓库投稿说明](https://github.com/Sywyar/PixivDownloader-community-plugins#投稿与版本管理)。

## 签名和发布

在主仓库构建 `pixivdownload-plugin-signature` 后，使用 `PluginSignatureTool` 管理 Ed25519 密钥与签名（私钥须保存在仓库与构建目录之外）：

```powershell
# 1. 生成密钥对与导出公钥指纹
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool keygen --directory "C:\secure\keys"
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool public-key --public-key "C:\secure\keys\public-key.pem" --key-id example-2026 --out "public.json"

# 2. 签署插件包与仓库 manifest.json
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool artifact --artifact "plugin.jar" --plugin-id example-download --version 0.1.0 --key-id example-2026 --private-key "C:\secure\keys\private-key.pem" --out "plugin.jar.sig"
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool manifest --manifest "manifest.json" --repository-id example.plugins --key-id example-2026 --private-key "C:\secure\keys\private-key.pem" --out "manifest.json.sig"
```

自建插件仓库可发布 `manifest.json` + `manifest.json.sig`（`manifest-v1` 协议）或实现 `paged-v2` 分页端点，并提供最大 64 KiB 的公网 HTTPS `repository.json` 描述符供用户在桌面设置中一键导入（支持 `DIRECT_STRICT` 或 `GITHUB_RELEASES` 网络策略，以及 `repository-update` 轮换证明和 `plugin-revocations` 撤销清单）。

需要向主仓库贡献 SDK 契约或官方插件修复时，请先阅读[主仓库开发](/zh-cn/development)。
