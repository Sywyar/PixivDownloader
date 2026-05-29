# 配置参考

`config.yaml` 是 PixivDownloader 的主要运行时配置文件，位于 `config/` 目录下。首次启动时自动生成，后续版本新增配置项会自动追加。

> [!NOTE]
> 可通过 GUI 的「配置」标签页可视化编辑，或直接编辑 `config.yaml` 文件。大部分配置修改后需**重启服务**生效，具体见各配置项的说明。

---

## 完整配置项说明

### 基础服务

```yaml
server.port: 6999
```
HTTP 服务端口号。修改后**需重启**。

```yaml
download.root-folder: pixiv-download
```
下载文件存储根目录。支持相对路径（相对于工作目录）或绝对路径。修改后**需重启**。

```yaml
download.user-flat-folder: false
```
是否使用扁平目录结构。`true` 时所有作品直接存放在根目录下，不创建子文件夹。`false` 时每个作品创建独立子目录。**支持热重载**。

```yaml
download.max-concurrent: 10
```
作品下载最大并发数。**支持热重载**。

```yaml
download.novel-max-concurrent: 10
```
小说下载最大并发数。**支持热重载**。

---

### 代理配置

后端的全部对外访问都经此 HTTP 代理：访问 Pixiv 与下载作品、在线更新（检查/下载新版本）、下载内置 FFmpeg、在线 TTS（听书的 Edge 神经语音）。首次安装向导（网页 `setup.html`、桌面 GUI 引导、CLI `--setup`）会引导配置代理。

```yaml
proxy.enabled: true
```
是否启用 HTTP 代理。**支持热重载**。

```yaml
proxy.host: 127.0.0.1
```
代理服务器地址。**支持热重载**。

```yaml
proxy.port: 7890
```
代理服务器端口。**支持热重载**。

> [!TIP]
> GUI 中的 FFmpeg 下载按钮同样复用此代理配置，便于拉取 GitHub 上的 FFmpeg 发布包。

---

### 多人模式配置

仅多人模式（Multi Mode）生效，自用模式不受影响。

#### 配额

```yaml
multi-mode.quota.enabled: true
```
是否启用配额。`true` 时每位访客有下载配额限制。**支持热重载**。

```yaml
multi-mode.quota.max-artworks: 50
```
每位访客在一个重置周期内可下载的最大作品数。**支持热重载**。

```yaml
multi-mode.quota.reset-period-hours: 24
```
配额重置周期（小时）。**支持热重载**。

```yaml
multi-mode.quota.archive-expire-minutes: 60
```
配额超额后生成的下载归档链接有效期（分钟）。访客可保存存档链接，在有效期内恢复下载。**支持热重载**。

```yaml
multi-mode.quota.limit-image: 0
```
单作品图片数阈值。当一个作品的总图片数超过此值，按多个配额计算。`0` 表示不限制。**支持热重载**。

```yaml
multi-mode.quota.max-proxy-requests: 200
```
每位访客在一个重置周期内允许发起的 Pixiv 代理请求数上限。控制通过后端代理 API 对 Pixiv 的访问频次。**支持热重载**。

```yaml
multi-mode.quota.archive-max-concurrent: 10
```
打包归档任务的最大并发数。**支持热重载**。

#### 下载后处理

```yaml
multi-mode.post-download-mode: pack-and-delete
```
多人模式下作品下载完成后的处理方式：
- `pack-and-delete`：打包后删除原始文件
- `never-delete`：永不删除
- `timed-delete`：定时删除（配合 `delete-after-hours`）**支持热重载**。

```yaml
multi-mode.delete-after-hours: 72
```
当 `post-download-mode` 为 `timed-delete` 时，多少小时后自动删除已下载作品。**支持热重载**。

#### 限流

```yaml
multi-mode.request-limit-minute: 300
```
每位访客每分钟最大 API 请求数。**支持热重载**。

