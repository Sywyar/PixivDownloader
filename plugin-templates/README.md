# PixivDownloader 外置插件模板

本目录提供可复制的第三方插件工程起点及仓库内模板验证 Reactor（这些模板不属于正式产品 Reactor，也不会打入应用发行包）。复制、改编模板及许可选择见[插件链接例外](../PLUGIN-LINKING-EXCEPTION.txt)。

| 模板目录 | 适用场景与执行模式 |
| --- | --- |
| [`minimal-feature-plugin`](minimal-feature-plugin/README.md) | 仅贡献声明式路由、静态资源与 i18n 的薄插件（`declarative-process` + `hot-reload`，运行在独立 worker JVM，无 Spring 子上下文） |
| [`download-type-plugin`](download-type-plugin/README.md) | 新增下载作品类型、五类取得模式、队列控制、计划来源、Vue 槽位与独立画廊（`host-process-full-trust` + `process-restart`，使用确定性内存模拟数据） |
| [`typed-service-plugin`](typed-service-plugin/README.md) | 跨插件独立契约 API、契约载体、服务提供者与管理员 HTTP 远程操作消费者 |

## 1. 构建与验证模板

安装 JDK 25+、Maven 与 Node.js，并准备与模板 SDK 版本匹配的开发包。在主仓库根目录执行验证 Reactor，将下列工具路径替换为该开发包中 `sdk-tools.jar` 的绝对路径（`typed-service-plugin` 需要它生成 Schema 与远程调用适配器）：

```bash
mvn -f plugin-templates/pom.xml clean verify "-Dpixivdownload.sdk.tools=/absolute/path/to/tools/sdk-tools.jar"
```

确认退出码为 `0` 且在对应 `target/` 生成 thin PF4J JAR（如 `minimal-feature-plugin/target/example-minimal-plugin-0.1.0.jar` 和 `download-type-plugin/target/example-download-plugin-0.1.0.jar`）。在匹配 SDK 的宿主插件管理页上传安装，核对插件状态后分别访问 `/example-minimal.html` 或 `/example-download-gallery.html`。复制到仓库外的 `minimal-feature-plugin` 或 `download-type-plugin` 可在自身目录运行 `mvn clean verify`；`typed-service-plugin` 的工具参数及安装顺序见其 [README](typed-service-plugin/README.md)。

## 2. 复制模板后的替换清单

以 `minimal-feature-plugin` 为例，复制目录后统一替换以下标识，并将源码与测试目录迁移到新 Java 包路径（`download-type-plugin` 的替换清单见其目录内 [README](download-type-plugin/README.md)）：

| 模板原值 | 替换为 |
| --- | --- |
| `example-minimal-plugin` | 你的 Maven `artifactId` |
| `example-minimal` | 全局唯一的小写短横线插件 ID、URL 前缀与 i18n namespace |
| `com.example.pixivdownload.minimal` | 你的 Java 包名及对应源码/测试目录层级 |
| `ExampleMinimal` | 你的 Java 类名前缀 |
| `0.1.0` | 插件 artifact 版本与 `plugin.version` |
| `<pixivdownload.sdk.version>1.4.0</pixivdownload.sdk.version>` 与 `plugin.requires=1.4` | 目标 SDK 版本；稳定 SDK 填 `major.minor`，预发布 SDK 填 `=完整SDK版本`（如 `plugin.requires==7.2.3-rc.4`） |
| `plugin.provider=Example Developer` | 你的发布者名称 |

## 3. 依赖与公共契约边界

模板 POM 不继承主仓库 parent，仅声明单个 `io.github.sywyar.pixivdownloader:pixivdownload-sdk`（`provided`）依赖（传递 `sdk-info`、`plugin-api`、`core-api` 及宿主提供的 PF4J、Spring、Servlet、Jackson 编译依赖，不打入插件 JAR）。其他构建工具可直接消费同一坐标：

```kotlin
// Gradle
dependencies {
    compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:1.4.0")
}
```

```scala
// sbt
libraryDependencies += "io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "1.4.0" % Provided
```

```xml
<!-- Ivy（仅映射编译配置，勿加入 runtime 配置） -->
<dependency org="io.github.sywyar.pixivdownloader" name="pixivdownload-sdk" rev="1.4.0" conf="compile->default"/>
```

跨构建工具（Maven / Gradle / sbt）编译与作用域检查命令：

```bash
node scripts/ci/sdk-build-tools.mjs --sdk-repository target/sdk-staging --sbt-launcher <sbt-launch.jar>
```

- **执行模式与装配**：`plugin.properties` 中的 `pixiv.execution-mode`、`pixiv.lifecycle-policy` 与可选 `pixiv.configuration-classes` 为运行期权威声明。`host-process-full-trust` 插件必须通过配置类 `@Bean` 显式注册 Bean，禁止依赖宿主根包扫描。
- **架构禁区**：严禁依赖 `pixivdownload-app`、宿主内部实现、`plugin-runtime`/installer/signature 内部类、宿主 mapper 或官方插件私有类。私有持久化使用 owner-bound `PluginDatabase`（旧 `PluginDataSource` 仅支持 SQLite）；参与主库协作仅使用 `PluginMainDatabase`。默认产物为不含 `BOOT-INF/` 与宿主共享类的 thin JAR。
