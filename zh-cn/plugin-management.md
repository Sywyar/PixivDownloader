# 插件管理

管理员在 `/plugin-market.html` 浏览并安装插件，在 `/plugin-manage.html` 核对已安装版本、当前进程加载状态与重启要求，或上传本地插件包。磁盘安装成功不等于当前进程已加载，部分插件需重启后端或完整退出软件后才生效。

## 批准插件的 HTTP Hook

在插件详情的「HTTP Hook 声明」中查看回调入口、目标接口、执行时机和所需字段。选择「预览并批准」，核对权限与受影响页面，再「确认并发布」；取消不会改变正式配置。新声明默认不执行。

已批准的相同声明在插件重新启用后恢复；目标或合同变更需要重新批准。目标缺席或有歧义时不能批准。停用插件会保留已批准的前置保护并拒绝受保护业务；如果决定解除保护，选择「预览撤销」并确认发布。每次操作只更新所选声明。开发方式见[插件开发](/zh-cn/plugin-development)。


## 安装插件与自定义仓库

1. 在插件市场选择受信仓库中的插件，或在插件管理页上传本地 `.jar` / `.zip`（可选附带 `.sig`）。
2. 查看插件用途、所需 SDK 版本、来源、依赖与执行模式。
3. 核对安装计划（目标包、依赖来源与版本、反向消费者、冲突及重启影响）并确认；若弹出执行信任确认，核对发布者公钥指纹或包 SHA-256。
4. 按回执提示执行所需重启，返回管理页确认加载版本与功能入口。失败时先检查原操作记录，勿盲目重复安装。

在桌面 GUI「设置 → 插件市场设置」中添加或编辑自定义仓库：可选择「使用仓库描述符」（预览发布者、联网主机与公钥完整指纹后加入草稿）或「自行填写」（填写 HTTPS 清单 URL、代理策略与 Ed25519 公钥），统一保存设置并重启后生效。

依赖按顺序使用独立安装事务：父包失败时，已安装成功的依赖会保留并在结果中列出版本与 `transactionId`。市场操作记录保存在当前进程内存中（最多 64 条、非活动记录保留 24 小时），关闭页面不会取消已开始的后端安装；进程重启或记录过期后，请先核对管理页的实际安装状态。

## 更新提示与历史版本

下载页导航的数字角标表示已安装插件存在兼容更新，感叹号表示存在需要更高 SDK 的新版；管理页横幅分别列出两类提示。稳定版插件默认只推荐稳定更新；手动安装预发布版后才跟随对应预发布渠道（也可升至更高稳定版）。同版本手动修复包（`-f`）不触发普通更新角标，需按修复说明重新安装。

官方主清单保留最新版本、最新稳定版，以及固定旧 SDK 集合（`1.0.0`、`1.1.0`）的最新兼容稳定版；其余历史版本保存在按摘要命名的 `history/` 清单中（单文件上限 900 个版本或 1 MiB），在查看历史或自动寻找兼容旧版时按需加载。

