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

插件通过 `PixivFeaturePlugin.privateSchema()` 声明当前 owner 的表、列、类型、主键、索引和约束，通过注入的 `PluginDatabase` 读写。宿主在 child context 创建业务 Bean 前交给所选提供者建表、安全补列和核验；声明不加入宿主主库。owner 来自可信插件身份，插件不选择物理路径、服务端 schema 或其它 owner。`PluginDatabase` 不继承 `DataSource`，业务代码不按 `databaseId()` 编写分支。

通过 `read` 与 `transaction` 使用同线程、回调结束即失效的 `DatabaseSession`。操作失败后即使捕获异常，事务也只能回滚；read 拒绝写入，不支持嵌套回调。私库事务不参加宿主主库或 Spring 默认事务。先检查 `available()`，仍须处理执行中的数据库失效；语义查询返回带不可用状态的空结果，写入明确失败并停止后续下载、文件删除或任务推进。不可用时不创建替代数据库。宿主共享服务通过 `DatabaseAvailability` 暴露主库状态。旧 `PluginDataSource` 仅保留 SQLite 兼容入口，其它方言以 SQLState `0A000` 拒绝；已安装的旧插件需要作者改用新契约并重新构建，安装提供者不会翻译第三方 SQL。

使用 `Query` / `Expression` 表达投影、过滤、分组聚合、排序、分页、别名、内 / 左关联、派生查询、UNION 和关联子查询。写入支持 insert / update / delete、生成键、从查询插入及返回条件更新后的行，可用于领取任务与 CAS。冲突可报错、仅忽略唯一键冲突，或针对声明的唯一键合并已有值与 `Incoming` 值。LIKE 使用 `%` / `_` 通配符、`!` 转义及 ASCII 大小写折叠；排序须显式指定 NULL 位置，需要稳定分页时加入唯一键。`scan` 逐行消费完整结果，可由回调提前停止。当前契约不提供任意 SQL、外键声明或任意结构重建；未知操作、结构和有损类型转换明确失败。

LIKE 模式由业务调用方按 SDK 语义构造：字面 `!` 写成 `!!`，字面 `%` / `_` 写成 `!%` / `!_`；需要通配时保留 `%` / `_`。提供者负责翻译 SQL 方言和转义声明，不再次转义已经构造好的模式。

需要参与宿主已有主库协作的插件使用 `PluginMainDatabase`。它加入当前 Spring 主库事务，仅能访问宿主为该 owner 登记的主库表，以及通过 `sharedMainStorage()` 请求的核心公开域；读写权限由核心声明决定。未知域、跨 owner 访问和对只读域写入均拒绝，child context 关闭后句柄永久失效。普通插件私有事实仍使用 `PluginDatabase`，不能通过主库接口连接任意表。主库和私库采用相同的中性操作模型。

可再生正文索引通过 `StorageFeatures.TextIndex` 声明来源、身份与可见条件；软删除关联清理通过 `StorageFeatures.Cleanup` 声明。提供者创建并核验辅助对象，索引模式明确区分 `MATERIALIZED` 与 `SOURCE_QUERY`。需要让索引失败不影响权威写入时，用主库 `NESTED` 保存点隔离辅助调用；失败不会被接口自动忽略。未知辅助能力明确返回不支持。

必要的业务数据转换通过 `privateStorageMigrations()` 按顺序声明稳定 ID 和仅使用会话的转换回调。启动时结构变更、转换与 `_storage_migrations` 完成记录在一个事务中提交。已应用 ID 必须是声明的精确前缀，不得重命名、重排或复用 ID 改变含义。回调不得执行文件、网络或其它不可回滚副作用，也不能依赖尚未创建的业务 Bean。失败停止该能力，保留数据；重新安装旧包不能撤销已提交的升级。自动结构演进只支持经过核验的建表、补索引及安全补列，删除、重命名、改类型或不兼容约束明确拒绝。

停机迁移复用同一声明。SQLite 只升级工作副本并执行尚未应用的转换；PostgreSQL 导出要求源结构和转换记录已处于声明版本，待应用转换明确拒绝，须先在源提供者上成功启动相应插件。迁移不改写源业务数据，不替操作者切配置。已有私库缺声明会阻止迁移；包含转换历史的保留私库需要可信插件提供回调。切换与启动检查要求有效私库收据，并由可信源提供者复核源变化。主库与各私库分别提交，没有跨库原子性。

以下操作用于已声明 `(work_type, work_id)` 复合主键的表，不包含引擎判断：

```java
database.transaction(session -> {
    session.insert("imported_works", Map.of("work_type", "artwork",
            "work_id", workId, "imported_at", System.currentTimeMillis()),
            DatabaseSession.Conflict.ignore("work_type", "work_id"));
    return null;
});
long count = database.read(session ->
        (Long) session.query(Query.count("imported_works", null)).get(0).get("count"));
```

