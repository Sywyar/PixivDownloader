# 配置参考

日常配置优先在桌面 GUI「配置 / 设置」页修改并保存，再按界面提示即时应用或重启。手动编辑配置文件前，先确认配置项所属文件与所有者：

| 配置类型 | 路径 | 所有者 |
| --- | --- | --- |
| 宿主配置与插件启停状态 | `config/config.yaml` | 应用宿主 |
| 插件业务配置 | `config/plugins/{pluginId}.properties` | 对应插件 |
| 插件敏感凭据 | `config/credentials/{pluginId}.properties` | 对应插件（由宿主加密维护） |

首次启动时生成默认 `config/config.yaml`，升级时只追加缺失宿主键、不覆盖已有值。手动编辑必须使用 UTF-8 扁平 `key: value` 行，不要把配置项注释掉代替空值，也不要把已外置插件的键（如 `mail.*`、`push.*`、`notification.*`、`download.novel-*`）写回 `config.yaml`。

## 凭据密钥与备份

每个安装实例生成独立随机主密钥，以受保护的形式保存在 `config/credentials.key`。Windows 默认使用当前用户的 DPAPI；服务应始终以同一账号运行。更换 Windows 用户或电脑时，先导出包含凭据且设有密码的 `.pdbackup`，再在目标环境导入，仅复制配置目录不足以恢复凭据。

Linux、macOS 和 Docker 必须在首次保存或升级凭据前提供独立的保护密钥文件。文件内容是 32 个随机字节的标准 Base64 编码，只允许运行账号读取，放在配置目录及其备份之外。安装 OpenSSL 后，可在 Bash 中首次创建文件并为启动进程设置环境变量；已有文件不会被覆盖：

```bash
umask 077
mkdir -p "$HOME/.config/pixivdownload-secrets"
( set -C; openssl rand -base64 32 > "$HOME/.config/pixivdownload-secrets/credentials.key" )
export PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE="$HOME/.config/pixivdownload-secrets/credentials.key"
```

在同一终端启动应用；后台服务需在服务环境中设置该变量。Docker 将此文件只读挂载到 `/run/secrets/pixivdownload-credentials`，并把变量设为该容器内路径。也可在 Java 启动参数中使用 `-Dpixivdownload.credential-key-file=/绝对路径`。保护密钥应单独安全备份，不要每次启动重新生成；密钥丢失或不匹配时，应用保留原凭据并拒绝解密。

旧版凭据会在普通读写时自动升级，停用插件的凭据也参与迁移。旧密钥只用于旧格式升级；全部文件迁移完成后拒绝旧格式重放。损坏文件保留以便修复重试，其他插件仍可使用。迁移不会改变已有离线备份的保护方式；跨环境恢复使用加密 `.pdbackup`，导入后由目标安装密钥重新加密。

仓库的 Compose 模板会自动挂载保护密钥，宿主环境变量仍填写宿主文件路径，未设置时拒绝启动。Linux 宿主需让容器账号 UID/GID `10001:10001` 独占读取该文件，例如在生成后执行 `sudo chown 10001:10001 "$PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE"` 和 `sudo chmod 400 "$PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE"`；文件型 Compose secret 沿用宿主权限。

## 配置与环境迁移

Swing 与 Compose 设置页均提供「配置与环境迁移」，仅读取已保存的配置（导出前请先保存更改，导入前先处理未保存草稿）：

1. **导出 `.pdbackup`**：默认包含宿主与已注册插件配置、插件开关及桌面偏好；可按需勾选凭据、数据库、已保存状态和用户资源（收藏夹图标、小说参考音）。包含凭据、状态或数据库时必须设置归档密码，导出期间暂时停止后端。
2. **预览与导入**：在目标环境准备好兼容插件与数据库连接后选择归档预览（未安装插件的配置与文件不会写入，归档不自动安装插件或恢复信任授权）。勾选要导入的字段、数据库和文件组（资源须与数据库一同导入），确认后先备份目标并在完整进程重启后执行迁移。

导入保留目标环境的数据库连接、数据库 provider 选择与安装身份，凭据使用目标主密钥重加密；导入管理员凭据会替换登录账号并注销旧会话；计划任务恢复为停用，手动队列恢复为暂停。程序本体、插件包、作品文件与缓存不在归档内，作品目录需另行复制（路径映射仅更新声明路径字段与数据库前缀，`{0}` 固定为确认的绝对目录）。任务备份与逐库报告保存在 `data/environment-transfers/<任务 ID>/`（最新状态见 `pending.json`）；出现「需要恢复」时应用会阻止启动，请核对报告与备份，勿删除日志强行启动。

