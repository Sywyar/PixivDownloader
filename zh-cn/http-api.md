# OpenAPI 与程序密钥

PixivDownloader 内置交互式 OpenAPI 文档与细粒度**程序访问密钥**（Program Key），供脚本、自动化工具或 AI Agent 安全调用下载、批次、画廊、翻译与维护接口（本程序仅提供 HTTP API，不内置独立 MCP 服务；开发新接口的规范见[编写 HTTP API 与权限声明](/zh-cn/http-api-development)）。

## 用脚本或 Agent 访问 API

1. **查看交互文档**：启动实例后在浏览器打开 `/openapi`（本机默认 `http://localhost:6999/openapi`）。访问说明公开可读；以管理员登录后可浏览完整接口列表、请求示例、所需权限，并通过响应下方的「相关接口」链接（`links` / `x-related-operations` / `externalDocs`）在关联操作与使用指南间跳转。
2. **创建程序访问密钥**：在桌面「设置 → 访问控制 → 程序访问密钥」中创建密钥（无桌面环境可由管理员登录会话调用 `/api/security/program-keys`），配置允许的来源 IP/网段并勾选所需权限。若脚本或 Agent 需自行拉取接口定义，请同时勾选 `core:api-reference.read`。
3. **调用接口或生成 Agent 提示词**：客户端通过请求头 `Authorization: Bearer <程序密钥>` 请求 `GET /api/openapi.json`（或用 `HEAD /api/openapi.json` 探测权限）获取机器可读的 OpenAPI 文档；配合 Agent 使用时，可在创建密钥后点击「复制提示词」将文档地址交给 Agent，再将密钥单独配置到 Agent 运行环境。

### 密钥安全、来源限制与 CSRF 规则

- **一次性密钥与配额**：完整密钥仅在创建成功时显示一次，服务端只保存摘要（密钥默认不过期，可随时修改权限、来源范围或撤销；勾选整个插件仅包含当前已列出的权限，不会自动授予未来新增权限）。每个安装实例最多创建 128 个密钥（名称最长 80 字符，单密钥最多 512 项权限与 64 个来源网段，管理请求体上限 128 KiB）。
- **HTTPS 与来源 IP 限制**：来自本机或局域网以外的明文 HTTP 程序密钥请求默认被拒绝（确需公网 HTTP 时须逐把密钥显式确认风险，后续编辑权限或来源会自动恢复 HTTPS 要求；即使请求被拒，明文传输的密钥仍可能已泄露，请立即撤销）。来源限制支持 IPv4、IPv6 与 CIDR 网段（受限模式至少填一项，「任何来源」需显式选择；反向代理部署须先配置 `server.trusted-proxy-cidrs`）。
- **认证隔离与状态码**：请勿在同一请求中混用程序密钥、管理员会话（`X-Session-Token` / Cookie）、邀请码或本机 GUI token（采集凭据仍通过 `X-Acquisition-Credential` 或 `X-Acquisition-Credentials` 独立传递）。
  - 持有效程序密钥访问未开放程序权限的接口（含匿名接口）返回 `403 auth.program.unsupported`（可查看文档中的 `x-program-alternative` 改用程序专用接口）；无效密钥或混用凭据返回 `401 auth.program.invalid`。
  - `/api/gui/**`、密钥管理与管理员密码修改仅限本机 GUI 或人类管理员会话。
- **同源信号（`x-csrf-origin`）**：携带 Cookie 的写请求必须通过同源检查。原生 Bearer 客户端也需留意接口的 `x-csrf-origin` 声明：例如 `POST /api/schedule/tasks`（`origin-or-referer`）要求发送同源 `Origin` 或 `Referer`；而无浏览器 Cookie 的原生 Bearer 客户端调用 `POST /api/download/tasks` 与 `POST /api/download/batches` 时可省略这两个请求头。
- **自检、管理 API 与密钥轮换**：
  - 调用 `GET /api/security/program-keys/self` 可无需额外业务权限核对当前密钥的 `id` 与 `permissions`（权限列表可为空）。
  - 管理员会话可通过 `GET /api/security/program-keys`（列表，不含秘密或摘要）、`GET /api/security/program-keys/permissions`（当前安装的权威权限目录）、`POST /api/security/program-keys`（传入 `name`、`permissions`、`anyIp`、`networks` 创建，返回 `key` 与一次性 `secret`）、`PUT /api/security/program-keys/{id}`（传入 `{"revision": 1, "policy": {...}}` 修改）与 `DELETE /api/security/program-keys/{id}`（传入 `{"revision": 1}` 撤销）管理密钥。
  - **轮换流程**：创建新密钥 → 客户端切换并用 `/self` 核对 → 撤销旧密钥。新密钥无法查看旧密钥创建的下载任务或批次；请在撤销前轮询旧任务至终态，或改用管理员会话跟踪（撤销仅阻止新请求，不会自动取消已接纳的在途任务）。

## 接口索引

