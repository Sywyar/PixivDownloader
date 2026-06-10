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
下载文件存储根目录。支持相对路径（相对于工作目录）或绝对路径。修改后**需重启**。保持相对路径（默认）时，数据库记录会跟随软件目录解析，整个软件文件夹可直接拷贝搬迁，详见[存储原理](/zh-cn/storage)。

> 把已下载的文件迁移到新目录：先手动把文件移动到新位置，再用 GUI 管家「状态页 → 迁移下载目录」，把数据库里记录的下载 / 分类目录改写到新路径（**只改数据库记录、不会移动磁盘文件**，留空保持不变，新目录须为已存在的文件夹）。若改的是当前下载根目录，会提示是否一并把此处的 `download.root-folder` 同步改掉（否则新下载仍会保存到旧目录，需重启生效）。各分支的具体行为见[存储原理](/zh-cn/storage)。
>
> **不要在没搬文件的情况下直接改这里的目录**：相对路径模式下历史记录会跟着解析到新目录，文件没搬就会全部找不到（启动日志会警告）。

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

#### 让网页版 Pixiv 走同一个代理（PAC，无需系统代理）

后端访问 Pixiv 走上面配置的代理，**不依赖系统级代理**。但如果你还想在浏览器里直接打开 `pixiv.net`（例如配合油猴脚本使用），通常得额外开启 Clash 等工具的「系统代理 / system proxy」，体验割裂。

为此内置了代理自动配置（PAC）端点 `/proxy.pac`：

- 在系统或浏览器的「自动代理配置脚本（PAC）URL」处填入 `http://localhost:<端口>/proxy.pac`（端口与 `server.port` 一致；启用 HTTPS 时为 `https://<域名>:<端口>/proxy.pac`）。
- 之后仅 Pixiv 相关域名（`pixiv.net`、`*.pixiv.net`、`*.pximg.net`、`*.pixiv.org`、`*.fanbox.cc`、`*.pixivision.net`）会走上面 `proxy.*` 配置的同一个代理，其余流量一律直连，互不影响。
- 该端点**仅本机可访问**；`proxy.*` 的改动（含热重载）会自动反映到 PAC 内容，无需重启。
- 当 `proxy.enabled: false` 或 host/port 为空时，PAC 对所有域名返回直连（等同关闭本特性）。

这样就可以**一直关闭系统代理**，由本配置统一决定网页版 Pixiv 是否走代理，免去来回切换。

##### 各浏览器 / 系统的设置入口

> [!TIP]
> 下表中的 `chrome://`、`edge://`、`about:` 是浏览器内部地址，出于安全限制**不能从网页里点链接跳转**，需要把它**复制到浏览器地址栏后回车**才能打开——在地址栏里它就相当于「一键直达」对应设置项。`ms-settings:` 可在地址栏或 `Win+R` 运行框中打开。

| 浏览器 / 系统 | 打开设置的地址 | 填 PAC URL 的位置 |
|---|---|---|
| **Firefox** | `about:preferences#general` | 拉到底「网络设置 → 设置…」→ 选「自动代理配置 URL(PAC)」→ 填入地址 → 确定。Firefox 使用**独立**代理设置，不影响系统与其它程序，最贴合「仅浏览器、免系统代理」。 |
| **Chrome（Windows）** | `chrome://settings/system` | 点「打开您计算机的代理设置」，进入下方的 Windows 系统代理设置填写。Chrome 在 Windows 上跟随系统代理，没有独立 PAC 入口。 |
| **Edge（Windows）** | `edge://settings/system` | 同上，点「打开计算机的代理设置」跳到 Windows 系统代理设置填写。 |
| **Windows 系统代理** | `ms-settings:network-proxy` | 「自动代理设置 → 使用安装脚本」打开开关，「脚本地址」填入 `http://localhost:6999/proxy.pac`，保存。Chrome / Edge 及所有跟随系统代理的程序都会生效。 |
| **macOS 系统代理** | 系统设置 → 网络 → 对应网络 →「详细信息… → 代理」 | 勾选「自动代理配置」，URL 填入 PAC 地址。Safari / Chrome 等跟随系统代理。 |