```yaml
multi-mode.static-resource-request-limit-minute: 1200
```
每位访客每分钟最大静态资源请求数。基于 TCP 源 IP 限流，上限为 50000 个独立 IP。仅作用于多人模式未登录的普通游客（邀请访客的静态资源限流见下方 `guest-invite.*`）。**支持热重载**。

```yaml
multi-mode.limit-page: 3
```
搜索模式下每页返回结果数限制。**支持热重载**。

#### 访客邀请限流（`guest-invite.*`）

> [!NOTE]
> 以下限流作用于**持有邀请会话的访客**。访客邀请独立于 solo/multi 运行模式（两种模式下都可用），因此这些限流在两种模式下都生效，且**按邀请码计数**（同一邀请码跨浏览器共享配额）。管理员 / solo 拥有者永不受限。

```yaml
guest-invite.request-limit-minute: 300
```
邀请访客每个邀请码每分钟最大 API 请求数。`0` 表示不限制。**支持热重载**。

```yaml
guest-invite.static-resource-request-limit-minute: 1200
```
邀请访客每个邀请码每分钟最大静态资源请求数。`0` 表示不限制。**支持热重载**。

```yaml
guest-invite.tts-request-limit-minute: 30
```
邀请访客每个邀请码每分钟最大在线 TTS（语音合成）请求数。`0` 表示不限制；管理员与浏览器内置语音引擎不受限。**支持热重载**。

> [!WARNING]
> **多人模式部署在反向代理/CDN 后的限流注意事项**
>
> 以上限流基于 TCP 源 IP（`request.getRemoteAddr()`）。如果部署在 nginx、Caddy、Cloudflare 等反向代理之后：
> - 所有用户共享同一限流计数
> - 任一用户打满配额会导致全部访客被拒绝
>
> 建议在反代层根据 `X-Forwarded-For` / `X-Real-IP` 做限流，并将后端限流调高或设为 `0` 关闭，避免双重限流。

---

### 登录安全

```yaml
setup.login-rate-limit-minute: 10
```
登录端点每分钟每 IP 最大尝试次数。`0` 表示不限制。此配置在 solo 和 multi 模式下均生效。**支持热重载**。

---

### 维护

```yaml
maintenance.enabled: true
```
是否启用定期维护任务。设为 `false` 后同时禁用调度和手动触发端点。**支持热重载**。

```yaml
maintenance.monday.enabled: true
maintenance.monday.time: "10:00"
```
周一的维护开关和时间（HH:mm 24 小时制，本机时区）。默认周一启用，其余星期默认关闭。**支持热重载**。

```yaml
maintenance.tuesday.enabled: false
maintenance.tuesday.time: "10:00"
maintenance.wednesday.enabled: false
maintenance.wednesday.time: "10:00"
maintenance.thursday.enabled: false
maintenance.thursday.time: "10:00"
maintenance.friday.enabled: false
maintenance.friday.time: "10:00"
maintenance.saturday.enabled: false
maintenance.saturday.time: "10:00"
maintenance.sunday.enabled: false
maintenance.sunday.time: "10:00"
```
周一至周日的维护开关和时间。可为多个星期分别启用并指定不同的维护开始时间。维护期间所有请求被拦截：页面请求重定向到维护提示页，API 请求返回 503。**支持热重载**。

> ⚠️ 时间值在 `config.yaml` 中需要加双引号（如 `"23:50"`），否则会被 YAML 解析为六十进制数字导致错误。通过 GUI 修改时会自动处理。

---

### SSL/HTTPS

```yaml
ssl.domain: localhost
```
SSL 证书域名，用于构造外部 URL。**需重启**。

```yaml
ssl.type: pem
```
证书类型：`pem`（推荐）或 `jks`。同时配置时 PEM 优先。**需重启**。

```yaml
server.ssl.enabled: false
```
是否启用 SSL。设为 `true` 时需配置证书。**需重启**。

