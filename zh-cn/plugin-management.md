# 插件管理

插件管理页位于 `/plugin-manage.html`，插件市场位于 `/plugin-market.html`。两者最终使用同一套包校验、安装事务和生命周期协调器；本地上传不是绕过运行时边界的另一套安装器。

## 安全模型

外置插件与宿主运行在同一个 JVM 中，当前没有进程或 OS 沙箱。签名能够证明 artifact 来自受信发布者且字节未被篡改，但不能证明插件行为无害。只安装你信任的发布者和仓库。

宿主在加载前会检查：

- 包大小、压缩比、路径和 JAR/ZIP 结构；
- `plugin.properties` 的 id、版本、核心 API 要求和依赖；
- SHA-256、结构化 Ed25519 签名和 provenance；
- 当前插件 API 兼容性、依赖版本与 required 插件约束。

校验、描述符解析和加载都使用同一份有界冻结字节。通过校验后不会重新打开可被外部进程替换的公开安装路径。

## 安装来源

### 发行包预置

标准发行包在 `plugins/` 预置 required 和 default-installed 官方插件。Douyin 是按需安装插件：标准包不预置，full-offline 包预置，也可以从插件市场安装。预置仍是独立 artifact，不会合入核心 Boot JAR。

### 本地上传

在插件管理页上传 `.jar` 或兼容 `.zip`。本地未签名包只有在本地上传策略允许时才接受，并持续记录为 `LOCAL_UPLOAD / UNSIGNED_ALLOWED` provenance；它不会被伪装为官方或远程仓库可信包。

本地上传适合开发和受控的私有分发。不要把“本机允许 unsigned”理解成市场仓库可以省略签名：远程来源仍 fail-closed。

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

“安装中”只是浏览器请求在途状态，最终结果以后端响应和刷新后的真实运行时状态为准。

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
| `process-restart` | 包先安全落盘，完整进程重启后激活 | 保存状态后提示重启软件 | 主题等会被 Swing 或进程级组件长期持有的能力 |

未填写时默认 `hot-reload`。值区分大小写，只接受上表三个 token。

“重启后端”只重建 Spring 后端上下文，不等于完整进程重启；它不能让 `process-restart` 插件生效。桌面生命周期管理器不可用时，管理页不能代替操作系统重启进程。

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

`restart` 适合重新创建服务足迹但不需要换类；代码或资源 artifact 已变化时使用 `reload`。异步插件必须让 `quiesce` 返回真实可等待的正 generation drain，不能用“已完成”哨兵掩盖仍在运行的后台任务。

## 启用、禁用与 required 插件

启用状态保存在 `config/config.yaml` 的 `plugins.{pluginId}.enabled`。`hot-reload` 插件的开关直接执行 start/stop；其它策略保存状态后给出相应重启提示。

required 插件不能被禁用或移除到不满足状态。required 包缺失、损坏、不兼容或离线复验失败，或者任意插件在启动时崩溃，核心壳都会进入恢复模式，不开放依赖插件的业务路由。插件市场会在横幅中列出缺失的必装插件，或指出启动失败的插件及其诊断原因，并自动显示默认安装插件以便修复。

## 依赖与版本

`plugin.dependencies` 使用 PF4J 依赖表达式。安装或启动前，宿主会检查所需插件是否存在、版本是否满足以及依赖图是否可解。升级公共依赖插件前，先确认所有消费者的版本范围。

`plugin.requires` 表示所需核心 API 版本，不是宿主应用的营销版本。市场把不兼容的未安装包显示为 `INCOMPATIBLE`，不会尝试加载后再碰运气。

## 文件边界

安装身份由 `plugins/` 根目录的原始 artifact 和 `plugins/provenance/` sidecar 组成。`plugins/runtime/` 只是每个 generation 的私有冻结工作区。不要：

- 在运行时手工覆盖插件 JAR；
- 把 `plugins/runtime/` 当安装目录、签名源或共享缓存；
- 只复制 artifact 而遗漏远程来源的 provenance；
- 把私钥放进 `plugins/`、源码、构建输出或日志。

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