> [!WARNING]
> Clash 等工具的「系统代理 / system proxy」开关会**覆盖** Windows 的代理设置（包括这里填的 PAC 脚本地址）。采用本 PAC 方案时请**保持 Clash 的系统代理开关关闭**，否则两者会互相覆盖。Firefox 因为用独立设置，不受此影响。

> 地址里的端口需与你的 `server.port` 一致（示例用 6999）；启用 HTTPS 时填 `https://<域名>:<端口>/proxy.pac`。

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

> 邮件与下面的「推送通知」同属 **GUI 配置页 →「通知」** 分组，下拉切换要编辑的服务，已启用的服务都会生效。

---

### 推送通知（多通道）

> 除邮件外还支持多种「推送」渠道，与邮件同属 **GUI 配置页 →「通知」** 分组。可同时启用任意多个渠道；每个渠道在 GUI 里都有独立的「启用」开关、配置字段，以及一个「测试此渠道」按钮（用当前表单值发送一条测试消息，无需先保存即可验证连通性）。**所有密钥 / Token 绝不会出现在日志或推送正文中。** 全部 `push.*` 字段**支持热重载**。

```yaml
push.enabled: false
```
推送总开关；关闭时所有渠道都不发送。默认关闭。

> 下面每个渠道都有自己的「启用」开关（如 `push.bark.enabled`）和「是否走代理」开关（`push.<渠道>.use-proxy`，开启后该渠道请求走 `proxy.*` 配置的 HTTP 代理，独立于全局开关；Telegram 默认开启，其余默认关闭）。

#### Bark（iOS）

```yaml
push.bark.enabled: false
push.bark.server: https://api.day.app
push.bark.device-key:
push.bark.sound:
```
Bark 是 iOS 上的推送 App。`server` 为官方公共服务器或你自建的地址；`device-key` 在 Bark App 中获取；`sound` 为可选提示音名称，留空用 App 默认。

#### 钉钉（DingTalk）

```yaml
push.dingtalk.enabled: false
push.dingtalk.access-token:
push.dingtalk.secret:
```
钉钉群「自定义机器人」。在钉钉群「设置 → 智能群助手 → 添加机器人 → 自定义」创建机器人，**在「安全设置」里勾选「加签」**。创建完成后会得到一个形如 `https://oapi.dingtalk.com/robot/send?access_token=xxxx` 的 Webhook 链接——把 `access_token=` 后面那一段填入 `access-token`；把勾选「加签」后生成的 **`SEC` 开头的密钥**填入 `secret`（该密钥关闭页面后无法再次查看，请及时复制）。若改用「自定义关键词」或「IP 白名单」，则 `secret` 留空。

#### Telegram

