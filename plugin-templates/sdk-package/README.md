# PixivDownloader 插件 SDK @SDK_VERSION@

[English](README_en.md)

解压后的根目录是独立 Maven 插件工程，源码位于 `src/`。SDK 身份为 `@SDK_RELEASE_ID@`，源码对应主仓库提交 `@SOURCE_SHA@`。API 文档入口为 `docs/javadocs/index.html`。

新 SDK 预发布版本使用 `alpha.N`、`beta.N`、`rc.N`，序号从 1 开始且不补零；工具兼容历史紧连后缀。Maven / Gradle / sbt 依赖版本及运行清单须保留所选 Release 的原始拼写，不能自行把 `rcN` 改成 `rc.N`。插件版本由插件自身维护。预发布 SDK 的 `plugin.requires` 值为 `=完整SDK版本`；稳定 SDK 使用 `major.minor` 兼容线。

在 properties 文件中，精确要求写成 `plugin.requires==7.2.3-rc.4`（版本仅为示例）。第一个等号分隔属性名和值，第二个表示精确合同。不同 RC、RC 与稳定版之间不承诺兼容；旧宿主不识别此格式时会在执行插件前拒绝。历史包若只声明 `1.0`，不能由此判断编译时的 RC，需要作者重新验证并发布新包。Nightly 插件继续绑定其配套宿主构建。

开发包自带 `.git/`，`main` 分支的初始提交包含全部交付文件，可直接用 `git status` 和 `git diff` 查看自己的修改。仓库未配置远端；提交自己的代码前，按需设置 Git 用户名、邮箱和远端地址。构建产物、IDE 本地配置与 `.dev/` 运行数据由 `.gitignore` 排除。

## 许可证与分发

SDK 采用 [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)，符合随包提供的[插件链接例外](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)条件的独立第三方插件可选择 MIT 许可证。

插件自身的许可证放在工程根目录 `LICENSE` 中。请保留 `licenses/pixivdownloader/` 中的上游许可及适用版权声明；复制示例另建工程时也应一并保留。

## 升级 SDK

1. 保留现有工程、源码和 `.dev/` 数据，将目标 SDK 开发包解压到另一个目录。先查看该版本说明和 Javadoc 中删除或改变的 API，再修改调用代码。
2. 在原工程中更新 Maven / Gradle / sbt 的 SDK 依赖及 `plugin.requires`。预发布版本使用精确要求；不要只把依赖版本改成新 RC。
3. 对比两份工程，将目标开发包的工具、合同资源和固定运行清单作为一套更新；逐项合并构建和 IDE 配置，保留自己的插件 ID、源码及数据。不要覆盖整个工程或只替换 `sdk-tools.jar`。
4. 执行原工程的 `clean verify`，再用目标运行包检查加载、启动、能力缺席、停用及重启。异步任务还须证明停止接收新工作、排空旧任务和释放资源；普通构建通过不能代替生命周期验证。
5. 使用新的插件版本生成候选并投稿。已发布的 SDK 与插件附件不可覆盖；不支持的组合应在插件执行前被拒绝。升级数据不代表支持降级，保留插件自身的格式约束。

## 数据迁移与启动失败

插件私有数据库通过当前 owner 的 `PluginDataSource` 访问，插件负责格式版本和迁移。把相关 DDL、数据变更与版本标记放在同一个 JDBC 事务内，成功后提交；遇到不认识的格式或迁移失败时回滚并停止启动受影响能力。不得删除数据库后重新建库，也不得把异常当成空数据继续运行。宿主安装事务保护插件包，不负责回滚插件业务数据；重新安装旧包也不能撤销已提交的数据迁移。

| 数据 | 维护约定 |
| --- | --- |
| 私有数据库 | 只访问本 owner 的 `PluginDataSource`；迁移可重试，版本标记只随成功事务提交。不要关闭宿主拥有的数据源。 |
| 普通配置、状态与文件 | 通过 owner-bound `RuntimePathProvider` 定位；先写同目录临时文件，验证成功后替换。清理旧来源前回读目标，冲突时保留原件。 |
| 凭证 | 使用宿主的 owner-scoped 凭证贡献与存储，不写进普通配置、日志、任务定义或 checkpoint。 |
| 计划定义、pending 与 checkpoint | 只迁移所属 schema/version；无法解释时保留旧输入并报告失败，不推进已完成位置。 |
| 宿主共享数据 | 只用公开语义端口；插件私有迁移不得修改宿主或其它插件的表。 |

