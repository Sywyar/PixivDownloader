# 管理员 HTTP API

外部程序可使用管理员登录得到的 `X-Session-Token` 调用业务接口。该令牌是人员会话，沿用会话撤销和维护模式限制；这里没有独立应用密钥或 MCP 服务。采集用的 Pixiv 凭据通过 `X-Acquisition-Credential` 传递，与管理员身份分开。

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
