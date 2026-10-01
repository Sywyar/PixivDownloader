# 插件管理

插件管理页位于 `/plugin-manage.html`，插件市场位于 `/plugin-market.html`。两者最终使用同一套包校验、安装事务和生命周期协调器；本地上传不是绕过运行时边界的另一套安装器。

在 GUI 的「设置 → 插件市场设置」中添加或编辑自定义仓库。弹窗顶部可切换「使用仓库描述符」和「自行填写」：前者预览发布者、联网主机及完整公钥指纹，确认后加入设置草稿；后者填写仓库地址、网络策略和信任密钥。统一保存设置后重启生效。Web 插件市场用于浏览和安装插件。

## 安全模型

签名能够证明 artifact 的发布者与字节完整性，但不代表安全审查，也不会授予额外运行权限。只安装你信任的发布者和仓库。

宿主在加载前会检查：

- 包大小、压缩比、路径和 JAR/ZIP 结构；
- `plugin.properties` 的 id、版本、核心 API 要求和依赖；
- SHA-256、结构化 Ed25519 签名和 provenance；
- 当前插件 API 兼容性、依赖版本与 required 插件约束。

校验、描述符解析和加载都使用同一份有界冻结字节。通过校验后不会重新打开可被外部进程替换的公开安装路径。

### 执行模式与隔离边界

每个外置插件都必须在 `plugin.properties` 显式声明 `pixiv.execution-mode`，只接受：

| 值 | 运行位置 | 权限边界 |
| --- | --- | --- |
| `host-process-full-trust` | 宿主 JVM | 继承宿主进程的文件、网络和 OS 权限 |
| `declarative-process` | 独立 worker JVM | 只通过有界协议发布声明式路由和能力 |

缺失、空白或未知值会在任何插件代码执行前被拒绝。worker 仍使用宿主的 OS 账号，只提供进程、协议和资源层面的有限隔离；当前没有完整 OS 沙箱，也没有要求 OS 沙箱的 JVM 开关。生产模式拒绝目录形式的 `declarative-process` 插件；显式开发模式会将其降级为 `host-process-full-trust`，状态和日志显示实际生效模式。

插件信任不会跨执行边界静默扩大。即使发布者未变，从 `declarative-process` 升级为 `host-process-full-trust`、SDK 主版本变化或信任撤销后也必须由管理员重新确认。宿主实际以管理员或其它高权限运行时，full-trust 插件会继承该权限，管理页会持续显示警告。

worker 默认使用 128 MiB heap、128 MiB metaspace、64 MiB direct memory，并在 OOM 时退出；初始化、命令、关闭超时分别为 10,000 / 5,000 / 2,000 ms。异常退出后最多重启 3 次，退避从 500 ms 增至最多 10,000 ms；stderr 最多读取 1 MiB，并保留末尾 16 KiB。每个 worker 同时只允许 1 个在途请求和 1 个排队请求。退出时宿主先撤回该插件的路由与能力，再按上述上限尝试恢复。

可在 JVM 启动前用 `pixivdownload.plugin-worker.*` 的 `initialize-timeout-ms`、`command-timeout-ms`、`shutdown-timeout-ms`、`restart-attempts`、`restart-initial-delay-ms`、`restart-max-delay-ms` 和 `stderr-max-bytes` 调整对应值。

### 插件包资源上限

默认准入上限为 192 MiB 归档、48,000 个条目、672 MiB 实际解压总量、64 MiB 单条目、1 MiB 描述符、压缩比 200（只检查至少 64 KiB 的条目）、1,024 个字符的条目名和 64 层路径。对应 JVM 属性为：

- `pixivdownload.plugin.package.max-archive-bytes`
- `pixivdownload.plugin.package.max-entries`
- `pixivdownload.plugin.package.max-total-uncompressed-bytes`
- `pixivdownload.plugin.package.max-entry-uncompressed-bytes`
- `pixivdownload.plugin.package.max-descriptor-bytes`
- `pixivdownload.plugin.package.max-compression-ratio`
- `pixivdownload.plugin.package.max-entry-name-length`
- `pixivdownload.plugin.package.max-entry-depth`