#### PEM 证书（推荐）

```yaml
server.ssl.certificate:
server.ssl.certificate-private-key:
```
PEM 格式证书和私钥文件的绝对路径。**需重启**。

#### JKS 证书

```yaml
server.ssl.key-store-type: JKS
server.ssl.key-store:
server.ssl.key-store-password:
```
JKS 格式密钥库路径和密码。**需重启**。

#### HTTP 重定向

```yaml
ssl.http-redirect: false
```
启用 SSL 后是否将 HTTP 流量重定向到 HTTPS。**需重启**。

```yaml
ssl.http-redirect-port: 80
```
HTTP 重定向监听端口。**需重启**。

---

### 语言

```yaml
app.language:
```
应用语言设置。留空时自动检测系统语言，可设为：
- 空：自动检测
- `zh-CN`：中文
- `en-US`：英文

**支持热重载**。

---

### 在线更新

```yaml
update.enabled: true
```
是否启用在线更新检查。**支持热重载**。

```yaml
update.manifest-url: https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json
```
更新清单 URL，用于检查新版本。**支持热重载**。

```yaml
update.auto-check: true
```
启动后是否自动检查更新。**支持热重载**。

```yaml
update.nightly-manifest-url: https://github.com/Sywyar/PixivDownloader/releases/download/nightly/update.json
```
每日构建版（nightly）更新清单 URL。**支持热重载**。

```yaml
update.check-nightly: false
```
是否检查每日构建版更新。正式版默认 `false`，每日构建版默认 `true`；未配置时按当前运行版本自动决定。**支持热重载**。

### 计划任务（管理员）

```yaml
schedule.enabled: true
```
是否启用计划任务调度。关闭后调度检查直接跳过，所有计划任务都不运行（已创建的任务保留，不删除）。**支持热重载**。

```yaml
schedule.tick-interval-ms: 60000
```
调度检查到期任务的间隔（毫秒），默认 60000（60 秒）。由启动时的定时器解析，**修改后需重启**才生效（不支持热重载）。

```yaml
schedule.max-tasks: 100
```
单库最多可创建的计划任务数，防滥用。默认 100。**支持热重载**。

```yaml
schedule.inbox-check-every: 500
```
绑定 Cookie 的计划任务每成功下载这么多个作品就读一次 Pixiv 站内信，检测「过度访问」警告（跳过 / 过滤的作品不计）。默认 500。**支持热重载**。

```yaml
schedule.auth-failure-circuit-breaker: 5
```
同一轮内单个作品连续抓取失败达到此次数即判定登录态失效、熔断挂起该任务等待重新授权。默认 5。**支持热重载**。

```yaml
schedule.pending-max-attempts: 5
```
被隔离的失败作品自动重试的最大次数，到上限即标记为「需人工」、停止自动重试。默认 5。**支持热重载**。

```yaml
schedule.overuse-defer-default-minutes: 60
```
账号因过度访问被暂停后，「稍后继续」默认延迟的分钟数（最低 60）。默认 60。**支持热重载**。

