# 编写 HTTP API 与权限声明

新增或修改 Web、Swing、Compose、CLI 的业务功能时，应同步检查是否需要提供配套的 HTTP API、程序密钥权限声明与多语言 OpenAPI 文档。客户端调用方式与完整权限清单见 [OpenAPI 与程序密钥](/zh-cn/http-api)，插件扩展契约见 [第三方插件 SDK](/zh-cn/plugin-development)。

## 什么时候需要配套 HTTP API

- **业务能力统一暴露 HTTP 接口**：查询业务状态、读取与保存设置、应用预设、下载、翻译、导入、导出、数据库迁移、维护和系统管理均应能通过 HTTP 完成。已有等价接口时直接复用同一业务服务，不为不同 GUI 复制执行逻辑。
- **分离客户端表现与服务端业务**：窗口大小、焦点、主题预览、剪贴板、系统文件选择器和打开本机浏览器属于客户端表现，无需服务端模拟。流程中包含文件操作时，远程客户端文件必须通过上传传输，服务端路径参数始终指向服务器本地文件系统，两者不得混用。
- **长任务生命周期**：异步长任务须提供启动（`202 Accepted`）、状态查询、按执行身份取消及结果查询接口，并在文档中写明取消收尾语义（请求取消不代表已立即停止，查不到任务不代表可安全重投）、进程重启后的状态以及幂等范围。

## 声明路由与程序权限

路由由功能所属 owner 在 `PixivFeaturePlugin.routes()` 中声明。`AccessPolicy` 控制人员登录、访客与本机 GUI 流程；程序密钥访问则通过 `WebRouteContribution.withProgramPermissions(...)` 按 HTTP 方法显式声明（未声明的操作默认拒绝程序密钥，不继承管理员权限）：

```java
WebRouteContribution.admin("/api/example/items")
    .withProgramPermissions(Map.of(
        HttpMethod.GET, new ProgramPermission("items.read", "permission.items.read"),
        HttpMethod.POST, new ProgramPermission("items.create", "permission.items.create")
    ));
```

