# 第三方插件 SDK

本指南面向要编写、调试和发布 PixivDownloader 外置插件的开发者。最稳妥的起点是复制官方模板，而不是从应用壳或官方插件中摘取实现类。

相关源码：

- [第三方插件模板](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates)
- [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api)
- [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api)
- [Douyin 官方示例插件](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-douyin)
- [插件签名工具](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature)

> Douyin 是完整官方实现的参考案例，展示下载、配置、代理、队列、计划任务和插件自有画廊如何组合。它也会使用只面向官方插件的内部装配，因此不能把整个模块当成第三方模板复制。第三方项目应先以 `plugin-templates` 为基线，只依赖本文列出的稳定契约。

## 先理解信任边界

外置插件与宿主运行在同一个 JVM 中，当前不是进程或 OS 级安全沙箱。插件代码拥有与同进程代码相同的风险级别：它可能读取进程可访问的文件、发起网络请求或消耗资源。

Ed25519 签名只证明 artifact 来自某个受信密钥且字节未被篡改，不能证明已签名代码没有恶意行为。安装前必须信任发布者、源码和仓库运营者。Cookie、Token、代理、作品目录和插件私有数据的合法使用也由插件作者负责。

宿主仍会执行结构、大小、路径、版本、依赖、SHA-256、签名和 provenance 校验，并用同一份冻结字节完成校验与加载。这些措施保护供应链完整性，不构成代码沙箱。详细安装行为见[插件管理](/zh-cn/plugin-management)。

## SDK 边界

`pixivdownload-plugin-api` 是稳定的扩展契约面；`pixivdownload-core-api` 提供少量稳定的宿主语义端口和值模型。依赖方向必须保持为：

```text
第三方插件
  ├─ pixivdownload-plugin-api  必选：插件入口与 contribution
  └─ pixivdownload-core-api    按需：下载设置、owner 路径等稳定语义端口

禁止依赖：pixivdownload-app、宿主实现类、plugin-runtime/installer/signature internal、
官方插件私有 service/mapper/controller、宿主 DataSource 或私有前端全局
```

插件通过描述符和 contribution 声明能力，宿主按可信的插件身份、包身份、generation 和 publication 注册能力。宿主不应出现按第三方插件 id、包名或作品类型写的特判。插件停用、卸载、损坏或不兼容时，它的路由、静态资源、i18n、导航、下载类型、队列和计划能力会撤回；消费者必须按“能力缺席”降级，不能产生白屏、空指针或半完成任务。

### 可以贡献什么

`PixivFeaturePlugin` 当前提供以下入口。未使用的入口保持默认空列表即可：

| 方法 | 能力 |
| --- | --- |
| `id`、`displayName`、`description`、`displayNamespace` | 插件身份和 i18n 展示键 |
| `iconKey`、`colorToken`、`kind` | 受控图标、颜色和类别 token |
| `start`、`stop` | 插件直接拥有的本地资源生命周期；`stop` 必须幂等 |
| `schema` | 插件自有表、补列和路径列声明；不能给核心表增加私有字段 |
| `routes` | 页面、API 和静态路径的访问策略 |
| `staticResources` | 插件 classpath 静态资源到 URL 的映射 |
| `i18n` | 插件自有 Web i18n namespace |
| `navigation` | 导航项和中性 placement |
| `startupRoutes`、`landings` | 默认启动落点和身份相关业务落点 |
| `pageSections`、`uiSlots` | 页面区块与受控 Web UI 挂载点 |
| `guiThemes` | 启动期 GUI 主题；通常需要 `process-restart` |
| `guiConfigContributions` | GUI 配置字段、分组、section、动作与预设 |
| `guiOnboardingSteps` | GUI 引导步骤 |
| `drilldowns` | 按语义 placement 解析的下钻链接 |
| `userscripts` | 稳定脚本 id 与精确 classpath 资源 |
| `scheduledSourceDescriptors` | 计划来源的纯数据描述符 |
| `downloadTypes` | 下载工作台作品类型描述符 |

