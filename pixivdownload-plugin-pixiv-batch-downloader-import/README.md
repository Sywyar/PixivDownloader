# PixivBatchDownloader 导入支持

官方 `pixiv-batch-downloader-import` 插件配合专用油猴脚本，将 PixivBatchDownloader 下载完成的插画、漫画、动图和小说以**只读引用**方式登记到本机画廊（源文件不复制、不移动，删除画廊记录也保留原文件），并提供历史记录批量导入。完整配置、限制与历史导入说明见[在线文档](https://sywyar.github.io/PixivDownloader/#/zh-cn/pixiv-batch-downloader-import)。

## 1. 快速启用自动导入

浏览器与本程序须运行在同一台机器上，且本程序使用单人模式（`solo`）：

1. 在插件市场安装并启用「PixivBatchDownloader 导入支持」（离线全量包已包含，标准包按需安装），完整退出并重启程序。
2. 在浏览器启用 PixivBatchDownloader 与 Tampermonkey，从本程序下载页「油猴脚本」入口（或 `/api/scripts/pixiv-batch-downloader-import.user.js`）安装并启用导入脚本。
3. 在桌面设置中选择浏览器下载根目录，或在首次下载完成后通过桌面弹窗点击「保存目录」确认候选根目录，随后刷新 Pixiv 页面。
4. 正常使用 PixivBatchDownloader 下载作品，完成后在画廊核对作品记录。已有历史下载可通过 Pixiv 页面油猴菜单「读取浏览器下载历史」或桌面「工具 → PixivBatchDownloader 历史导入」补录。

## 2. 排障与统计

- **下载完成却未显示**：在 Pixiv 页面油猴菜单查看「自动导入状态」：`SOURCE_ROOT_REQUIRED` 表示需确认或配置下载根目录；`SERVER_UNAVAILABLE` 表示需检查本机服务与端口。缺页、未知元数据或跳过下载的作品不会作为完整作品入库。
- **导入统计**：桌面首页「已导入的作品」按 `(作品类型, 作品 ID)` 去重统计本插件成功导入的作品数（保存在 `data/pixiv-batch-downloader-import/plugin.db`），重启或删除画廊记录不扣减计数。

## 3. 所用公开 SDK 契约

- **`DesktopDirectorySuggestionSource`**：为插件自身的非敏感 `PATH_DIR` 字段提供 `HOT_RELOAD` 目录候选，经桌面用户确认后保存。
- **`WorkFileImporter` 与 `WorkFileImportHandler`**：校验本地完整文件并原子登记只读路径引用（`WorkAssetService.isReadOnly` 标记外部源文件，删除画廊记录与媒体维护均保留原文件）。
- **`WebRouteContribution.trustedWriteOrigins`**：为受控 `LOCAL` POST 路由声明可信 HTTPS Origin，配合单人模式、回环地址、单次令牌（及浏览器历史导入会话 ID）完成无环境 Cookie 的安全写入。
- **`UserscriptContribution`、`DownloadObserver` 与 `DownloadAdmissionPolicy`**：提供插件自带脚本的本地化词典，以及通过宿主 `DownloadLifecycle` 连接的同步尽力观察与前置准入校验。
