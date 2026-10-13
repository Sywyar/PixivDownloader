# 配置参考

PixivDownloader 的配置按所有权分为三类，不能混用：

| 配置类型 | 路径 | 所有者 |
| --- | --- | --- |
| 宿主配置和插件启用状态 | `config/config.yaml` | 应用壳 |
| 插件业务配置 | `config/plugins/{pluginId}.properties` | 对应插件 |
| 插件凭据 | `config/credentials/{pluginId}.properties` | 对应插件；由宿主加密存储 |

推荐在桌面 GUI 的“配置”页修改。首次启动时，应用会用当前版本的默认模板创建 `config/config.yaml`；升级后只追加缺失的宿主键，不覆盖已有值。手工编辑时使用 UTF-8，并保留活跃的 `key: value` 行，不要用注释行代替空值。

## 宿主配置

### 服务、调试和下载

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `6999` | HTTP/HTTPS 服务端口 |
| `debug.enabled` | `false` | 调试模式 |
| `download.root-folder` | `pixiv-download` | 作品产物根目录 |
| `download.user-flat-folder` | `false` | 画师目录是否使用平铺布局 |
| `download.max-concurrent` | `10` | 宿主下载并发上限；最多另排队 100 个作品，队列已满时新任务返回 429 |
| `database.maximum-pool-size` | `28` | 宿主主库连接池上限，最小 8 |

`download.root-folder` 只存放下载作品。配置、数据库、插件状态和缓存不会写入该目录。小说、Douyin 等下载类型的私有设置不属于宿主配置；安装插件后由插件自己的配置贡献管理。

Pixiv 作品图片、小说封面和内嵌图的固定安全上限为每张 100 MiB；单个普通插画或小说下载任务内的这些响应累计最多 1 GiB。服务同时检查 `Content-Length` 和实际解码后的响应流，超过上限会中止传输并清理部分文件。最终扩展名根据 URL 路径、响应 `Content-Type` 与已验证的文件头从图片白名单中选择，查询参数不会进入文件名。这些上限不能通过配置提高。

Ugoira 的 ZIP 上限为 100 MiB、最多 500 个条目和帧；单条目展开最多 32 MiB、总展开量最多 200 MiB，单帧最多 25,000,000 像素。每个转码进程最多运行 10 分钟、输出 100 MiB。超限会终止处理并清理临时文件；已选择保留的 ZIP 和帧时序文件仍保留。

### 主数据库与历史迁移

默认安装独立的 SQLite 数据库插件，保留 `data/pixiv_download.db` 与旧目录定位规则。需要 PostgreSQL 17 或 18（UTF8）时，从插件市场安装 PostgreSQL 数据库插件并完整重启，再选择连接目标。主库和已适配的插件私有存储使用同一选定引擎；SQLite 私库仍位于 `data/{pluginId}/plugin.db`，PostgreSQL 私库按 owner 使用独立 schema 和受限账号。

#### 在设置中切换数据库

在 Swing 或 Compose 的「设置 → 运行与网络 → 主数据库」，或插件市场的数据库设置中操作：

1. 选择已加载的数据库插件并填写连接信息。密码留空保留已存值；需要删除时，勾选清除密码并完成保存。
2. 点击「测试连接」检查地址和账号。检查通过只表示连接可用，不会保存设置、创建数据库或迁移数据。修改数据库设置后，点击「检查并继续」，检查应用数据及全部插件数据的来源、目标和处理方式。
3. 核对确认清单。目标已有数据且提供者支持安全替换时，清单会说明先备份再替换；不支持安全替换的目标会被拒绝。点击「取消」可返回修改，已填内容保留，数据库连接设置尚未保存。
4. 确认后，应用先保存本页其它设置，再自动停止业务并完整重启。新进程迁移并核验清单中的数据，所有数据库通过核验后才切换连接设置并继续启动。只修正同一数据库的连接信息时，使用「应用连接并重启」，不复制数据。

连接详情显示正在使用的数据库与重启后将使用的数据库。环境变量或启动参数覆盖了连接设置时，先修正这些覆盖项，再继续切换。

替换目标前会保留旧目标的备份；迁入的数据先经过核验，再逐库替换。备份及迁移记录保存在 `data/database-migrations/` 下对应的任务目录。备份可能包含计划凭据，应按敏感文件保管。作品、参考音和凭据文件仍保留原位。

失败时保留原数据库连接设置，并显示各项数据的实际结果。已经迁入并保存的数据不会因其它项失败而自动撤销；「结果不确定」表示尚不能确认该项是否已保存，不能当作已回滚。保留原数据库和备份，修正提示的问题后重新检查。重试会核对源数据是否变化、目标内容是否仍匹配，只有核验通过的已迁入数据才会复用。