在自己的测试中放入有记录的旧格式，注入一次迁移失败，再连续启动两次，核对旧记录、凭证和 checkpoint 未丢失且没有重复迁移。另验证不支持的格式会阻止功能启动，而管理页仍能报告诊断。安装成功、等待重启和功能已启动是不同状态；排障时记录插件版本、宿主 SDK、执行模式和安全错误码，移除插件默认保留数据。

## 生命周期验收

在独立工程执行 `mvnw.cmd clean verify`（Windows）或 `./mvnw clean verify`，再执行 `verify exec:exec@sdk-run` 启动配套宿主。用管理员账号打开插件管理页，记录包版本、generation、策略和诊断；停止整套开发实例使用 `exec:exec@sdk-stop`。

| 操作 | 应检查的结果 |
| --- | --- |
| 安装并启动 | 描述符与实际执行模式一致，所属路由、资源和能力可用。开发目录的 full-trust 转换不代表正式 worker 已验收。 |
| 支持运行中停止的插件执行 stop | 新工作被拒绝，旧工作按契约排空，路由与能力撤回；数据和未完成任务保留。 |
| 禁用 process-restart 插件 | 当前实例继续运行；完整退出后再启动时不再加载，页面应提示生效边界。 |
| 再启动 | 能力恢复，旧 publication 的令牌和句柄仍失效，不能转投新实例。 |
| reload | 仅适用于支持热重载的策略；物理 generation 和 classloader 换代，旧资源释放。 |
| process-restart 换包 | 安装结果提示等待重启；完整退出后再启动，核对真正加载的新版本。 |
| 故意启动失败 | 可选插件只隔离自身能力，管理页仍可移除或换包；required 限制由宿主决定。 |

`DownloadObserver`、`DownloadAdmissionPolicy`、`DownloadOptionsHook` 和 `DownloadSubmissionHandler` 是可选 full-trust Bean，在插件配置类中显式注册，由宿主按精确 publication 发布和撤回。插件实现接口即可，不需要继承下载器基类。使用前核对所选发行包的 Javadoc；当前源码中的接口不一定已包含在旧公开 SDK 中。

`DownloadTasks.submit` 接收作品类型、不透明作品键、输出选项和调用方生成的 `requestId`，返回宿主分配的 `attemptId`。同一进程的保留窗口内，相同请求标识及内容返回原任务，选项或凭据不同则报 `CONFLICT`。失败后主动发起新尝试须使用新的请求标识。提交只接受可信管理员身份，HTTP 消费方必须使用 `RequestOwnerIdentityResolver`；凭据通过独立参数传递，不能写入选项、事件或日志。

类型插件通过 `DownloadSubmissionHandler` 校验和解析自己的命令，把同一个 attempt 交给 `DownloadLifecycle.track` 与 `QueueTaskTracker.Task`，再提交所属执行通道。插件报告 `STARTED`，在文件和权威记录成功提交后报告 `COMPLETED`，失败报告 `FAILED`。排队取消和实际退出会补齐终态；收到取消请求不等于运行线程已经退出。同步本地导入使用 `register` 登记身份。

`find` 和 `snapshot` 提供当前状态，`cancel` 只操作捕获的那次队列任务。快照的 `epoch` 在宿主重启后改变，`revision` 在状态变化后递增。记录最多 4096 条，终态至少保留五分钟；满额时明确拒绝新任务，不丢弃活动任务。过期后不再保证请求去重。快照与在线事件均不提供跨进程恢复或持久重放。

事件包含 `ACCEPTED`、`QUEUED`、`STARTED`、`COMPLETED`、`FAILED`、`CANCELLED`。`ACCEPTED` 表示开始接纳，仍可能被后续校验拒绝；同步执行可以没有 `QUEUED`。观察回调在发布线程同步执行，可能并发，应保持短小且线程安全；普通观察异常不回滚已提交的事实。并发发布不承诺跨线程通知顺序，断线或重连后以任务快照为准。