Spring Bean 不从 `PixivFeaturePlugin` 返回。外置入口通过 `PixivPluginProvider.configurationClasses()` 声明配置类，宿主为活动插件创建独立的子 `ApplicationContext`。

## 从模板开始

### 选择模板

| 模板 | 适用情况 | 已包含内容 |
| --- | --- | --- |
| `minimal-feature-plugin` | 页面、API、导航、i18n、配置或插件自有 schema | PF4J 入口、provider、feature、显式子上下文、controller、route/static/i18n/schema、thin JAR 测试 |
| `download-type-plugin` | 新增一种可下载作品类型 | 下载描述符、五类取得模式、队列、计划来源、Vue 槽位、独立画廊、前后端测试 |

仓库内验证两个模板：

```powershell
mvn -f plugin-templates/pom.xml clean verify
mvn -f plugin-templates/pom.xml -pl minimal-feature-plugin -am verify
mvn -f plugin-templates/pom.xml -pl download-type-plugin -am verify
```

复制到仓库外后，每个模板都是不继承 PixivDownloader 根 parent 的独立 Maven 项目。在模板目录执行：

```powershell
mvn clean verify
```

### 获取 SDK artifact

模板使用以下 Maven 坐标：

```xml
<dependency>
    <groupId>top.sywyar.lovepopup</groupId>
    <artifactId>pixivdownload-plugin-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

当前模板要求构建环境能从本地或团队 Maven 仓库解析该 artifact；仓库内模板验证不代表它已发布到公共 Maven 仓库。从源码开发时可先在 PixivDownloader 仓库根安装稳定 API：

```powershell
./mvnw.cmd -pl pixivdownload-plugin-api,pixivdownload-core-api -am install -DskipTests
```

只有确实需要稳定宿主语义端口时才增加 Core API，并保持 `provided`：

```xml
<dependency>
    <groupId>top.sywyar.lovepopup</groupId>
    <artifactId>pixivdownload-core-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

PF4J、Spring、Jackson、Servlet API 等由宿主父 classloader 提供的依赖也必须是 `provided`。不要把共享契约或框架类复制进插件 JAR，否则同名类会因 classloader 不同而无法转换。

### 复制后必须统一改名

以下载类型模板为例，至少同时替换：

| 模板值 | 替换内容 |
| --- | --- |
| `example-download-plugin` | Maven `artifactId` |
| `example-download` | 全局唯一插件 id、队列类型、URL 前缀和 i18n namespace |
| `com.example.pixivdownload.downloadtype` | Java 包及对应目录 |
| `ExampleDownload` | Java 类型名前缀 |
| `0.1.0` | artifact 和 `plugin.version` |
| `plugin.requires=1.0` | 兼容的 Plugin API major.minor |
| `plugin.provider=Example Developer` | 发布者名称 |

还要同步修改路由、静态路径、前端常量、schema 名称、测试和两种语言的 i18n 文案。只改 `plugin.properties` 会造成 descriptor、feature 与运行时 publication 身份不一致，宿主会拒绝接入。

## 插件包和入口

### `plugin.properties`

文件必须位于 JAR 根部。基础示例：

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

字段规则：

| 字段 | 规则 |
| --- | --- |
| `plugin.id` | 全局唯一、小写短横线 token；必须与 `PixivFeaturePlugin.id()` 相同 |
| `plugin.version` | 插件 artifact 版本 |
| `plugin.requires` | 所需 Plugin API `major.minor`，不是应用发行版本 |
| `plugin.class` | PF4J 主类，实现 `PixivPluginProvider` |
| `plugin.provider`、`plugin.description` | 发布者和 descriptor 说明 |
| `plugin.dependencies` | 可选 PF4J 插件依赖表达式 |
| `pixiv.*` 展示字段 | i18n namespace/key 和受控展示 token |
| `pixiv.replaces` | 可选的被替换插件身份 |
| `pixiv.lifecycle-policy` | `hot-reload`、`backend-restart` 或 `process-restart`；区分大小写，缺省为 `hot-reload` |

