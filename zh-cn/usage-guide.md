# CLI 与进阶参考

本页汇总命令行启动参数、文件名模板变量、媒体与维护工具及访客邀请说明。常规下载与管理操作请直接查阅[快捷获取](/zh-cn/quick-access)、[URL 批量下载](/zh-cn/batch-download)、[画师批量下载](/zh-cn/user-download)、[搜索下载](/zh-cn/search)、[小说与朗读](/zh-cn/novel)、[作品画廊](/zh-cn/gallery)、[计划任务](/zh-cn/scheduled-tasks)和[油猴脚本](/zh-cn/userscripts)。

## 启动与管理参数

在完整解压的发行包目录内执行（将 `APP.jar` 替换为实际主程序 JAR 名称；单行命令在 PowerShell 与 Bash 中通用）：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --help
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

| 参数 | 作用 |
| --- | --- |
| `--no-gui` | 不启动桌面 GUI，适用于无头服务器或 Docker（首次启动前必须先完成 `--setup`，否则以退出码 `78` 退出） |
| `--intro` | 启动时在浏览器打开产品介绍页 |
| `--help`、`-h` | 打印命令行帮助并退出 |
| `--setup` | 首次初始化管理员账号、运行模式与 HTTP 代理 |
| `--change-password` | 验证原密码后修改管理员密码 |
| `--reset-password` | 忘记密码时在本机强制重置管理员密码 |
| `--web-publications` | 离线查询或恢复页面编辑器的已发布页面状态（详见[页面编辑器](/zh-cn/page-editor#表单验证与资源授权)） |

执行 `--setup`、`--change-password`、`--reset-password` 等管理命令前，必须先停止使用同一数据目录的运行实例。密码修改或重置会使现有会话失效，重启后请用新密码验证登录（详见[首次配置](/zh-cn/first-setup)）。Windows 安装版可直接运行 `PixivDownload.exe`；发行包同时包含 Compose（默认）和 Swing 桌面界面，切换 `app.gui-provider` 后需完整重启。

## 文件名模板变量

在下载页「下载设置 → 文件名模板」中可使用以下 11 个变量（文件名中的非法字符会自动过滤，新模板仅对后续新下载生效）：

| 变量 | 含义 |
| --- | --- |
| `{artwork_id}` | 作品 ID |
| `{artwork_title}` | 作品标题 |
| `{author_id}` | 作者 ID |
| `{author_name}` | 作者名称 |
| `{timestamp}` | 下载时的 Unix 时间戳（毫秒） |
| `{page}` | 当前页索引（从 `0` 开始） |
| `{count}` | 作品总页数 |
| `{ai}` | AI 生成作品为 `AI`，否则为空字符串 |
| `{ai+}` | `AI` 或 `Human` |
| `{R18}` | `R18`、`R18G` 或空字符串 |
| `{R18+}` | `SFW`、`R18` 或 `R18G` |

示例：`{author_name}/{artwork_id}_p{page}` 会按作者名称建立子文件夹保存图片。

## 自动收藏与动图（Ugoira）处理

- **自动收藏**：在下载页勾选「自动收藏」后，后端在作品下载完成时使用已保存的 Cookie（需含 `PHPSESSID`）调用 Pixiv API 收藏作品；油猴脚本则使用浏览器当前 Pixiv 登录态收藏，不向后端转发 `document.cookie`。收藏为尽力执行（best-effort），收藏失败不会使已完成的下载变为失败。
- **动图（Ugoira）**：下载 ZIP 帧包并按帧序解压后，调用系统 `PATH` 或自动安装的 `ffmpeg` 转码为所选格式（默认 WebP，可选 GIF、APNG、MP4 或保留原始 ZIP，详见[配置参考](/zh-cn/configuration#图片与-ugoira-保存格式)），同时将首帧保存为 `_p0_thumb.jpg` 缩略图。

## 下载监控、疑似重复检测与桌面工具

- **下载监控（`http://localhost:6999/monitor.html`）**：查看当前活跃下载进度、按作者 / 标签 / AI 状态筛选或模糊搜索历史记录，以及下载趋势图。
- **疑似重复检测（`http://localhost:6999/pixiv-duplicates.html`，仅管理员，需启用 `gallery-tools`）**：基于感知哈希（dHash）查找视觉相似图片（即便文件名或分辨率不同），可调节汉明距离阈值（默认 `10`，越小越严格）、切换「跨作品模式 / 全库模式」，并点击缩略图进入详情页处理。
- **桌面 GUI 工具页**：提供**图片分类工具**、**数据库目录检查**、**数据回填工具**、**旧数据与元数据导入**、**历史媒体处理**和**PixivBatchDownloader 历史导入**。

!> 涉及数据库的桌面维护工具会使用独占维护会话，由 GUI 自动停止并恢复后端。执行前请先备份所选数据库（PostgreSQL 还需备份服务端数据库），切勿同时启动同一数据目录的另一实例。操作结束后请核对工具报告、后端状态与画廊访问，不要把「关闭工具窗口」当作执行成功。

本地作品导入是需要后端运行的在线工具，不使用上述停服维护流程。按路径模板批量登记，或保存本地导入预设后只输入作品 ID，见[使用说明与示例](/zh-cn/local-import)。

## 访客邀请

访客邀请在 `solo` 与 `multi` 模式下均可用。管理员在画廊右上角点击「邀请访客」，设置过期时间、允许的内容分级（SFW / R18 / R18G）及标签 / 作者白名单后，即可复制 `http://host:port/invite?code=xxx` 分享给外部访客只读浏览。所有邀请码可在 `http://localhost:6999/pixiv-invite-manage.html` 集中查看、禁用或删除。

邀请链接只打开预填的兑换页面，不会在 GET 请求中创建会话。访客提交邀请码并完成已发布的验证后，宿主签发与邀请码不同的会话凭据；旧的原始邀请码 Cookie 不能登录，需要重新兑换。暂停、删除或到期的邀请码仍按既有权限规则拒绝访问，退出登录会撤销当次凭据。
