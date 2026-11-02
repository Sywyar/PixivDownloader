# PixivDownloader 插件 SDK @SDK_VERSION@

[English](README_en.md)

解压后的根目录是独立 Maven 插件工程（源码位于 `src/`）。当前 SDK 发行标识为 `@SDK_RELEASE_ID@`，对应主仓库源码提交 `@SOURCE_SHA@`。离线 API 文档位于 `docs/javadocs/index.html`，完整开发指南见 [在线文档](https://sywyar.github.io/PixivDownloader/#/zh-cn/plugin-development)。

## 1. 快速运行与调试

安装 JDK 17+ 与 Node.js（确保 `java`、`node` 在 `PATH` 中；Linux / macOS 若无 `unzip`，Wrapper 会使用 JDK `jar` 解压）。IDE 导入工程仅解析依赖，执行显式 Run / Debug 才会编译当前插件、按需下载固定运行包（缓存于 `~/.cache/pixivdownloader-sdk/`）并启动隔离宿主（数据保存在工程 `.dev/`）：

| IDE | 导入方式 | 运行 | 调试 |
| --- | --- | --- | --- |
| IntelliJ IDEA | 打开根 `pom.xml` | 运行 `Developer Mode` | 调试 `Developer Mode`（同进程加载 `target/classes`） |
| VS Code | 打开本目录（安装 Java Extension Pack） | `Tasks: Run Task > Run Plugin` | `Run and Debug > Debug Plugin`（打包安装，默认连接 `127.0.0.1:5005`） |
| Eclipse | `Import > Existing Maven Projects` | `eclipse/Run Plugin.launch` | `eclipse/Debug Plugin.launch` 启动组 |

命令行构建与运行（Linux / macOS 将 `.\mvnw.cmd` 替换为 `./mvnw`）：

```powershell
# 仅构建并运行单元与 thin JAR 边界测试
.\mvnw.cmd clean verify

# 构建插件、准备运行包并启动开发宿主（调试改为 sdk-debug）
.\mvnw.cmd verify exec:exec@sdk-run

# 停止当前工程的开发宿主
.\mvnw.cmd exec:exec@sdk-stop
```

构建成功后也可直接调用 `tools/sdk-tools.jar`：

```text
java -jar tools/sdk-tools.jar run <工程绝对路径> <本次插件JAR绝对路径> --no-gui
java -jar tools/sdk-tools.jar debug <工程绝对路径> <本次插件JAR绝对路径> --debug-port=5005
java -jar tools/sdk-tools.jar stop <工程绝对路径>
```

首次启动宿主后完成管理员初始化，在 `/plugin-manage.html` 核对示例插件已启动，并访问 `/example-minimal.html`。测试结束后请执行 `Stop Plugin`（或 `sdk-stop`）；停止后删除工程 `.dev/` 可重置开发测试数据。Windows 下若因路径过深触发 worker 启动错误 `267`，请将工程移至较短路径。

## 2. 附带的独立示例工程

下载类型、Gradle 与 sbt 示例拥有独立的 `.pixivdownloader-plugin-project`、`sdk-project.json` 与开发运行数据目录 `.dev/`，共用根目录 `tools/sdk-tools.jar`。类型化服务示例按其 README 构建和安装，不使用这套独立宿主启动配置。

下表命令使用 PowerShell；Linux / macOS 将 `.\mvnw.cmd` 替换为 `./mvnw`，将 `.\gradlew.bat` 替换为 `./gradlew`。类型化服务示例的工具路径须替换为当前开发包 `tools/sdk-tools.jar` 的绝对路径。

| 目录 | 说明 | 构建与运行命令 |
| --- | --- | --- |
| `examples/download-type-plugin/` | 下载类型、五类取得模式、队列、计划来源与独立画廊（见其目录内 `README.md`） | 在 SDK 根运行 `.\mvnw.cmd -f examples/download-type-plugin/pom.xml verify exec:exec@sdk-run`（停止用 `exec:exec@sdk-stop`） |
| `examples/typed-service-plugin/` | 跨插件独立契约 API、载体、提供者与远程调用消费者（见其目录内 `README.md`） | 在 SDK 根运行 `.\mvnw.cmd -f examples/typed-service-plugin/pom.xml package "-Dpixivdownload.sdk.tools=/absolute/path/to/tools/sdk-tools.jar"`，签名后先安装 `contract` 并完整重启，再安装 `provider` / `consumer` |
| `examples/gradle-plugin/` | 使用 Gradle（Wrapper 9.5.0）构建声明式功能插件 | 进入该目录运行 `.\gradlew.bat runPlugin`，调试或停止时将任务名替换为 `debugPlugin` 或 `stopPlugin` |
| `examples/sbt-plugin/` | 使用 sbt（1.10.11）构建声明式功能插件 | 进入该目录运行 `sbt runPlugin` 或 `sbt debugPlugin`；在另一终端用 `java -jar ../../tools/sdk-tools.jar stop .` 停止 |

## 3. 依赖声明、兼容性与定制规则

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>@SDK_VERSION@</version>
    <scope>provided</scope>
</dependency>
```

Gradle 使用 `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:@SDK_VERSION@")`，sbt 使用 `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "@SDK_VERSION@" % Provided`，Ivy 使用 `<dependency org="io.github.sywyar.pixivdownloader" name="pixivdownload-sdk" rev="@SDK_VERSION@" conf="compile->default"/>`（运行配置勿继承编译配置）。

- **版本与 `plugin.requires`**：稳定 SDK 写 `plugin.requires=major.minor`；预发布 SDK 写 `plugin.requires==完整SDK版本`（如 `plugin.requires==7.2.3-rc.4`，要求精确匹配，不同 RC 及 RC 与稳定版默认不兼容）。升级 SDK 时，请将新开发包解压到独立目录，成套更新编译依赖、`plugin.requires`、`tools/`、`contracts/community/v1/` 与 `sdk-project.json`。
- **定制插件身份**：修改 `src/main/resources/plugin.properties` 时，同步替换插件 ID、Java 包名、路由、i18n namespace、版本号、`plugin.provider` 及 `pixiv.risk-signals`（查阅 `contracts/community/v1/catalogs.json`）。
- **核心架构约束**：
  - **路径、凭据与网络**：配置通过 `GuiConfigContribution` 声明；私有目录通过 `RuntimePathProvider` 获取；敏感凭据通过 `credentialKeys()` 与 `PluginCredentials` 管理；出站 HTTP / WebSocket 使用 `OutboundHttpClientFactory` / `OutboundWebSocketClientFactory`。
  - **数据库与迁移**：私有持久化通过 `PixivFeaturePlugin.privateSchema()` 与 `privateStorageMigrations()` 声明，注入 `PluginDatabase` 使用 `read` / `transaction` 与 `Query` / `Expression` 无方言操作（支持 SQLite 与 PostgreSQL，以及通过 `DatabaseMigrationProvider` 的停机双向迁移）；参与宿主主库受管表或 `sharedMainStorage()` 协作时使用 `PluginMainDatabase`。旧 `PluginDataSource` 仅兼容 SQLite。
  - **下载任务与外部文件导入**：可选注册 `DownloadSubmissionHandler`、`DownloadOptionsHook`、`DownloadAdmissionPolicy` 与 `DownloadObserver` Bean（通过 `DownloadTasks.submit` / `DownloadLifecycle` 协作，解析请求身份须使用 `RequestOwnerIdentityResolver`）；只读外部文件登记使用 `WorkFileImporter`。
  - **生命周期验收**：发布前须在管理页逐项验证安装启动、`stop`（拒绝新工作、排空在途任务、撤回路由与能力）、`reload`（换代释放旧 ClassLoader）以及 `process-restart` 插件的完整退出重启生效边界。

## 4. 社区候选 CI 与投稿

开发包自带初始 Git 树（含 `.pixivdownloader-plugin-project` 与 `.github/workflows/candidate.yml`）：

1. 配置你自己的 `LICENSE` 与 Git 远端，推送到公开 GitHub 仓库默认分支，等待 `Plugin candidate` 工作流全部通过（若需指定子工程或构建工具，可在 `tools/candidate-projects.json` 配置 `projectDir` 与 `profileId`：`maven-java17-v1` / `gradle-java17-v1` / `sbt-java17-v1`）。
2. 按[社区插件仓库投稿说明](https://github.com/Sywyar/PixivDownloader-community-plugins/blob/master/README.md#投稿与版本管理)运行 PowerShell 投稿向导，核对版本、能力声明、许可证并可选附加 `README`、外链与按 `## [v版本]` 提取的 `CHANGELOG.md` 更新说明。

## 5. 许可证

SDK 采用 [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)。符合 `licenses/pixivdownloader/PLUGIN-LINKING-EXCEPTION.txt` 条件的独立第三方插件可选用 MIT 等开源许可证（分发或复制示例工程时请保留 `licenses/pixivdownloader/` 中的上游许可文本）。