### Windows 安装目录搬迁

移动 Windows 安装目录后，运行安装包并选择新目录确认迁移，安装器会更新注册表安装路径、快捷方式、卸载记录及已有当前用户自启动项（不会开启原本关闭的自启动）。修复备份保存在新目录 `state/installation-backups/`；确认新位置启动与数据正常后再清理旧目录，切勿运行旧目录的卸载程序。旧 MSI 版本需先导出备份、卸载 MSI，再安装新版并导入。

## 宿主配置（`config/config.yaml`）

### 服务、下载与并发上限

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `6999` | HTTP / HTTPS 监听端口 |
| `debug.enabled` | `false` | 调试模式 |
| `download.root-folder` | `pixiv-download/{artwork_id}` | 插画最终保存目录模板；见下方说明 |
| `local-import.presets` | `[]` | 保存可重复使用的路径模板；在工具页保存后用于后续扫描；[用法与示例](/zh-cn/local-import) |
| `download.mode-templates.<mode>` | 见下方示例 | 按取得模式覆盖完整目录；空值使用默认模板 |
| `download.max-concurrent` | `10` | 下载并发上限；另最多排队 100 件作品，满额返回 429 |
| `database.maximum-pool-size` | `28` | 主数据库连接池上限（最小 `8`） |
| `ffmpeg.max-concurrent` | `2` | FFmpeg 转码与媒体探测共享并发数（范围 `1–8`，重启后端生效） |

固定安全上限（不可通过配置调高）：单张作品图片 / 封面 / 内嵌图上限 100 MiB，单次插画或小说下载任务累计上限 1 GiB；Ugoira ZIP 上限 100 MiB、最多 500 帧/条目、单条目解压 32 MiB、总解压 200 MiB、单帧 25,000,000 像素，单次转码最长 10 分钟、输出 100 MiB。服务同时校验 `Content-Length`、实际解码流与图片文件头白名单，超限中止并清理半文件。

### 主数据库与引擎切换

默认使用 `database-sqlite`（`data/pixiv_download.db`）。如需切换至 PostgreSQL 17 / 18（UTF8），先从插件市场安装 `database-postgresql` 并完整重启；主库与已适配插件私库共用所选引擎（SQLite 私库位于 `data/{pluginId}/plugin.db`，PostgreSQL 私库按 owner 隔离 schema 与受限角色）。

若 GUI 与数据库同时不可用，先通过宿主恢复窗口进入插件市场修复插件，或在「已安装」中重新启用被禁用的插件。重启后进入桌面数据库设置检查连接；数据库不可用时仍可打开该设置页。

在桌面「设置 → 运行与网络 → 主数据库」中切换：

1. 选择已加载的数据库插件并填写连接信息（密码留空保持已保存值），点击「测试连接」。
2. 点击「检查并继续」预检主库与全部插件私库的源、目标及处理方式（非空目标仅在 provider 支持安全备份替换时允许继续）。
3. 确认清单后，应用保存本页设置并自动完整重启，在新进程中完成备份、迁入、逐库校验（类型化 SHA-256、行数、生成键水位与 NUL 字符清洗）及原子替换，全部通过后才切换连接配置。仅修正同一数据库的连接参数时使用「应用连接并重启」。

迁移记录与备份保存在 `data/database-migrations/`（可能含计划凭据，请妥善保管并保留供启动复核与重试使用）。所选数据库插件缺失、禁用或连接失败时会进入恢复模式（业务 API 返回 503，后台数据库任务停止），不会自动改连其他引擎或新建空 SQLite。

#### PostgreSQL 准备与配置示例

预先创建专用数据库与主库 schema，授予建表、索引、序列、函数、触发器及读写权限；使用插件私库还需 `CREATEROLE` 与数据库 `CREATE` 权限，且不得向 `PUBLIC` 开放主库或其他 owner 权限。`currentSchema` 仅支持单个小写字母、数字或下划线名称（非数字开头，最长 63 字符，默认 `public`）。远程连接建议使用 `sslmode=verify-full`；密码放在独立配置项或环境变量 `DATABASE_PASSWORD`，禁止写入 JDBC URL（直接改 `config.yaml` 不会迁移旧数据）：