属性只接受正整数；非法值会使插件运行时初始化失败，不会静默回退。

## 安装来源

### 发行包预置

Windows 安装包与 Java 标准包在 `plugins/` 预置默认官方插件，包括 required `download-workbench`、默认 `gui-compose` 和后备 `gui-swing`。full-offline 包还包含官方可选插件。[PixivBatchDownloader 导入支持](/zh-cn/pixiv-batch-downloader-import)属于按需安装的官方插件，普通包不预装，离线全量包包含。Douyin 是普通第三方插件，只从自定义仓库或本地包安装。预置插件仍是独立 artifact，不会合入核心 Boot JAR。

### 本地上传

在插件管理页选择 `.jar` 或兼容 `.zip`，也可同时提供 detached `.sig`。本地上传不建立自定义信任根：签名存在时必须与精确 artifact 匹配并通过适用信任根验证；未签名包会标为 `LOCAL_UPLOAD / UNSIGNED_ALLOWED`。非官方本地包在生产模式也能安装，但任何代码执行前都必须确认风险；签名包按发布者指纹批准，未签名包只批准当前精确 SHA-256。更新、换 key、撤销或执行权限提升可能再次要求确认。

远程仓库包始终要求仓库清单声明的签名，不能降级为本地 unsigned。需要长期信任自有 key 的第三方分发应配置自定义仓库。

### 插件市场

市场与内嵌官方仓库默认启用；启动本身不访问仓库，打开或刷新市场、执行安装时才发起请求。可在配置中关闭 `plugin-catalog.enabled`。官方仓库使用程序内嵌的地址和信任根；自定义仓库必须在 `config.yaml` 中配置自己的 HTTPS manifest 和 Ed25519 公钥，详见[配置参考](/zh-cn/configuration)。

市场状态码含义：

| 状态 | 含义 |
| --- | --- |
| `NOT_INSTALLED` | 有兼容的可安装版本 |
| `INSTALLED` | 已安装，且没有严格更高的兼容版本 |
| `UPDATE_AVAILABLE` | 存在严格更高的兼容版本 |
| `INCOMPATIBLE` | 最新可安装版本不满足当前核心 API |
| `UNAVAILABLE` | 清单没有可下载的版本制品 |

市场操作记录保存后端获取与安装状态；最终生效情况仍须结合安装结果和真实运行时状态判断。

## 安装与更新事务

本地上传和市场安装都遵循同一流程：

1. 有界读取并冻结候选 artifact；
2. 校验结构、描述符、API、依赖、摘要、签名和来源；
3. 撤回旧 generation 的新请求接纳并等待它 drain；
4. 原子替换 artifact 与 provenance；
5. 按生命周期策略激活新包；
6. 任一步失败时恢复旧 artifact、provenance 和可用 generation。

因此更新是**事务化替换**，不是在旧类实例上打补丁。浏览器页面应在操作后重新读取状态，不要仅凭按钮返回猜测插件已生效。

## 三类生命周期策略

插件在 `plugin.properties` 中用 `pixiv.lifecycle-policy` 声明策略：

| 值 | 安装/更新 | 启用/禁用 | 适用情况 |
| --- | --- | --- | --- |
| `hot-reload` | 当前进程事务替换并即时激活 | 直接 start/stop | 没有启动期专属资源，能够完整撤回贡献并释放任务/客户端 |
| `backend-restart` | 当前进程事务替换并即时激活 | 保存状态后提示重启后端 | 需要重建 Spring 后端上下文，但不要求结束桌面进程 |
| `process-restart` | 包先安全落盘，完整进程重启后激活 | 保存状态后提示重启软件 | 桌面 provider、主题、托盘等会被进程级组件长期持有的能力 |

未填写时默认 `hot-reload`。值区分大小写，只接受上表三个 token。

