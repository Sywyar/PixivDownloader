# 编写 HTTP API 与权限声明

新增或修改 Web、Swing、Compose、CLI 的业务功能时，同时检查是否需要配套 HTTP API。调用方式与接口清单见 [HTTP API](/zh-cn/http-api)，插件契约见 [第三方插件 SDK](/zh-cn/plugin-development)。

## 什么时候需要配套接口

查询业务状态、读取与保存设置、应用预设、下载、翻译、导入、导出、迁移、维护和系统管理，都应能通过 HTTP 完成。已有等价接口时复用同一业务服务，不为每种 GUI 复制执行逻辑。

窗口大小、焦点、主题预览、剪贴板、系统文件选择器和打开本机浏览器属于客户端表现，不要求服务器模拟。业务流程包含这些操作时，把业务部分独立出来：远程客户端文件需要上传，服务器路径指向服务器文件系统，不能混用。

长任务提供开始、状态、按执行身份取消和必要的结果查询。202 表示接纳，取消请求不表示已经结束；查询不到任务不表示可以安全重投。接口应说明重启后的状态和幂等范围。

## 声明权限

路由由实际功能 owner 的 `PixivFeaturePlugin.routes()` 声明。原有 `AccessPolicy` 管理人员、访客及本机流程；程序访问使用 `WebRouteContribution.programPermissions` 按 HTTP 方法显式声明。未声明的程序调用会被拒绝，不继承管理员权限。

```java
WebRouteContribution.admin("/api/example/items")
    .withProgramPermissions(Map.of(
        HttpMethod.GET, new ProgramPermission("items.read", "permission.items.read"),
        HttpMethod.POST, new ProgramPermission("items.create", "permission.items.create")
    ));
```

示例中的 owner 由宿主注册身份确定；若 owner 为 `example`，完整权限为 `example:items.read` 和 `example:items.create`。标签 key 放在 owner 展示命名空间的 i18n 资源中。程序权限只用于明确的 `/api/` 路由和方法，不接受 `**`、OPTIONS 或 GUI/LOCAL 专用流程。完整路径段 `*` 可表示资源 ID。

| 业务 | 分开的权限 | 额外检查 |
| --- | --- | --- |
| 设置和预设 | 读取、校验与保存 | 每个字段的实际 owner；敏感值不回传 |
| 媒体维护、文件导入 | 读取、预览、执行、取消、完成确认 | 精确插件 publication、预览 token 和执行 ID |
| 下载、翻译 | 查询、创建或恢复、取消、删除记录 | 数据范围、凭据和任务归属 |
| 数据库、环境 | 查询、检查、预览、迁移、导出、恢复 | 全安装范围、副作用、确认和停写窗口 |
| 插件、系统 | 查询、安装、启停、删除、重启 | 可执行代码与进程影响 |
| 密钥及管理员凭据 | 人员管理员专用 | 程序不得创建密钥或提升自己的权限 |

同一权限下的接口应有一致的业务目的和数据范围。插件分组只批量选择当前明确列出的权限，不保存对未来权限的通配授权。权限目录从当前声明和能力生成；管理员会话可通过 GET `/api/security/program-keys/permissions` 读取实际 ID、owner、标签和端点。

共享接口还须检查目标资源。设置、媒体和文件导入同时要求 core 入口权限及目标插件的相应权限；读取列表按目标裁剪，批量写入在任何副作用前检查全部目标。数据库迁移和环境归档作用于整个安装，不能解释为单个插件的数据操作。插件撤回或换代后不得调用旧 publication，也不能借另一个 owner 的同名路由授权。

## 输入、错误和验收

接口说明列出方法、路径、权限、目标范围、请求字段与预算、异步状态和主要错误。路径、凭据和 JSON 在信任边界校验；程序密钥只走 `Authorization: Bearer`，不写入 URL、日志或浏览器持久存储。来源 IP 使用可信代理处理后的客户端地址，不能自行信任转发头。既有 CSRF、恢复模式和插件租约继续生效。

错误保留稳定 `code` 与本地化 `error`。区分参数错误、拒绝、对象缺失、修订冲突、超限、能力不可用和结果不确定。网络中断后先查询结果，不自动重放写操作。

测试经过真实过滤器或 HTTP 入口，证明权限不足时没有业务副作用，并覆盖方法差异、目标插件、来源网段、撤销、换代和保存失败。修改接口时同步权限目录、调用方、五语言文档和相关回归；源码声明或编译通过不能代替交互与运行验证。

## 权限范围与专用入口

计划任务使用 `core:schedule.*` 中对应动作的精确权限，并要求来源插件的 `schedule.manage`；账号策略动作要求策略 owner 的同名权限。列表按来源授权裁剪。这里的 `*` 只是文档分组，不是可保存的权限。计划属于安装的持久配置，授权包含该来源的完整调度业务；撤销创建密钥不会删除或停用计划。需要取消后续运行时，先停用或删除计划。

插件生命周期、启用状态和信任操作同时要求对应 `core:plugins.*` 动作与目标的 `<plugin>:plugins.manage`；有依赖消费者时，还检查消费者授权。尚未加载但已安装的可管理插件也会出现在目标目录中。安装权限允许安装包及其依赖在服务器执行代码；全局 Hook 发布权限允许修改安全策略，应按这一实际影响授权。

共享凭据分为 `core:credentials.read`、`core:credentials.write` 和 `core:credentials.use`。读取只返回信息，不返回秘密；使用 `X-Acquisition-Credentials` 引用已保存凭据时，除业务权限外还需要 `credentials.use`。直接提供当次取得凭据仍使用 `X-Acquisition-Credential`。

画廊、小说库、收藏夹、站内信、归档、页面编辑、更新与维护权限作用于共享安装，不自动限制为该密钥创建的数据。下载任务与手动批次继续按密钥身份隔离。本机维护、恢复和旧元数据迁移仍要求本机来源；权限不会替代来源、确认、修订或租约检查。

身份建立与密钥管理、GUI token、验证码、浏览器采集票据、匿名配额及页面装配保留专用流程。程序下载与进度使用 `/api/download/tasks`，文件导入使用 `/api/tools/file-import`，作品管理使用 `/api/library/artworks/{id}`，页面业务使用 `/api/page-editor`。站内信测试使用 `/api/admin/notifications/test` 或 `/test-all`。旧浏览器兼容路由不另授予程序权限。