Plugin API 当前以 `1.0.0` 为初始契约基线。兼容判断使用 `requiredMajor == hostMajor && requiredMinor <= hostMinor`，PATCH 不参与准入判断。首次公开发布后，破坏性契约变更升 MAJOR，向后兼容新增升 MINOR，兼容修复升 PATCH。

### PF4J provider 与 Spring 子上下文

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

每个外置包必须返回且只返回一个非空 feature，其 id 与 descriptor 相同。配置类用 `@Bean` 显式装配插件 Bean：

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

不要依赖宿主根包扫描，也不要从插件包扫描任意类。子上下文可以注入父上下文明确提供的 Plugin API、Core API、JDK 类型和规范依赖，但不能注入 app 实现类。

## Web 路由、静态资源和 i18n

每个 controller 映射、静态目录和顶层 HTML 都必须由所属插件在 `routes()` 中声明。未声明的 `path + HTTP method` 会返回 404；前端隐藏入口不构成鉴权。

常用命名工厂：

| 工厂 | 实际访问面 |
| --- | --- |
| `publicRoute` | 无需鉴权，solo/multi 一致 |
| `visitor` | multi 游客可达，solo 需要会话，受邀访客不可达 |
| `visitorAndInvitedGuest` | multi 游客与受邀访客均可读 |
| `invitedGuest` | 管理员和受邀访客可达，同时受 monitor 保护 |
| `admin` | 仅管理员 |
| `local` | 本机流程特例 |
| `gui` | 本机可信请求和 GUI token 双重校验 |

需要限制 HTTP 方法时使用 `WebRouteContribution` 标准构造器并显式传 `HttpMethod` 集合。相同特异性的非 PUBLIC 策略冲突会 fail-fast。

独立管理页的完整声明示例：

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

页面、CSS 和 JavaScript 分文件存放；用户可见文案进入插件 namespace。渲染外部数据时使用 DOM API 和 `textContent`，不要把未知文本拼进 `innerHTML`。

## 新增下载类型的完整流程

一个下载类型不是单个 Java 类，而是一组由同一插件拥有、能一起发布和撤回的能力：

```text
plugin.properties + provider
        ↓
DownloadTypeDescriptor ──→ 下载工作台发现类型和取得模式
        ↓
同源行为模块 ───────────→ 导入、发现、入队、状态更新、筛选和设置
        ↓
插件 controller/service ─→ 解析请求并完成真实领域工作
        ↓
QueueOperations ─────────→ 取消、清空和生命周期 drain
        ├─ WebUiSlotContribution（可选）
        ├─ ScheduledSourceDescriptor + executor（可选）
        └─ 插件自有独立画廊（可选）
```

### 1. 声明 `DownloadTypeDescriptor`

当前没有 `QueueTypeContribution`、`independentPage`、gallery capability bag 或 descriptor 内的 `uiSlots` 字段。下载类型、队列、UI 槽位、计划来源和独立页面各走自己的稳定契约。

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

字段含义：

| 字段 | 要求 |
| --- | --- |
| `contractVersion` | 当前必须为 `DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION`，值为 1 |
| `type` | 全局唯一作品类型；通常与 `QueueOperations.queueType()` 一致，但注册中心不假定二者必然对应 |
| `displayNamespace`、`displayI18nKey` | 类型名称的 namespace 和纯 key |
| `order` | 稳定排序值 |
| `iconKey`、`colorToken` | 宿主白名单内的受控 token，不是 URL、HTML 或任意 CSS |
| `moduleUrl` | 必填的同源绝对 `.js` 路径，必须由同一插件静态资源贡献拥有 |
| `acquisitionModes` | `single-import`、`user`、`series`、`search`、`quick` 的声明集合 |
| `cancelSupported` | 是否提供单项取消；为 `true` 时队列项必须有顶层 `cancelWorkKey` |
| `filters`、`settings` | 行为模块中对应契约 id 的白名单 |
| `i18nNamespace` | 行为模块状态和错误文案 namespace |

