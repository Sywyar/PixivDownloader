# PixivDownload 外置插件模板

此目录中的项目是可复制的第三方插件起点，也是仓库内的模板验证工程；它们不会加入正式产品 reactor，也不会进入应用发行包。

目录包含两个独立子项目：

- <a href="minimal-feature-plugin/README.md"><code>minimal-feature-plugin</code></a>：不含站点业务逻辑的基础 route/static/i18n/schema/controller 插件；
- <code>download-type-plugin</code>：下载工作台 contract version 1、队列操作、计划来源、Vue 槽位与独立画廊示例。

模板目标是提供一套稳定、高可用、易上手的 SDK 起点：稳定来自版本化公共契约和 owner/publication 边界，高可用来自失败隔离、fail-closed 与真实生命周期清退，易上手来自可复制工程、命名工厂和确定性守卫。目录名中的 `minimal` 只表示示例不携带站点业务逻辑，不表示删减安全、生命周期或降级路径来追求文件更少。

## 基础功能插件

<code>minimal-feature-plugin</code> 演示一个没有站点业务逻辑的 thin PF4J 插件，包含：

- 根部 <code>plugin.properties</code>、PF4J 主类与 <code>PixivPluginProvider</code>；
- <code>PixivFeaturePlugin</code> 的管理员路由、静态资源、i18n 与插件自有 schema 声明；
- 由插件子 <code>ApplicationContext</code> 显式装配的基础 <code>@RestController</code>；
- 独立 HTML/CSS/JS 页面、明暗主题变量和中英文资源；
- descriptor、贡献对象、Spring 配置、JavaScript 语法和 thin JAR 守卫。

仓库内验证需要 JDK 17、Maven 和可从命令行调用的 Node.js：

    mvn -f plugin-templates/pom.xml verify

这个小 reactor 仅把同仓的 <code>../pixivdownload-plugin-api</code> 作为待构建契约模块加入验证。复制到仓库外时，两个子项目仍是无相对 parent 的独立 POM，但构建环境必须能从本地或团队 Maven 仓库解析 <code>top.sywyar.lovepopup:pixivdownload-plugin-api:1.0.0</code>；不要改成引用宿主源码目录或应用模块。

产物位于 <code>plugin-templates/minimal-feature-plugin/target/example-minimal-plugin-0.1.0.jar</code>。将复制并改名后的插件 JAR 通过插件管理页安装，或放入宿主的运行期 <code>plugins/</code> 目录；两种方式都受宿主的包验证与本地未签名策略约束，模板不包含签名、信任根或 installer 内部实现。插件启用后可由管理员直接访问 <code>/example-minimal.html</code>。

下载类型模板有自己的 [README](download-type-plugin/README.md) 和精确替换表，产物位于 <code>plugin-templates/download-type-plugin/target/example-download-plugin-0.1.0.jar</code>。它演示宿主可信用户 owner 解析、宿主桥接以 JSON 承载 <code>queueType + opaque workKey + descriptor publication identity</code> 的定向取消、受控队列状态提交、quick 结果发布、owner-scoped UI action，以及不触碰宿主 DOM 的计划来源输入 / 回灌 context；站点数据、队列和画廊仍是确定性内存示例。复制时必须用真实、合法且同步完成后才报告成功的领域实现替换，不得把模拟响应当作生产下载器。

## 复制后的精确替换表

先复制整个 <code>minimal-feature-plugin</code> 目录。按下表替换所有文本；包名替换后还要把 Java 源码与测试目录移动到匹配的目录层级。

| 模板中的精确值 | 替换为 |
| --- | --- |
| <code>example-minimal-plugin</code> | 你的 Maven <code>artifactId</code> |
| <code>example-minimal</code> | 你的小写短横线插件 id；同时用于 URL 与 i18n namespace |
| <code>example_minimal</code> | 你的插件自有 SQL 名称前缀；只使用小写字母、数字与下划线 |
| <code>com.example.pixivdownload.minimal</code> | 你的 Java 包名 |
| <code>com/example/pixivdownload/minimal</code> | 与新包名匹配的源码、测试目录 |
| <code>ExampleMinimal</code> | 你的 Java 类型名前缀 |
| <code>0.1.0</code> | 插件项目版本与 <code>plugin.version</code> |
| <code>plugin.requires=1.0</code> | 目标宿主的 major.minor 契约版本；只替换这一整行，不要误改 <code>1.0.0</code> |
| <code>&lt;pixivdownload-plugin-api.version&gt;1.0.0&lt;/pixivdownload-plugin-api.version&gt;</code> | 构建环境提供的兼容 plugin-api 工件版本 |
| <code>plugin.provider=Example Developer</code> | 你的 provider 名称 |

最后修改两份 i18n 文件中的展示文案，并再次运行 <code>mvn verify</code>。不要只改 <code>plugin.properties</code>：feature id、route、static、namespace、schema owner 和测试必须保持一致。

## 运行时边界

模板 POM 不继承本仓库根 parent，也不依赖 <code>pixivdownload-app</code>、<code>pixivdownload-core-api</code> 或 <code>pixivdownload-plugin-runtime</code>。<code>pixivdownload-plugin-api</code>、PF4J 与 Spring 均为 <code>provided</code>：它们由宿主父 classloader 提供，不能复制进插件 JAR，否则跨 classloader 的契约类型将不再相同。

<code>configurationClasses()</code> 返回的配置类由宿主放入该插件专属的子 <code>ApplicationContext</code>。插件 Bean 必须在配置类中用 <code>@Bean</code> 显式创建；不要依赖宿主根包扫描。模板没有使用运行时内部的 <code>@ConditionalOnPluginEnabled</code>：外置插件只有在启用并建立子上下文后，这些 Bean 才会存在。

可用的稳定接缝限于 <code>pixivdownload-plugin-api</code> 暴露的契约、宿主提供的受控前端 context，以及宿主明确提供的规范依赖。以下内容不是本模板可用的第三方接缝：宿主 app/core 实现类、plugin-runtime/installer/signature 内部类、宿主 mapper 或数据源、官方插件私有 service，以及依赖根上下文组件扫描的 Bean。示例 schema 只声明插件自有表；不要给核心表加列，也不要直接执行建表 DDL。

默认产物是无 <code>BOOT-INF/</code>、无内嵌 <code>lib/*.jar</code> 的 thin JAR。若插件以后需要第三方私有库，应先采用宿主明确支持的 PF4J 私有依赖打包方式并增加包边界测试；不要 shade 或打入 plugin-api、PF4J、Spring 或任何宿主类。