“重启后端”只重建 Spring 后端上下文，不等于完整进程重启；它不能让 `process-restart` 插件生效。桌面生命周期管理器不可用时，管理页不能代替操作系统重启进程。

官方 `gui-compose` 是默认桌面 provider，`gui-swing` 自动后备。两者分别拥有自己的页面与交互，共享应用业务语义，均为 `process-restart`；安装、更新、启停、移除或在“配置 → 界面”切换 provider 后都必须完整退出并重新启动软件。

## 生命周期动作

对可管理的 `hot-reload` 外置插件，管理 API 提供八个动作：

| 动作 | 语义 |
| --- | --- |
| `load` | 从已安装 artifact 创建类加载器并加载插件，尚不发布运行能力 |
| `start` | 启动已加载插件、创建子上下文并发布贡献 |
| `quiesce` | 停止接纳新工作并等待当前 publication 的任务/调用排空 |
| `stop` | 撤回贡献并停止插件实例；不会删除安装包 |
| `unload` | 在停止后释放 PF4J 插件与类加载器；不会删除安装包 |
| `remove` | 完成安全清退后删除已安装 artifact 和对应 provenance |
| `restart` | stop/start 当前实例，保留 generation 与 classloader |
| `reload` | quiesce、stop、unload 后重新 load/start，创建新的 generation 与 classloader |

没有 `purge` 动作。需要删除插件时使用 `remove`；插件自己的 `config/`、`state/`、`data/` 是否保留属于数据保留策略，不应由一个含糊的别名隐式清空。

可选的 `process-restart` 插件也可以通过管理页移除。操作会删除已安装包及其来源证明，保留配置、凭据、数据库、状态和已下载文件。若当前进程已加载该插件，页面会提示完整退出并重新启动；在此之前，当前实例继续运行。必选插件和仍有活动依赖者的插件会被拒绝移除。

更新或移除后，管理页分别显示磁盘安装状态与当前进程仍加载的版本，并提示重启后生效。新包按自身内容和来源证明验证；旧实例的验证结果不能代替新包验证。重新安装后仍可使用保留的数据。

`restart` 适合重新创建服务足迹但不需要换类；代码或资源 artifact 已变化时使用 `reload`。异步插件必须让 `quiesce` 返回真实可等待的正 generation drain，不能用“已完成”哨兵掩盖仍在运行的后台任务。

## 启用、禁用与 required 插件

启用状态保存在 `config/config.yaml` 的 `plugins.{pluginId}.enabled`。`hot-reload` 插件的开关直接执行 start/stop；其它策略保存状态后给出相应重启提示。

required 插件不能被禁用或移除到不满足状态。required 包缺失、损坏、不兼容、离线复验失败或启动失败时，核心壳进入恢复模式，保留管理与修复入口。可选插件故障只隔离其能力。插件市场横幅列出不可用的必选插件与诊断，并显示默认安装插件以便修复。

## 依赖与版本

`plugin.dependencies` 使用 PF4J 依赖表达式。安装或启动前，宿主会检查所需插件是否存在、版本是否满足以及依赖图是否可解。升级公共依赖插件前，先确认所有消费者的版本范围。

`plugin.requires` 表示所需核心 API 版本，不是宿主应用的营销版本。市场把不兼容的未安装包显示为 `INCOMPATIBLE`，不会尝试加载后再碰运气。

## 文件边界

安装身份由 `plugins/` 根目录的原始 artifact 和 `plugins/provenance/` sidecar 组成。`plugins/runtime/` 只是每个 generation 的私有冻结工作区。不要：

- 在运行时手工覆盖插件 JAR；
- 把 `plugins/runtime/` 当安装目录、签名源或共享缓存；
- 只复制 artifact 而遗漏远程来源的 provenance；
- 把私钥放进 `plugins/`、源码、构建输出或日志。

