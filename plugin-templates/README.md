# PixivDownload 外置插件模板

此目录中的项目是可复制的第三方插件起点，也是仓库内的模板验证工程；它们不会加入正式产品 reactor，也不会进入应用发行包。

目录包含以下独立项目：

- <a href="minimal-feature-plugin/README.md"><code>minimal-feature-plugin</code></a>：可在独立 worker 中运行的 route/static/i18n 声明式插件；
- [typed-service-plugin](typed-service-plugin/README.md)：独立 API、契约包装包、服务提供者与管理员 HTTP 消费入口；
- <code>download-type-plugin</code>：经包验证与管理员信任确认后可用的宿主进程完全信任示例，覆盖下载工作台 contract version 1、队列操作、计划来源、Vue 槽位与独立画廊。

模板目标是提供一套稳定、高可用、易上手的 SDK 起点：稳定来自版本化公共契约和 owner/publication 边界，高可用来自失败隔离、fail-closed 与真实生命周期清退，易上手来自可复制工程、命名工厂和确定性守卫。目录名中的 `minimal` 只表示示例不携带站点业务逻辑，不表示删减安全、生命周期或降级路径来追求文件更少。

项目自有模板的复制、改编及许可选择见[插件链接例外](../PLUGIN-LINKING-EXCEPTION.txt)。

## 基础功能插件

<code>minimal-feature-plugin</code> 演示一个没有站点业务逻辑的 thin PF4J 插件，包含：

- 根部 <code>plugin.properties</code>、PF4J 主类与 <code>PixivPluginProvider</code>；
- <code>PixivFeaturePlugin</code> 的管理员路由、静态资源与 i18n 声明；
- 不含配置类、controller 或其它同进程行为的声明式贡献；
- 独立 HTML/CSS/JS 页面、明暗主题变量和完整正式语言资源；
- descriptor、贡献对象、JavaScript 语法和 thin JAR 守卫。

仓库内验证需要 JDK 17、Maven 和可从命令行调用的 Node.js：

    mvn -f plugin-templates/pom.xml verify

这个验证 reactor 会先构建同仓的 SDK 模块。两个模板只声明一个 <code>io.github.sywyar.pixivdownloader:pixivdownload-sdk:1.0.0-rc4</code> 依赖，作用域为 <code>provided</code>，不继承仓库 parent。复制到仓库外时，需要能解析该版本的 Maven 仓库；源码中的候选版本不代表已经公开发布。

产物位于 <code>plugin-templates/minimal-feature-plugin/target/example-minimal-plugin-0.1.0.jar</code>。将复制并改名后的插件 JAR 通过插件管理页安装，或放入宿主的运行期 <code>plugins/</code> 目录；两种方式都受宿主的包验证与签名策略约束，模板不包含签名、信任根或 installer 内部实现。其 <code>declarative-process</code> 描述符与零配置类边界允许宿主在独立 worker 中接纳声明式贡献，启用后管理员可访问 <code>/example-minimal.html</code>。

下载类型模板有自己的 [README](download-type-plugin/README.md) 和精确替换表，产物位于 <code>plugin-templates/download-type-plugin/target/example-download-plugin-0.1.0.jar</code>。它演示宿主可信用户 owner 解析、宿主桥接以 JSON 承载 <code>queueType + opaque workKey + descriptor publication identity</code> 的定向取消、受控队列状态提交、quick 结果发布、owner-scoped UI action，以及不触碰宿主 DOM 的计划来源输入 / 回灌 context；站点数据、队列和画廊仍是确定性内存示例。该模板需要 Spring 子 context 与行为能力，因此明确声明 <code>host-process-full-trust + process-restart</code>；第三方包经过包验证与管理员信任确认后可以在生产环境使用该完整能力路径，签名只证明发布者身份与内容完整性。复制时必须用真实、合法且同步完成后才报告成功的领域实现替换，不得把模拟响应当作生产下载器，也不得在保留配置类和行为能力时把描述符改写成 <code>declarative-process</code>。

## 复制后的精确替换表

先复制整个 <code>minimal-feature-plugin</code> 目录。按下表替换所有文本；包名替换后还要把 Java 源码与测试目录移动到匹配的目录层级。

| 模板中的精确值 | 替换为 |
| --- | --- |
| <code>example-minimal-plugin</code> | 你的 Maven <code>artifactId</code> |
| <code>example-minimal</code> | 你的小写短横线插件 id；同时用于 URL 与 i18n namespace |
| <code>com.example.pixivdownload.minimal</code> | 你的 Java 包名 |
| <code>com/example/pixivdownload/minimal</code> | 与新包名匹配的源码、测试目录 |
| <code>ExampleMinimal</code> | 你的 Java 类型名前缀 |
| <code>0.1.0</code> | 插件项目版本与 <code>plugin.version</code> |
| <code>plugin.requires=1.0</code> | 目标宿主的 major.minor 契约版本；只替换这一整行，不要误改 <code>1.0.0</code> |
| <code>&lt;pixivdownload.sdk.version&gt;1.0.0-rc4&lt;/pixivdownload.sdk.version&gt;</code> | 构建环境提供的 SDK 版本 |
| <code>plugin.provider=Example Developer</code> | 你的 provider 名称 |

最后修改各正式语言 i18n 文件中的展示文案，并再次运行 <code>mvn verify</code>。不要只改 <code>plugin.properties</code>：feature id、route、static、namespace 和测试中的对应值必须同步替换。

## 运行时边界