> 计划任务的使用说明（创建、来源类型、Cookie 授权、过度访问暂停与恢复等）见[使用指南](zh-Usage-Guide#-计划任务仅管理员)。

### 邮件 / SMTP

> 这里配置一个 SMTP 邮箱用于接收来自 PixivDownloader 的运营通知（如计划任务遇到过度访问警告 / 登录态失效时的提醒）。所有字段都可在 **GUI 配置页 → 邮件 / SMTP** 直接编辑，配好之后可点击"发送测试邮件"按钮发送一封"邮件配置成功"邮件验证设置；同一面板底部还有"发送所有邮件模板"按钮，可使用示例数据一次性把全部 4 种通知模板（配置成功 / 过度访问暂停 / 鉴权失效 / 熔断挂起）逐封发送给收件人，便于一次性预览所有邮件样式。**密码 / 授权码绝不会出现在日志或邮件正文中。** 修改后**支持热重载**。

```yaml
mail.enabled: false
```
邮件发送总开关；关闭时所有发信都跳过。默认关闭。

```yaml
mail.host: smtp.example.com
mail.port: 587
mail.security: starttls
```
SMTP 主机 / 端口 / 加密方式。`mail.security` 可选 `none` / `ssl` / `starttls`（默认 `starttls`；`ssl` 即 SMTPS，常用 465；`starttls` 常用 587）。GUI 中提供"服务商预设"下拉，可一键填入 163 / QQ / Gmail / Outlook / iCloud / 网易企业 / 腾讯企业 / 阿里云企业 / Microsoft 365 / Google Workspace 等主流邮箱的标准值。

```yaml
mail.username: you@example.com
mail.password:
```
SMTP 用户名（通常是完整邮箱地址）和**授权码 / 应用专用密码**。**注意**：国内主流邮箱（163 / 126 / QQ / 网易企业邮箱等）通常拒绝登录密码，需要在邮箱网页"账号设置"中申请"客户端授权码"作为 SMTP 密码；Gmail / Outlook.com / iCloud / Yahoo 需要在帐号开启两步验证后生成"应用专用密码"。Microsoft 365 / Google Workspace 企业邮箱目前强制 OAuth，**本程序当前版本不支持 OAuth**，请管理员显式开启 SMTP AUTH 或使用其它邮箱。

```yaml
mail.from:
mail.to: admin@example.com,ops@example.com
```
发件人地址（留空时回退用 `mail.username`），收件人地址（支持逗号分隔多个）。

```yaml
mail.socks-proxy:
```
可选 SOCKS 代理，格式 `host:port`（如 `127.0.0.1:1080`）。**既有的 `proxy.*` HTTP 代理不承载 SMTP**，故此处独立配置；为空表示直连。

```yaml
mail.subject-prefix: "[PixivDownloader]"
```
邮件主题前缀，便于客户端过滤；自动拼接在模板标题之前。

---

## 配置热重载说明

以下配置项修改后可通过 GUI 保存**无需重启**：

| 配置项 | 热重载 |
|--------|:------:|
| `proxy.*` | ✅ |
| `multi-mode.quota.*` | ✅ |
| `multi-mode.request-limit-minute` | ✅ |
| `multi-mode.static-resource-request-limit-minute` | ✅ |
| `multi-mode.limit-page` | ✅ |
| `guest-invite.request-limit-minute` | ✅ |
| `guest-invite.static-resource-request-limit-minute` | ✅ |
| `guest-invite.tts-request-limit-minute` | ✅ |
| `multi-mode.post-download-mode` | ✅ |
| `multi-mode.delete-after-hours` | ✅ |
| `download.user-flat-folder` | ✅ |
| `download.max-concurrent` | ✅ |
| `download.novel-max-concurrent` | ✅ |
| `setup.login-rate-limit-minute` | ✅ |
| `maintenance.enabled` | ✅ |
| `maintenance.monday.enabled` | ✅ |
| `maintenance.monday.time` | ✅ |
| `maintenance.tuesday.enabled` | ✅ |
| `maintenance.tuesday.time` | ✅ |
| `maintenance.wednesday.enabled` | ✅ |
| `maintenance.wednesday.time` | ✅ |
| `maintenance.thursday.enabled` | ✅ |
| `maintenance.thursday.time` | ✅ |
| `maintenance.friday.enabled` | ✅ |
| `maintenance.friday.time` | ✅ |
| `maintenance.saturday.enabled` | ✅ |
| `maintenance.saturday.time` | ✅ |
| `maintenance.sunday.enabled` | ✅ |
| `maintenance.sunday.time` | ✅ |
| `server.ssl.*` | ❌ 需重启 |
| `ssl.*` | ❌ 需重启 |
| `app.language` | ✅ |
| `update.*` | ✅ |
| `schedule.enabled` | ✅ |
| `schedule.max-tasks` | ✅ |
| `schedule.inbox-check-every` | ✅ |
| `schedule.auth-failure-circuit-breaker` | ✅ |
| `schedule.pending-max-attempts` | ✅ |
| `schedule.overuse-defer-default-minutes` | ✅ |
| `schedule.tick-interval-ms` | ❌ 需重启 |
| `mail.*` | ✅ |
| `server.port` | ❌ 需重启 |
| `download.root-folder` | ❌ 需重启 |

---

## 配置示例

```yaml
# ========================================================
# PixivDownloader 运行时配置
# 修改后需重启服务才能生效
# ========================================================

server.port: 6999                          # HTTP 服务端口

download.root-folder: pixiv-download        # 下载文件存储目录
download.user-flat-folder: false            # 是否使用扁平目录
download.max-concurrent: 10                 # 作品下载最大并发数
download.novel-max-concurrent: 10           # 小说下载最大并发数

# --- 代理配置 ---
proxy.enabled: true
proxy.host: 127.0.0.1
proxy.port: 7890

# --- 多人模式配置 ---
multi-mode.quota.enabled: true
multi-mode.quota.max-artworks: 50
multi-mode.quota.reset-period-hours: 24
multi-mode.quota.archive-expire-minutes: 60
multi-mode.quota.limit-image: 0
multi-mode.quota.max-proxy-requests: 200
multi-mode.quota.archive-max-concurrent: 10

multi-mode.post-download-mode: pack-and-delete
multi-mode.delete-after-hours: 72

multi-mode.request-limit-minute: 300
multi-mode.static-resource-request-limit-minute: 1200
multi-mode.limit-page: 3

# --- 访客邀请限流（solo / multi 两种模式下均作用于邀请访客，按邀请码计数）---
guest-invite.request-limit-minute: 300
guest-invite.static-resource-request-limit-minute: 1200
guest-invite.tts-request-limit-minute: 30

# --- 登录安全 ---
setup.login-rate-limit-minute: 10

# --- 维护 ---
maintenance.enabled: true
maintenance.monday.enabled: true
maintenance.monday.time: 10:00
maintenance.tuesday.enabled: false
maintenance.tuesday.time: 10:00
maintenance.wednesday.enabled: false
maintenance.wednesday.time: 10:00
maintenance.thursday.enabled: false
maintenance.thursday.time: 10:00
maintenance.friday.enabled: false
maintenance.friday.time: 10:00
maintenance.saturday.enabled: false
maintenance.saturday.time: 10:00
maintenance.sunday.enabled: false
maintenance.sunday.time: 10:00

# --- SSL/HTTPS ---
ssl.domain: localhost
ssl.type: pem
server.ssl.enabled: false
server.ssl.certificate:
server.ssl.certificate-private-key:
server.ssl.key-store-type: JKS
server.ssl.key-store:
server.ssl.key-store-password:
ssl.http-redirect: false
ssl.http-redirect-port: 80

# --- 语言 ---
app.language:

# --- 在线更新 ---
update.enabled: true
update.manifest-url: https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json
update.auto-check: true
update.nightly-manifest-url: https://github.com/Sywyar/PixivDownloader/releases/download/nightly/update.json
update.check-nightly: false

# 计划任务（管理员）
schedule.enabled: true
schedule.tick-interval-ms: 60000
schedule.max-tasks: 100
schedule.inbox-check-every: 500
schedule.auth-failure-circuit-breaker: 5
schedule.pending-max-attempts: 5
schedule.overuse-defer-default-minutes: 60

# 邮件 / SMTP（计划任务通知）
mail.enabled: false
mail.host:
mail.port: 587
mail.security: starttls
mail.username:
mail.password:
mail.from:
mail.to:
mail.socks-proxy:
mail.subject-prefix: "[PixivDownloader]"
```