```yaml
database.provider: database-postgresql
database.engine: postgresql
database.jdbc-url: "jdbc:postgresql://db.example.com:5432/pixiv?currentSchema=pixiv&sslmode=verify-full"
database.username: pixiv_app
database.password: "REPLACE_WITH_DATABASE_PASSWORD"
```

#### 停机 CLI 迁移工具（可选）

需要独立安排停机并向**空目标库**迁移时，停止所有使用源库的进程，准备显式包含 `database.provider` 的 `source.yaml` 与 `target.yaml`（SQLite 源可配 `database.provider: database-sqlite` 与 `database.jdbc-url: "jdbc:sqlite:data/pixiv_download.db"`），在完整解压目录执行：

```powershell
java '-Dloader.main=top.sywyar.pixivdownload.migration.DatabaseMigration' -cp '<boot.jar>' org.springframework.boot.loader.launch.PropertiesLauncher --source-config source.yaml --target-config target.yaml --backup migration/source.backup --report migration/report.json
```

- 源库含小说可重建对象时显式附加 `--rebuild-objects novels_fts,novel_soft_delete_cleanup`（仅填实际存在的对象）。
- 准备卸载但保留数据库数据的插件，可在卸载前用 `--export-schema schema.json` 导出声明，迁移时用 `--retained-schema schema.json` 补入。
- 仅当 `report.json` 状态为 `DATA_VERIFIED`（且全部 `stores` 为 `COMMITTED`）时才表示复制核验完成，随后再切换生效配置并启动应用。

### 图片与 Ugoira 保存格式

在桌面「设置 → 下载」或网页下载设置中多选保存格式（至少保留一项，默认值保存在 `config/plugins/download-workbench.properties` 的 `download-workbench.media.*`）：

- **图片**：原始格式（默认）、PNG、JPG、WebP。取消原始格式时，仅当所有所选派生格式生成成功后才删除原图。
- **Ugoira**：WebP（默认）、GIF、APNG、MP4（需 FFmpeg H.264 编码器）、原始 ZIP（同时保存 `.frames.properties` 帧时序供离线补转）。
- **转码参数**：`quality` 默认 `90`（`1–100`），`webp-lossless` 默认 `false`，`maximum-edge` 默认 `0`（不缩放，可设 `1–16383`）；仅作用于新编码副本。

已有作品可在桌面「工具 → 历史媒体处理」按目标格式检测并补齐缺失格式与缩略图（单批最多 500 件作品、10,000 个候选文件）。画廊按「保留原图 → WebP → PNG → JPG」顺序优先展示，MP4 使用视频播放器。

### 插件市场

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | 插件市场总开关；关闭后不访问任何插件仓库 |
| `plugin-catalog.official-repository-enabled` | `true` | 是否启用内嵌官方仓库 |
| `plugin-catalog.community-repository-enabled` | `true` | 是否启用内嵌社区仓库 |
| `plugin-catalog.connect-timeout-ms` | `15000` | 仓库连接超时（毫秒） |
| `plugin-catalog.read-timeout-ms` | `60000` | 仓库读取超时（毫秒） |
| `plugin-catalog.max-manifest-bytes` | `1048576` | 单个清单大小上限（1 MiB） |
| `plugin-catalog.max-package-bytes` | `201326592` | 单个插件包大小上限（192 MiB） |
| `plugin-catalog.repositories` | 空列表 | 自定义插件仓库列表 |

推荐通过桌面「设置 → 插件市场设置」导入 `repository.json` 描述符或填写自定义仓库；手动配置示例：

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

仓库 `id` 必须唯一，不可使用保留值 `official`、`community` 或 `configured`。`proxy-policy` 支持 `direct-strict`（直连、仅公网 HTTPS、禁重定向）、`proxy-trusted`（走应用代理、仅对内置信任主机允许最多 5 跳重定向并逐跳复核）和 `custom`。

### 出站代理与浏览器 PAC

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `proxy.enabled` | `true` | 启用宿主出站 HTTP 代理 |
| `proxy.host` | `127.0.0.1` | 代理主机 |
| `proxy.port` | `7890` | 代理端口 |

若希望浏览器访问 `pixiv.net`（如配合油猴脚本）也走后端配置的同一代理、但不想开启全局系统代理，可将系统或浏览器的「自动代理配置脚本（PAC）URL」设为 `http://localhost:6999/proxy.pac`（仅本机可访问，随代理配置热重载自动更新，仅对 Pixiv 相关域名走代理）。

### 多人模式配额与访客限流

