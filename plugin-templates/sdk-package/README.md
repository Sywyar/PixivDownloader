# PixivDownloader 插件 SDK @SDK_VERSION@

[English](README_en.md)

解压后的根目录是独立 Maven 插件工程，源码位于 `src/`。SDK 身份为 `@SDK_RELEASE_ID@`，源码对应主仓库提交 `@SOURCE_SHA@`。API 文档入口为 `docs/javadocs/index.html`。

开发包自带 `.git/`，`main` 分支的初始提交包含全部交付文件，可直接用 `git status` 和 `git diff` 查看自己的修改。仓库未配置远端；提交自己的代码前，按需设置 Git 用户名、邮箱和远端地址。构建产物、IDE 本地配置与 `.dev/` 运行数据由 `.gitignore` 排除。

## 开始开发

根 Maven 工程和三个 `examples/` 工程各自包含已纳入 Git 的 `.pixivdownloader-plugin-project`。该文件只标识选中工程的格式，不证明身份或安全；`sdk-project.json` 另行固定开发环境。插件 JAR、`.dev/` 和运行附件不包含工程标识。

开发者在包内 `plugin.properties` 的 `pixiv.risk-signals` 中声明能力，使用逗号分隔的 token。根工程、Gradle 和 sbt 示例是显式空声明；下载类型示例声明 `HOST_DATA_ACCESS`，对应其使用的宿主身份与任务上下文。添加行为时一并更新声明；缺失、空值或没有扫描命中均不代表安全，也不授予运行时权限。

安装 JDK 17 和 Node.js，让 `java`、`node` 可从命令行调用。IDE 导入只解析工程。显式 Run / Debug 才编译当前插件、准备固定运行包并启动完整应用；构建失败会中止启动。Maven Wrapper 会取得固定版本的 Maven，无需克隆宿主仓库或手工复制宿主和官方插件。首次应用配置使用宿主自己的 setup 流程。

| IDE | 导入 | 运行 | 调试 |
| --- | --- | --- | --- |
| IntelliJ IDEA | 打开根 `pom.xml` | 选择 `Developer Mode`，点击 Run | 选择同一个 `Developer Mode`，点击 Debug |
| VS Code | 打开本目录，安装推荐的 Java Extension Pack | `Tasks: Run Task > Run Plugin` | `Run and Debug > Debug Plugin` |
| Eclipse | `Import > Existing Maven Projects` | `eclipse/Run Plugin.launch` | `eclipse/Debug Plugin.launch` 启动组 |

IntelliJ 的 `Developer Mode` 是原生 Application 配置，启动前由 Maven 执行 `test-compile exec:exec@sdk-prepare`。IDE 直接启动 JVM，在同一进程中运行宿主、配套官方插件和当前工程的 `target/classes`。在 `ExampleMinimalPlugin.java` 的 `routes()` 内设置断点，再点击 Debug 即可，无需远程连接或固定调试端口。入口 `src/test/java/sdk/DevelopmentLauncher.java` 不进入插件 JAR；运行配置把随包工具加入启动 classpath，以便解析 Spring Boot 嵌套 JAR。

此配置显式启用插件开发模式：当前源码按 `host-process-full-trust` 执行，运行状态如实显示该模式，源描述符保持原样。重新 Run / Debug 会重新编译并加载源码。结束时使用 `Stop Plugin`，或停止 Application 会话。

VS Code、Eclipse 和下述命令行任务使用打包验证流程，安装当前 JAR 并保留其声明的执行模式。它们的远程调试地址默认为 `127.0.0.1:5005`；`declarative-process` 连接插件 worker，`host-process-full-trust` 连接宿主。使用 `Stop Plugin` 或停止整个组合来结束应用，单独断开远程调试连接不会停止应用。

## 命令行