### 2. 实现前端行为模块

模块在宿主创建的真实 `<script>` 求值窗口中调用 `PixivBatch.queueTypes.registerModule(initializer)`。不要复制 Vue，也不要直接读写宿主的 `state`、`saveQueue`、`renderQueue`、`updateStats` 或私有 DOM id。

contract version 1 的主要入口：

| 入口 | 职责 | 缺席语义 |
| --- | --- | --- |
| `process(item, context)` | 把一个队列项交给插件 API，并用 `context.updateItem(patch)` 提交白名单状态 | 必选；缺失则类型不应激活 |
| `import` | URL 匹配、构建单作品队列项和 `cancelWorkKey` | 未声明 `single-import` 时省略 |
| `acquisition.user` | 用户输入、分页发现、渲染和队列元数据 | 未声明 `user` 时省略 |
| `acquisition.series` | 系列 URL、分页、顺序和队列元数据 | 未声明 `series` 时省略 |
| `acquisition.search` | 搜索请求、范围请求、渲染和队列同步 | 未声明 `search` 时省略 |
| `acquisition.quick` | 快捷动作和作品发布；结果通过 `context.publishWorks(payload)` 提交 | 未声明 `quick` 时省略 |
| `filters` | 只实现 descriptor 已列出的筛选 id | 空列表表示没有额外筛选 |
| `settings` | 只实现 descriptor 已列出的设置 id | 空列表表示没有额外设置 |
| `slots` | 类型模块同源的声明式片段 | 没有片段时省略；独立动态槽位走 `WebUiSlotContribution` |

initializer 会收到 `AbortSignal`、`isActive()`、`assertActive()` 和 `onCleanup()`。所有异步结果在写回前必须确认 publication 仍然有效；清理监听器、定时器和已挂载组件。

### 3. 后端解析可信 owner

HTTP controller 只从父上下文注入 `RequestOwnerIdentityResolver`，由当前请求解析管理员/用户 owner：

```java
RequestOwnerIdentity identity = ownerResolver.resolve(request);
queue.submit(command, identity);
```

不能信任 JSON、query 或自定义 header 中的 owner UUID。descriptor 的 `pluginId/packageId/generation/publicationId` 只证明下载类型 publication 的 currentness，也不是用户鉴权身份。

真实下载器必须在文件已经耐久写入、历史或来源关系等成功事实已经落地后，才把任务标记为 completed。模板的内存完成响应只是确定性测试夹具，不能直接用于生产插件。

### 4. 实现 `QueueOperations`

```java
public final class ExampleQueue implements QueueOperations {
    @Override public String queueType() { return "example-download"; }
    @Override public void cancel(String workKey, String ownerUuid, boolean admin) { /* ... */ }
    @Override public int clearAll() { /* ... */ }
    @Override public int clearForOwner(String ownerUuid) { /* ... */ }
}
```

`workKey` 是该 queue type 内的不透明稳定字符串，不要求是数字，也不能放进 URL path segment。宿主使用 `POST /api/download/queue/{queueType}/cancel`，在 JSON 中传原始 `workKey` 和 descriptor publication 身份；插件前端应调用宿主桥接，不自行构造控制请求。

严格同步且没有后台任务的实现可以使用默认 generation 0 completed drain。只要存在排队、执行器 handoff、延迟回调或任何越过当前调用栈的工作，就必须：

1. `prepareQuiesce(registeredQueueType)` 原子停止接纳新任务，返回正 generation 的真实 `QueueDrain`；
2. 宿主保存 drain 后，`cancelQuiescedTasks()` 才发送协作式取消；
3. 所有活动任务归零后 drain 才完成；
4. 重复 prepare 返回相同的 `queueType + generation`，新插件实例使用新 generation。

不能用 completed 哨兵伪造异步队列已经退出。插件自己的 executor、scheduler、HTTP/WebSocket client 必须由子上下文拥有并在关闭时释放。