portable 安装可以让 `plugins/` 根本身指向符号链接或 Windows junction；运行时会先解析并固定真实根目录，但仍逐个拒绝根目录内的链接制品。宿主会在文件系统支持时收紧 `plugins/runtime/` 与 `plugins/provenance/` 的 POSIX 权限或 Windows ACL；FAT32、exFAT、SMB 等不支持这些能力时会记录诊断，并继续依赖普通文件、`NOFOLLOW`、冻结快照与哈希检查。

完整布局见[存储原理](/zh-cn/storage)。

## 常见故障

### 安装成功但页面没有出现

先查看安装响应中的 `effectiveAfterRestart` 和插件策略。`process-restart` 必须完整退出并重新启动软件；浏览器刷新或后端重启不够。其它策略刷新管理页，确认插件处于 `STARTED` 且贡献没有注册诊断。

### 显示不兼容

检查 `plugin.requires`、`requiredCoreApi` 和依赖插件版本。不要手工改 descriptor 绕过版本检查；升级宿主或安装发布者提供的兼容版本。

### 签名或 provenance 失败

重新从原仓库下载。远程包缺签名、未知/撤销 key、摘要不一致或 sidecar 与 artifact 不匹配都会 fail-closed，不会降级成本地 unsigned。

### stop/reload 一直等待

插件仍有活动调用、队列任务、HTTP/WebSocket 客户端或调度线程。先查看插件日志；插件作者需要停止新接纳、取消或等待任务 drain，并在子上下文关闭时释放自有客户端、执行器和 scheduler。

### 移除后配置还在

这是有意的数据保留。`remove` 删除安装包和 provenance，不等同于删除 `config/plugins/{id}.properties`、加密凭据或 owner 数据。确认不再需要且已经备份后，再在程序停止时按 owner 精确清理。

## 开发者下一步

要创建插件、贡献下载类型或独立画廊，请阅读[第三方插件 SDK](/zh-cn/plugin-development)。从模板开始，不要复制应用壳实现类。

## 确认变更与查询安装结果

市场安装前统一列出目标包、依赖的来源和版本、共享依赖的反向消费者、冲突及重启影响。确认后会重新核对这些事实；状态已变化时，重新预览后再确认。目录尚未提供新包的生命周期信息时只能提示可能重启，以实际安装回执为准。

依赖按顺序使用独立安装事务。父包失败时，已经安装成功的依赖会保留，结果列出其版本和 transactionId；核对管理页后再决定下一步，不要把部分成功当成全部回滚。

市场的操作记录从下载准备延续到安装结束。关闭页面不取消已开始的后端请求；重新打开页面可查询原操作。同一操作 ID 的重复执行只回读原状态。记录只在当前进程保存，最多 64 条，非活动记录最长保留 24 小时。进程重启、过期或记录淘汰后，结果为未知；先核对已安装版本、诊断及事务状态，再明确发起新操作。页面不会因查询失败自动重新安装。

## 启动失败后的修复

失败的外置插件卡片提供“换包修复”，打开现有本地上传流程。选择同一插件 ID 的兼容修复包并完成原有校验与风险确认。必选插件允许换包，但不能移除；可选失败包可在满足依赖与生命周期约束后移除。移除和换包保留配置、数据与未完成任务；插件自身的数据迁移仍由插件负责。可选插件故障只隔离其能力，必选能力不可用才进入全局恢复模式。

## 撤销信息与运行实例

管理页显示最后成功刷新时间、文档有效期、宽限截止和限制原因。管理员可主动刷新已配置且启用的来源；市场浏览与安装继续按原流程刷新。普通管理状态查询不联网，也没有后台定时刷新。离线或刷新失败会保留已验证的最后有效快照，不能解释为没有限制。

`YANKED` 阻止新安装和更新；`REVOKED` 还阻止后续加载或启动。没有有效快照或超过 24 小时宽限期时，新安装失败关闭。当前进程可能仍持有旧实例，刷新撤销信息不会强制终止 full-trust 代码；要停止它须完整退出应用，核对修复后再启动。