没有数据库插件、选中插件缺失 / 禁用 / 不兼容 / 加载失败，或启动时无法建立数据库连接，都会进入恢复模式：业务页面跳转市场，业务 API 返回 503；直接语义查询返回空并标明数据库不可用，写入与依赖数据库的后台操作停止。恢复原插件或修正连接后，可通过上述检查和确认流程重启；应用不会自动改投其它引擎或新建空 SQLite。原连接无法解析时，确认清单会明确说明仅修正连接，不迁移原数据库的数据。

#### PostgreSQL 连接准备

请先创建专用数据库和主库 schema，授予应用账号相应的建表、索引、序列、函数、触发器及数据读写权限。使用插件私库还需要 `CREATEROLE` 和数据库上的 `CREATE` 权限，以建立独立登录角色与 schema；不能向 `PUBLIC` 开放主库或其它 owner 的数据权限，否则隔离检查会拒绝私库连接。`currentSchema` 只能指定一个小写字母、数字或下划线组成的名称（首字符不能是数字，最多 63 字符）；省略时使用 `public`。远程连接建议使用 `sslmode=verify-full`，需要自定义 CA 时添加 `sslrootcert`。JDBC 直接连接管理员指定的服务器，不使用 Pixiv HTTP 代理。

以下是连接配置的扁平键示例。已有数据需要迁移时，优先使用设置页的确认流程；直接编辑 `config/config.yaml` 只会改变下次启动的连接，不会复制历史数据。密码放在独立配置项或 `DATABASE_PASSWORD` 环境变量中，不能写入 JDBC URL。

```yaml
database.provider: database-postgresql
database.engine: postgresql
database.jdbc-url: "jdbc:postgresql://db.example.com:5432/pixiv?currentSchema=pixiv&sslmode=verify-full"
database.username: pixiv_app
database.password: "替换为数据库密码"
```

#### 管理员可选：停机迁移工具

需要独立安排停机或检查报告时，可以使用 CLI。它不会自动切换应用配置，也不提供设置页的非空目标备份替换流程。安装源和目标数据库插件，停止所有使用源库的应用、插件和离线写入工具；保持作品文件、插件私有库、参考音、配置及凭据文件可用。分别把源和目标连接配置保存为 `source.yaml`、`target.yaml`，保持当前生效配置不变。在完整解压目录运行下列命令，将 `<boot.jar>` 替换为实际应用 boot JAR 文件名：

```powershell
java '-Dloader.main=top.sywyar.pixivdownload.migration.DatabaseMigration' -cp '<boot.jar>' org.springframework.boot.loader.launch.PropertiesLauncher --source-config source.yaml --target-config target.yaml --backup migration/source.backup --report migration/report.json
```

工具取得实例锁，由源提供者保留一致备份、准备结构和数据，目标提供者向空目标导入并核验。SQLite 源备份包含已提交的 WAL 数据，旧结构在工作副本上升级。SQLite 与 PostgreSQL 支持双向迁移；迁移保留 ID、毫秒时间、NULL、软删除、元数据、媒体和文件引用，并核对每表行数、摘要及生成键序列。目标非空、源表没有所属声明或类型不兼容时会停止，不会清空目标或略过未知表。

小说的可再生 SQLite 对象需显式确认：源库存在时添加 `--rebuild-objects novels_fts,novel_soft_delete_cleanup`，列表中只填实际存在的对象。PostgreSQL 启动后由数据库提供者按小说插件的声明恢复软删除触发器，正文搜索直接读取迁移后的正文。其它插件的辅助对象须先确认所选提供者支持重建；普通历史表不能用此参数排除。

已安装但禁用的插件仍提供迁移声明。准备卸载保留数据库数据的插件前，可用同一入口的 `--export-schema schema.json` 保存声明；迁移时用 `--retained-schema schema.json` 补入缺席 owner，文件必须匹配当前 SDK。需要执行私有存储结构升级的插件仍须安装，不能仅靠导出的声明代替。缺少声明时先恢复相应插件或补齐经核对的声明，不要删除历史表。

工具同时迁移声明了兼容私有 schema 的 owner 数据；未知 owner 或缺少私有声明会在复制前拒绝。主库与各私库分别提交，部分失败不会自动回滚已提交库。保存数据库选择和启动前会复核私库迁移收据与源数据变化；第三方自带 JDBC 或 SQLite 专用 SQL 不会自动兼容另一种数据库，需先由作者适配。