### 5. 增加 UI 槽位

槽位独立发布，不放回下载 descriptor：

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

动态槽位模块通过宿主 `PixivVue.mountUiSlot` 挂载，并且只用 owner-scoped `context.supports(type, mode)`、`context.dispatchQuickAction(action)` 和 `context.onCleanup(...)`。不要随插件打包 Vue runtime。

### 6. 增加计划任务能力

计划任务是可选能力。feature 只贡献纯数据 `ScheduledSourceDescriptor`，`ScheduledSourceExecutor` 和 `ScheduledWorkExecutor` 作为插件子上下文 Bean 提供。

浏览器来源模块负责：

- `capture`：把当前取得输入序列化成插件拥有、带 schema/version 的 definition；
- `restore`：把保存的 definition 回灌编辑器；
- `summary`：生成受控的展示结构。

浏览器只通过 publication-scoped `context.acquisitionInput(mode)` 和 `context.restoreAcquisition(mode, value)` 访问宿主允许的输入。当前第三方中性 adapter 只开放 `single-import`，不要读取宿主 DOM 或调用私有 mode global 模拟其它模式。

后端来源执行器拥有 definition schema、发现和 checkpoint；作品执行器拥有 payload schema 和同步作品执行。宿主继续拥有 claim、lease、credential、Guard、pending、取消和 checkpoint CAS。`ScheduledWorkExecutor.execute` 只有在作品文件和成功事实都已耐久提交后才能返回 `COMPLETED` 或 `ALREADY_COMPLETED`。插件或执行器缺席时，任务数据保留并挂起，不应删除或提前推进 checkpoint。

## 新增插件自有画廊

“独立页面”是一个设计模式，不是名为 `independentPage` 的 API 或 descriptor 字段。它表示插件完整拥有：

- 顶层 HTML 和页面专属 CSS/JavaScript；
- 自己的 route、static 和 i18n contribution；
- 自己的 controller/API、可见性检查和数据模型；
- 自己的导航或类型切换入口（如需要）。

因此，新增第三方画廊的稳定做法就是按前文的独立管理页示例新增页面和 API。页面是否存在自然跟随插件 publication；插件停止后路由和资源撤回，不需要宿主按类型写分支。

边界必须明确：

- `/pixiv-gallery.html` 是长期维护的官方 Pixiv 主画廊，不是第三方下载类型的通用挂载壳；
- `/api/gallery/unified/**` 和旧 `unifiedGallery` ABI/wire 字段只是已弃用的内部兼容面，禁止新增消费者；
- 官方主画廊的 provider/registry/broker 与来源 renderer 是内部装配，不是第三方 SDK；
- 第三方页面不得复制 gallery/novel 实现、直连宿主数据库或导入 core/gallery 私有类；
- 资产 serving、删除、可见性、通用搜索、收藏和统计需要真实稳定端口；SDK 没有相应端口时，应先提出中性契约贡献，不要从 app 实现绕过。

Douyin 的 `/pixiv-douyin-gallery.html`、详情页和 `/api/douyin/gallery/**` 是这种来源自有页面的完整官方案例；第三方实现仍以 `download-type-plugin` 的独立画廊为可复制基线。

## 配置、凭据和文件

### 三类配置所有权

| 内容 | 路径 | 插件如何取得 |
| --- | --- | --- |
| 宿主设置和启停状态 | `config/config.yaml` | 只经 Core API 的只读语义端口读取需要的最小值 |
| 插件业务配置 | `config/plugins/{pluginId}.properties` | 子上下文 `Environment`、`@Value`、`@ConfigurationProperties`；需要直接管理文件时用 `RuntimePathProvider` |
| 插件凭据 | `config/credentials/{pluginId}.properties` | 宿主加密维护，只把当前 owner 已声明的解密值注入该插件子上下文 |

插件业务键应使用自己的 id 前缀，例如：

```properties
example-download.download.directory=
example-download.proxy.mode=inherit
example-download.download.include-cover=false
```

普通读取示例：