- **权限 ID 与适用路径**：完整权限 ID 由宿主按插件注册身份自动拼为 `<owner>:<permission>`（如 `example:items.read`），展示文案 key 放入该 owner 的展示 i18n 资源中。程序权限仅支持明确的 `/api/` 路由与具体 HTTP 方法（支持单路径段 `*` 表示资源 ID，不接受 `**`、`OPTIONS` 或 GUI/LOCAL 专用路由）。
- **权限粒度与目标复核**：同一权限下的接口应具有一致的业务目的与数据范围；插件分组勾选仅批量选择当前明确列出的权限，不生成面向未来权限的通配符。共享接口（设置、媒体维护、文件导入、插件生命周期、计划任务等）除校验 `core` 入口权限外，还必须在产生任何副作用前校验目标插件或来源的权限，并按已获授权范围裁剪读取列表（各业务域的组合规则与专用入口见 [OpenAPI 与程序密钥：权限范围与专用入口](/zh-cn/http-api#权限范围与专用入口)）。

| 业务域 | 权限拆分原则 | 附加安全校验 |
| --- | --- | --- |
| 设置与预设 | 读取、校验与保存分离 | 校验每个字段的实际 owner；敏感值只返回 `stored` 标志、绝不回传明文 |
| 媒体维护与文件导入 | 读取、预览、执行、取消、完成确认分离 | 校验精确插件 publication、预览 token 与执行 ID |
| 下载与翻译 | 查询、创建/恢复、取消、删除记录分离 | 校验数据范围、采集凭据与任务创建者归属 |
| 数据库与环境归档 | 查询、检查、预览、迁移、导出、恢复分离 | 作用于整套安装，须校验显式确认、预览 token 与停写窗口 |
| 插件与系统管理 | 查询、安装、启停、删除、重启分离 | 涉及可执行代码加载与进程重启，须同时校验目标插件与反向消费者权限 |
| 密钥与管理员凭据 | 仅限人类管理员会话 | 严禁程序密钥创建/修改密钥或提升自身权限 |

## 多语言文档与 OpenAPI 扩展字段

Spring MVC 提供基础方法、路径与参数定义；编译期保留的 Javadoc、`@Operation`、`@Schema` 与请求体注解用于补充示例、手动解析的 JSON 结构及返回状态。

### 多语言接口说明与折叠「详细信息」

在 Controller 上标注 `@HttpApiGroup("core-batch")`，即可绑定所属模块下的 `src/main/resources/i18n/http/core-batch.properties` 及各语言资源文件（如 `core-batch_en.properties`）。键名前缀为 `<Controller类名>.<Java方法名>.<大写HTTP方法>`（未限定单一 HTTP 方法时用 `ANY`）：

| 资源键 | 作用 |
| --- | --- |
| `guide` / `<前缀>.guide` | 整组共用或单个操作专属的使用指南路径（生成到操作的 `externalDocs`，关联操作引用的指南去重汇总到 `x-related-guides`） |
| `<前缀>.summary` / `<前缀>.description` | 操作标题与概要说明 |
| `<前缀>.details` | 可选的 Markdown 详细说明（追加到 `description`，在 `/openapi` 页面默认折叠为「详细信息」，点击或按键盘展开） |
| `schema.DownloadSubmission.<workType>.description` | 为 `POST /api/download/tasks` 补充各已启用下载类型的专属说明（操作与提供方均无正文时自动省略折叠区） |

撰写说明前须通读从 HTTP 入口到鉴权、校验、提供方、异步执行、落盘、结果查询与失败恢复的完整调用链，区分提交时立即可知的结果与异步终态。完成后逐语言核对 `/api/openapi.json` 及 `/openapi` 页面的折叠展开与键盘可访问性。

### OpenAPI 扩展元数据

| 扩展字段 | 语义与声明方式 |
| --- | --- |
| `x-program-permission` / `x-program-permissions` | 单一路由权限写入 `x-program-permission`；含附加校验时在 `x-program-permissions` 中同时列出 `route`、`discovery`（获取来源令牌的接口）、`submit`（固定权限、请求头触发权限或目录查询条件）和 `execution`（如 `executePermissionsByWorkType`）。顶层 `x-program-permission-conditions` 定义 `request#`、`task#`、`batch#`、`item#` 的解析规则与 `when.keyFrom` / `when.value` 条件（如导出仅在 `request#/deleteAfter == true` 时附加删除权限）及 `reportedIn.pointer` 缺权字段位置 |
| `x-schedule-sources` / `x-manual-batch-source` | `x-schedule-sources` 列出各来源的 `schedulePermission` 与 `batchSubmitPermission`；代为提交固定来源手动批次的插件接口用 `x-manual-batch-source` 声明来源类型 |
| `x-csrf-origin` | 标识写请求的来源校验策略：`origin-or-referer`（原生客户端亦须发送同源 `Origin` 或 `Referer`）、`origin-required` 或 `conditional`（按是否携带 Cookie 等浏览器信号区分） |
| `x-program-alternative` | 在不支持程序密钥的浏览器接口上指向对应的程序接口（格式为 `<METHOD> <path>`，如 `POST /api/download/tasks`） |
| `links`（`@ApiResponse`） | 有明确后续调用且可从响应提取参数时声明响应链接，供 `/openapi` 生成「相关接口」跳转 |
| `x-sse-data-event` / `x-sse-events` | 发布具名 JSON SSE 事件时，在 `text/event-stream` 的 content 上声明事件 DTO schema 与 `x-sse-data-event` 事件名；生成文档保留字符串传输格式并在 `x-sse-events` 中按事件名导出 DTO schema |

## 参数校验、错误契约与测试验收

- **信任边界与错误结构**：路径、凭据和 JSON 在入口边界严格校验；程序密钥仅通过 `Authorization: Bearer` 传递，禁止写入 URL、日志或浏览器持久存储；来源 IP 仅信任经 `server.trusted-proxy-cidrs` 校验后的客户端地址。错误响应统一保留稳定机器码 `code` 与本地化 `error`（下载提交缺权可通过 `DownloadTaskException.programPermission()` 暴露缺失权限 ID）。
- **结构化参数错误（`HttpParameterException`）**：插件在 HTTP 入口手动校验参数时，可抛出 `pixivdownload-core-api` 中的 `top.sywyar.pixivdownload.core.web.HttpParameterException(in, field, reason)`（例如查询参数枚举非法时抛出 `new HttpParameterException("query", "order", "invalid_value")`），并在方法上标注 `@KnownErrorCodes(status = "400", value = "error.request.param.invalid")` 以导出到 OpenAPI：
  - 宿主统一返回 HTTP 400、`code = "error.request.param.invalid"`、本地化 `error` 以及 `in`、`field`、`reason` 三个定位字段（严禁在字段中回显调用方提交的原始值或凭据；Spring 自身的缺参或类型转换错误由宿主自动定位）。
  - `in` 取 `query`、`path`、`header`、`part` 或 `body`；前四者的 `field` 填声明的参数名或表单分块名，`body` 填 JSON Pointer（如 `/options/pages`）；`reason` 使用稳定机器码（如 `required`、`type`、`invalid_value`、`out_of_range`）。
- **真实验收**：测试必须经过真实安全过滤器或 HTTP 入口，验证缺权请求无任何业务副作用，并覆盖 HTTP 方法差异、目标插件裁剪、来源网段限制、密钥撤销、插件换代及保存失败回滚；接口或权限变更时同步更新权限目录、调用方、五语言文档与回归测试。