当插件最新版不兼容当前 SDK 时，市场会自动查找同一仓库中最高的兼容可安装旧版，并在卡片与详情中标明默认选择；也可在详情中展开阅读静态隔离的 README、本次更新说明或 CHANGELOG，或手动选择历史版本。插件许可与社区收录规则见[第三方插件 SDK](/zh-cn/plugin-development#插件许可证与发布渠道)。

## 安全模型与包准入上限

Ed25519 签名证明发布者来源与字节完整性，不代表代码安全审查或操作系统沙箱隔离；请仅安装信任的发布者与仓库。宿主加载前会将插件包有界冻结到私有工作区，并对同一份冻结字节校验包结构、`plugin.properties`、SHA-256、签名、provenance、SDK 兼容性与依赖。

### 执行模式

每个外置插件必须在 `plugin.properties` 显式声明 `pixiv.execution-mode`（缺失或未知值在代码执行前拒绝）：

| 值 | 运行位置 | 隔离与权限边界 |
| --- | --- | --- |
| `host-process-full-trust` | 宿主 JVM | 继承宿主进程的文件、网络与操作系统权限（宿主提权运行时管理页持续显示警告） |
| `declarative-process` | 独立 worker JVM | 共享宿主 OS 账户，仅通过有界协议发布声明式路由与能力（无完整 OS 沙箱；生产拒绝目录形式，开发模式临时降级为 full-trust 并如实显示） |

从 `declarative-process` 升级为 `host-process-full-trust`、SDK 主版本变化或信任撤销后，必须由管理员重新确认信任。worker 默认限制 128 MiB heap、128 MiB metaspace、64 MiB direct memory（OOM 退出，最多自动重启 3 次，退避 `500–10000` ms），初始化 / 命令 / 关闭超时分别为 `10000` / `5000` / `2000` ms，同时仅允许 1 个在途请求和 1 个排队请求（可通过 `pixivdownload.plugin-worker.*` JVM 属性调整）。

### 插件包默认资源上限

| JVM 属性（前缀 `pixivdownload.plugin.package.`） | 默认上限 |
| --- | --- |
| `max-archive-bytes` | 192 MiB |
| `max-entries` | 48,000 个条目 |
| `max-total-uncompressed-bytes` | 672 MiB |
| `max-entry-uncompressed-bytes` | 64 MiB |
| `max-descriptor-bytes` | 1 MiB |
| `max-compression-ratio` | 200（仅检查 ≥ 64 KiB 的条目） |
| `max-entry-name-length` / `max-entry-depth` | 1,024 字符 / 64 层目录 |

以上属性仅接受正整数，非法值直接阻止插件运行时初始化。

## 安装来源与市场状态

- **发行包预置**：Windows 安装包与 Java 标准包在 `plugins/` 预置默认官方插件（`download-workbench`、`database-sqlite`、`gui-compose`、`gui-swing`、`gallery`、`novel` 等）；离线全量包额外包含 `database-postgresql`、[PixivBatchDownloader 导入支持](/zh-cn/pixiv-batch-downloader-import)等官方可选插件。`douyin` 为独立第三方插件，仅从自定义仓库或本地包安装。
- **本地上传**：不建立自定义信任根；带签名时须通过既有信任根校验（按发布者指纹批准），未签名包标记为 `LOCAL_UPLOAD / UNSIGNED_ALLOWED`（仅批准当前精确 SHA-256）。远程仓库包强制要求清单签名，不会降级为本地未签名。开发模式下若安装包与源码模块同名冲突，仅保存安装包而不启用，需移出冲突包或排除源码模块后重启。
- **市场状态码**：`NOT_INSTALLED`（有兼容可安装版本）、`INSTALLED`（已安装且无更高兼容版）、`UPDATE_AVAILABLE`（有更高兼容版）、`INCOMPATIBLE`（无兼容当前 SDK 的版本）、`UNAVAILABLE`（无可安装制品或兼容查询未完成）。

## 事务替换与生命周期策略

插件安装与更新采用原子事务：冻结候选字节 → 校验描述符/签名/依赖 → 停止旧代际接纳新请求并等待排空（drain） → 原子替换 artifact 与 provenance → 按策略激活（失败时完整回滚旧代际）。

| `pixiv.lifecycle-policy` | 安装 / 更新 | 启用 / 禁用 | 适用插件 |
| --- | --- | --- | --- |
| `hot-reload`（默认） | 当前进程即时替换激活 | 直接 start / stop | 可完整撤回贡献并释放后台任务与客户端的插件 |
| `backend-restart` | 当前进程即时替换激活 | 保存状态后提示重启后端 | 需重建 Spring 后端上下文的插件 |
| `process-restart` | 落盘保存，完整退出并重启软件后激活 | 保存状态后提示完整重启软件 | `gui-compose`、`gui-swing`、数据库 provider、契约载体与主题等进程级插件 |

可管理的 `hot-reload` 插件支持 8 个动作：`load`、`start`、`quiesce`（停止新接纳并等待排空）、`stop`、`unload`、`remove`（删除安装包与 provenance，保留 `config/`、`state/`、`data/` 中的业务数据）、`restart`（同代际重启）与 `reload`（新建 generation 与 classloader）。无 `purge` 动作。

## 恢复模式、撤销信息与排障

- **启用状态与恢复模式**：开关保存在 `config/config.yaml` 的 `plugins.{pluginId}.enabled`。必选插件或当前选定的主数据库 provider 缺失、损坏、不兼容或启动失败时，应用进入恢复模式（保留登录、插件管理与「换包修复」入口）；普通可选插件故障仅隔离其自身能力。
- **撤销信息（`YANKED` / `REVOKED`）**：`YANKED` 阻止新安装与更新，`REVOKED` 同时阻止后续加载与启动；无有效撤销快照或超过 24 小时宽限期时禁止新安装。刷新撤销信息不会强行终止当前进程中已运行的 full-trust 代码，需完整退出应用并修复后再启动。
- **常见故障**：
  - **安装后页面未出现**：核对回执中的 `effectiveAfterRestart`；`process-restart` 插件必须完整退出软件重开，仅刷新网页或重启后端无效。
  - **提示不兼容或验签失败**：核对 `plugin.requires`（所需 SDK 版本而非应用版本号）与依赖版本，从原仓库重新获取完整包，切勿手改 `plugin.properties`。
  - **`stop` / `reload` 长时间等待**：检查 `log/latest.log` 中是否有未退出的队列任务、HTTP/WebSocket 客户端或调度线程。

插件文件存储结构见[存储与备份](/zh-cn/storage)，开发第三方插件见[第三方插件 SDK](/zh-cn/plugin-development)。