```java
@Bean
ExampleSettings settings(Environment environment) {
    return new ExampleSettings(
            environment.getProperty("example-download.download.directory", ""),
            environment.getProperty("example-download.proxy.mode", "inherit"));
}
```

GUI 字段通过 `GuiConfigContribution` 声明，宿主按可信 owner 保存。敏感字段或 `PASSWORD` 字段不会写入普通 properties；插件只读取注入后的值，不能读取、解密或重写 credential envelope。owner-scoped 目录和加密凭据仍不构成恶意同 JVM 代码的硬隔离。

### 稳定路径和作品目录

需要精确文件路径时，按需依赖 Core API：

```java
Path config = runtimePathProvider.resolvePluginConfigPath("example-download", "properties");
Path state = runtimePathProvider.resolvePluginStateDirectory("example-download");
Path data = runtimePathProvider.resolvePluginDataDirectory("example-download");
```

`state/{pluginId}` 用于可重建运行状态，`data/{pluginId}` 用于插件管理的数据和缓存。作品文件不能写进这两个目录。

下载作品默认从 `DownloadSettings.getRootFolder()` 继承宿主作品根，并由插件自主管理自己的子目录：

```java
Path defaultOutput = Path.of(downloadSettings.getRootFolder())
        .resolve("example-download")
        .normalize();
```

Douyin 当前使用同一规则得到 `{rootFolder}/douyin`，再按 owner 管理作品；用户在 `config/plugins/douyin.properties` 设置保存位置后改用插件自己的覆盖目录。第三方插件可以采用相同模式，但具体子目录、文件名和迁移逻辑归插件所有。

不要依赖 app 的 `RuntimeFiles`、`DownloadConfig`、`ProxyConfig` 或具体线程池 Bean。需要稳定语义时使用 `RuntimePathProvider`、`DownloadSettings`、`OutboundProxySettings` 等 Core API 端口；SDK 没有覆盖的宿主实现不是隐式公共 API。

完整路径说明见[存储原理](/zh-cn/storage)，配置键说明见[配置参考](/zh-cn/configuration)。

## 出站 HTTP 与 WebSocket

插件网络访问使用纯 JDK 稳定工厂：

```java
@Bean(destroyMethod = "close")
OutboundHttpClient exampleHttpClient(OutboundHttpClientFactory factory) {
    return factory.open(OutboundHttpClientProfile.standard(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            OutboundHttpRoute.inherit()));
}
```

`OutboundHttpClient.exchangeStream` 返回的 live response 每条路径都必须关闭；`exchange` 会完整缓冲并自动关闭响应。非 2xx 状态仍是普通响应，业务调用方负责解释。

WebSocket 使用 `OutboundWebSocketClientFactory.open(profile)`，客户端同样由插件 Bean 拥有并在子上下文关闭时 `close()`。插件声明超时、redirect、cookie、连接池和中性 route profile；宿主拥有实际传输、全局/任务代理解析和 ProxySelector。

不要自行创建 `java.net.http.HttpClient`、`ProxySelector`，不要依赖 Apache 类型或 app 的 HTTP 配置。鉴权头、站点请求头和协议消息属于插件业务，不能塞进通用传输层。Douyin 的 Spring `RestTemplate` adapter 是官方兼容写法，不是第三方基线。

## 构建、测试、调试和安装

### 必要测试

至少保留模板已有的检查：

- descriptor、provider、feature id 和 contribution 一致性；
- 子上下文显式装配及 controller 注册；
- route/static/i18n/schema 或下载 publication；
- Queue owner、opaque work key、清空和 drain；
- 前端模块 `node --check` 及实际行为测试；
- JAR 根 `plugin.properties` 与 thin JAR 边界。

从仓库根验证模板的标准命令是：

```powershell
mvn -f plugin-templates/pom.xml clean verify
```

若插件同时贡献后端和前端，不要只用 Java 测试搜索脚本文本；前端 contract 应由 Node 实际执行。

### 本地开发

第三方独立项目的基线流程：

