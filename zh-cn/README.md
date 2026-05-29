# PixivDownloader Wiki

PixivDownloader 是一款**本地 Pixiv 作品批量下载工具**，基于 Spring Boot 3.5.7 / Java 17 构建，支持桌面 GUI（Swing + FlatLaf）、Web 界面和 Tampermonkey 油猴脚本三种交互方式。

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/Sywyar/PixivDownloader)](https://github.com/Sywyar/PixivDownloader/releases)

?> 本文档中「作品」涵盖插画、漫画、动图（Ugoira）和小说。

---

## 核心功能

| 功能 | 说明 |
|----|------|
| ⚡ **快捷获取** | 一键拉取你在 Pixiv 的收藏、关注、珍藏集 |
| 🎨 **批量导入** | 粘贴作品 URL / ID 列表，支持插画小说混合 |
| 👤 **画师下载** | 输入画师 ID 或链接，下载其全部作品 |
| 🔍 **搜索下载** | 内置搜索代理，关键词搜索预览后批量下载 |
| 📚 **系列下载** | 整个漫画/小说系列一键下载，自动跟进更新 |
| ⏰ **计划任务** | 后台定时自动发现并补充新作品，无需手动操作 |
| 📖 **小说下载** | TXT / HTML / EPUB 三种格式，支持系列合订 |
| 🎬 **动图转换** | Ugoira 自动通过 ffmpeg 转为 WebP |
| 🖼️ **作品画廊** | 本地画廊，支持搜索、筛选、收藏夹管理 |
| 🧩 **油猴脚本** | 在 Pixiv 页面直接操作，6 个专用脚本 + All-in-One 整合包 |
| 🌐 **多语言/暗色模式** | 中英双语，所有页面支持暗色模式 |
| 👥 **多人模式** | 多用户共享服务器，配额与限流控制 |
| 🔗 **访客邀请** | 邀请码分享画廊，支持内容分级/标签/作者白名单 |

---

## 新手入门

第一次使用？按以下顺序阅读：

1. **[📥 安装与启动](/zh-cn/installation)** — 下载安装，环境要求
2. **[⚙️ 首次配置](/zh-cn/first-setup)** — 设置管理员账号、运行模式、代理
3. **[⬇️ 第一次下载](/zh-cn/first-download)** — 完整的入门下载教程

---

## 按需查看

| 我想要… | 查看 |
|---------|------|
| 下载我的 Pixiv 收藏 | [快捷获取](/zh-cn/quick-access) |
| 粘贴链接批量下载 | [URL 批量下载](/zh-cn/batch-download) |
| 下载某位画师的全部作品 | [画师批量下载](/zh-cn/user-download) |
| 按关键词搜索下载 | [搜索下载](/zh-cn/search) |
| 下载小说/合订 EPUB | [小说下载](/zh-cn/novel) |
| 整理和浏览已下载作品 | [作品画廊](/zh-cn/gallery) |
| 定时自动下载新作品 | [计划任务](/zh-cn/scheduled-tasks) |
| 在 Pixiv 网页上直接下载 | [油猴脚本](/zh-cn/userscripts) |
| 查看所有配置项说明 | [配置参考](/zh-cn/configuration) |
| 解决常见问题 | [常见问题](/zh-cn/faq) |

---

## 项目信息

| 项目 | 详情 |
|------|------|
| **作者** | [Sywyar](https://github.com/Sywyar) |
| **许可证** | [GNU AGPL v3](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) |
| **语言** | 中文 / English |
| **Java 版本** | 17 |
| **框架** | Spring Boot 3.5.7 |
| **数据库** | SQLite (WAL 模式) |
| **默认端口** | 6999 |

## 免责声明

本项目仅供个人学习和研究使用，请勿用于商业用途。使用本工具下载的内容版权归原作者所有，请尊重创作者权益。本工具通过用户自行提供的 Cookie 访问 Pixiv，使用者需自行承担账号风险。本项目与 Pixiv 官方无任何关联。请合理设置下载间隔，避免对 Pixiv 服务器造成过大压力。