只有报告为 `DATA_VERIFIED` 才表示数据库复制核验完成。检查报告、待重建对象及每项数据的结果后，再修改生效配置并启动应用，核验画廊、小说搜索、通知、计划及文件访问。`IN_PROGRESS` 或 `FAILED_TARGET_NOT_VERIFIED` 都不能视为成功；保留备份并核对目标事务结果，CLI 重试使用新备份和经确认的空目标，不提供断点续传。切换后若目标已接受新写入，直接恢复旧连接会遗漏这些增量；应通过设置页重新检查并迁回，或由管理员在停机后处理。

### 图片与 Ugoira 保存

在桌面“设置 / 配置 → 下载”中设置默认保存格式、质量、WebP 无损和最长边，保存后重启后端生效。格式下拉框支持多选，至少保留一项：

- 图片：原始格式（默认）、PNG、JPG、WebP。可取消原始格式；只有所有所选产物生成成功后才移除原图，失败或取消时保留原图。选择与原图相同的格式会复用原文件。
- Ugoira：WebP（默认）、GIF、APNG、MP4、原始 ZIP。MP4 需要当前 FFmpeg 提供 H.264 编码器；保留 ZIP 时同时保存帧时序，供离线补转。
- 转码质量默认 90，可设 1–100；WebP 无损默认关闭；最长边默认 0（保留尺寸），可设 1–16,383。质量、无损和缩放只影响新编码的副本，不重编码复用的原文件。

两版网页下载设置都有相同的格式多选，可用于本次下载和新建计划。默认值保存在 `config/plugins/download-workbench.properties`，键为 `download-workbench.media.image-formats`、`ugoira-formats`、`quality`、`webp-lossless`、`maximum-edge`（后四项也使用相同前缀）。

FFmpeg 共享并发数默认 2，可在桌面下载设置中改为 1–8，保存后重启后端生效。对应宿主键为 `config/config.yaml` 中的 `ffmpeg.max-concurrent`；转码与媒体探测共用此额度。

已有作品在 Compose 桌面“工具 → FFmpeg 及其工具 → 历史媒体处理”中选择目标格式并检测缺失项目，无需填写作品 ID。查看结果后点击开始，只补缺失格式和缩略图，保留已有文件；返回不取消任务。Swing 在工具页提供同样的检测与处理。Ugoira 补转需要保留的 ZIP 和帧时序；FFmpeg 管理中可检查实际编解码能力，MP4 需要 H.264 编码器。

每批最多处理 500 个命中作品、10,000 个候选文件，同时只运行一个任务；达到上限会提示处理后再次检测。检测不生成文件，修改选项后须重新检测，修改下载设置不会自动开始历史处理。

画廊优先显示仍保留的原图，其次为 WebP、PNG、JPG；原图已移除时使用实际存在的副本，MP4 使用视频播放器。

### 插件市场

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | 插件市场主开关；打开 / 刷新市场或安装插件时访问仓库，关闭后不访问任何仓库 |
| `plugin-catalog.official-repository-enabled` | `true` | 是否启用内嵌官方仓库 |
| `plugin-catalog.connect-timeout-ms` | `15000` | 全局连接超时 |
| `plugin-catalog.read-timeout-ms` | `60000` | 全局读取超时 |
| `plugin-catalog.max-manifest-bytes` | `1048576` | 单个清单上限 |
| `plugin-catalog.max-package-bytes` | `104857600` | 单个插件包上限 |
| `plugin-catalog.repositories` | 空列表 | 自定义仓库列表 |

官方仓库的地址和信任根内嵌于程序。自定义仓库必须显式配置自己的 HTTPS 清单和 Ed25519 公钥，不继承官方信任根。推荐用 GUI 的仓库编辑器；手写示例：

```yaml
plugin-catalog.enabled: true
plugin-catalog.repositories:
  - id: example
    display-name-key: plugin.market.repository.example.name
    manifest-url: https://plugins.example.com/manifest.json
    enabled: true
    proxy-policy: direct-strict
    trusted-keys:
      - key-id: example-2026
        algorithm: Ed25519
        public-key: BASE64_X509_SUBJECT_PUBLIC_KEY_INFO
        state: ACTIVE
        publisher: Example Publisher
        trust-label: Example repository release key
```

仓库 id 必须唯一，且不能使用保留值 `official` 或 `configured`。代理策略为：

- `direct-strict`：直连、仅 HTTPS、拒绝非公网地址和重定向。
- `proxy-trusted`：使用应用代理，只对内置信任主机允许最多五次重定向；每一跳都会重新校验。
- `custom`：使用条目中的 `allow-redirects`、`strict-https`、`allow-non-public-addresses` 和 `use-proxy`。

