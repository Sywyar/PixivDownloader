# HTTP API 与程序权限

## 用脚本或 Agent 访问 API

1. 在运行 PixivDownloader 的实例上打开 `/openapi`（本机默认 `http://localhost:6999/openapi`）。以管理员身份登录后，可查看当前接口、请求示例和每项操作所需的权限。
2. 在桌面「设置 → 访问控制 → 程序访问密钥」创建密钥，选好来源 IP 和任务所需权限。脚本或 Agent 要自行读取接口文档，还需勾选 `core:api-reference.read`。没有桌面时，管理员可用登录会话调用 `/api/security/program-keys`。
3. 脚本通过 `Authorization: Bearer <程序密钥>` 请求 `GET /api/openapi.json`，即可读取机器可读的 OpenAPI 文档。使用 Agent 时，可在创建密钥后点「复制提示词」，将实例的文档地址交给 Agent，密钥单独配置。密钥缺少文档读取权限时，由管理员提供所需的接口说明。

任何人都可以打开 `/openapi` 的访问说明。读取 JSON 文档需要管理员会话或具有文档读取权限的程序密钥；`HEAD /api/openapi.json` 可用相同身份检查权限。

建议使用 HTTPS。来自本机或局域网以外的 HTTP 程序请求默认被拒绝。如需例外，请先复核该密钥的风险，再确认允许公网 HTTP。编辑权限或来源会恢复 HTTPS 要求。即使服务器拒绝请求，已通过明文发送的密钥仍可能被截获。

完整密钥只在创建成功时显示一次，服务端只保存摘要。密钥默认不过期，可编辑权限及来源范围，也可撤销。选择整个插件只包含当前列出的权限。

来源限制接受数值 IPv4、IPv6 和 CIDR 网段。受限模式至少填写一项，「任何来源」需要明确选择。使用反向代理前先配置可信代理。不要同时发送程序密钥、管理员会话、邀请码或 GUI 凭据。采集凭据仍通过独立的 `X-Acquisition-Credential` 传递。

管理员会话仍可通过 `X-Session-Token` 使用原有接口。程序密钥只能访问权限目录明确列出的方法与路径，其他接口保留人类管理员或本机流程。密钥管理和管理员凭据修改只允许人类管理员。这里不提供 MCP 服务。

单独发送有效密钥访问未声明 `programKey` 的操作会收到 `403 auth.program.unsupported`，包括允许匿名访问的操作。无效密钥或混用凭据可能返回 `401 auth.program.invalid`。

使用 Cookie 的写请求必须通过同源检查。浏览器发送跨域 Origin 会被拒绝；插件管理、计划任务等入口还要求明确的同源信号。`/api/gui/**` 保留本机与 GUI token 限制，远程程序使用下列业务接口。插件能力只在对应插件活动且依赖可用时提供。

调用 `GET /api/security/program-keys/self` 可查看当前密钥的 `id` 和 `permissions`，权限列表可以为空。使用 Bearer 发送写请求时也要查看 `x-csrf-origin`。`POST /api/schedule/tasks` 要求同源 Origin 或 Referer。对于没有浏览器凭证的原生 Bearer 客户端，`POST /api/download/tasks` 和 `/api/download/batches` 允许省略两者。

轮换密钥时，管理员在桌面安全页或通过管理员会话创建新密钥。客户端切换后，用 `GET /api/security/program-keys/self` 核对新密钥的身份和权限，再由管理员撤销旧密钥。新密钥无法读取旧密钥提交的任务，可在撤销前轮询至终态，或用管理员会话跟踪。

## 接口索引

