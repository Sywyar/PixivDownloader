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

1. 启动 PixivDownloader，打开当前实例的下载页，例如 `http://localhost:6999/pixiv-batch.html`；端口以实际地址为准。
2. 展开「油猴脚本」入口，点击目标脚本的「安装」按钮并在 Tampermonkey 中确认。
3. 刷新下载页与已打开的 Pixiv 页面，确认脚本状态与页面浮动面板。

通过后端安装时，脚本的更新链接和检测范围绑定当前地址，包含实际端口。后端下载脚本首次连接也采用该安装地址；已有手动保存的地址优先。需要远端访问的脚本会替换 `@connect YOUR_SERVER_HOST`，外部导入脚本仍限本机连接。若安装了 **All-in-One 整合包**，请禁用其中已包含的独立下载脚本，避免页面出现双面板（两者本地存储互相独立）。

### 方式二：从源码仓库安装独立脚本

在[源码仓库](https://github.com/Sywyar/PixivDownloader)打开已提交的 `.user.js` Raw 内容由 Tampermonkey 安装，随后核对脚本设置中的后端地址与 `@connect` 许可。All-in-One 整合包在构建时生成，且发布流程不上传独立 userscript Release 附件，请直接从运行中的后端安装。

## 历史跳过与非本机部署

各后端下载脚本的设置面板均支持「跳过历史下载」，开启后可进一步勾选：

- **实际目录检测**：同时检查磁盘文件是否存在，文件缺失则重新下载。
- **允许已删除的作品被重新下载**：画廊删除的作品保留[删除标记](/zh-cn/gallery#删除作品)，默认按已下载跳过；勾选后重新下载并在成功后清除删除标记。

后端部署在其他机器或非默认端口时（默认 `http://localhost:6999`）：

1. 从目标后端安装后，首次连接会采用实际协议、主机和端口。若以前保存过其他地址，在脚本设置中更改。
2. 通过该后端的 Web 页面重新安装脚本以更新 `@connect` 主机许可（手动安装的脚本需在脚本头部核对 `@connect`；仅修改 `@connect` 不会改变请求发送目标）。
3. 刷新 Pixiv 页面并提交一件作品，确认目标后端队列收到任务。

## 安装地址与运行检测

例如从 `http://localhost:7000` 安装，更新链接与下载页检测均使用 7000；不会扫描其他端口。脚本在 Pixiv 页面上的连接设置与下载页检测分别生效，修改连接设置不会扩大检测范围。

| 位置 | 用途 |
| --- | --- |
| `@updateURL`、`@downloadURL` | 完整安装地址，包括协议、主机、端口和应用子路径 |
| `@include` 与注入的 `origin` / `pages` | 限定脚本能响应检测的后端页面；`@include` 是正则，源代码中的斜杠会转义 |
| `@connect` | 允许请求的主机；此处不填写端口，也不决定请求目标 |

检测通过同一页面内的 `window.postMessage` 完成，返回脚本实际运行的版本，不向后端发送检测请求。卡片右上角合并显示检测版本与可安装版本，相同版本只显示一次；可更新版本以橙色置于首行，已检测到为绿色，多合一覆盖为蓝色，较旧版本与未检测到为中性色，同时保留状态文字。

只比较同一脚本的一至三段数字版本，以及 `alpha.N`、`beta.N`、`rc.N` 预发布版本。自定义格式、多个检测版本排序冲突时，仅显示可安装版本。多合一版本不与独立脚本版本比较。

### 开发者：声明检测页面与默认地址

脚本通过插件的 `UserscriptContribution` 声明。在 userscript 头部结束标记之前加入：

```javascript
// @pixiv-presence-page /pixiv-batch.html
// @pixiv-presence-page /pixiv-batch-alt.html
```

在最外层 IIFE 的 `'use strict'` 之后、业务初始化之前加入 `// @pixiv-presence-bootstrap`。两部分缺一不可；只有引导标记时，宿主不会补写 `@include`。在后端页面上应只响应检测，不启动 Pixiv 业务。

宿主 `ScriptController.buildInstallUrl` 从当前 HTTP 请求取得协议、主机、端口和 context path，重写更新链接；`UserscriptPresenceInstaller` 从同一地址生成检测范围。根目录后端脚本的 `scripts/shared/installed-server.js` 读取 `GM_info`（兼容 `GM.info`）中的 `script.downloadURL`、`script.updateURL` 或 `scriptUpdateURL`，从安装 URL 取得默认连接地址。只有没有有效后端安装地址时才回退到源码默认 `http://localhost:6999`；通过 `GM_getValue` 保留已有用户设置，不在初始化时覆盖它。

共享片段通过 `scripts/sync-shared-snippets.ps1` 同步，All-in-One 在构建时生成。外部导入脚本有自己的 `installedServer()`，仍只连接无子路径的本机地址；Local Download 不需要后端默认地址。

## 常见问题排查

- **Pixiv 页面没有出现脚本面板**：确认 Tampermonkey 扩展与对应脚本均已启用、当前 URL 匹配脚本 `@match` 规则（如 User Batch 仅在 `/users/` 页面生效），并刷新页面。 Chrome 138+ 还需在扩展详情中开启「允许用户脚本」。
- **下载页显示「未检测到」**：表示当前页面未收到脚本的页内消息响应，不代表未安装。核对脚本是否启用、站点权限及安装来源的端口；需要更新时从当前后端安装并刷新下载页。若安装响应仍没有 `@include`，需更新提供脚本的插件；重复安装相同内容不会补齐缺失的声明。
- **提示「后端未运行」或「连接失败」**：核对后端进程状态、单人模式登录态及脚本中的后端地址。一键导入 Cookie 失败时，按[第一次下载](/zh-cn/first-download#方式-c：油猴脚本「一键导入-cookie」)检查 Tampermonkey 的 HttpOnly Cookie 权限。