仓库条目还可覆盖 `connect-timeout-ms`、`read-timeout-ms`、`max-manifest-bytes`、`max-package-bytes`；省略或设为 `0` 表示继承全局值。

### 出站代理

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `proxy.enabled` | `true` | 启用宿主出站 HTTP 代理 |
| `proxy.host` | `127.0.0.1` | 代理主机 |
| `proxy.port` | `7890` | 代理端口 |

插件需要代理时应使用稳定 HTTP/WebSocket SDK 或 `core-api` 的代理语义端口，不应读取宿主 `ProxyConfig` 实现类。

### 多人模式配额和限流

| 键 | 默认值 |
| --- | --- |
| `multi-mode.quota.enabled` | `true` |
| `multi-mode.quota.max-artworks` | `50` |
| `multi-mode.quota.reset-period-hours` | `24` |
| `multi-mode.quota.archive-expire-minutes` | `60` |
| `multi-mode.quota.limit-image` | `0` |
| `multi-mode.quota.max-proxy-requests` | `200` |
| `multi-mode.quota.archive-max-concurrent` | `10` |
| `multi-mode.post-download-mode` | `pack-and-delete` |
| `multi-mode.delete-after-hours` | `72` |
| `multi-mode.request-limit-minute` | `300` |
| `multi-mode.static-resource-request-limit-minute` | `1200` |

`multi-mode.post-download-mode` 支持 `pack-and-delete`、`never-delete`、`timed-delete`。`multi-mode.limit-page=0` 表示不限制页数；当前默认值为 `3`。

邀请访客在 solo 与 multi 模式都使用独立限流：

| 键 | 默认值 |
| --- | --- |
| `guest-invite.request-limit-minute` | `300` |
| `guest-invite.static-resource-request-limit-minute` | `1200` |
| `guest-invite.tts-request-limit-minute` | `30` |
| `setup.login-rate-limit-minute` | `10` |

### 维护窗口

`maintenance.enabled` 默认 `true`。每日默认值如下：

| 星期 | 启用键/默认值 | 时间键/默认值 |
| --- | --- | --- |
| 星期一 | `maintenance.monday.enabled=true` | `maintenance.monday.time=10:00` |
| 星期二 | `maintenance.tuesday.enabled=false` | `maintenance.tuesday.time=10:00` |
| 星期三 | `maintenance.wednesday.enabled=false` | `maintenance.wednesday.time=10:00` |
| 星期四 | `maintenance.thursday.enabled=false` | `maintenance.thursday.time=10:00` |
| 星期五 | `maintenance.friday.enabled=false` | `maintenance.friday.time=10:00` |
| 星期六 | `maintenance.saturday.enabled=false` | `maintenance.saturday.time=10:00` |
| 星期日 | `maintenance.sunday.enabled=false` | `maintenance.sunday.time=10:00` |

### HTTPS 与反向代理

| 键 | 默认值 |
| --- | --- |
| `ssl.domain` | `localhost` |
| `ssl.type` | `pem` |
| `server.ssl.enabled` | `false` |
| `server.ssl.certificate` | 空 |
| `server.ssl.certificate-private-key` | 空 |
| `server.ssl.key-store-type` | `JKS` |
| `server.ssl.key-store` | 空 |
| `server.ssl.key-store-password` | 空 |
| `server.trusted-proxy-cidrs` | 空 |
| `ssl.http-redirect` | `false` |
| `ssl.http-redirect-port` | `80` |

`ssl.type=pem` 使用证书和私钥路径；`ssl.type=jks` 使用 key store。不要把证书私钥或 key store 密码提交到仓库。

`server.trusted-proxy-cidrs` 是使用反向代理时的信任边界。它只接受逗号分隔的数字 IPv4/IPv6 CIDR，例如：

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

只填写实际连接后端的反向代理出口地址或容器网段，不要填写客户端网段，也不要信任 `0.0.0.0/0` 或 `::/0`。留空时应用按直连模式运行，并拒绝任何 `Forwarded`、`X-Forwarded-*` 或 `X-Real-IP` 请求头。

受信代理必须为每个请求提供以下一种完整格式，不能混用：

- RFC `Forwarded`：所选代理边界必须同时包含 `for`、`proto` 和 `host`；
- 传统格式：`X-Forwarded-For`、`X-Forwarded-Proto`、`X-Forwarded-Host`，可选 `X-Forwarded-Port`。