`multi-mode.*` 仅约束多人模式下未登录且无邀请码的**游客**；持邀请码的**访客**在 `solo` / `multi` 模式下均使用独立的 `guest-invite.*` 限流：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `multi-mode.quota.enabled` | `true` | 游客配额开关 |
| `multi-mode.quota.max-artworks` | `50` | 周期内最大作品配额 |
| `multi-mode.quota.reset-period-hours` | `24` | 配额重置周期（小时） |
| `multi-mode.quota.archive-expire-minutes` | `60` | 打包归档过期分钟数 |
| `multi-mode.quota.limit-image` | `0` | 单作品超过多少张图折算多份配额（`0` 不折算） |
| `multi-mode.quota.max-proxy-requests` | `200` | 代理请求配额 |
| `multi-mode.quota.archive-max-concurrent` | `10` | 归档并发上限 |
| `multi-mode.post-download-mode` | `pack-and-delete` | 可选 `pack-and-delete`、`never-delete`、`timed-delete` |
| `multi-mode.delete-after-hours` | `72` | `timed-delete` 保留小时数 |
| `multi-mode.limit-page` | `3` | 页数限制（`0` 不限制） |
| `multi-mode.request-limit-minute` | `300` | 游客每分钟请求上限 |
| `multi-mode.static-resource-request-limit-minute` | `1200` | 游客每分钟静态资源请求上限 |
| `guest-invite.request-limit-minute` | `300` | 邀请访客每分钟请求上限 |
| `guest-invite.static-resource-request-limit-minute` | `1200` | 邀请访客每分钟静态资源上限 |
| `guest-invite.tts-request-limit-minute` | `30` | 邀请访客每分钟 TTS 请求上限 |
| `setup.login-rate-limit-minute` | `10` | 登录与邀请码兑换每分钟尝试上限 |

### 维护窗口、界面、更新与计划任务

