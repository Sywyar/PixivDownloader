# HTTP API 与程序权限

外部程序可使用 `Authorization: Bearer <程序密钥>`。在安全页面的「程序访问密钥」中创建密钥，设置名称、明确权限和允许的来源 IP。完整密钥仅显示一次，服务端只保存摘要。密钥默认不过期，可编辑权限及来源范围，也可撤销。选择整个插件只包含当前列出的权限，不包含未来新增权限。

来源限制接受数值 IPv4、IPv6 和 CIDR 网段。受限模式至少填写一项，「任何来源」需要明确选择。使用反向代理前先配置可信代理。不要同时发送程序密钥、管理员会话、邀请码或 GUI 凭据。采集凭据仍通过独立的 `X-Acquisition-Credential` 传递。

管理员会话仍可通过 `X-Session-Token` 使用原有接口。程序密钥只能访问权限目录明确列出的方法与路径，其他接口保留人类管理员或本机流程。密钥管理和管理员凭据修改只允许人类管理员。这里不提供 MCP 服务。

使用 Cookie 的写请求必须通过同源检查。浏览器发送跨域 Origin 会被拒绝；插件管理、计划任务等入口还要求明确的同源信号。`/api/gui/**` 保留本机与 GUI token 限制，远程程序使用下列业务接口。插件能力只在对应插件活动且依赖可用时提供。

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
2. POST `/api/download/batches` 提交 `name`、`sourceType`、`activationToken`、`definitionJson`，可选 `proxy`。`definitionJson` 是 JSON 字符串，最多 1 MiB UTF-8；整个请求最多 8 MiB。
3. 使用返回的 UUID 查询 GET `/api/download/batches/{id}` 和 `/{id}/items?offset=0&limit=100`。逐项身份为批次 UUID 加 `workType`、`id`；它不是单项下载的执行 UUID。
4. POST `/{id}/pause`、`/cancel`、`/resume`、`/retry` 分别暂停、取消、恢复扫描或只重试失败项。停止中的状态只有在实际执行退出后才终结。重试已发现的失败项不会补扫尚未完成的来源。
5. 空闲时 DELETE `/{id}` 删除批次记录。一个进程同时保留一个批次；开始新批次前必须删除旧批次。结果不跨进程恢复。