应用从代理链右侧向左查找首个非受信地址作为客户端地址，并在鉴权、限流和 CSRF 同源判断前统一规范化客户端地址、外部协议、主机与端口。未受信来源提供转发头，代理链没有非受信客户端地址，或受信代理缺少、混用、错位、伪造格式的代理元数据时，请求会返回 400。代理必须覆盖所有到后端的请求；如果把 `127.0.0.1/32` 设为受信代理，同一地址发起但没有代理头的直连请求也会被拒绝。

### 语言与桌面界面

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `app.language` | 空 | 跟随系统；也可使用受支持语言代码 |
| `app.theme` | `system` | GUI 主题 id |
| `app.config-menu-expand-all` | `false` | Swing 是否展开全部配置菜单；Compose 不提供此选项，进入 Compose 时会关闭并保存 |
| `app.gui-provider` | `gui-swing` | 桌面 GUI provider id；只能选择当前活动插件提供的值 |

可用主题由已安装的主题插件贡献；GUI provider 下拉项同样由当前活动的 `DesktopUiProvider` 动态提供，不是宿主硬编码清单。官方 `gui-swing` 默认安装，`gui-compose` 按需安装。切换 provider 必须完整重启；配置的 provider 不可用时，只会回退到唯一默认 provider，不按发现顺序任选。

### 在线更新

| 键 | 默认值 |
| --- | --- |
| `update.enabled` | `true` |
| `update.manifest-url` | 官方最新正式版 `update.json` |
| `update.nightly-manifest-url` | 官方 nightly `update.json` |
| `update.auto-check` | `true` |
| `update.check-nightly` | nightly 构建为 `true`，正式版为 `false` |

### 计划任务宿主

| 键 | 默认值 |
| --- | --- |
| `schedule.enabled` | `true` |
| `schedule.tick-interval-ms` | `60000` |
| `schedule.max-tasks` | `100` |
| `schedule.inbox-check-every` | `500` |
| `schedule.auth-failure-circuit-breaker` | `5` |
| `schedule.pending-max-attempts` | `5` |
| `schedule.overuse-defer-default-minutes` | `60` |

这些键配置中性的计划任务宿主。具体下载来源、认证信息和下载参数由相应插件贡献并拥有。

### 插件启用状态

`plugins.{pluginId}.enabled` 由宿主管理，例如：

```yaml
plugins.douyin.enabled: true
```

required 插件不能通过该键禁用。是否能即时生效由插件的 `pixiv.lifecycle-policy` 与当前生命周期操作决定，详见[插件管理](/zh-cn/plugin-management)。

## 插件业务配置

每个插件只写 `config/plugins/{pluginId}.properties`。文件使用 UTF-8 Java properties 语法：

```properties
example.timeout-ms=15000
example.output-format=json
```

宿主会拒绝插件配置文件覆盖宿主默认键、`plugins.*.enabled` 或看起来像凭据的键。不同插件文件中的键也不应重复。插件子 Spring 上下文通过 `Environment`、`@Value` 或 `@ConfigurationProperties` 读取这些值；第三方插件不应直接读配置文件，也不应依赖应用壳的配置类。

插件的 `GuiConfigContribution` 是其完整配置 section 的事实来源，可声明字段、分组、布局、动作、预设和联动。应用壳把它与核心配置合并为一份工具包无关的桌面文档并负责统一保存；Swing / Compose 只渲染，不各自维护插件页面。保存后，宿主会刷新插件配置源，并按字段与插件生命周期策略给出即时生效、后端重启或完整进程重启结果。手工编辑后不确定时，完整重启最稳妥。

## 插件凭据

密码、Cookie、Token、API key、secret 和 webhook key 等敏感值写入 `config/credentials/{pluginId}.properties`。宿主负责加密、权限、迁移和按 owner 注入；插件只从自己的子上下文 `Environment` 读取已经解密的属性值。

不要把凭据放入 `config.yaml` 或 `config/plugins/*.properties`，也不要让插件读取、解析或解密凭据文件。备份凭据时必须同时保护凭据主密钥；缺少原主密钥时，加密值不能在另一环境中恢复。

## 如何确认当前配置

1. 先看 GUI 配置页；它会合并宿主字段与当前已安装插件的配置贡献。
2. 宿主默认键以当前版本的 `DefaultConfigTemplate` 生成结果为准。
3. 插件字段以对应插件的 `GuiConfigContribution`、`@ConfigurationProperties` 或设置服务为准。
4. 不要从旧版示例复制已经外置的 `mail.*`、`push.*`、`notification.*` 或 `download.novel-*` 到 `config.yaml`；它们由各自插件拥有。