| 功能 | 入口 |
| --- | --- |
| 设置字段、默认值和脱敏当前值 | GET `/api/settings`；GET `/api/settings/presets` |
| 校验和保存设置 | POST `/api/settings/validate`；POST `/api/settings` |
| 插件仓库配置 | GET / PUT `/api/settings/repositories` |
| 数据库设置与连接测试 | GET `/api/settings/database`、`/providers`、`/status`；POST `/check` |
| 数据库迁移 | POST `/api/settings/database/migration-previews`、`/migrations`；GET `/migration` |
| 环境归档 | POST `/api/settings/environment/preview`、`/restore`、`/exports`；GET `/restore`、`/exports`、`/exports/{id}/archive`、`/installation` |
| 元数据回填 | GET `/api/tools/backfill/defaults`、`/tasks/current`、`/tasks/{id}`；POST `/candidates`、`/tasks`、`/tasks/{id}/cancel` |
| 媒体维护与记录文件导入 | GET `/api/tools/media`、`/api/tools/file-import`；各入口下 POST `/previews`、`/tasks`、`/tasks/cancel`，GET `/tasks/current` |
| 媒体能力与导入完成确认 | POST `/api/tools/media/capabilities`；POST `/api/tools/file-import/completion/acknowledge` |
| 工具历史与日志附件 | GET `/api/tools/history`、`/api/tools/logs/{fileName}` |
| 目录引用检查与修正 | GET / POST `/api/tools/directories` |
| 路径前缀 | `/api/tools/path-prefixes` |
| 图片分类 | `/api/tools/classifier/settings`、`/folders`、`/images`、`/image`、`/moves` |
| FFmpeg 状态和安装 | GET `/api/tools/ffmpeg`；POST `/api/tools/ffmpeg/tasks` |
| 系统与控制中心 | `/api/admin/system`、`/api/admin/control-center` |
| AI、邮件和推送测试 | `/api/admin/ai/test`、`/models`；`/api/admin/mail/test`、`/test-all`；`/api/admin/push/test`、`/test-all` |
| 软件更新 | `/api/admin/update/check`、`/last`、`/download`、`/download/progress`、`/install` |
| 单项下载和历史 | `/api/download/tasks`、`/api/download/tasks/history` |
| 后台手动批次 | `/api/download/batches` |

表中缩写路径接在同一行的完整前缀后。翻译已有 `/api/translation/**`，术语表、朗读、画廊、收藏夹、计划、维护和插件市场继续使用各自接口。桌面窗口、文件选择器和打开本机浏览器属于客户端操作。

## 保存设置

先读取字段目录，再按返回的 `owner`、`packageId`、`generation` 和 `key` 提交变更。宿主字段的 owner/packageId 为 null、generation 为 0。敏感字段仅返回 `stored` 标志，不返回明文或默认秘密；使用 `KEEP`、`SET`、`CLEAR` 明确保留、替换或清除。预设是字段值集合，通过同一保存接口应用。`effect` 表示生效方式，保存不等于已经重启后端或进程。

以下是 POST `/api/settings/validate` 或 `/api/settings` 的示例正文：

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

仓库编辑须携带 GET 返回的 `revision`；增加仓库或改变信任、地址及网络策略时须传 `trustConfirmed:true`。修订冲突时重新读取，不覆盖其他编辑。连接测试与工具参数仅作用于本次操作，不保存配置。

## 后台手动批次

1. GET `/api/schedule/sources` 获取当前 `sourceType` 和 `activationToken`。来源定义由所属插件解释。
2. POST `/api/download/batches` 提交 `name`、`sourceType`、`activationToken`、`definition`，可选 `proxy`。 将 `definition` 作为 JSON 对象发送，序列化为 UTF-8 后最多 1 MiB，整个请求最多 8 MiB。
3. 使用返回的 UUID 查询 GET `/api/download/batches/{id}` 和 `/{id}/items?offset=0&limit=100`。逐项身份为批次 UUID 加 `workType`、`id`；它不是单项下载的执行 UUID。
4. POST `/{id}/pause`、`/cancel`、`/resume`、`/retry` 分别暂停、取消、恢复扫描或只重试失败项。停止中的状态只有在实际执行退出后才终结。重试已发现的失败项不会补扫尚未完成的来源。
5. 空闲时 DELETE `/{id}` 删除批次记录。一个进程同时保留一个批次；开始新批次前必须删除旧批次。结果不跨进程恢复。

提交时收到 403，可从 `programPermission` 读取已知的缺失权限。收到 `auth.program.unsupported` 时，查看操作的 `x-program-alternative`。收到 202 后，持 `core:batches.read` 访问 `Location`。若后台执行缺权，批次结果会在 `programPermission` 中给出权限 ID。

`explicit-list` 支持混合插画和小说。将下列对象放入 `definition`。作品 ID 必须为正整数的十进制字符串，同类型不接受重复 ID：