准入规则允许或拒绝执行。选项 hook 按 `order`、插件 ID 和 Bean 名依次运行，输入和输出为不可变字符串 map，只能修改类型执行器明确开放的选项；最终仍由类型执行器验证。每份选项最多 32 项、累计 16 KiB UTF-8。规则或 hook 抛错、返回非法值，或调用前所属 publication 已撤回，都会拒绝本次执行。观察者适合完成通知等辅助动作，不能代替文件提交或必要后处理。

`WorkFileImporter` 登记只读源文件引用，类型 owner 缺席时返回明确失败。删除画廊记录不会删除所引用的原文件。

## 投稿排障

| 现象 | 下一步 |
| --- | --- |
| 编译成功但加载被拒绝 | 核对编译 SDK、`plugin.requires`、配套运行清单与实际宿主 SDK；不要编辑已签名包内描述符。 |
| `SDK_ARTIFACT_MISMATCH` | 保留诊断，重新取得同一发行物并核对摘要；不要混用不同版本的工具、资源和运行附件。 |
| 没有当前 CI 候选 | 检查源码默认分支的当前提交与 `Plugin candidate` 运行，以及 `tools/candidate-projects.json` 的工程选择；旧成功运行不能代替当前候选。 |
| 登录、权限或 Git 推送失败 | 核对当前 GitHub 身份、目标仓库与具体权限；保存原投稿记录。不要通过创建另一请求掩盖原请求的未知结果。 |
| 审核事实或所有权变化 | 回到向导读取当前绑定和原请求，重新核对来源、版本、签名与差异，再明确确认。历史签名不证明当前所有权。 |
| 下载或写入响应丢失 | 重新打开原工程与投稿记录，核对远端对象和向导提供的恢复选项；结果不明确时保留现场，不自动重复发布、推送或投稿。 |

诊断材料应包含插件 ID、版本、源码提交、SDK 身份、失败步骤和错误码。分享前去掉凭据、私钥、签名下载 URL 的查询参数及个人路径。

## 开始开发

根 Maven 工程和三个 `examples/` 工程各自包含已纳入 Git 的 `.pixivdownloader-plugin-project`。该文件只标识选中工程的格式，不证明身份或安全；`sdk-project.json` 另行固定开发环境。插件 JAR、`.dev/` 和运行附件不包含工程标识。

开发者在包内 `plugin.properties` 的 `pixiv.risk-signals` 中声明能力，使用逗号分隔的 token。根工程、Gradle 和 sbt 示例是显式空声明；下载类型示例声明 `HOST_DATA_ACCESS`，对应其使用的宿主身份与任务上下文。添加行为时一并更新声明；缺失、空值或没有扫描命中均不代表安全，也不授予运行时权限。

安装 JDK 17 和 Node.js，让 `java`、`node` 可从命令行调用。IDE 导入只解析工程。显式 Run / Debug 才编译当前插件、准备固定运行包并启动完整应用；构建失败会中止启动。Maven Wrapper 会取得固定版本的 Maven，无需克隆宿主仓库或手工复制宿主和官方插件。首次应用配置使用宿主自己的 setup 流程。

Linux / macOS 的 Maven Wrapper 始终下载配置中的 ZIP 并验证 SHA-256。未安装 `unzip` 时，使用 `JAVA_HOME/bin/jar` 或 `PATH` 中的 JDK `jar` 解压；两者都不可用时会在下载前报错。

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
./mvnw verify exec:exec@sdk-run
./mvnw verify exec:exec@sdk-debug
./mvnw exec:exec@sdk-stop
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

Windows 使用 `mvnw.cmd` / `gradlew.bat`；Linux / macOS 使用 `./mvnw` / `./gradlew`。每个示例单独导入，拥有自己的 `sdk-project.json` 和 `.dev/`，共用根目录 `tools/sdk-tools.jar`。Gradle Wrapper 固定为 9.5.0，sbt 工程固定为 1.10.11。Gradle / sbt 示例提供编译、打包和 JavaScript 语法检查；Maven 工程另含 JUnit 与 thin JAR 验证。

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

## 社区格式与资源