提供者实现 `DatabaseConnectionTarget` 的结构应用、自省、物理类型映射、维护与会话操作。`applySchema` / `initializeStorage` 必须遵守调用方事务，不得隐式提交；不支持事务性 DDL 时须在任何修改前拒绝。业务转换不能被 DDL 提前提交。可选的 `storage.relational.jdbc.JdbcDatabaseSession` 复用参数绑定与校验，提供者可覆盖完整语句或自行实现会话；宿主和业务插件不增加引擎分支。新增提供者仍须通过真实数据库的行为、隔离、失败和迁移测试。

`DatabaseConnectionSettings` 传递应用数据根与历史目录，文件名、WAL 等附属文件及服务端 schema 均由提供者映射。`resolve` 只定位；`prepareStorage` 在实例锁与提供者选择固定后准备位置；`localFileSettings` 解释管理员显式指定的文件，非文件提供者应拒绝。owner 私库仍通过宿主绑定的 `DatabasePrivateStorageSettings` 定位，不向业务插件开放这些管理入口。

数据库提供者必须实现 `DatabaseMigrationProvider` 的必需迁移方法，不得返回假成功。`inspectReplacement` / `replaceDatabase` 是可选的非空目标替换能力，默认抛出 `TARGET_REPLACEMENT_UNSUPPORTED`；宿主不能自行清空目标来替代它们。

必需方法包括只读预检 `inspectMigration`、完整回读核验 `verifyImported`，以及以下迁出、迁入和私库发现方法：

- `privateDatabaseOwners` 枚举当前安装的既有 owner 私库，包括声明已缺失的 owner；`retainedLocalPrivateDatabases` 只读发现本提供者拥有的本地私库，远程提供者无本地存储时返回空映射。
- `exportDatabase` 锁定源，拒绝未知对象，生成耐久一致备份，返回 `DatabaseExportSession`。结构升级和种子行只能作用于副本；会话关闭释放源锁，保留备份。`verifySource` 根据提供者自己的版本化凭据核对原源，不能假设源一定是本地文件。
- `importDatabase` 先核验完全空的目标及支持的格式，返回未提交的 `DatabaseImportSession`。`write` 消费类型化行流，`verify` 回读结构、每表行数与摘要、引用警告和序列高水位。只有核验成功后才允许 `commit`；`abort` 与未提交会话的 `close` 中止事务。创建会话失败也须释放已取得的资源。

交换格式 1 使用 `MigrationSchema` 的结构声明、`MigrationSnapshot` 的验证事实和逐表 `MigrationRows`，不传递源引擎 dump 或 SQL。支持带符号 64 位 INTEGER、有限双精度 REAL、Unicode TEXT、BLOB 和 NULL；时间单位保持不变，不能静默转换或丢失精度。提供者按复合主键列序逐列升序输出，NULL 在前，数值按数值、TEXT 按 Unicode 标量、BLOB 按无符号字节比较；文本顺序不得依赖数据库编码或 locale。字段按声明顺序排列，游标每次只交付当前行；值只能在下一次 `next` 或关闭前使用。摘要按类型标签、四字节长度和内容计算，序列值是禁止复用的历史高水位。默认值使用受限字面量；新 CHECK 通过 `TableSpec.withCheck(Expression)` 声明同表比较、长度、集合和布尔条件，`LOWER_HEX` 表达 ASCII 小写十六进制，长度须另行约束。已发布的字符串 CHECK 入口保留，由提供者限定支持范围，不保证跨引擎翻译。提供者须在写入前拒绝自身无法实现的语义。未知格式版本拒绝读取。

`MigrationControl.checkpoint` 用于合作取消和进度报告。提供者须在逐行复制与验证中调用，并传播取消或报告失败；宿主随后中止尚未尝试提交的目标并关闭两端。提交抛错时结果按未知处理，不能报告回滚成功。报告先持久化 `COMMITTING`，逐库记录实际结果，最后才记录整体成功；报告落盘或资源释放失败同样阻止整体成功。引擎事务、目录、备份格式与隔离操作属于提供者，宿主只通过这些合同编排。`database.migration.jdbc` 提供可选的中性 JDBC 帮助类，提供者可以自行实现会话而不使用它们。

| 数据 | 维护约定 |
| --- | --- |
| 私有数据库 | 只通过本 owner 的 `PluginDatabase` 访问；声明结构和转换，事务完成后才执行依赖其结果的副作用。 |
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

配置使用 `GuiConfigContribution`；私有路径使用 owner-bound `RuntimePathProvider`，数据库使用 `PluginDatabase`，出站 HTTP / WebSocket 使用稳定 factory 与 route 契约。不要依赖 app、plugin-runtime、installer、签名内部实现、宿主数据库或具体 GUI provider。禁用、卸载及 reload 的贡献撤回需在真实宿主中验证。