```json
{
  "kind": "mixed",
  "source": {
    "acquisitionMode": "single-import",
    "works": [
      {
        "kind": "illust",
        "id": "123"
      },
      {
        "kind": "novel",
        "id": "456"
      }
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

参数中的作品 ID 是演示值，请替换为目标作品。来源还包括作者投稿、约稿、搜索、系列、收藏、关注新作和珍藏集。筛选支持内容分级、AI、标签、插画类型、页数、小说字数和收藏数；启用收藏数筛选时，未知收藏数不能通过。已完成和筛选跳过的作品在恢复时不会重复执行。

批次最多接纳 5,000 项，并受 64 MiB 编码内容预留预算约束，先达到的限制生效；这不是 JVM 堆大小保证。每页最多 200 项。凭据替换使用 PUT `/{id}/credentials`，正文为 `{"expectedRun":1}`，新凭据通过请求头传入，缺省表示清除；只允许当前轮次已空闲的批次，恢复前会重新探活。待处理选择使用 POST `/{id}/resolve`，包含 `expectedRun`、`workType`、`workId`、`reasonCode`、`userAction`、`rememberForRun`；原因和轮次必须与当前记录一致。这两个请求正文均最多 16 KiB。

## 工具与重启操作

媒体维护和记录文件导入先取清单，再提交清单中的精确 `identity`（pluginId/packageId/generation/publication）及 `options` 做预览；开始执行须提交同一身份和预览 `token`，取消须同时携带 `executionId`。插件换代或桌面启动替代任务后，旧 token/UUID 不会作用于新任务。路径均指服务器文件系统，不是远程客户端的本机路径。

回填 POST `/api/tools/backfill/tasks` 示例：

```json
{
  "delayMs": 0,
  "artworkIds": [12345],
  "dryRun": true,
  "alwaysNetwork": false
}
```

`limit:0` 表示全量；正文最多 4 KiB。202 只表示接纳，继续查询本次执行状态。分类移动分别返回复制结果、源目录是否删除和数据库登记是否确认；部分完成后先检查结果，不盲目重试移动。

数据库迁移使用预览返回的 token 确认。环境恢复使用预览 token、选择字段、路径映射及密码，返回 `RESTORE_PENDING` 后由替代进程执行。环境导出须给出服务器绝对 `destination` 和 `confirmRestart:true`，可选 `password`、`includeCredentials`、`includeDatabases`、`includeState`、`includeResources`；资源须同时包含数据库，凭据、数据库或状态须设置密码。归档不会覆盖已有目标。服务会停止后端、导出、再请求完整重启，客户端需重新连接查询；重启失败时须手动启动应用。更新安装须提交当前已验证安装包的 SHA-256。

`POST /api/library/artworks/{artworkId}/recover-metadata` 接受调用方提供的字段，保留已有值；请求体缺失、不可读或没有可用字段会返回 `400`。要由后端获取标签和系列，可按导入作品 ID 启动回填。 [导入后补齐作品资料](/zh-cn/local-import?id=metadata-backfill)

使用 `GET /api/gallery/artworks?fileManagement=EXTERNAL_REFERENCE` 筛选引用外部文件的作品。`MANAGED` 表示文件由应用管理，删除作品时一并清理。`EXTERNAL_REFERENCE` 表示当前登记引用外部文件，删除作品时保留原件。删除后，作品库保留 `deleted=true` 的记录供查重。

在 `/openapi` 打开一个操作，点击响应列表下方「相关接口」里的方法名即可跳转。例如，「列出本地导入预设」可直接跳到「预览本地作品扫描」。服务端按语言缓存接口文档和相关链接。插件路由等目录内容发生变化后，下次打开页面即可看到更新。

操作的 `externalDocs` 指向使用指南。响应 `links` 给出后续请求，并指明从响应何处取参。`x-related-operations` 优先列出声明的替代入口和响应链接，其次是说明中提到的路由和同资源或同组操作。调用前核对目标接口的参数和权限。`x-related-guides` 列出这些操作的指南。

插画和小说的批量筛选覆盖所有匹配页。`"mode":"filter"` 要求 `filter` 是 JSON 对象。ID 数组只接受正的 JSON 整数，例如 `42`。小数 `42.9`、字符串 `"42"`、零和负数都会返回 HTTP 400，可根据响应中的 `field` 修正。显式传入 `"filter":{}` 或仅含分页参数的 `filter` 时，会选中该类型的全部作品。删除或导出后清理源作品时，须用 `"confirmAll":true` 确认这种选择；程序密钥还须具备对应图库的删除权限。导出返回 `archiveToken` 后，使用 `core:archives.read` 轮询 `GET /api/archive/status/{token}`。状态为 `ready` 时，使用 `core:archives.download` 调用 `GET /api/archive/download/{token}`。

## 错误与历史

查询插画或小说元数据，或通过 `POST /api/download/tasks` 提交下载时，`404 WORK_NOT_FOUND` 表示来源在当前身份下找不到目标作品。请核对作品 ID 和可见性。按任务 ID 查询时，`404 DOWNLOAD_TASK_NOT_FOUND` 表示当前调用方查不到该任务。

轮询手动批次时，用 `state` 判断状态。`code` 可以为空，也可以是来源插件或账号策略提供的原因码。`batch.work-failed` 可通过批次的 `items` 查看失败项；作者批次返回 `schedule.pixiv.discovery-not-found` 时，应核对作者 ID。遇到未知码仍按 `state` 处理。

错误响应包含机器 `code` 和本地化 `error`。400 表示非法参数，401/403 表示身份或权限拒绝，404 表示指定身份不存在，409 表示忙碌、版本冲突或状态不允许，413 表示正文超限，503 表示当前能力不可用。按业务状态处理，不把所有非 2xx 自动重试。

`POST /api/download/tasks` 返回 202 时，可轮询 `Location`。路径参数 `taskId` 取回执中的 `task.attempt.attemptId`。

建议每 2 秒读取一次。`revision` 持续不变时逐步延长至 10 秒，有更新时恢复到 2 秒。`phase` 为 `COMPLETED`、`FAILED` 或 `CANCELLED` 时停止。收到 `Retry-After` 时，至少等待指定时间再请求。

从 `x-download-submissions` 选择 `workType`。未发布的类型返回 `400 DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE`。请求体字段出错时，`field` 是 JSON Pointer。查询、路径、请求头和 multipart 参数的 `field` 是接口声明中的名称。调用方可按 `in`、`field` 和 `reason` 定位问题。例如 `/options/pages` 配合 `out_of_range` 表示页码超出作品范围。`explicit-list` 批次缺少 `/definition/source/acquisitionMode` 时，在创建批次前返回 400。

有进度时，一起读取 `progress.unit`、`completed` 和 `total`。这些值描述本次尝试的当前步骤，最终结果看 `phase`。`progress` 为空表示没有测量值。

| 值 | 读取和提交方式 |
| --- | --- |
| Pixiv 作品 ID | 图库 `artworkId`、导出 `ids[]` 是 JSON 整数。Pixiv 搜索 `id`、任务 `workId`、批次 `key.id` 是字符串。转换时保留整数精度，其他提供方自行定义 `workId`。 |
| 下载设置 | 单任务 `options` 用 `{"bookmark":"true","mediaQuality":"80","mediaWebpLossless":"true"}`。批次 `definition.download` 用 `{"bookmark":true,"mediaQuality":80,"mediaWebpLossless":true}`。 |
| 时间与文件 | 任务 `updatedAt` 使用 ISO 8601。批次 `createdTime`、图库 `time`、计划 `nextRunTime` 使用 Unix 毫秒。`outputPath` 是本次任务预定的服务器目录，图库 `folder` 是作品登记位置。按作品 ID 关联。 |
| 响应结构 | `GET /api/authors` 返回数组，`GET /api/authors/paged` 返回含 `content` 的分页对象，`GET /api/downloaded/statistics` 返回含 `success` 和计数的对象。 |

| 接口 | 分页参数 | 如何获取下一页 |
| --- | --- | --- |
| `GET /api/gallery/artworks` | `page`, `size` | 首页传 `page=0`，随后将 `page` 加一。 |
| `GET /api/download/batches/{id}/items` | `offset`, `limit` | 从 `offset=0` 开始，按本页实际返回的条目数增加偏移量。 |
| `GET /api/download/tasks/history` | `limit`, `beforeTime`, `beforeId` | 首页省略两个游标字段。续页时原样传回上一页返回的两个值。 |
| `GET /api/pixiv/search` | `page` | 从第 1 页开始，小于 1 取第 1 页。 |
| `GET /api/plugin-market/catalog` | `cursor`, `limit` | 将 `nextCursor` 原样传为 `cursor`。插件版本列表的续页字段是 `nextVersionCursor`。 |

下载任务历史默认每页 50 项，最多 200 项。它只枚举已保存终态，不恢复在途下载。同一调用方在当前进程仍保留幂等记录时，以相同 `requestId` 重试相同命令可取得原回执。终态结果保存后，幂等记录可在任务结束五分钟后过期，再次提交可能创建新任务。工具历史与批次结果使用各自保留规则。

## 程序权限列表

当前安装的权威目录由管理员调用 GET `/api/security/program-keys/permissions` 获取。下表列出内置声明；外部插件的权限以当前目录为准。`*` 表示一个路径段。密钥只接受精确授权，不支持插件或路径通配授权。

共享设置、媒体工具和文件导入同时要求 `core` 入口权限与目标插件的同名权限；目录只列出已获授权的目标。单项下载需要 `core:downloads.create` 和实际下载类型所属插件的 `downloads.create`。批次需要 `core:batches.create` 或 `core:batches.resume`，以及来源和完整执行计划中每个作品插件的 `batches.execute`。查询来源目录使用 `core:schedule.sources.read`。

程序只能查询、取消和管理自身创建的下载任务及批次；管理员仍有安装范围的管理权限。批次仍是每个进程共用一个，不会因密钥数量增加并发槽位。翻译、维护工具、设置、日志、数据库和环境权限作用于对应插件或整个安装；它们不是每把密钥的私有副本。数据库迁移与环境归档包括整套安装的选定内容。

密钥管理仅供管理员会话：GET `/api/security/program-keys` 列表、GET `/permissions` 目录、POST 根路径创建、PUT `/{id}` 修改、DELETE `/{id}` 撤销。创建体包含 `name`、`permissions`、`anyIp`、`networks`；修改体为 `{"revision":1,"policy":{...}}`，撤销体为 `{"revision":1}`。使用读取所得的修订号；冲突后重新读取，不覆盖别人的修改。创建成功返回 `key` 与一次性 `secret`，列表不返回秘密或摘要。

每个安装最多 128 个密钥，每个名称最多 80 个字符、最多 512 项权限和 64 个来源网段；管理写入体最多 128 KiB。最近使用时间只记录本次进程观察到的成功认证。撤销阻止新请求，不会自动取消已经接纳的任务。

启动缓存使用 GET `/api/tools/startup-cache`、POST `/api/tools/startup-cache/tasks` 和 POST `/api/tools/startup-cache/tasks/{id}/cancel`；开始请求体为 `{}`。返回接纳不表示训练成功，继续读取状态。

### 权限范围与专用入口

计划任务使用 `core:schedule.*` 中对应动作的精确权限，并要求来源插件的 `schedule.manage`；账号策略动作要求策略 owner 的同名权限。列表按来源授权裁剪。这里的 `*` 只是文档分组，不是可保存的权限。计划属于安装的持久配置，授权包含该来源的完整调度业务；撤销创建密钥不会删除或停用计划。需要取消后续运行时，先停用或删除计划。

插件生命周期、启用状态和信任操作同时要求对应 `core:plugins.*` 动作与目标的 `<plugin>:plugins.manage`；有依赖消费者时，还检查消费者授权。尚未加载但已安装的可管理插件也会出现在目标目录中。安装权限允许安装包及其依赖在服务器执行代码；全局 Hook 发布权限允许修改安全策略，应按这一实际影响授权。

共享凭据分为 `core:credentials.read`、`core:credentials.write` 和 `core:credentials.use`。读取只返回信息，不返回秘密；使用 `X-Acquisition-Credentials` 引用已保存凭据时，除业务权限外还需要 `credentials.use`。直接提供当次取得凭据仍使用 `X-Acquisition-Credential`。

画廊、小说库、收藏夹、站内信、归档、页面编辑、更新与维护权限作用于共享安装，不自动限制为该密钥创建的数据。下载任务与手动批次继续按密钥身份隔离。本机维护、恢复和旧元数据迁移仍要求本机来源；权限不会替代来源、确认、修订或租约检查。

身份建立与密钥管理、GUI token、验证码、浏览器采集票据、匿名配额及页面装配保留专用流程。程序下载与进度使用 `/api/download/tasks`，文件导入使用 `/api/tools/file-import`，作品管理使用 `/api/library/artworks/{id}`，页面业务使用 `/api/page-editor`。站内信测试使用 `/api/admin/notifications/test` 或 `/test-all`。旧浏览器兼容路由不另授予程序权限。

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