桌面 GUI 使用 `DesktopUiToolHost.findToolLog(fileName)` 只读查找已有 HTML 日志，再将返回路径的 URI 交给 `DesktopUiHost.openExternalUri`。文件名来自日志会话或工具历史；内置工具的最新日志使用 `stem-latest.html`。查询不创建目录、轮换文件或打开浏览器，文件尚未生成或已被轮换删除时返回空值。系统打开应在 GUI 工作线程执行。该能力要求 SDK 1.3。

`openToolLog(stem)` 连接宿主内置回填和旧 JSON 迁移的日志源，文件主干名不能指定插件日志源。插件自身的日志继续使用宿主提供的日志实现；不要为了查看日志创建写入会话，也不要从 `RuntimePathProvider` 的配置、状态或数据目录推导宿主日志路径。

媒体提供者通过可选 `DesktopMediaTool.CompletionSource` 交付已接纳执行的终态，拒绝提交时不通知。后台工作使用当前 owner 的 `PluginRuntimeTaskRegistrar` 登记并绑定取消句柄；关闭时先取消、排空，再释放观察者。宿主记录 `ToolHistoryEntry.executionId` 和 `sourceIdentity`，分别标识一次执行与提供者的精确 publication。`ToolId` 是历史业务类别，新增媒体提供者不需要增加枚举值。旧历史的身份字段为空，不按时间或作品编号补猜。

历史记录的日志文件名指向执行时关联的会话；文件被轮换删除时显示缺失，不改为打开 latest。下载恢复使用既有 `DownloadTasks.find(taskId, owner)` 查询精确执行的持久终态，`snapshot(owner)` 只列进程内快照；没有查到结果时保留待确认状态。

模板 POM 不继承本仓库根 parent，也不依赖 <code>pixivdownload-app</code> 或 <code>pixivdownload-plugin-runtime</code>。薄入口 <code>pixivdownload-sdk</code> 传递 SDK Info、Plugin API、Core API、PF4J、Spring context/web/webmvc、Servlet 和 Jackson 编译依赖。消费者将整个入口声明为宿主提供；这些共享依赖不能复制进插件 JAR，否则跨 classloader 的契约类型将不再相同。JUnit 由模板单独声明，测试库不属于 SDK 编译入口。原有三个 SDK 模块和 BOM 坐标仍保留。

其它构建工具可以直接消费同一 Maven 坐标，不需要导入 BOM：

```kotlin
// Gradle
dependencies {
    compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:1.0.0-rc4")
}
```

```scala
// sbt
libraryDependencies += "io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "1.0.0-rc4" % Provided
```

Ivy 使用 Maven 兼容 resolver，并将 SDK 的默认传递依赖映射到自己的编译配置；不要把这个配置加入运行或打包配置：

```xml
<configurations>
    <conf name="compile"/>
    <conf name="runtime"/>
</configurations>
<dependencies>
    <dependency org="io.github.sywyar.pixivdownloader" name="pixivdownload-sdk"
                rev="1.0.0-rc4" conf="compile->default"/>
</dependencies>
```

仓库的跨工具消费者检查使用同一份 Java 源码，验证三个 SDK 模块和框架类型的编译、SDK JAR 字节以及运行作用域。先构建 SDK 暂存仓库，再执行：

```text
node scripts/ci/sdk-build-tools.mjs --sdk-repository target/sdk-staging --sbt-launcher <sbt-launch.jar>
```

也可用 <code>--tool maven</code>、<code>--tool gradle</code> 或 <code>--tool sbt</code> 单独运行。Maven 和 Gradle 使用仓库 Wrapper；sbt 使用调用者提供的 launcher，版本由消费者的 <code>project/build.properties</code> 固定。检查在新的 <code>target/</code> 子目录中保存工程与缓存。Ivy 示例说明标准配置映射，不属于这三种工具的实测结果。

已验证 <code>plugin.properties</code> 中的 <code>pixiv.kind</code>、<code>pixiv.execution-mode</code>、<code>pixiv.lifecycle-policy</code> 和可选的 <code>pixiv.configuration-classes</code> 是运行边界的权威来源。<code>minimal-feature-plugin</code> 不声明配置类，宿主只注册独立 worker 返回的声明式贡献；<code>download-type-plugin</code> 的配置类只会在完全受信准入后用于插件专属子 <code>ApplicationContext</code>。下载模板的 <code>configurationClasses()</code> 仅为旧版宿主与 SDK 工具兼容而保留，并与描述符保持一致。完全受信插件 Bean 必须在配置类中用 <code>@Bean</code> 显式创建，不得依赖宿主根包扫描。

可用的稳定接缝限于 SDK Info、Plugin API、Core API 的公开契约、宿主提供的受控前端 context，以及宿主明确提供的规范依赖。以下内容不是本模板可用的第三方接缝：宿主 app/core 实现类、plugin-runtime/installer/signature 内部类、宿主 mapper、官方插件私有 service，以及依赖根上下文组件扫描的 Bean。插件私有持久化使用 owner-scoped <code>PluginDatabase</code>，通过 privateSchema 声明结构、通过 read / transaction 表达中性操作，并处理不可用状态；旧 <code>PluginDataSource</code> 只兼容 SQLite。参与已有主库协作时，仅通过 <code>PluginMainDatabase</code> 访问宿主授予的表或核心公开域；不要借用主库 JDBC、给核心表加列或自行执行宿主 schema DDL。

默认产物是无 <code>BOOT-INF/</code>、无内嵌 <code>lib/*.jar</code> 的 thin JAR。若插件以后需要第三方私有库，应先采用宿主明确支持的 PF4J 私有依赖打包方式并增加包边界测试；不要 shade 或打入 plugin-api、PF4J、Spring 或任何宿主类。