`contracts/community/v1/` 提供社区 JSON Schema、能力声明 token、市场分类与标签、许可证模板，以及签名和数据校验向量。填写 `pixiv.risk-signals` 时查阅其中的 `catalogs.json`；许可证模板可按项目需要选用，许可证声明不受模板清单限制。

`bundle-manifest.json` 固定每份资源的大小和 SHA-256，并记录工具版本。`tools/community-contract.json` 记录 SDK、源码提交、合同版本、资源清单摘要及本次 `sdk-tools.jar` 的大小与摘要。消费这些资源时固定完整发行物和摘要；更新时使用同一发行物中的工具与资源，不单独替换目录文件。它们随开发包进入初始 Git 提交，不进入插件 JAR 或公共 Maven 编译依赖。

## 市场说明文档与链接

投稿向导可以附带 README、完整 CHANGELOG 和当前版本更新说明。仓库文件取自候选绑定的源码提交；也可明确选择本地文件或直接输入多行文本。README 可省略，支持 UTF-8 Markdown 与 HTML。相对图片只从选定目录读取，外部图片须确认下载后才能随文档发布。

插件的 `CHANGELOG.md` 使用二级版本标题、三级分类，例如：

```markdown
## [v8.2.6] - 2031.3.2

### Features

- 新增导出功能。

### Bug Fixes

- 修复重复导出。
```

向导精确提取当前插件版本并展示预览。缺失或重复版本标题时，可重选文件、手工输入或明确不提供；不会把 Unreleased 或其它版本当成本次更新。多行输入中 Enter 换行，Tab 选择确认后按 Enter 完成；Ctrl+B 返回，Ctrl+S 保存。

链接支持仓库主页、使用文档、问题提交和自定义用途，可修改、删除或全部留空。向导按本次默认语言收录文档和自定义标题；默认语言参与市场文字与文档的回退。

文档与图片按大小和 SHA-256 冻结，确认投稿后先进入来源仓库的市场内容附件 Release，审核后与插件包一起进入社区的同一个版本 Release。源码候选的两附件协议保持不变。HTML 仅作为静态文档展示，脚本、表单、事件处理器及自定义样式不会执行。

这些字段需要匹配的 SDK 投稿工具、社区部署和支持内容展示的宿主。社区尚未升级固定工具时会提示升级，开发包版本本身不能证明社区已部署。旧插件仅补充市场资料无需提高 `plugin.requires`；SDK 1.1 宿主仍接受 `requires=1.0`。新模板声明的是所选 SDK 的 major.minor，请按实际使用的 API 确定运行要求。

## CI 候选与投稿

将工程推送到公开 GitHub 仓库的默认分支，等待 `Plugin candidate` 工作流全部通过，再按[社区投稿说明](https://github.com/Sywyar/PixivDownloader-community-plugins#投稿与版本管理)运行向导。CI 会测试插件、比较离线重建的包，并为每个插件复用一个 Draft Release，覆盖其中的候选产物；无需手动下载附件或创建 Release。向导确认投稿后，将已核对的包保存为绑定源码提交的 Pre-release，等待社区审核。

CI 默认构建根工程，自动识别 Maven、Gradle 或 sbt。构建模型决定实际版本及安装产物路径。若要投稿某个示例、选择多个工程，或工程有多种构建方式，在 `tools/candidate-projects.json` 中明确选择，例如：

```json
[
  { "projectDir": "examples/gradle-plugin", "profileId": "gradle-java17-v1" }
]
```

可用配置为 `maven-java17-v1`、`gradle-java17-v1` 和 `sbt-java17-v1`。模型有多个安装产物时，再增加 `artifactPath`，其值相对所选工程目录，必须属于模型的实际输出。一个插件 ID 只能选择一个候选工程。修改配置后提交并推送，等待新 CI 结果。

Draft 使用 `candidate-<插件ID>`，其中的 `source-candidate.json` 记录源码提交、CI 运行、工程、构建方式及包摘要。默认分支的新构建会替换包和元数据，旧提交的迟到运行不会覆盖较新的候选。Actions 附件过期不影响 Draft；需要恢复归档时须使用默认分支当前提交。确认投稿后生成的固定 Pre-release 不随 CI 覆盖，正在审核的包保持不变。源码 CI 通过不表示社区已批准。

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