Windows：

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd verify exec:exec@sdk-debug
.\mvnw.cmd exec:exec@sdk-stop
```

Linux / macOS：

```bash
sh ./mvnw verify exec:exec@sdk-run
sh ./mvnw verify exec:exec@sdk-debug
sh ./mvnw exec:exec@sdk-stop
```

`sdk-debug` 等待 IDE 附加，不自行打开调试器。只验证插件使用 `clean verify`，只准备运行包使用 `exec:exec@sdk-prepare`。默认产物为 `target/example-minimal-plugin-0.1.0.jar`。

已成功构建本次产物后，也可直接调用随包工具：

```text
java -jar tools/sdk-tools.jar run <工程绝对路径> <本次插件JAR绝对路径> --no-gui
java -jar tools/sdk-tools.jar debug <工程绝对路径> <本次插件JAR绝对路径> --debug-port=5005
java -jar tools/sdk-tools.jar stop <工程绝对路径>
```

`--debug-connect` 用于连接已监听的 IDE。`run` / `debug` 只接受工程内、`.dev/` 外的产物，并保留描述符中的执行模式，使用本次 JAR 的 SHA-256 完成本地安装确认。官方插件在开发和打包验证时均验证原签名及 provenance。请使用唯一插件 ID，避免与配套插件冲突。

## 独立示例

| 目录 | 用途 | 运行 / 调试 / 停止 |
| --- | --- | --- |
| `examples/download-type-plugin/` | 下载类型、队列、计划来源和插件自有画廊 | 在 SDK 根运行 `mvnw -f examples/download-type-plugin/pom.xml verify exec:exec@sdk-run`；调试改为 `sdk-debug`，停止只执行 `exec:exec@sdk-stop` |
| `examples/gradle-plugin/` | 用 Gradle 构建基础功能插件 | 进入目录执行 `gradlew runPlugin`、`gradlew debugPlugin`、`gradlew stopPlugin` |
| `examples/sbt-plugin/` | 用 sbt 构建同一基础功能插件 | 安装 sbt 后进入目录执行 `sbt runPlugin` 或 `sbt debugPlugin`；停止使用另一终端执行 `java -jar ../../tools/sdk-tools.jar stop .` |

Windows 使用 `mvnw.cmd` / `gradlew.bat`；Linux / macOS 使用 `sh ./mvnw` / `sh ./gradlew`。每个示例单独导入，拥有自己的 `sdk-project.json` 和 `.dev/`，共用根目录 `tools/sdk-tools.jar`。Gradle Wrapper 固定为 9.5.0，sbt 工程固定为 1.10.11。Gradle / sbt 示例提供编译、打包和 JavaScript 语法检查；Maven 工程另含 JUnit 与 thin JAR 验证。

## 单个 SDK 依赖

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>@SDK_VERSION@</version>
    <scope>provided</scope>
</dependency>
```

Gradle 使用 `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:@SDK_VERSION@")`，sbt 使用 `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "@SDK_VERSION@" % Provided`。标准 Ivy 可映射编译配置：

```xml
<dependency org="io.github.sywyar.pixivdownloader" name="pixivdownload-sdk"
            rev="@SDK_VERSION@" conf="compile->default"/>
```

Ivy 的运行配置不要继承此编译配置。标准 Maven 元数据传递公开 API 及 PF4J、Spring、Servlet、Jackson 编译依赖；产物仍是 thin PF4J JAR，不将这些宿主提供类打包。测试框架自行声明，三个 API 模块和 BOM 仍可单独消费。

## 运行包、缓存和工程数据

`sdk-project.json` 与发行附件 `sdk-release.json` 记录同一套 SDK、宿主、官方插件清单及完整运行 ZIP 的固定身份、大小与 SHA-256。运行 ZIP 是 SDK Release 的独立附件，首次显式准备时下载，后续复用 `~/.cache/pixivdownloader-sdk/` 中的已校验缓存。清空缓存后仍取得相同字节；资源不可用或摘要不符会失败。

每次启动创建独立运行副本，并核对宿主及逐个官方插件。缓存不承载应用状态。工程 `.dev/` 保存配置、数据库、日志、下载和运行副本；配置及状态按运行包摘要隔离。此环境关闭宿主与官方插件自动更新，日常安装的应用数据不参与这条启动链。

停止应用后，可删除本工程的 `.dev/` 重置开发数据，这也会删除其中的下载文件。直接调用工具时，可通过 JVM 属性 `-Dpixivdownload.sdk.cache-dir=<目录>` 指定缓存位置；工程运行目录仍独立。

离线使用需要提前完成运行包准备和构建工具依赖缓存，两者互不替代。Maven 使用 `-o`，Gradle 使用 `--offline`；缓存不完整会失败。更新 SDK 时使用新版本开发包及配套清单，再迁入自己的源码。

Windows 下已实测 IntelliJ Application 一键 Debug 和当前源码断点，以及两个 Maven 示例的同进程启动、页面与正常停止。VS Code 和 Eclipse 的图形操作尚未实测。打包验证时，目录过深仍可能触及 Windows 创建 worker 时的工作目录长度限制，遇到错误 267 时请移到较短路径。运行包声明的其它平台需在对应操作系统上验收，不能以 Windows 结果代替。

## 修改插件

描述符位于 `src/main/resources/plugin.properties`。同步修改插件 ID、Java 包名、路由、i18n namespace、版本和 provider；Maven 运行任务读取实际 `finalName`。Eclipse 配置内的项目名需要与导入后的工程名一致。

稳定契约覆盖 route、static、i18n、navigation、Web UI slot、GUI 配置、下载类型、队列、计划来源和通知模板。`examples/download-type-plugin/README.md` 说明五类取得模式、取消与 drain、凭证策略、Guard 和 `gallery.type-switch`。各插件独立拥有画廊页面、API、静态资源和数据操作。

配置使用 `GuiConfigContribution`；私有路径使用 owner-bound `RuntimePathProvider`，数据库使用 `PluginDataSource`，出站 HTTP / WebSocket 使用稳定 factory 与 route 契约。不要依赖 app、plugin-runtime、installer、签名内部实现、宿主数据库或具体 GUI provider。禁用、卸载及 reload 的贡献撤回需在真实宿主中验证。
