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
| `database.maximum-pool-size` | `28` | SQLite 连接池上限 |

`download.root-folder` 只存放下载作品。配置、数据库、插件状态和缓存不会写入该目录。小说、Douyin 等下载类型的私有设置不属于宿主配置；安装插件后由插件自己的配置贡献管理。

Pixiv 作品图片、小说封面和内嵌图的固定安全上限为每张 100 MiB；单个普通插画或小说下载任务内的这些响应累计最多 1 GiB。服务同时检查 `Content-Length` 和实际解码后的响应流，超过上限会中止传输并清理部分文件。这些上限不能通过配置提高。

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

### HTTPS

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
| `ssl.http-redirect` | `false` |
| `ssl.http-redirect-port` | `80` |

`ssl.type=pem` 使用证书和私钥路径；`ssl.type=jks` 使用 key store。不要把证书私钥或 key store 密码提交到仓库。

### 语言与桌面界面

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `app.language` | 空 | 跟随系统；也可使用受支持语言代码 |
| `app.theme` | `system` | GUI 主题 id |
| `app.config-menu-expand-all` | `false` | 是否默认展开全部配置菜单 |

可用主题由已安装的主题插件贡献；配置值不是宿主对具体主题实现的硬编码清单。

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

插件 GUI 配置贡献是字段定义和保存入口的事实来源。保存后，宿主会刷新插件配置源，并按字段与插件生命周期策略给出即时生效、后端重启或完整进程重启结果。手工编辑后不确定时，完整重启最稳妥。

## 插件凭据

密码、Cookie、Token、API key、secret 和 webhook key 等敏感值写入 `config/credentials/{pluginId}.properties`。宿主负责加密、权限、迁移和按 owner 注入；插件只从自己的子上下文 `Environment` 读取已经解密的属性值。

不要把凭据放入 `config.yaml` 或 `config/plugins/*.properties`，也不要让插件读取、解析或解密凭据文件。备份凭据时必须同时保护凭据主密钥；缺少原主密钥时，加密值不能在另一环境中恢复。

## 如何确认当前配置

1. 先看 GUI 配置页；它会合并宿主字段与当前已安装插件的配置贡献。
2. 宿主默认键以当前版本的 `DefaultConfigTemplate` 生成结果为准。
3. 插件字段以对应插件的 `GuiConfigContribution`、`@ConfigurationProperties` 或设置服务为准。
4. 不要从旧版示例复制已经外置的 `mail.*`、`push.*`、`notification.*` 或 `download.novel-*` 到 `config.yaml`；它们由各自插件拥有。