- **维护窗口**：`maintenance.enabled` 默认 `true`；`maintenance.monday.enabled=true`，其余星期默认 `false`，各星期时间键 `maintenance.<day>.time` 默认 `10:00`。
- **语言与桌面界面**：`app.language` 默认空（跟随系统，只影响界面，运行日志固定使用英文）；`app.theme` 默认 `system`（可选 `light`、`dark` 及当前界面扩展主题）；`app.gui-provider` 默认空（默认选 Compose，切换需完整重启）；`app.config-menu-expand-all`（默认 `false`）仅作用于 Swing。
- **在线更新**：`update.enabled=true`、`update.auto-check=true`；`update.manifest-url` 与 `update.nightly-manifest-url` 指向官方签名清单；`update.check-nightly` 在正式版默认 `false`、nightly 版默认 `true`。
- **计划任务宿主**：`schedule.*` 键见[计划任务相关配置](/zh-cn/scheduled-tasks#排障与相关配置)。
- **插件开关**：`plugins.{pluginId}.enabled` 由宿主管理，生效方式取决于插件的生命周期策略（见[插件管理](/zh-cn/plugin-management)）。

### HTTPS 与反向代理

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `ssl.domain` | `localhost` | 外部访问域名 |
| `ssl.type` | `pem` | `pem` 或 `jks`（同时配置时 PEM 优先） |
| `server.ssl.enabled` | `false` | 是否启用内置 HTTPS |
| `server.ssl.certificate` / `server.ssl.certificate-private-key` | 空 | PEM 证书与私钥路径 |
| `server.ssl.key-store-type` / `key-store` / `key-store-password` | `JKS` / 空 / 空 | JKS 证书库配置 |
| `ssl.http-redirect` / `ssl.http-redirect-port` | `false` / `80` | HTTP 自动跳转 HTTPS |
| `server.trusted-proxy-cidrs` | 空 | 受信反向代理出口 IPv4 / IPv6 CIDR（逗号分隔） |

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

`server.trusted-proxy-cidrs` 留空时按直连模式运行，拒绝任何包含 `Forwarded`、`X-Forwarded-*` 或 `X-Real-IP` 的请求。配置后，只填写实际连接后端的反代出口或容器网段（严禁填 `0.0.0.0/0`、`::/0` 或客户端网段）；受信反代必须为每个请求提供完整且单一格式的转发头（含 `for`/`proto`/`host` 的 RFC `Forwarded`，或成套 `X-Forwarded-For` + `X-Forwarded-Proto` + `X-Forwarded-Host`），缺失、混用或由受信 CIDR 无头直连均会返回 HTTP 400。

## 插件配置、凭据与扩展设置

- **插件业务配置（`config/plugins/{pluginId}.properties`）**：使用 UTF-8 Java properties 格式，由桌面设置页聚合展示并统一保存；禁止覆盖宿主键或写入明文凭据。
- **插件凭据（`config/credentials/{pluginId}.properties`）**：存储密码、Cookie、API Key、Secret 与 Webhook 密钥，由宿主加密并按 owner 注入插件上下文。跨环境恢复使用包含凭据且设有密码的 `.pdbackup`；同环境文件备份还需保留 `config/credentials.key` 及其解锁条件。
- **翻译设置（`translation` 插件）**：普通项位于 `config/plugins/translation.properties`（`translation.engine` 默认 `ai`，可选 `youdao`、`libretranslate`；`translation.libretranslate-url` 不含 `/translate`；`translation.use-proxy` 默认 `false`，仅控制有道与 LibreTranslate；`translation.max-concurrent` 默认 `10`），保存后重启后端生效。详见[小说与朗读](/zh-cn/novel#翻译引擎（管理员）)。
- **登录验证策略**：`setup.login-verification-policies` 默认为空；可配置最多 16 个逗号分隔的 `LoginVerification.policyId()` 策略键，重启后端生效。配置前请确认对应验证插件已启用，详见[操作验证码](/zh-cn/captcha)与[第三方插件 SDK](/zh-cn/plugin-development#不依赖-gui-的凭据与通用操作验证)。

## 作品保存目录模板 :id=directory-templates

在「设置 → 下载」中分别编辑插画和小说的保存目录。展开后的路径就是最终作品目录，不会再自动追加作者或作品 ID 层级。相对路径从应用工作目录解析，也可填写绝对路径；保存后重启应用生效。

每类作品保留一个默认模板。点击「添加模式」可为单作品、User、系列、搜索或快捷模式设置完整覆盖；未添加、移除或留空的模式使用该类作品的默认模板。模式表示本次取得来源，作品属于某个系列并不等于使用系列模式。

插画模板保存在宿主 `config/config.yaml` 的 `download.root-folder` 与 `download.mode-templates.<mode>`；小说模板由 `novel` 插件保存在 `config/plugins/novel.properties` 的 `download.novel-directory-template` 与 `download.novel-mode-templates.<mode>`。`<mode>` 为 `single-import`、`user`、`series`、`search` 或 `quick`。

可用变量：`{artwork_id}`、`{artwork_title}`、`{author_id}`、`{author_name}`、`{collection_name}`、`{collection_id}`、`{series_id}`、`{series_name}`、`{series_title}`、`{series_order}`、`{username}`、`{R18}`。变量名按这里的拼写精确匹配。

模板必须包含独占一个路径段的 `{artwork_id}`，该段可以带固定文字，例如 `novel-{artwork_id}`，但不能同时包含其他变量。小说 ID 仍是纯数字，建议用 `novel-` 区分插画和小说。

`{username}` 是取得来源的用户名。收藏夹、系列和 `R18` 变量缺值时展开为空；展开后完全为空的目录层会省略，同层的固定文字保留。其他必需值缺失会拒绝本次下载。`{page}`、`{extension}`、`{name}` 等本地导入变量不能用于保存目录。

以下为默认模板和可选的模式模板示例：

| 用途 | 模板 |
| --- | --- |
| 插画默认 | `pixiv-download/{artwork_id}` |
| 插画 User | `pixiv-download/{username}/{R18}/{artwork_id}` |
| 小说默认 | `pixiv-download/novel-{artwork_id}` |
| 小说 User | `pixiv-download/{username}/{R18}/novel-{artwork_id}` |
| 系列模式示例 | `pixiv-download/{series_id}/{series_order}/{artwork_id}` |

选中配置了目录的收藏夹时，收藏夹目录替换模板中首个含变量的目录层之前的固定路径；该目录层及其后续部分完整保留，包括固定文字。例如 `pixiv-download/novel-{artwork_id}` 配合收藏夹目录 `D:/Favorites`，会按 `D:/Favorites/novel-{artwork_id}` 展开。

这些模板始终指定下载目录，末尾不要求 `/`。本地导入采用另一套规则：以 `/` 结尾匹配文件夹，不以 `/` 结尾匹配文件，见[本地作品导入](/zh-cn/local-import)。修改模板只影响后续下载，不会移动已有文件；搬迁已有目录见[存储与备份](/zh-cn/storage)。
