# PixivBatchDownloader 导入支持

官方 `pixiv-batch-downloader-import` 插件配合专用油猴脚本，可将 PixivBatchDownloader 下载完成的插画、漫画、动图和小说以**只读引用**方式登记到本机画廊。源文件不复制、不移动，删除画廊记录也保留原文件。还支持将已有的 PixivBatchDownloader 历史记录批量导入画廊。

浏览器与本程序必须运行在同一台机器上，且本程序使用单人模式（`solo`）。脚本仅连接本机回环服务，无需开启浏览器「允许访问文件网址」权限。

## 启用自动导入

普通发行包不预装此插件，离线全量包已包含。

1. 在插件市场安装并启用「PixivBatchDownloader 导入支持」，完全退出并重启程序。
2. 在浏览器启用 PixivBatchDownloader 与 Tampermonkey（Chrome 138+ 需在「扩展程序 → Tampermonkey → 详情」开启「允许用户脚本」，其他版本参见 [Tampermonkey 权限说明](https://www.tampermonkey.net/faq.php?ext=dhdg&q=Q209)）。
3. 配置外部下载根目录：可在桌面「设置 → PixivBatchDownloader 导入支持」中选择，或在 `config/plugins/pixiv-batch-downloader-import.properties` 中填写绝对路径 `pixiv-batch-downloader-import.source-root=D:/Downloads`（Windows 使用正斜杠，保存后立即生效）；也可等首次下载完成后在弹窗中确认候选目录。
4. 在浏览器登录本机服务后，访问 `http://localhost:6999/api/scripts/pixiv-batch-downloader-import.user.js`（或从下载页「油猴脚本」入口）安装并启用导入脚本，随后刷新 Pixiv 页面。
5. 保持本机后端运行，正常使用 PixivBatchDownloader 下载；整部作品全部文件落盘后会自动登记到对应画廊。

未预先配置目录时，首个完整作品的绝对路径会触发桌面弹窗确认候选目录。候选目录取自该作品所有文件的共同父目录（可能只是单部作品子目录），请选择去掉 PixivBatchDownloader 相对路径后的顶层下载根（例如文件在 `D:/Downloads/pixiv/作者/123_p0.png`、相对路径为 `pixiv/作者`，应选 `D:/Downloads`），点击「保存目录」后生效并自动重试。取消不会写入配置，同一候选不会重复弹窗。

## 自动导入状态与统计

下载完成却未出现在画廊时，在 Pixiv 页面的 Tampermonkey 菜单打开「自动导入状态」：

- `SOURCE_ROOT_REQUIRED`：需在桌面弹窗确认候选目录，或在设置中指定下载根目录。
- `SERVER_UNAVAILABLE`：检查本机后端是否运行、单人模式是否已登录，以及脚本菜单「设置本机服务地址」中的端口是否正确。

后端暂时不可用时，待登记记录保存在 Tampermonkey 存储中（最多 1,000 部作品、5,000 个文件、8 MiB），打开 Pixiv 页面时自动退避重试；缺页、未知元数据、跳过下载或路径冲突的作品不会作为完整作品入库。上游事件基于 [PixivBatchDownloader 固定版本源码](https://github.com/xuejianxianzun/PixivBatchDownloader/tree/e3bbdfdd2fb452cc9073450b8aa1ad065f982b64)，同批元数据须先于下载成功事件到达。

桌面首页「已导入的作品」按 `(作品类型, 作品 ID)` 去重统计本插件成功导入的作品数，保存在 `data/pixiv-batch-downloader-import/plugin.db`（备份时请一并保留）；删除画廊记录不扣减计数。

## 文件格式、只读引用与上限

- 实际文件必须位于已配置根目录内，拒绝符号链接、junction、越界路径、缺失文件与非法文件头。
- 图片支持 JPEG、PNG、WebP、GIF；动图支持 WebP、GIF、APNG、WebM、MP4、ZIP（ZIP 缩略图取首个图片条目，原文件保持 ZIP）；小说支持 TXT、EPUB（实时自动导入的正文取自同次抓取元数据，暂不导入小说封面、附件和原始发布时间，上传时间显示为登记时间）。
- 已有记录（含软删除）不覆盖、不重复登记；小说插件不可用时保留待处理记录，不返回成功。媒体维护工具会跳过外部只读作品；移动原文件后需修复路径，程序不会猜测新位置。
- 单页文件上限 1 GiB，单部作品上限 8 GiB / 1,000 页；单次 HTTP 请求上限 12 MiB、JSON 深度 20、小说正文 300 万字符。

## 导入历史记录

安装插件前的历史下载有两种补录方式，均沿用上述文件格式与只读引用规则，已有作品（含软删除）自动跳过；导入后可用数据回填工具补齐受支持字段，但不能恢复缺失的源文件。

历史导入仅处理本地记录与文件。页数以本地连续页记录为准，无法确认是否与 Pixiv 当前页数一致。成功登记后，正在运行的桌面 GUI 会请求焦点，询问是否启动数据回填；选择「稍后」或关闭提示不会启动回填，也可从「工具 → 数据回填」再次运行。回填检查画廊中所有缺少资料的作品，通过后端补齐标题、作者、标签等字段，并将裁剪后的元数据保存到 `metadata_json`，已有值保持不变。获取失败不会撤销导入。小说原文件仍可访问，但历史导入与元数据回填不恢复在线阅读、翻译所需的正文。

### 方式一：从油猴脚本直接读取浏览器历史（无需导出文件）

1. 保持本机后端运行，在插件设置中保存「外部下载目录」，刷新已登录的 Pixiv 页面。
2. 在使用 PixivBatchDownloader 的同一浏览器配置中打开原 Pixiv 站点，暂停外部下载，点击 Tampermonkey 菜单「读取浏览器下载历史 → 读取并检查」（脚本只读分批扫描当前站点历史库，单次上限 1 GiB，期间勿修改或清空历史）。
3. 核对待导入、已存在及缺失 / 冲突计数（最多展示 100 条明细；待导入为 `0` 时检查即结束），点击「开始导入」。脚本将历史记录交给本机服务，校验原文件并登记；此过程不向 Pixiv 请求元数据，也不读取或发送 Pixiv Cookie。

导入期间请保持页面打开；100% 表示处理完毕而非全部成功。取消、离页或换服会停止后续处理，已登记作品保留。临时数据位于 `data/pixiv-batch-downloader-import/browser-history/`，正常结束或约 15 分钟空闲后自动清理。

### 方式二：在桌面工具导入导出的 JSON

1. 在桌面 GUI「工具」中打开「PixivBatchDownloader 导入支持」。Compose 将其放在「快捷工具」中，以弹窗打开。
2. 在 PixivBatchDownloader 中导出历史 JSON；若有多份分片，放入同一独立目录并选择该目录（单文件上限 512 MiB，最多 64 个文件、合计 1 GiB）。
3. 选择源文件根目录（与记录中的相对路径 `n` 拼接后需对应真实文件；此次选择不改写自动导入配置），点击「检查并预览」。
4. 点击「开始导入」并核对成功、跳过与失败计数，无需填写 Pixiv Cookie。临时整理目录位于 `data/pixiv-batch-downloader-import/history-import/`，任务结束或程序正常退出时清理。

使用「PixivBatchDownloader 导入支持」脚本导入历史下载数据更便捷，可按上面的方式一直接读取浏览器历史。桌面导入也可以独立使用；关闭导入弹窗后任务继续运行，需要停止时请点击取消。
