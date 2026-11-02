# PixivDownloader 使用文档

PixivDownloader 将 Pixiv 插画、漫画、动图和小说下载到本地电脑或服务器，并通过本地画廊管理作品。支持桌面界面、浏览器下载页和 Tampermonkey 脚本操作；下载受限内容需配置你自己的 Pixiv Cookie。

## 首次使用

1. [安装与启动](/zh-cn/installation)：选择 Windows 安装包、Java 分发包或 Docker 部署并完整解压。
2. [首次配置](/zh-cn/first-setup)：创建本程序的管理员账号并配置后端代理。
3. [第一次下载](/zh-cn/first-download)：保存 Cookie、下载单件作品，并在本地目录与画廊核对结果。

默认桌面界面为 Compose，发行包同时包含 Swing；支持简体中文、繁体中文、英文、日文和韩文。默认数据库为 SQLite；使用 PostgreSQL 时需先安装对应数据库插件，并在设置页检查与迁移数据。

## 按任务查找

| 任务 | 文档 |
| --- | --- |
| 下载自己的收藏、作品、关注新作或珍藏集 | [快捷获取](/zh-cn/quick-access) |
| 粘贴一组作品链接或 ID 下载 | [URL 批量下载](/zh-cn/batch-download) |
| 下载指定画师的作品 | [画师批量下载](/zh-cn/user-download) |
| 按关键词搜索 Pixiv 并下载 | [搜索下载](/zh-cn/search) |
| 保存小说、合订 EPUB、阅读、翻译或朗读 | [小说与朗读](/zh-cn/novel) |
| 定期自动发现并下载新作 | [计划任务](/zh-cn/scheduled-tasks) |
| 在 Pixiv 网页直接调用下载辅助 | [油猴脚本](/zh-cn/userscripts) |
| 将 PixivBatchDownloader 的下载登记到画廊 | [导入外部下载](/zh-cn/pixiv-batch-downloader-import) |
| 浏览、筛选、导出与删除已登记作品 | [作品画廊](/zh-cn/gallery) |
| 修改配置、代理、数据库与媒体格式 | [配置参考](/zh-cn/configuration) |
| 备份、恢复或搬迁作品与数据目录 | [存储与备份](/zh-cn/storage) |
| 查询启动参数、文件名模板、维护工具与访客邀请 | [CLI 与进阶参考](/zh-cn/usage-guide) |
| 核对外部请求目标、发送数据与关闭方式 | [网络访问与第三方服务](/zh-cn/network-access) |
| 排查启动、连接、Cookie 或下载故障 | [常见问题](/zh-cn/faq) |
| 安装、更新、启停或修复插件 | [插件管理](/zh-cn/plugin-management) |
| 编辑页面布局、样式与自定义组件 | [页面编辑器](/zh-cn/page-editor) |
| 为登录等表单配置在线或离线验证码 | [操作验证码](/zh-cn/captcha) |

## 开发与参与

- [第三方插件 SDK](/zh-cn/plugin-development)：使用模板创建工程、接入扩展契约、签名与投稿。
- [主仓库开发](/zh-cn/development)：准备环境、构建、测试与提交 PR。
- [SDK 下载](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) · [版本化 API 参考](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)

## 版本与求助

从 [GitHub Releases](https://github.com/Sywyar/PixivDownloader/releases) 下载发行包，在 [CHANGELOG](https://github.com/Sywyar/PixivDownloader/blob/master/CHANGELOG.md) 查看变更记录。排障后仍未解决时，在 [Issues](https://github.com/Sywyar/PixivDownloader/issues) 提交版本、复现步骤、实际结果与脱敏日志（勿包含 Cookie、密码或令牌）。

默认 Web 地址为 `http://localhost:6999/`。可用页面取决于已启用插件；出现恢复页时，先检查必需插件与所选数据库状态。

本项目由 [Sywyar](https://github.com/Sywyar) 维护，采用 [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) 及随附的[插件链接例外](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt)。下载作品的著作权归原作者所有，项目与 Pixiv 无隶属关系；请遵守服务条款并合理控制请求频率。