`explicit-list` 支持混合插画和小说。将下列对象序列化成 `definitionJson`；作品 ID 必须为正整数的十进制字符串，同类型不接受重复 ID：

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
  "limit": 100,
  "dryRun": true,
  "alwaysNetwork": false
}
```

`limit:0` 表示全量；正文最多 4 KiB。202 只表示接纳，继续查询本次执行状态。分类移动分别返回复制结果、源目录是否删除和数据库登记是否确认；部分完成后先检查结果，不盲目重试移动。

数据库迁移使用预览返回的 token 确认。环境恢复使用预览 token、选择字段、路径映射及密码，返回 `RESTORE_PENDING` 后由替代进程执行。环境导出须给出服务器绝对 `destination` 和 `confirmRestart:true`，可选 `password`、`includeCredentials`、`includeDatabases`、`includeState`、`includeResources`；资源须同时包含数据库，凭据、数据库或状态须设置密码。归档不会覆盖已有目标。服务会停止后端、导出、再请求完整重启，客户端需重新连接查询；重启失败时须手动启动应用。更新安装须提交当前已验证安装包的 SHA-256。

## 错误与历史

错误响应包含机器 `code` 和本地化 `error`。400 表示非法参数，401/403 表示身份或权限拒绝，404 表示指定身份不存在，409 表示忙碌、版本冲突或状态不允许，413 表示正文超限，503 表示当前能力不可用。按业务状态处理，不把所有非 2xx 自动重试。

GET `/api/download/tasks/history` 默认每页 50 项、最多 200 项，按返回的 `beforeTime` 与 `beforeId` 一起续页。它只枚举已保存终态，不恢复在途下载。单项下载的请求去重限于当前进程保留窗口；查不到执行不代表可以安全重投。工具历史与批次结果使用各自保留规则。
## 程序权限列表

当前安装的权威目录由管理员调用 GET `/api/security/program-keys/permissions` 获取。下表列出内置声明；外部插件的权限以当前目录为准。`*` 表示一个路径段。密钥只接受精确授权，不支持插件或路径通配授权。

共享设置、媒体工具和文件导入同时要求 `core` 入口权限与目标插件的同名权限；目录只列出已获授权的目标。单项下载需要 `core:downloads.create` 和实际下载类型所属插件的 `downloads.create`。批次需要 `core:batches.create` 或 `core:batches.resume`，以及来源和完整执行计划中每个作品插件的 `batches.execute`。查询来源目录使用 `core:schedule.sources.read`。

程序只能查询、取消和管理自身创建的下载任务及批次；管理员仍有安装范围的管理权限。批次仍是每个进程共用一个，不会因密钥数量增加并发槽位。翻译、维护工具、设置、日志、数据库和环境权限作用于对应插件或整个安装；它们不是每把密钥的私有副本。数据库迁移与环境归档包括整套安装的选定内容。

密钥管理仅供管理员会话：GET `/api/security/program-keys` 列表、GET `/permissions` 目录、POST 根路径创建、PUT `/{id}` 修改、DELETE `/{id}` 撤销。创建体包含 `name`、`permissions`、`anyIp`、`networks`；修改体为 `{"revision":1,"policy":{...}}`，撤销体为 `{"revision":1}`。使用读取所得的修订号；冲突后重新读取，不覆盖别人的修改。创建成功返回 `key` 与一次性 `secret`，列表不返回秘密或摘要。

每个安装最多 128 个密钥，每个名称最多 80 个字符、最多 512 项权限和 64 个来源网段；管理写入体最多 128 KiB。最近使用时间只记录本次进程观察到的成功认证。撤销阻止新请求，不会自动取消已经接纳的任务。

启动缓存使用 GET `/api/tools/startup-cache`、POST `/api/tools/startup-cache/tasks` 和 POST `/api/tools/startup-cache/tasks/{id}/cancel`；开始请求体为 `{}`。返回接纳不表示训练成功，继续读取状态。

| 权限 | 用途 | 方法与路径 |
| --- | --- | --- |
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
| `core:database.check` | 检查数据库连接 | POST `/api/settings/database/check` |
| `core:database.migrate` | 迁移整套安装的数据库 | POST `/api/settings/database/migrations` |
| `core:database.preview` | 预览整套安装的数据库迁移 | POST `/api/settings/database/migration-previews` |
| `core:database.read` | 查看数据库配置与迁移状态 | GET `/api/settings/database/migration`; GET `/api/settings/database/providers`; GET `/api/settings/database/status`; GET `/api/settings/database` |
| `core:directories.read` | 检查作品目录 | GET `/api/tools/directories` |
| `core:directories.write` | 修改作品目录记录 | POST `/api/tools/directories` |
| `core:downloads.cancel` | 取消本密钥的下载任务 | POST `/api/download/tasks/*/cancel` |
| `core:downloads.create` | 创建下载任务 | POST `/api/download/tasks` |
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
| `core:logs.read` | 读取运行日志 | GET `/api/tools/logs/*` |
| `core:media.cancel` | 取消媒体处理 | POST `/api/tools/media/tasks/cancel` |
| `core:media.execute` | 执行媒体处理 | POST `/api/tools/media/tasks` |
| `core:media.preview` | 预览媒体处理 | POST `/api/tools/media/previews` |
| `core:media.read` | 查看媒体工具与任务 | GET `/api/tools/media/tasks/current`; GET `/api/tools/media`; POST `/api/tools/media/capabilities` |
| `core:paths.read` | 查看路径前缀 | GET `/api/tools/path-prefixes` |
| `core:paths.write` | 修改路径前缀 | POST `/api/tools/path-prefixes/pin`; POST `/api/tools/path-prefixes` |
| `core:repositories.read` | 查看插件仓库设置 | GET `/api/settings/repositories` |
| `core:repositories.write` | 修改插件仓库设置 | PUT `/api/settings/repositories` |
| `core:schedule.sources.read` | 查看批次与计划来源目录 | GET `/api/schedule/sources` |
| `core:settings.read` | 读取设置与预设 | GET `/api/settings/presets`; GET `/api/settings` |
| `core:settings.write` | 校验与保存设置 | POST `/api/settings/validate`; POST `/api/settings` |
| `core:startup-cache.cancel` | 取消启动缓存训练 | POST `/api/tools/startup-cache/tasks/*/cancel` |
| `core:startup-cache.execute` | 训练启动缓存 | POST `/api/tools/startup-cache/tasks` |
| `core:startup-cache.read` | 查看启动缓存状态 | GET `/api/tools/startup-cache` |
| `core:system.check` | 检查网络连接 | POST `/api/admin/system/connectivity` |
| `core:system.read` | 查看系统状态 | GET `/api/admin/system` |
| `core:system.reload` | 重新加载配置 | POST `/api/admin/system/reload` |
| `core:system.restart` | 重启程序 | POST `/api/admin/system/restart` |
| `core:tools.history.read` | 查看工具执行历史 | GET `/api/tools/history` |
| `translation:cancel` | 暂停或取消翻译任务 | POST `/api/translation/jobs/*/cancel`; POST `/api/translation/jobs/*/pause` |
| `translation:delete` | 删除翻译任务记录 | DELETE `/api/translation/jobs/*` |
| `translation:execute` | 开始或恢复小说与系列翻译 | POST `/api/translation/jobs/*/resume`; POST `/api/translation/jobs`; POST `/api/translation/novel`; POST `/api/translation/series` |
| `translation:read` | 查看翻译状态、语言与任务 | GET `/api/translation/jobs/*`; GET `/api/translation/jobs`; GET `/api/translation/languages`; GET `/api/translation/status`; POST `/api/translation/language` |
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
