# 油猴脚本

通过 Tampermonkey 脚本可在 Pixiv 网页直接勾选、抓取作品或将任务发往本地后端。油猴脚本为可选项，不安装脚本也可在 `pixiv-batch.html` 完成批量下载。

脚本目录与安装 API 由应用宿主提供，不依赖 `download-workbench`。宿主仅列出当前已启用插件声明的脚本；停用下载工作台会移除其下载辅助脚本，其他插件（如外部导入插件）声明的脚本仍可安装。单人模式连接后端需先登录本程序；`@updateURL` 指向后端时不保证 Tampermonkey 后台更新请求携带登录会话。

## 前提与脚本列表

浏览器需安装 [Tampermonkey](https://www.tampermonkey.net/)。使用 Java 后端版脚本或工具箱时，后端必须运行且已在单人模式下登录；纯浏览器 `Local Download` 无需后端，但不会写入元数据或登记画廊。

| 脚本名称 | 生效位置与用途 |
| --- | --- |
| All-in-One 整合包 | 合并内置的下载辅助脚本与工具箱（不含外部导入等其他插件脚本） |
| 页面批量下载器 (Page Scrape) | 在搜索、关注动态、排行榜等列表页勾选缩略图批量入队 |
| User 批量下载器 (User Batch) | 在画师主页（`/users/<id>` 及子页）按插画 / 小说一键入队全部作品 |
| URL 批量导入 (URL Batch) | 在任意 Pixiv 页面打开浮动面板粘贴 URL 批量下载 |
| 单作品下载器 (Java 后端版) | 在单作品页（`/artworks/<id>`）经后端下载，保留元数据与自动收藏 |
| 单作品下载器 (Local Download) | 在单作品页由浏览器直接保存文件，无需 Java 后端，不入画廊数据库 |
| 体验增强工具箱 (Toolbox) | 在 Pixiv 页面右侧提供已下载作品彩色边框标记、一键导入 Cookie 等功能 |

[导入外部下载](/zh-cn/pixiv-batch-downloader-import)脚本由 `pixiv-batch-downloader-import` 插件单独提供，不包含在 All-in-One 中；安装插件并重启后单独安装该脚本。

## 安装步骤

### 方式一：从后端下载页安装（推荐）

1. 启动 PixivDownloader，打开当前实例的下载页（默认 `http://localhost:6999/pixiv-batch.html`，端口与子路径以实际访问地址为准）。
2. 展开「油猴脚本」入口，点击目标脚本的「安装」按钮并在 Tampermonkey 中确认。
3. 刷新下载页与已打开的 Pixiv 页面，确认脚本状态与页面浮动面板。

从后端安装时，宿主会自动将脚本的更新链接、下载页检测范围及首次默认连接地址绑定到当前实例地址（含实际端口与 context path；若浏览器已手动保存过连接地址，则优先保留用户设置），并将远端脚本的 `@connect YOUR_SERVER_HOST` 替换为当前主机（外部导入脚本仍仅限本机回环连接）。若安装了 **All-in-One 整合包**，请禁用其中已包含的独立下载脚本，避免页面出现双面板（两者本地存储互相独立）。

### 方式二：从源码仓库安装独立脚本

在[源码仓库](https://github.com/Sywyar/PixivDownloader)打开已提交的 `.user.js` Raw 内容由 Tampermonkey 安装，随后核对脚本设置中的后端地址与 `@connect` 许可。All-in-One 整合包在构建时生成，且发布流程不上传独立 userscript Release 附件，请直接从运行中的后端安装。

## 历史跳过与非本机部署

各后端下载脚本的设置面板均支持「跳过历史下载」，开启后可进一步勾选：

- **实际目录检测**：同时检查磁盘文件是否存在，文件缺失则重新下载。
- **允许已删除的作品被重新下载**：画廊删除的作品保留[删除标记](/zh-cn/gallery#删除作品)，默认按已下载跳过；勾选后重新下载并在成功后清除删除标记。

后端部署在其他机器或非默认端口时（默认 `http://localhost:6999`）：

1. 直接从目标后端的 Web 下载页安装脚本（首次连接会自动采用该后端的协议、主机与端口，并写入对应的 `@connect` 主机许可）。
2. 若此前已保存过旧地址，在 Pixiv 页面打开脚本设置将其改为目标后端地址（仅修改脚本头部的 `@connect` 不会改变实际请求目标）。
3. 刷新 Pixiv 页面并提交一件作品，确认目标后端队列收到任务。

## 安装地址与运行检测

下载页的脚本状态检测与脚本在 Pixiv 页面上的后端连接地址彼此独立：例如从 `http://localhost:7000` 安装脚本后，更新链接与下载页检测均固定绑定 `7000` 端口，不会扫描其他端口，在 Pixiv 页面修改脚本连接地址也不会扩大检测范围。

| 脚本元数据 | 作用 |
| --- | --- |
| `@updateURL`、`@downloadURL` | 指向完整安装地址（含协议、主机、端口与应用子路径） |
| `@include` 与注入的 `origin` / `pages` | 限定脚本响应页内检测的后端页面（`@include` 为正则表达式，源码中的斜杠会自动转义） |
| `@connect` | 声明允许跨域请求的主机名（不含端口，也不决定实际请求目标） |

下载页通过页内 `window.postMessage` 检测脚本实际运行版本（不向后端发起检测请求），并在卡片右上角合并展示检测版本与可安装版本（版本号相同时只显示一次）：可更新版本以橙色置顶，已检测到为绿色，由 All-in-One 覆盖为蓝色，较旧版本或未检测到为中性色，同时附带状态文字。版本比较仅针对同一脚本的 1–3 段数字版本及 `alpha.N`、`beta.N`、`rc.N` 预发布后缀；自定义版本格式、多个检测版本排序冲突时仅展示可安装版本，All-in-One 版本不与独立脚本版本交叉比较。

### 开发者：声明检测页面与默认地址

插件通过 `UserscriptContribution` 贡献脚本时，需同时在脚本头部结束前与最外层 IIFE 的 `'use strict'` 之后声明检测页面与引导标记（两者缺一不可，仅写引导标记不会补写 `@include`；脚本在后端页面上应仅响应检测，不启动 Pixiv 页面业务）：

```javascript
// 1. 放在 userscript 元数据头部结束标记之前：
// @pixiv-presence-page /pixiv-batch.html
// @pixiv-presence-page /pixiv-batch-alt.html

// 2. 放在最外层 IIFE 的 'use strict' 之后、业务初始化之前：
// @pixiv-presence-bootstrap
```

- **服务端重写**：`ScriptController.buildInstallUrl` 从当前 HTTP 请求解析协议、主机、端口与 context path 重写更新链接，`UserscriptPresenceInstaller` 基于同一地址注入检测范围。
- **客户端默认地址解析**：根目录后端脚本通过 `scripts/shared/installed-server.js` 读取 `GM_info`（兼容 `GM.info`）中的 `script.downloadURL`、`script.updateURL` 或 `scriptUpdateURL` 作为默认后端地址（无有效安装地址时回退 `http://localhost:6999`，并通过 `GM_getValue` 优先保留用户已保存设置）。
- **构建同步**：修改共享片段后运行 `scripts/sync-shared-snippets.ps1` 同步并重新生成 All-in-One（外部导入脚本使用自有 `installedServer()` 且仅连接无子路径的本机地址；`Local Download` 不连接后端）。

## 常见问题排查

- **Pixiv 页面没有出现脚本面板**：确认 Tampermonkey 扩展与对应脚本均已启用、当前 URL 匹配脚本 `@match` 规则（如 User Batch 仅在 `/users/` 页面生效），并刷新页面。Chrome 138+ 还需在扩展详情中开启「允许用户脚本」。
- **下载页显示「未检测到」**：表示当前下载页未收到脚本的页内消息响应，不代表未安装。请核对脚本是否启用、站点权限及安装来源端口是否与当前下载页一致；需要更新时从当前后端重新安装并刷新下载页（若安装响应仍无 `@include`，需先更新提供该脚本的插件）。
- **提示「后端未运行」或「连接失败」**：核对后端进程状态、单人模式登录态及脚本中的后端地址。一键导入 Cookie 失败时，按[第一次下载](/zh-cn/first-download#方式-c：油猴脚本「一键导入-cookie」)检查 Tampermonkey 的 HttpOnly Cookie 权限。