```yaml
push.telegram.enabled: false
push.telegram.bot-token:
push.telegram.chat-id:
push.telegram.use-proxy: true
```
向 [@BotFather](https://t.me/BotFather) 申请 Bot 获得 `bot-token`；`chat-id` 为目标会话（用户 / 群 / 频道）的 id。国内通常需开启代理（`use-proxy` 默认 `true`）。

#### 飞书（Feishu）

```yaml
push.feishu.enabled: false
push.feishu.webhook-key:
push.feishu.secret:
```
飞书群「自定义机器人」。`webhook-key` 为机器人 Webhook 地址 `.../bot/v2/hook/` 之后那一段；若在机器人「安全设置」里启用「签名校验」，把密钥填入 `secret`，否则留空。

#### 企业微信（WeCom）

```yaml
push.wecom.enabled: false
push.wecom.key:
```
企业微信群机器人。`key` 为机器人 Webhook 链接 `?key=` 后面那一段。

#### PushPlus（推送加）

```yaml
push.pushplus.enabled: false
push.pushplus.token:
```
推送到微信。`token` 为 PushPlus 用户令牌，在 [官网](https://www.pushplus.plus) 或「pushplus 推送加」微信公众号回复 `token` 获取。

#### Server 酱（Turbo / ³）

```yaml
push.serverchan.enabled: false
push.serverchan.send-key:
```
推送到微信。`send-key` 为 Server 酱 SendKey；`sctp` 前缀的 Key 会自动走 Server 酱³ 端点，其余走 Turbo 端点。

#### 自定义 Webhook

```yaml
push.webhook.enabled: false
push.webhook.url:
push.webhook.content-type: application/json
push.webhook.body-template:
```
万能渠道：把通知套进你提供的请求体模板后 POST 到 `url`，可对接 Discord / Slack / ntfy / Gotify 等任意 Webhook。模板支持 `{{title}}` / `{{content}}` 占位符，留空时用 `{"title":"{{title}}","content":"{{content}}"}`；当 `content-type` 为 JSON 类型时，占位符值会自动做 JSON 转义，避免正文里的引号 / 换行破坏 JSON 结构。

---

### 需要通知的类型

> 在 **GUI 配置页 →「通知」** 的「启用推送」与「通知服务」之间，可按通知类型逐项开关。这些开关同时作用于**邮件与推送**：取消勾选某类型后，该类型的通知不再通过任何介质发送。所有类型**默认全部启用**，修改后**支持热重载**。

```yaml
notification.scenario.overuse-paused.enabled: true
notification.scenario.auth-expired.enabled: true
notification.scenario.circuit-breaker.enabled: true
notification.scenario.pending-exhausted.enabled: true
notification.scenario.degraded-anonymous.enabled: true
notification.scenario.run-failed.enabled: true
notification.scenario.run-summary.enabled: true
```

各类型含义：`overuse-paused` 过度访问暂停、`auth-expired` 登录失效挂起、`circuit-breaker` 失败熔断挂起、`pending-exhausted` 作品重试耗尽待处理、`degraded-anonymous` 降级匿名续跑、`run-failed` 整轮运行失败、`run-summary` 运行成功摘要。

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
| `push.*` | ✅ |
| `notification.scenario.*` | ✅ |
| `server.port` | ❌ 需重启 |
| `download.root-folder` | ❌ 需重启 |

---

## 配置示例

```yaml
# ========================================================
# PixivDownloader 运行时配置
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

# 推送通知（多通道，与邮件同属 GUI「通知」分组）
push.enabled: false
push.bark.enabled: false
push.bark.server: https://api.day.app
push.bark.device-key:
push.bark.sound:
push.bark.use-proxy: false
push.dingtalk.enabled: false
push.dingtalk.access-token:
push.dingtalk.secret:
push.dingtalk.use-proxy: false
push.telegram.enabled: false
push.telegram.bot-token:
push.telegram.chat-id:
push.telegram.use-proxy: true
push.feishu.enabled: false
push.feishu.webhook-key:
push.feishu.secret:
push.feishu.use-proxy: false
push.wecom.enabled: false
push.wecom.key:
push.wecom.use-proxy: false
push.pushplus.enabled: false
push.pushplus.token:
push.pushplus.use-proxy: false
push.serverchan.enabled: false
push.serverchan.send-key:
push.serverchan.use-proxy: false
push.webhook.enabled: false
push.webhook.url:
push.webhook.content-type: application/json
push.webhook.body-template:
push.webhook.use-proxy: false

# 通知类型开关（同时作用于邮件与推送，取消勾选的类型不再发送）
notification.scenario.overuse-paused.enabled: true
notification.scenario.auth-expired.enabled: true
notification.scenario.circuit-breaker.enabled: true
notification.scenario.pending-exhausted.enabled: true
notification.scenario.degraded-anonymous.enabled: true
notification.scenario.run-failed.enabled: true
notification.scenario.run-summary.enabled: true
```