下表中同一行的短路径接在首个完整路径前缀之后；翻译（`/api/translation/**`）、术语表、朗读、画廊、收藏夹、计划任务、维护与插件市场完整路径见下方[程序权限列表](#程序权限列表)或实例 `/openapi` 页面。

| 功能 | 主要入口 |
| --- | --- |
| 设置字段、默认值、脱敏当前值与预设 | `GET /api/settings`、`GET /api/settings/presets` |
| 校验与保存设置 | `POST /api/settings/validate`、`POST /api/settings` |
| 插件仓库配置 | `GET` / `PUT /api/settings/repositories` |
| 数据库设置、连接测试与迁移 | `GET /api/settings/database`、`/providers`、`/status`、`/migration`；`POST /check`、`/migration-previews`、`/migrations` |
| 环境归档导出与恢复 | `POST /api/settings/environment/preview`、`/restore`、`/exports`；`GET /restore`、`/exports`、`/exports/{id}/archive`、`/installation` |
| 元数据回填 | `GET /api/tools/backfill/defaults`、`/tasks/current`、`/tasks/{id}`；`POST /candidates`、`/tasks`、`/tasks/{id}/cancel` |
| 媒体维护与记录文件导入 | `GET /api/tools/media`、`/api/tools/file-import`（各前缀下 `POST /previews`、`/tasks`、`/tasks/cancel`、`GET /tasks/current`）；`POST /api/tools/media/capabilities`；`POST /api/tools/file-import/completion/acknowledge` |
| 本地作品导入与启动缓存 | `GET /api/local-import/presets`、`POST /previews`、`POST /tasks`；`GET /api/tools/startup-cache`、`POST /tasks`、`POST /tasks/{id}/cancel` |
| 目录引用、路径前缀与图片分类 | `GET` / `POST /api/tools/directories`；`/api/tools/path-prefixes`；`/api/tools/classifier/settings`、`/folders`、`/images`、`/image`、`/moves` |
| FFmpeg 状态、安装与工具历史/日志 | `GET /api/tools/ffmpeg`、`POST /api/tools/ffmpeg/tasks`；`GET /api/tools/history`、`/api/tools/logs/{fileName}` |
| 系统、控制中心与更新 | `/api/admin/system`、`/api/admin/control-center`；`/api/admin/update/check`、`/last`、`/download`、`/download/progress`、`/install` |
| AI、邮件、推送与站内信测试 | `/api/admin/ai/test`、`/models`；`/api/admin/mail/test`、`/test-all`；`/api/admin/push/test`、`/test-all`；`/api/admin/notifications/test`、`/test-all` |
| 单项下载、历史与后台手动批次 | `/api/download/tasks`、`/api/download/tasks/history`；`/api/download/batches` |

## 保存设置

先调用 `GET /api/settings` 读取字段目录，再按返回的 `owner`、`packageId`、`generation` 与 `key` 提交修改（宿主字段的 `owner` 与 `packageId` 为 `null`、`generation` 为 `0`）。敏感字段仅返回 `stored` 布尔标志，不回传明文或默认秘密；提交时通过 `action`（`KEEP`、`SET`、`CLEAR`）明确保留、替换或清除。返回结果中的 `effect` 标明生效方式（保存成功不代表已自动重启后端或进程）：

```json
{
  "changes": [
    {
      "owner": null,
      "packageId": null,
      "generation": 0,
      "key": "server.port",
      "action": "SET",
      "value": "6999"
    }
  ]
}
```

预设通过同一保存接口应用。修改插件仓库（`PUT /api/settings/repositories`）须携带 `GET` 返回的 `revision`，新增仓库或修改信任、地址及网络策略时须显式传入 `"trustConfirmed": true`（修订冲突时需重读，不会覆盖他人修改）。数据库连接测试与工具单次运行参数仅当次有效，不会回写配置文件。

## 后台手动批次

每个进程同一时间仅保留一个手动批次（客户端断开后仍在后台继续执行，但不跨进程重启恢复；新建批次前必须先删除已空闲的旧批次）：

1. **查询可用来源**：`GET /api/schedule/sources` 获取当前 `sourceType` 与 `activationToken`（来源定义由所属插件解释，支持单作品列表 `explicit-list`、作者投稿、约稿、搜索、系列、收藏、关注新作和珍藏集）。
2. **创建批次**：`POST /api/download/batches` 提交 `name`、`sourceType`、`activationToken`、`definition`（JSON 对象，UTF-8 序列化上限 1 MiB，整请求上限 8 MiB）及可选 `proxy`。若提交时返回 `403`，可从响应体 `programPermission` 读取缺失权限 ID；返回 `202` 后持 `core:batches.read` 访问 `Location`（后台执行若缺权，批次结果同样在 `programPermission` 中标明）。
3. **轮询状态与条目**：`GET /api/download/batches/{id}` 查看整体状态（以 `state` 判断阶段；`code` 为可选原因码，如 `batch.work-failed` 可进一步查 `items`，作者批次 `schedule.pixiv.discovery-not-found` 需核对作者 ID），`GET /api/download/batches/{id}/items?offset=0&limit=100` 分页读取条目（单页最多 200 项；逐项标识为批次 UUID + `workType` + `id`，不同于单项下载的执行 UUID）。
4. **控制与恢复**：`POST /api/download/batches/{id}/pause`、`/cancel`、`/resume`、`/retry` 分别暂停、取消、恢复扫描或仅重试已发现的失败项（停止中状态须等待在途执行实际退出后才进入终态；已完成和筛选跳过的作品在恢复时不会重复执行）。
5. **替换凭据、处理待确认项与删除**：
   - 在批次空闲时调用 `PUT /api/download/batches/{id}/credentials`（正文 `{"expectedRun": 1}`，上限 16 KiB；通过请求头传入新凭据，省略请求头表示清除，恢复前会重新探活）。
   - 调用 `POST /api/download/batches/{id}/resolve` 处理待确认项（正文含 `expectedRun`、`workType`、`workId`、`reasonCode`、`userAction`、`rememberForRun`，上限 16 KiB；原因与轮次须匹配当前记录）。
   - 空闲时调用 `DELETE /api/download/batches/{id}` 删除批次记录。

单批次最多接纳 5,000 项作品，并受 64 MiB 编码内容预留预算约束（先达到者生效）。筛选支持内容分级、AI、标签、插画类型、页数、小说字数和最低收藏数（启用收藏数筛选时，未知收藏数不予通过）。

- **搜索翻页间隔（`pageDelayMs`）**：搜索来源可在 `download.pageDelayMs` 指定相邻两次请求开始时间的最短间隔（毫秒；省略时固定页数默认 `0`、`maxPages = -1` 默认 `10000`，显式填 `0` 取消额外等待）。手动批次将其作为 JSON 对象放入 `definition`（计划任务则序列化为 `definitionJson` 字符串；共享下载方案使用 `settings.pageDelayMs`，填 `null` 采用默认值）：

  ```json
  {"source":{"word":"landscape","maxPages":3},"download":{"pageDelayMs":5000}}
  ```

- **混合作品列表（`explicit-list`）`definition` 示例**（作品 ID 须为正整数十进制字符串，同类型不可重复；请将演示 ID 替换为实际作品，缺少 `/definition/source/acquisitionMode` 会在创建前返回 400）：

  ```json
  {
    "kind": "mixed",
    "source": {
      "acquisitionMode": "single-import",
      "works": [
        {"kind": "illust", "id": "123"},
        {"kind": "novel", "id": "456"}
      ]
    },
    "filters": {
      "content": "safe",
      "aiFilter": "exclude",
      "tagsExact": [],
      "tagsFuzzy": []
    },
    "download": {
      "concurrent": 1,
      "intervalMs": 1000,
      "bookmark": false
    }
  }
  ```

## 工具、图库批量操作与重启操作

所有工具接口中的路径参数均指**运行后端的服务器本地路径**，而非远程客户端路径：

- **媒体维护与记录文件导入**：先 `GET` 工具清单，再提交清单中的精确 `identity`（`pluginId`、`packageId`、`generation`、`publication`）与 `options` 执行预览；启动任务须携带同一 `identity` 与预览 `token`，取消须同时携带 `executionId`（插件换代或桌面启动替代任务后旧 token/UUID 自动失效）。
- **元数据回填与手动恢复**：`POST /api/tools/backfill/tasks`（请求体上限 4 KiB，`limit: 0` 表示全量，`202` 仅代表已接纳）：

  ```json
  {
    "delayMs": 0,
    "artworkIds": [12345],
    "dryRun": true,
    "alwaysNetwork": false
  }
  ```

  单个插画也可调用 `POST /api/library/artworks/{artworkId}/recover-metadata` 直接提交字段补缺（保留已有非空值；请求体缺失或无可用字段返回 400；需从 Pixiv 拉取标签和系列时请按作品 ID 启动回填，见[导入后补齐作品资料](/zh-cn/local-import?id=metadata-backfill)）。
- **画廊文件管理筛选与批量导出/删除**：
  - `GET /api/gallery/artworks?fileManagement=EXTERNAL_REFERENCE` 可筛选外部只读引用作品（删除仅移除登记，保留原文件）；`MANAGED` 表示应用管理文件（删除时连同文件清理，并在库中保留 `deleted=true` 供查重）。
  - 插画与小说的批量接口支持 `"mode": "filter"`（跨所有匹配页，要求 `filter` 为 JSON 对象）或按 ID 数组选择（仅接受正整数如 `42`，小数、字符串、零或负数均返回 HTTP 400 及对应 `field`）。当显式传入 `"filter": {}` 或仅含分页参数的 `filter` 时会选中全库该类作品，执行删除或「导出后删除」必须同时传入 `"confirmAll": true` 且密钥须具备对应删除权限。
  - 导出返回 `archiveToken` 后，持 `core:archives.read` 轮询 `GET /api/archive/status/{token}`，状态变为 `ready` 后持 `core:archives.download` 请求 `GET /api/archive/download/{token}`。
- **图片分类、启动缓存、数据库迁移与环境归档**：
  - 图片分类移动会分别返回复制结果、源目录删除状态与数据库登记结果，部分完成时请先核对返回状态，勿盲目重试。
  - 启动缓存训练调用 `POST /api/tools/startup-cache/tasks`（请求体 `{}`），返回接纳后轮询 `GET /api/tools/startup-cache`。
  - 数据库迁移与环境恢复均使用预览返回的 `token` 确认（环境恢复返回 `RESTORE_PENDING` 后由替代进程完成）。环境导出（`POST /api/settings/environment/exports`）须提供服务器绝对路径 `destination`（不覆盖已有文件）与 `"confirmRestart": true`，包含凭据、数据库或状态时必须设置 `password`（勾选 `includeResources` 须同时勾选 `includeDatabases`）；导出会停止后端并请求完整进程重启，重启失败时需在服务器手动启动。安装在线更新须提交已验证安装包的 SHA-256。

## 错误处理、轮询与分页

错误响应统一包含机器码 `code` 与本地化说明 `error`（`400` 参数非法、`401`/`403` 认证或权限拒绝、`404` 对象不存在、`409` 任务忙碌/修订冲突/状态不允许、`413` 请求体超限、`503` 插件或数据库能力不可用）。请按业务状态处理，不要对所有非 2xx 响应无条件重试。

- **单项下载提交与轮询（`POST /api/download/tasks`）**：
  - 从 OpenAPI `x-download-submissions` 中选择已启用的 `workType`（未启用类型返回 `400 DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE`；上游明确返回作品不存在时首次提交返回 `404 WORK_NOT_FOUND`；按任务 ID 查询不到属于当前调用方的任务时返回 `404 DOWNLOAD_TASK_NOT_FOUND`）。
  - 参数校验失败返回 `in`、`field`（请求体为 JSON Pointer 如 `/options/pages`，查询/路径/请求头/multipart 为声明参数名）与 `reason`（如 `out_of_range`）。
  - 返回 `202` 后按 `Location`（路径参数 `taskId` 取回执中的 `task.attempt.attemptId`）轮询：建议初始间隔 **2 秒**，`revision` 连续不变时逐步退避至 **10 秒**，有更新时恢复 2 秒，直至 `phase` 进入 `COMPLETED`、`FAILED` 或 `CANCELLED`（收到 `Retry-After` 时至少等待指定时长）。当 `progress` 非空时可读取同一单位（`PAGES`、`IMAGES` 或 `BYTES`）的 `progress.unit`、`completed` 与 `total`。
  - **幂等与任务历史**：同一调用方在当前进程幂等记录有效期内（终态保存后约 5 分钟过期），以相同 `requestId` 重试相同命令会返回原回执；`GET /api/download/tasks/history` 仅枚举已保存的终态记录（默认每页 50 项，上限 200 项），不恢复在途下载。

| 数据项 | 读取与提交格式约定 |
| --- | --- |
| Pixiv 作品 ID | 画廊 `artworkId`、导出 `ids[]` 使用 JSON 整数；Pixiv 搜索 `id`、下载任务 `workId`、手动批次 `key.id` 使用十进制字符串（转换时须保留 64 位整数精度，第三方下载插件自行定义 `workId` 格式） |
| 下载选项类型 | 单任务 `options` 使用字符串值（如 `{"bookmark":"true","mediaQuality":"80","mediaWebpLossless":"true"}`）；手动批次 `definition.download` 使用原生 JSON 类型（如 `{"bookmark":true,"mediaQuality":80,"mediaWebpLossless":true}`） |
| 时间戳与路径 | 下载任务 `updatedAt` 为 ISO 8601 字符串；手动批次 `createdTime`、画廊 `time`、计划任务 `nextRunTime` 为 Unix 毫秒时间戳。任务 `outputPath` 为预定服务器目录，画廊 `folder` 为实际登记目录，两者按作品 ID 关联 |
| 常见响应结构 | `GET /api/authors` 返回 JSON 数组；`GET /api/authors/paged` 返回含 `content` 的分页对象；`GET /api/downloaded/statistics` 返回含 `success` 与各统计计数的对象 |

| 分页接口 | 分页参数 | 翻页方式 |
| --- | --- | --- |
| `GET /api/gallery/artworks` | `page`, `size` | 首页传 `page=0`，后续每次将 `page` 加 `1` |
| `GET /api/download/batches/{id}/items` | `offset`, `limit` | 从 `offset=0` 开始，每次按本页实际返回条目数累加 `offset` |
| `GET /api/download/tasks/history` | `limit`, `beforeTime`, `beforeId` | 首页省略两个游标参数；续页时原样传回上一页末尾的 `beforeTime` 与 `beforeId` |
| `GET /api/pixiv/search` | `page` | 从 `page=1` 开始（小于 `1` 时按第 1 页处理） |
| `GET /api/plugin-market/catalog` | `cursor`, `limit` | 将响应中的 `nextCursor` 原样传为下一页 `cursor`（插件版本列表续页使用 `nextVersionCursor`） |

## 程序权限列表

当前安装实例的实时权限目录由管理员调用 `GET /api/security/program-keys/permissions` 获取。密钥仅接受精确权限 ID，不支持插件或路径通配符（下表路由中的 `*` 代表单个路径段）：

### 权限范围与专用入口

- **复合权限校验**：
  - **共享设置、媒体维护与文件导入**：须同时具备 `core` 入口权限与目标插件的同名权限（读取列表仅返回已获授权的目标）。
  - **单项下载与手动批次**：单项下载需 `core:downloads.create` + 目标下载类型插件的 `<plugin>:downloads.create`；手动批次需 `core:batches.create`（或 `core:batches.resume`）+ 来源插件与执行计划涉及的每个作品插件的 `<plugin>:batches.execute`（查来源目录需 `core:schedule.sources.read`）。
  - **持久计划任务**：需 `core:schedule.*` 对应动作权限 + 来源插件的 `<plugin>:schedule.manage`（账号策略动作需策略 owner 的同名权限；计划属于安装级持久配置，撤销创建该计划的密钥不会删除或停用计划）。
  - **插件生命周期与安全策略**：需 `core:plugins.*` 对应动作权限 + 目标插件的 `<plugin>:plugins.manage`（有反向依赖消费者时还须具备消费者权限；`core:plugins.install` 与 `plugin-market:install` 允许在服务器执行代码，`core:hooks.publish` 允许修改全局操作保护策略，请谨慎授权）。
  - **采集凭据**：`core:credentials.read` 仅返回凭据名称与状态、不返回秘密；请求头通过 `X-Acquisition-Credentials` 引用已保存共享凭据时，除业务权限外还需 `core:credentials.use`（单次直接传入凭据仍用 `X-Acquisition-Credential`）。
- **数据隔离与安装级作用域**：下载任务与手动批次按密钥身份隔离（每把密钥只能查询、取消和管理自己创建的任务与批次，管理员仍可查看全局；多把密钥仍共享单进程唯一手动批次槽位）。画廊、小说库、收藏夹、翻译、站内信、归档、页面编辑、设置、日志、更新、维护、数据库迁移与环境归档均直接作用于共享安装实例，不为每把密钥维护私有副本；本机专属维护与恢复接口仍强制校验本机回环来源。
- **程序专用入口**：身份建立、密钥管理、GUI token、验证码交互、浏览器采集票据、匿名配额与页面装配保留专用流程，旧浏览器兼容路由不开放程序权限。程序调用请使用 `/api/download/tasks`（下载与进度）、`/api/tools/file-import`（记录文件导入）、`/api/library/artworks/{id}`（作品管理）、`/api/page-editor`（页面编辑）以及 `/api/admin/notifications/test`、`/test-all`（站内信测试）。

| 权限 | 用途 | 方法与路径 |
| --- | --- | --- |
| `ai:connection.test` | 测试 AI 连接（会调用模型） | POST `/api/admin/ai/test` |
| `ai:models.read` | 查询可用 AI 模型 | POST `/api/admin/ai/models` |
| `ai:status.read` | 查看 AI 配置状态 | GET `/api/admin/ai/status` |
| `core:api-reference.read` | 读取或探测 HTTP 接口目录 | GET, HEAD `/api/openapi.json` |
| `core:application.read` | 读取程序信息与界面语言资源 | GET `/api/app/info`; GET `/api/i18n/messages/*`; GET `/api/i18n/meta` |
| `core:archives.create` | 创建插画导出归档 | POST `/api/archive/pack-artworks` |
| `core:archives.download` | 下载安装实例的导出归档 | GET `/api/archive/download/*` |
| `core:archives.read` | 查看安装实例的导出归档与状态 | GET `/api/archive/list`; GET `/api/archive/status/*` |
| `core:assets.export` | 下载插画原始文件 | GET `/api/downloaded/rawfile/*/*` |
| `core:assets.read` | 读取插画预览与图片 | GET `/api/downloaded/image/*/*`; GET `/api/downloaded/thumbnail-file/*/*`; GET `/api/downloaded/thumbnail/*/*` |
| `core:authors.read` | 查看全库作者 | GET `/api/authors/paged`; GET `/api/authors` |
| `core:authors.refresh` | 刷新 Pixiv 作者资料 | POST `/api/pixiv/user/*/meta/schedule` |
| `core:backfill.cancel` | 取消记录回填 | POST `/api/tools/backfill/tasks/*/cancel` |
| `core:backfill.execute` | 执行记录回填 | POST `/api/tools/backfill/tasks` |
| `core:backfill.read` | 查看记录回填候选与状态 | GET `/api/tools/backfill/defaults`; GET `/api/tools/backfill/tasks/*`; GET `/api/tools/backfill/tasks/current`; POST `/api/tools/backfill/candidates` |
| `core:batches.cancel` | 取消本密钥的批次 | POST `/api/download/batches/*/cancel` |
| `core:batches.create` | 创建后台批次 | POST `/api/download/batches` |
| `core:batches.credentials` | 替换本密钥的批次凭据 | PUT `/api/download/batches/*/credentials` |
| `core:batches.delete` | 删除本密钥的批次记录 | DELETE `/api/download/batches/*` |
| `core:batches.pause` | 暂停本密钥的批次 | POST `/api/download/batches/*/pause` |
| `core:batches.read` | 查看本密钥的批次与结果 | GET `/api/download/batches/*/items`; GET `/api/download/batches/*`; GET `/api/download/batches` |
| `core:batches.resolve` | 处理本密钥批次的待确认项 | POST `/api/download/batches/*/resolve` |
| `core:batches.resume` | 恢复或重试本密钥的批次 | POST `/api/download/batches/*/resume`; POST `/api/download/batches/*/retry` |
| `core:classifier.configure` | 修改图片分类设置 | PUT `/api/tools/classifier/settings` |
| `core:classifier.delete` | 删除图片分类目录 | DELETE `/api/tools/classifier/folders` |
| `core:classifier.read` | 查看图片分类与图片 | GET `/api/tools/classifier/folders`; GET `/api/tools/classifier/image`; GET `/api/tools/classifier/images`; GET `/api/tools/classifier/settings` |
| `core:classifier.write` | 移动分类图片 | POST `/api/tools/classifier/moves` |
| `core:collections.delete` | 删除精选集 | DELETE `/api/collections/*` |
| `core:collections.members.write` | 添加及移除精选集作品 | DELETE `/api/collections/*/artworks/*`; DELETE `/api/collections/*/novels/*`; POST `/api/collections/*/artworks/*`; POST `/api/collections/*/novels/*` |
| `core:collections.read` | 读取全库精选集与归属 | GET `/api/collections/*/icon`; GET `/api/collections/novels/of/*`; GET `/api/collections/of/*`; GET `/api/collections`; POST `/api/collections/memberships`; POST `/api/collections/novels/memberships` |
| `core:collections.write` | 创建及修改精选集、图标与下载目录 | DELETE `/api/collections/*/icon`; POST `/api/collections/*/icon`; POST `/api/collections`; PUT `/api/collections/*/download-root`; PUT `/api/collections/*/sort-order`; PUT `/api/collections/*` |
| `core:control-center.directory` | 在服务器上打开控制中心目录 | POST `/api/admin/control-center/directory` |
| `core:control-center.read` | 查看控制中心状态 | GET `/api/admin/control-center` |
| `core:credentials.read` | 读取采集凭据库信息（不含秘密） | GET `/api/acquisition/credentials` |
| `core:credentials.use` | 在已授权请求中使用共享采集凭据 | HEADER `X-Acquisition-Credentials` |
| `core:credentials.write` | 修改共享采集凭据库 | PUT `/api/acquisition/credentials` |
| `core:database.check` | 检查数据库连接 | POST `/api/settings/database/check` |
| `core:database.migrate` | 迁移整套安装的数据库 | POST `/api/settings/database/migrations` |
| `core:database.preview` | 预览整套安装的数据库迁移 | POST `/api/settings/database/migration-previews` |
| `core:database.read` | 查看数据库配置与迁移状态 | GET `/api/settings/database/migration`; GET `/api/settings/database/providers`; GET `/api/settings/database/status`; GET `/api/settings/database` |
| `core:directories.read` | 检查作品目录 | GET `/api/tools/directories` |
| `core:directories.write` | 修改作品目录记录 | POST `/api/tools/directories` |
| `core:downloads.cancel` | 取消本密钥的下载任务 | POST `/api/download/queue/*/cancel`; POST `/api/download/queue/clear`; POST `/api/download/tasks/*/cancel` |
| `core:downloads.create` | 创建下载任务 | POST `/api/download/tasks` |
| `core:downloads.extensions.read` | 查看下载扩展能力 | GET `/api/download/extensions` |
| `core:downloads.read` | 查看本密钥的下载任务 | GET `/api/download/tasks/*`; GET `/api/download/tasks/history`; GET `/api/download/tasks` |
| `core:environment.export` | 导出完整环境归档 | GET `/api/settings/environment/exports/*/archive`; POST `/api/settings/environment/exports` |
| `core:environment.preview` | 预览完整环境恢复 | POST `/api/settings/environment/preview` |
| `core:environment.read` | 查看安装与环境迁移状态 | GET `/api/settings/environment/exports`; GET `/api/settings/environment/installation`; GET `/api/settings/environment/restore` |
| `core:environment.restore` | 恢复完整环境 | POST `/api/settings/environment/restore` |
| `core:ffmpeg.install` | 安装 FFmpeg | POST `/api/tools/ffmpeg/tasks` |
| `core:ffmpeg.read` | 查看 FFmpeg 状态 | GET `/api/tools/ffmpeg` |
| `core:file-import.acknowledge` | 确认文件导入结果 | POST `/api/tools/file-import/completion/acknowledge` |
| `core:file-import.cancel` | 取消文件导入 | POST `/api/tools/file-import/tasks/cancel` |
| `core:file-import.execute` | 执行文件导入 | POST `/api/tools/file-import/tasks` |
| `core:file-import.preview` | 预览文件导入 | POST `/api/tools/file-import/previews` |
| `core:file-import.read` | 查看文件导入工具与任务 | GET `/api/tools/file-import/tasks/current`; GET `/api/tools/file-import` |
| `core:hooks.prepare` | 预览操作 Hook 配置变更 | POST `/api/web/operation-hooks/declarations/prepare`; POST `/api/web/operation-hooks/prepare` |
| `core:hooks.publish` | 发布全局操作 Hook 安全策略 | POST `/api/web/operation-hooks/publish` |
| `core:hooks.read` | 读取全局操作 Hook 与授权配置 | GET `/api/web/operation-hooks/catalog`; GET `/api/web/operation-hooks/configuration`; GET `/api/web/operation-hooks/declarations`; GET `/api/web/operation-hooks/definitions`; GET `/api/web/operation-hooks/targets` |
| `core:legacy-migration.execute` | 迁移旧 JSON 元数据（仅本机） | GET `/api/migration/json-to-sqlite/stream`; POST `/api/migration/json-to-sqlite` |
| `core:library.move` | 移动服务器上的插画文件 | POST `/api/library/artworks/*/move` |
| `core:library.read` | 查看全库插画登记、历史与路径 | GET `/api/downloaded/by-move-folder`; GET `/api/downloaded/history/paged`; GET `/api/downloaded/history`; GET `/api/downloaded/statistics`; GET `/api/library/artworks/*`; POST `/api/downloaded/batch` |
| `core:library.recover` | 恢复插画元数据 | POST `/api/library/artworks/*/recover-metadata` |
| `core:local-import.cancel` | 取消本地导入扫描与登记 | POST `/api/local-import/previews/*/cancel`; POST `/api/local-import/tasks/*/cancel` |
| `core:local-import.execute` | 将本地插画登记到数据库 | POST `/api/local-import/tasks` |
| `core:local-import.preview` | 扫描服务器上的本地插画 | POST `/api/local-import/previews` |
| `core:local-import.read` | 读取本地导入预设与任务 | GET `/api/local-import/presets`; GET `/api/local-import/previews/*/items`; GET `/api/local-import/previews/*`; GET `/api/local-import/tasks/*/items`; GET `/api/local-import/tasks/*` |
| `core:logs.read` | 读取运行日志 | GET `/api/tools/logs/*` |
| `core:maintenance.read` | 查看维护状态（仅本机） | GET `/api/admin/maintenance/status` |
| `core:maintenance.run` | 执行维护（仅本机） | POST `/api/admin/maintenance/run` |
| `core:media.cancel` | 取消媒体处理 | POST `/api/tools/media/tasks/cancel` |
| `core:media.execute` | 执行媒体处理 | POST `/api/tools/media/tasks` |
| `core:media.preview` | 预览媒体处理 | POST `/api/tools/media/previews` |
| `core:media.read` | 查看媒体工具与任务 | GET `/api/tools/media/tasks/current`; GET `/api/tools/media`; POST `/api/tools/media/capabilities` |
| `core:paths.read` | 查看路径前缀 | GET `/api/tools/path-prefixes` |
| `core:paths.write` | 修改路径前缀 | POST `/api/tools/path-prefixes/pin`; POST `/api/tools/path-prefixes` |
| `core:pixiv.read` | 查询 Pixiv 在线数据 | GET `/api/pixiv/me/uid`; GET `/api/pixiv/thumbnail-proxy`; GET `/api/pixiv/user/*/meta` |
| `core:plugins.enabled` | 修改已授权插件的启用状态 | PUT `/api/plugins/*/enabled-preview`; PUT `/api/plugins/*/enabled` |
| `core:plugins.install` | 安装本地插件包（允许服务器执行代码） | POST `/api/plugins/install` |
| `core:plugins.load` | 加载已授权插件 | POST `/api/plugins/*/actions/load`; POST `/api/plugins/*/load` |
| `core:plugins.quiesce` | 静默已授权插件 | POST `/api/plugins/*/actions/quiesce`; POST `/api/plugins/*/quiesce` |
| `core:plugins.read` | 查看插件状态、依赖与安装操作 | GET `/api/plugins/*/impact`; GET `/api/plugins/acquisitions/*`; GET `/api/plugins/acquisitions`; GET `/api/plugins/status`; GET `/api/plugins/updates` |
| `core:plugins.reload` | 重新加载已授权插件 | POST `/api/plugins/*/actions/reload`; POST `/api/plugins/*/reload` |
| `core:plugins.remove` | 删除已授权插件的安装包 | POST `/api/plugins/*/actions/remove`; POST `/api/plugins/*/remove` |
| `core:plugins.restart` | 重启已授权插件 | POST `/api/plugins/*/actions/restart`; POST `/api/plugins/*/restart` |
| `core:plugins.revocations.refresh` | 刷新插件仓库的撤销信息 | POST `/api/plugins/revocations/*/refresh` |
| `core:plugins.start` | 启动已授权插件 | POST `/api/plugins/*/actions/start`; POST `/api/plugins/*/start` |
| `core:plugins.stop` | 停止已授权插件 | POST `/api/plugins/*/actions/stop`; POST `/api/plugins/*/stop` |
| `core:plugins.trust` | 批准及撤销已授权插件的执行信任 | DELETE `/api/plugins/*/trust`; PUT `/api/plugins/*/trust` |
| `core:plugins.unload` | 卸载已授权插件的运行实例 | POST `/api/plugins/*/actions/unload`; POST `/api/plugins/*/unload` |
| `core:profile.read` | 读取安装实例的显示称呼 | GET `/api/onboarding/profile` |
| `core:profile.write` | 修改安装实例的显示称呼 | POST `/api/onboarding/profile` |
| `core:recovery.read` | 查看恢复模式诊断 | GET `/api/plugins/recovery` |
| `core:repositories.read` | 查看插件仓库设置 | GET `/api/settings/repositories` |
| `core:repositories.write` | 修改插件仓库设置 | PUT `/api/settings/repositories` |
| `core:schedule.create` | 创建持久计划（撤销密钥不会删除计划） | POST `/api/schedule/tasks` |
| `core:schedule.credentials` | 管理计划凭据与账号策略 | DELETE `/api/schedule/tasks/*/credential`; POST `/api/schedule/credential-policies/actions`; POST `/api/schedule/tasks/*/credential` |
| `core:schedule.delete` | 删除持久计划 | DELETE `/api/schedule/tasks/*` |
| `core:schedule.pause` | 暂停持久计划 | POST `/api/schedule/tasks/*/pause` |
| `core:schedule.pending.write` | 处理及清除计划待处理项 | DELETE `/api/schedule/tasks/*/pending`; POST `/api/schedule/tasks/*/pending/resolve` |
| `core:schedule.read` | 读取已授权来源的持久计划 | GET `/api/schedule/tasks/*/pending`; GET `/api/schedule/tasks/*/queue`; GET `/api/schedule/tasks/*`; GET `/api/schedule/tasks` |
| `core:schedule.run` | 启用、运行及恢复持久计划 | POST `/api/schedule/tasks/*/enabled`; POST `/api/schedule/tasks/*/resume`; POST `/api/schedule/tasks/*/run` |
| `core:schedule.sources.read` | 查看批次与计划来源目录 | GET `/api/schedule/sources` |
| `core:schedule.write` | 修改持久计划与代理 | POST `/api/schedule/tasks/*/proxy`; PUT `/api/schedule/tasks/*` |
| `core:scripts.read` | 读取已安装插件的用户脚本 | GET `/api/scripts/*.user.js`; GET `/api/scripts/*/install`; GET `/api/scripts/*`; GET `/api/scripts` |
| `core:series.read` | 查看全库插画系列 | GET `/api/series/*/cover`; GET `/api/series/*`; GET `/api/series/paged`; GET `/api/series` |
| `core:settings.read` | 读取设置与预设 | GET `/api/settings/presets`; GET `/api/settings` |
| `core:settings.write` | 校验与保存设置 | POST `/api/settings/validate`; POST `/api/settings` |
| `core:startup-cache.cancel` | 取消启动缓存训练 | POST `/api/tools/startup-cache/tasks/*/cancel` |
| `core:startup-cache.execute` | 训练启动缓存 | POST `/api/tools/startup-cache/tasks` |
| `core:startup-cache.read` | 查看启动缓存状态 | GET `/api/tools/startup-cache` |
| `core:system.check` | 检查网络连接 | GET `/api/onboarding/connectivity`; POST `/api/admin/system/connectivity` |
| `core:system.exit` | 退出恢复模式中的程序（仅本机） | POST `/api/plugins/recovery/exit` |
| `core:system.read` | 查看系统状态 | GET `/api/admin/system` |
| `core:system.reload` | 重新加载配置 | POST `/api/admin/system/reload` |
| `core:system.restart` | 重启程序 | POST `/api/admin/system/restart`; POST `/api/plugins/backend-restart`; POST `/api/plugins/recovery/restart` |
| `core:tools.history.read` | 查看工具执行历史 | GET `/api/tools/history` |
| `core:updates.download` | 下载程序更新 | POST `/api/admin/update/download` |
| `core:updates.install` | 安装程序更新 | POST `/api/admin/update/install` |
| `core:updates.read` | 检查程序更新与下载进度 | GET `/api/admin/update/check`; GET `/api/admin/update/download/progress`; GET `/api/admin/update/last` |
| `download-workbench:page.write` | 切换下载工作台页面 | POST `/api/batch/page` |
| `download-workbench:pixiv.read` | 查询 Pixiv 在线数据 | GET `/api/pixiv/artwork/*/meta`; GET `/api/pixiv/artwork/*/pages`; GET `/api/pixiv/artwork/*/ugoira`; GET `/api/pixiv/me/collection/*/works`; GET `/api/pixiv/me/collections`; GET `/api/pixiv/me/follow-latest`; GET `/api/pixiv/me/following`; GET `/api/pixiv/me/illust-bookmarks`; GET `/api/pixiv/search/range`; GET `/api/pixiv/search`; GET `/api/pixiv/series/*`; GET `/api/pixiv/user/*/artworks`; GET `/api/pixiv/user/*/illust-cards`; GET `/api/pixiv/user/*/request-artworks` |
| `download-workbench:profiles.read` | 读取共享下载预设 | GET `/api/batch/profiles` |
| `download-workbench:profiles.write` | 修改共享下载预设 | PUT `/api/batch/profiles` |
| `download-workbench:state.read` | 读取单人模式的工作台状态 | GET `/api/batch/state` |
| `download-workbench:state.write` | 修改单人模式的工作台状态 | POST `/api/batch/state` |
| `gallery-tools:duplicates.read` | 查看重复作品与扫描状态 | GET `/api/duplicates/groups`; GET `/api/duplicates/scan/status` |
| `gallery-tools:duplicates.scan` | 扫描重复作品 | POST `/api/duplicates/rescan`; POST `/api/duplicates/scan` |
| `gallery-tools:statistics.read` | 查看全库统计 | GET `/api/stats/dashboard` |
| `gallery:collections.write` | 修改插画的精选集归属 | POST `/api/gallery/artworks/collect` |
| `gallery:delete` | 删除全库中的插画及文件 | DELETE `/api/gallery/artwork/*`; POST `/api/gallery/artworks/delete` |
| `gallery:export` | 导出全库中的插画文件 | POST `/api/gallery/artworks/export` |
| `gallery:read` | 查看全库插画与标签 | GET `/api/gallery/artwork/*/by-author`; GET `/api/gallery/artwork/*/by-series`; GET `/api/gallery/artwork/*/related`; GET `/api/gallery/artwork/*/series`; GET `/api/gallery/artwork/*`; GET `/api/gallery/artworks`; GET `/api/gallery/tags/lookup`; GET `/api/gallery/tags` |
| `mail:templates.send` | 发送所有邮件模板 | POST `/api/admin/mail/test-all` |
| `mail:test.send` | 发送测试邮件 | POST `/api/admin/mail/test` |
| `notification:inbox.delete` | 删除站内信 | DELETE `/api/notifications/*` |
| `notification:inbox.read` | 读取安装实例的站内信 | GET `/api/notifications/*/content`; GET `/api/notifications/*`; GET `/api/notifications` |
| `notification:inbox.templates.test` | 发送所有站内信模板 | POST `/api/admin/notifications/test-all` |
| `notification:inbox.test` | 发送测试站内信 | POST `/api/admin/notifications/test` |
| `notification:inbox.update` | 标记站内信已读及调查不可用 | POST `/api/notifications/*/read`; POST `/api/notifications/*/survey-unavailable`; POST `/api/notifications/read-all` |
| `novel:collections.write` | 修改小说的精选集归属 | POST `/api/gallery/novels/collect` |
| `novel:content.read` | 读取小说正文与内嵌图片 | GET `/api/gallery/novel/*/content`; GET `/api/gallery/novel/*/embed/*` |
| `novel:downloads.read` | 读取本密钥提交的小说下载状态 | GET `/api/novel/status/*` |
| `novel:glossary.delete` | 删除小说术语表 | DELETE `/api/admin/glossary/*` |
| `novel:glossary.read` | 读取小说术语表 | GET `/api/admin/glossary/*`; GET `/api/admin/glossary/novel/*/default`; GET `/api/admin/glossary/series/*/default`; GET `/api/admin/glossary` |
| `novel:glossary.write` | 创建及修改小说术语表 | POST `/api/admin/glossary`; PUT `/api/admin/glossary/*/entries`; PUT `/api/admin/glossary/*` |
| `novel:library.delete` | 删除小说及文件 | DELETE `/api/gallery/novel/*`; POST `/api/gallery/novels/delete` |
| `novel:library.export` | 导出小说文件 | GET `/api/novel/*/export`; GET `/api/novel/series/*/merged`; POST `/api/gallery/novels/export/options`; POST `/api/gallery/novels/export` |
| `novel:library.read` | 查看全库小说与系列 | GET `/api/gallery/novel/*/by-series`; GET `/api/gallery/novel/*/cover`; GET `/api/gallery/novel/*/series`; GET `/api/gallery/novel/*`; GET `/api/gallery/novel/series/*/cover`; GET `/api/gallery/novel/series/*`; GET `/api/gallery/novel/translated-titles`; GET `/api/gallery/novels/authors`; GET `/api/gallery/novels/series`; GET `/api/gallery/novels/tags`; GET `/api/gallery/novels`; GET `/api/novel/series/*/novel-ids`; POST `/api/gallery/novels/downloaded-batch` |
| `novel:narration.read` | 读取听书角色与音色 | GET `/api/narration/availability`; GET `/api/narration/cast/voice/reference`; GET `/api/narration/cast`; GET `/api/narration/casts/*/voices`; GET `/api/narration/casts/novel/*/default`; GET `/api/narration/casts`; GET `/api/narration/narrator-presets` |
| `novel:narration.script` | 生成听书脚本 | POST `/api/narration/script` |
| `novel:narration.synthesize` | 合成听书及参考音频 | POST `/api/narration/cast/voice/reference/generate`; POST `/api/narration/tts/line`; POST `/api/narration/tts/preview` |
| `novel:narration.write` | 修改听书角色与参考音频 | DELETE `/api/narration/cast/voice/reference`; POST `/api/narration/cast/voice/reference`; POST `/api/narration/casts`; PUT `/api/narration/cast/voice` |
| `novel:pixiv.read` | 查询 Pixiv 在线数据 | GET `/api/pixiv/me/novel-bookmarks`; GET `/api/pixiv/novel-search/range`; GET `/api/pixiv/novel-search`; GET `/api/pixiv/novel/*/bookmark-count`; GET `/api/pixiv/novel/*/meta`; GET `/api/pixiv/novel/series/*`; GET `/api/pixiv/user/*/novel-cards`; GET `/api/pixiv/user/*/novels` |
| `novel:series.merge` | 生成小说系列合订本 | POST `/api/novel/series/*/merge` |
| `novel:translation.execute` | 翻译小说与系列标题 | POST `/api/novel/*/translate`; POST `/api/novel/series/*/translate-title` |
| `novel:translation.probe` | 检测小说翻译语言 | POST `/api/novel/translate-lang-probe` |
| `novel:translation.read` | 读取小说自动翻译状态 | GET `/api/novel/translate-status/*` |
| `page-editor:components.preview` | 预览组件 | POST `/api/page-editor/components/preview`; POST `/api/page-editor/components/previews/*/close` |
| `page-editor:components.publish` | 发布及停用组件 | POST `/api/page-editor/components/*/availability`; POST `/api/page-editor/components/*/publish` |
| `page-editor:components.read` | 读取组件库 | GET `/api/page-editor/components/*/versions/*`; GET `/api/page-editor/components/*`; GET `/api/page-editor/components` |
| `page-editor:components.write` | 保存组件草稿 | POST `/api/page-editor/components/save` |
| `page-editor:pages.preview` | 预览及校验页面 | POST `/api/page-editor/documents/*/prepare`; POST `/api/page-editor/documents/*/preview`; POST `/api/page-editor/migrate`; POST `/api/page-editor/styles/parse` |
| `page-editor:pages.publish` | 发布及恢复页面 | POST `/api/page-editor/documents/*/publish`; POST `/api/page-editor/documents/*/restore` |
| `page-editor:pages.read` | 读取页面目录与文档 | GET `/api/page-editor/catalog`; GET `/api/page-editor/documents/*/default`; GET `/api/page-editor/documents/*` |
| `page-editor:pages.write` | 保存页面草稿 | POST `/api/page-editor/documents/*/save` |
| `plugin-market:install` | 安装市场插件及依赖（允许服务器执行代码） | POST `/api/plugin-market/*/*/*/install-preview`; POST `/api/plugin-market/*/*/*/install`; POST `/api/plugin-market/operations/*/execute` |
| `plugin-market:operations.discard` | 放弃待执行的插件获取操作 | POST `/api/plugin-market/operations/*/discard` |
| `plugin-market:operations.prepare` | 准备插件获取操作 | POST `/api/plugin-market/operations` |
| `plugin-market:read` | 浏览插件市场与安装预览 | GET `/api/plugin-market/*/*/*/install-preview`; GET `/api/plugin-market/catalog`; GET `/api/plugin-market/content/*/*/*/*`; GET `/api/plugin-market/content/*/*/image`; GET `/api/plugin-market/plugins/*/*/*/facts`; GET `/api/plugin-market/plugins/*/*`; GET `/api/plugin-market/repositories` |
| `plugin-market:repositories.preview` | 检查待导入的插件仓库 | POST `/api/plugin-market/repositories/import/preview` |
| `plugin-market:repositories.trust` | 信任并导入插件仓库 | POST `/api/plugin-market/repositories/import/trust` |
| `push:templates.send` | 发送所有推送模板 | POST `/api/admin/push/test-all` |
| `push:test.send` | 发送测试推送 | POST `/api/admin/push/test` |
| `translation:cancel` | 暂停或取消翻译任务 | POST `/api/translation/jobs/*/cancel`; POST `/api/translation/jobs/*/pause` |
| `translation:delete` | 删除翻译任务记录 | DELETE `/api/translation/jobs/*` |
| `translation:execute` | 开始或恢复小说与系列翻译 | POST `/api/translation/jobs/*/resume`; POST `/api/translation/jobs`; POST `/api/translation/novel`; POST `/api/translation/series` |
| `translation:read` | 查看翻译状态、语言与任务 | GET `/api/translation/jobs/*`; GET `/api/translation/jobs`; GET `/api/translation/languages`; GET `/api/translation/status`; POST `/api/translation/language` |
| `tts:synthesize` | 在线合成朗读音频 | POST `/api/tts/edge/synthesize` |
| `tts:voices.read` | 查看在线朗读音色 | GET `/api/tts/edge/voices` |
| `<plugin>:settings.read` | 读取设置与预设 | `core:settings.read` |
| `<plugin>:settings.write` | 校验与保存设置 | `core:settings.write` |
| `<plugin>:media.read` | 查看媒体工具与任务 | `core:media.read` |
| `<plugin>:media.preview` | 预览媒体处理 | `core:media.preview` |
| `<plugin>:media.execute` | 执行媒体处理 | `core:media.execute` |
| `<plugin>:media.cancel` | 取消媒体处理 | `core:media.cancel` |
| `<plugin>:file-import.read` | 查看文件导入工具与任务 | `core:file-import.read` |
| `<plugin>:file-import.preview` | 预览文件导入 | `core:file-import.preview` |
| `<plugin>:file-import.execute` | 执行文件导入 | `core:file-import.execute` |
| `<plugin>:file-import.cancel` | 取消文件导入 | `core:file-import.cancel` |
| `<plugin>:file-import.acknowledge` | 确认文件导入结果 | `core:file-import.acknowledge` |
| `<plugin>:downloads.create` | 创建下载任务 | `core:downloads.create` |
| `<plugin>:batches.execute` | 允许批次调用此插件的来源与作品执行器 | `core:batches.create`, `core:batches.resume` |
| `<plugin>:schedule.manage` | 管理此插件来源的持久计划与凭据策略 | `core:schedule.*` |
| `<plugin>:plugins.manage` | 允许管理此插件的生命周期、启用状态与信任 | `core:plugins.*` |