1. `mvn clean verify`；
2. 在 JAR 中确认根 `plugin.properties`、类和资源；
3. 从管理员插件管理页本地上传 JAR；
4. 对 `hot-reload` 插件执行事务替换和即时激活；
5. 刷新页面，验证 controller、route、static、i18n 和下载类型都属于当前 generation；
6. 修改后重新构建、上传并使用 `reload`，不要在运行时手工覆盖 JAR。

```powershell
jar tf target/example-download-plugin-0.1.0.jar
```

也可以在应用停止时把 JAR 放入工作目录 `plugins/`，再启动应用。`plugins/runtime/` 是宿主私有冻结工作区，不是安装目录或调试输出目录。

仓库中的官方插件使用专用开发模式，它先编译官方插件并从各模块当前 `target/classes` 加载：

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

该入口适合贡献官方插件或核对 Douyin 示例，不是仓库外第三方项目的自动发现器。IDE 可使用仓库提交的 IntelliJ IDEA、VS Code 或 Eclipse `Developer Mode` 共享配置。

调试时检查：

- `/plugin-manage.html` 中状态为 `STARTED`，generation 与本次替换一致；
- 插件页面/API 能访问，stop 后变为未声明，start/reload 后恢复；
- 页面脚本、CSS 和 i18n 的改动确实来自新 artifact，而不是浏览器缓存或旧 `plugins/` 包；
- `log/` 没有 route 冲突、重复 id、子上下文装配、版本、签名或 drain 诊断；
- 异步任务停止后不再写文件、回调页面或持有旧 classloader。

### artifact 形态

模板默认生成 thin PF4J JAR：

- JAR 根有 `plugin.properties`；
- 没有 Spring Boot `BOOT-INF/`；
- 没有 `lib/*.jar`；
- 不包含 plugin-api、core-api、PF4J、Spring、Jackson、Servlet API 或宿主类副本。

宿主也支持 PF4J JAR-with-lib，用于插件私有第三方库：根部仍是 descriptor、插件类和资源，私有依赖放 `lib/*.jar`。不要 shade 或私带共享契约。选择 JAR-with-lib 时应增加包结构和独立 classloader 加载测试；官方默认交付格式仍是 `.jar`，不是 ZIP。

## 签名和发布

### 生成 artifact 签名

私钥必须是 Ed25519 PKCS#8 PEM，保存在仓库和构建输出之外；仓库配置使用 Base64 编码的 X.509 SubjectPublicKeyInfo 公钥。构建签名工具后，CLI 通过 classpath 主类调用：

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

输出是结构化 JSON：`formatVersion`、`algorithm=Ed25519`、`keyId` 和 `value`。同时记录 artifact 的精确字节数和 SHA-256：

```powershell
$artifact = Get-Item -LiteralPath <plugin.jar>
$artifact.Length
(Get-FileHash -Algorithm SHA256 -LiteralPath $artifact.FullName).Hash.ToLowerInvariant()
```

### catalog manifest

仓库清单 schema version 1 的顶层字段为 `schemaVersion`、`generatedTime` 和 `entries`。最小可发布条目：

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
        "displayName": {"zh": "示例下载", "en": "Example download"},
        "summary": {"zh": "示例下载类型", "en": "Example download type"},
        "description": {"zh": "插件详细说明", "en": "Plugin description"},
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
          "requiredCoreApi": "1.0",
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

`market` 只用于展示、搜索和排序，不参与安装安全决策。`packageUrl` 和 manifest URL 必须是 HTTPS；安装仍以 artifact 的大小、SHA-256、结构化签名和内部 descriptor 为权威。

对 manifest 原始字节生成 detached 签名：

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

发布 `manifest.json`、同地址追加 `.sig` 的 `manifest.json.sig`、artifact 和可选的 artifact detached signature。不要在签名后格式化或重写 manifest。

验签命令：

