# 外部下载自动导入

官方 `external-download-import` 插件配合油猴脚本，将 PixivBatchDownloader 此后下载完成的插画、漫画、动图和小说自动加入本机画廊。首次使用配置一次下载目录，之后无需手动导入。源文件不复制、不移动；删除画廊记录也保留原文件。

## 使用

1. 在浏览器中安装并启用 PixivBatchDownloader 和 Tampermonkey，允许 Tampermonkey 在 Pixiv 网站运行。Chrome 138+ 还需在「扩展程序 → Tampermonkey → 详情」中开启“允许用户脚本”；其它版本按 [Tampermonkey 的权限说明](https://www.tampermonkey.net/faq.php?ext=dhdg&q=Q209)操作。
2. 在本程序桌面设置的“外部下载自动导入”中选择 PixivBatchDownloader 使用的浏览器下载根目录，保存并重启后端。文件可保存在该目录的子目录中。也可在 `config/plugins/external-download-import.properties` 设置 `external-download-import.source-root`，使用绝对路径。
3. 打开本程序下载页的「更多 → 油猴脚本」，单独安装 **PixivBatchDownloader 自动导入画廊**，在 Tampermonkey 安装页确认安装并保持启用。
4. 刷新已打开的 Pixiv 页面。脚本默认连接 `http://localhost:6999`；若端口不同，在 Pixiv 页面的油猴菜单“设置本机服务地址”中修改一次。
5. 保持本机后端运行，正常使用 PixivBatchDownloader 抓取和下载。整部作品的所有文件下载完成后会自动加入对应画廊，无需复制或粘贴 JSON。

浏览器与本程序必须在同一台机器上，本程序使用单人模式。脚本只连接本机服务，并使用油猴存储保存待处理信息；不需要开启“允许访问文件网址”。安装前的历史下载不会自动扫描。

下载页的“已安装”标记只记录安装按钮是否被点击，实际安装和启用状态以 Tampermonkey 为准。下载完成却未出现在画廊时，先在 Pixiv 页面的油猴菜单查看“自动导入状态”：`SOURCE_ROOT_REQUIRED` 表示需要配置下载目录并重启后端；`SERVER_UNAVAILABLE` 表示需检查后端是否运行、服务地址是否正确。

服务暂时不可用时，待登记信息保存在油猴存储中；Pixiv 页面打开后自动重试，刷新页面不会丢失已保存的待登记记录。“自动导入状态”菜单显示待处理、未完成和需要检查的数量。未知元数据、缺页、跳过下载或冲突路径不会被记为完整作品；需要重新抓取取得完整信息。脚本不扫描旧下载目录，也不修改 PixivBatchDownloader 的状态。

## 文件与数据

- 浏览器实际保存路径必须位于配置的根目录内；允许任意文件名和子目录。宿主拒绝链接、junction、越界路径、缺失文件和不受支持的文件头。
- 图片支持 JPEG、PNG、WebP、GIF；动图支持 WebP、GIF、APNG、WebM、MP4、ZIP；小说支持 TXT、EPUB，正文来自同次抓取的元数据。ZIP 缩略图只读首个图片条目，源文件仍是 ZIP。
- 小说封面、附件和原始发布时间尚未导入；当前小说页会将登记时间显示为上传时间。
- 数据库保存逐页真实路径和作品元数据，原文件保持不变。媒体维护跳过外部只读作品。移动原文件后，需要修复文件位置；应用不会猜测新路径。
- 已有记录（包括软删除）不覆盖、不重复登记。小说能力不可用时保留待处理信息，不返回成功。
- 单页文件最多 1 GiB，单作品最多 8 GiB、1000 页。浏览器当前元数据最多 1000 部作品、8 MiB；待处理队列最多 1000 部作品、5000 个文件、8 MiB。队列满时提示并停止接纳新记录。
- HTTP 请求最多 12 MiB，JSON 深度 20，小说正文最多 300 万字符。客户端逐项发送，失败间隔逐步增加到 60 秒；令牌单次使用、有效一分钟。连接地址仅限 loopback，不发送 Pixiv Cookie 或管理员密码。

事件对应 [PixivBatchDownloader 的固定源码](https://github.com/xuejianxianzun/PixivBatchDownloader/tree/e3bbdfdd2fb452cc9073450b8aa1ad065f982b64)。这些是上游内部事件；元数据必须先于同批下载成功事件到达。真实扩展事件兼容性取决于浏览器和油猴管理器。

## SDK

`WorkFileImporter.importFiles(WorkFileImportRequest)` 核验完整本地作品并原子登记文件引用。调用方负责授权允许目录；浏览器不能改变该目录。宿主提供插画处理，小说 owner 通过 `WorkFileImportHandler` 贡献登记能力；缺席时导入失败且不产生记录。处理器按精确 publication 代理调用，撤回拒绝新调用并等待在途调用。

`WorkAssetService.isReadOnly` 表明资产来自外部原文件。读取与删除使用同一登记事实；删除仅移除画廊记录，媒体维护不得改写这些文件。

`WebRouteContribution.trustedWriteOrigins` 可为精确 ADMIN / LOCAL POST 路由声明 HTTPS 来源。它只允许无环境 cookie 的脚本通过 CSRF 来源检查，不取消路由鉴权。该插件还检查单人模式、loopback 地址和短期单次令牌。

`UserscriptContribution(id, classpathResource, i18nNamespace)` 支持插件自持脚本翻译。下载观察与准入规则使用 `DownloadObserver` / `DownloadAdmissionPolicy`；宿主 `DownloadLifecycle` 已连接插画执行和本地导入。观察是同步尽力通知，不保证重放、崩溃终态或全局顺序；准入规则在副作用前执行，错误按拒绝处理。它们不替换私有方法，也不提供 WebView。
