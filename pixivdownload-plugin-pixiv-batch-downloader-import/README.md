# PixivBatchDownloader 导入支持

官方 `pixiv-batch-downloader-import` 插件配合油猴脚本，将 PixivBatchDownloader 此后下载完成的插画、漫画、动图和小说自动加入本机画廊。首次使用配置一次下载目录，之后无需手动导入。源文件不复制、不移动；删除画廊记录也保留原文件。

[安装、配置与故障排查](https://sywyar.github.io/PixivDownloader/#/zh-cn/pixiv-batch-downloader-import)。

## SDK

`WorkFileImporter.importFiles(WorkFileImportRequest)` 核验完整本地作品并原子登记文件引用。调用方负责授权允许目录；浏览器不能改变该目录。宿主提供插画处理，小说 owner 通过 `WorkFileImportHandler` 贡献登记能力；缺席时导入失败且不产生记录。处理器按精确 publication 代理调用，撤回拒绝新调用并等待在途调用。

`WorkAssetService.isReadOnly` 表明资产来自外部原文件。读取与删除使用同一登记事实；删除仅移除画廊记录，媒体维护不得改写这些文件。

`WebRouteContribution.trustedWriteOrigins` 可为精确 ADMIN / LOCAL POST 路由声明 HTTPS 来源。它只允许无环境 cookie 的脚本通过 CSRF 来源检查，不取消路由鉴权。该插件还检查单人模式、loopback 地址和短期单次令牌。

`UserscriptContribution(id, classpathResource, i18nNamespace)` 支持插件自持脚本翻译。下载观察与准入规则使用 `DownloadObserver` / `DownloadAdmissionPolicy`；宿主 `DownloadLifecycle` 已连接插画执行和本地导入。观察是同步尽力通知，不保证重放、崩溃终态或全局顺序；准入规则在副作用前执行，错误按拒绝处理。它们不替换私有方法，也不提供 WebView。