```text
verify-manifest --manifest <manifest.json> --signature <manifest.json.sig> --repository-id <id> [--policy official|custom]
verify-artifact --artifact <jar> --signature <sig.json> --plugin-id <id> --version <version> --expected-size <bytes> --sha256 <hex> [--policy official|custom]
```

验证自定义 root 时再传 `--trusted-key-id`、`--trusted-public-key`；可选字段有 `--trusted-algorithm`、`--trusted-state`、`--trusted-publisher`、`--trusted-label` 和 `--trusted-official`。

### 让用户添加自定义仓库

推荐用户在 GUI 的插件市场配置中添加仓库。等价的 `config.yaml`：

```yaml
plugin-catalog.enabled: true
plugin-catalog.repositories:
  - id: example
    display-name-key: plugin.market.repository.example.name
    manifest-url: https://plugins.example.com/manifest.json
    enabled: true
    proxy-policy: direct-strict
    trusted-keys:
      - key-id: example-2026
        algorithm: Ed25519
        public-key: BASE64_X509_SUBJECT_PUBLIC_KEY_INFO
        state: ACTIVE
        publisher: Example Publisher
        trust-label: Example repository release key
```

自定义仓库不继承官方 trust root。仓库 id 不能使用保留值 `official` 或 `configured`。发布者轮换密钥时应先发布新 ACTIVE root，再按明确的 RETIRED/REVOKED 策略处理旧 key，不能只替换公钥却复用相同 key id。

## 向项目贡献

编写私有或社区插件通常不需要修改宿主。以下内容适合向主仓库贡献：

- 修复 Plugin API、Core API、插件运行时或模板中的真实缺陷；
- 为多个插件都需要的中性语义新增稳定端口；
- 完善模板、SDK 文档、边界测试和失败诊断；
- 贡献或修复官方外置插件；
- 改进签名、安装事务、生命周期和能力缺席降级；
- 修正文档与当前实现的偏移。

如果现有 SDK 缺少能力，不要先依赖 app 私有类。先提出一个不认识具体站点或插件 id 的中性契约，并同时说明真实消费者、所有权、生命周期、错误/缺席语义和测试。公共契约变更需要同步更新 Plugin API/Core API 版本、模板、官方示例和本文。

基本流程：

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/plugin-api/your-capability upstream/master
```

提交前：

1. 运行直接相关的模块测试，再运行受影响的模板和边界测试；
2. 保持插件 id、descriptor、feature、route、静态资源、i18n 和测试一致；
3. 不提交 `target/`、`build/`、运行配置、凭据、私钥或下载数据；
4. PR 说明动机、稳定边界、失败/缺席行为以及实际执行的验证命令；
5. 代码、模板和核心开发文档向 `master` 提交 PR；在线站点内容位于独立 `gh-pages` 分支，需要文档 PR 时按该分支当前结构修改。

## 发布前检查表

- [ ] 只依赖 Plugin API 和确有需要的 Core API 稳定端口，全部共享依赖为 `provided`
- [ ] `plugin.properties` 位于 JAR 根，id/version/requires/class 与代码一致
- [ ] feature 只返回一个，子上下文只显式装配自己的 Bean
- [ ] 每个 controller、页面和静态目录都有正确 `AccessPolicy` 路由声明
- [ ] i18n、错误码和状态不会泄露凭据或异常细节
- [ ] 下载完成只在文件和成功事实耐久落地后报告
- [ ] owner 来自 `RequestOwnerIdentityResolver`，`workKey` 保持不透明字符串
- [ ] 异步队列、任务、客户端、executor 和 scheduler 可真实 quiesce/drain/close
- [ ] 独立画廊只使用插件自有页面/API，不消费 unified 兼容面或主画廊内部实现
- [ ] 配置、凭据、state/data 和作品目录符合 owner 与目录边界
- [ ] `mvn clean verify`、前端行为测试和 JAR 结构检查通过
- [ ] 发布 artifact 的大小、SHA-256、签名和 manifest 完全对应同一份字节
- [ ] 私钥不在源码、构建输出、日志、插件包或仓库服务器公开目录中
