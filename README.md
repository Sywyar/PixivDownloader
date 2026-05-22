# PixivDownload

中文 | [English](./README_en.md)

> [!NOTE]
> 此文档中提及的作品范围包括 插画/漫画/动图/小说

### 本地 Pixiv 作品批量下载工具，支持小说/漫画的各种类型下载

- 批量通过作品链接下载作品
- 通过用户ID批量下载作品
- 通过内置搜索代理批量下载作品
- 通过输入作品系列链接或者系列中作品链接批量下载整个系列作品
- 通过油猴脚本在 Pixiv 网页上抓取插画/漫画/动图/小说，或在单作品页直接下载
- 强大的作品/小说画廊

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![GitHub Repo stars](https://img.shields.io/github/stars/Sywyar/PixivDownload)](https://github.com/Sywyar/PixivDownload/stargazers)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/Sywyar/PixivDownload)](../../releases)
[![GitHub commit activity](https://img.shields.io/github/commit-activity/m/Sywyar/PixivDownload)](https://github.com/Sywyar/PixivDownload/commits)

## 功能特点

- 一站式下载网页，支持批量导入单作品、User 模式、Search 模式、系列模式
- 页面批量下载脚本 — 抓取搜索页、关注动态、排行榜等 Pixiv 页面中的插画/漫画/动图/小说
- 体验增强工具箱脚本（已下载标记、Cookie 导入）
- 强大的作品/小说画廊，支持搜索范围选择、筛选排序和收藏夹
- 小说下载与系列合订（TXT/HTML/EPUB，EPUB 支持多级目录和内嵌图片）
- 动图 (Ugoira) 自动转 WebP
- 自定义文件名模板（11 个变量）
- 多用户场景配额和限流功能
- 访客邀请系统（分级/标签/作者白名单）
- 多语言/暗色模式
- 桌面 GUI（Swing + FlatLaf），在线更新

## 使用截图

> [!NOTE]
> 少许截图设备启用了 HDR，颜色效果可能不同

### [浅色模式使用截图](./zh-CN/md/light-screenshot.md)
### [暗色模式使用截图](./zh-CN/md/dark-screenshot.md)

## 快速开始

### 下载

从 [Releases](../../releases) 下载最新版：

| 类型 | 说明 |
|------|------|
| `PixivDownload-vX.X.X.jar` | 通用 JAR，需 Java 17+ |
| `PixivDownload-*-win-x64-setup.exe` | Windows 安装包，支持修复/更改/卸载，可选安装 FFmpeg |

### 启动

```bash
# JAR 启动
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar

# Windows EXE 启动
PixivDownload.exe

# 可选参数
--no-gui    # 禁用 GUI，纯命令行运行（适合服务器/Docker）
--intro     # 启动时打开产品介绍页
```

首次启动后按引导完成配置，即可访问 `http://localhost:6999/pixiv-batch.html` 开始下载。

> [!TIP]
> **详细安装步骤、使用指南、配置参考、开发指南等请查阅 [Wiki](https://github.com/Sywyar/PixivDownload/wiki/zh-Home)**

---

## 免责声明

- 本项目仅供个人学习和研究使用，请勿用于任何商业用途。
- 使用本工具下载的内容版权归原作者所有，请尊重创作者权益，不得二次传播或商业使用。
- 本工具通过用户自行提供的 Cookie 或在经过用户允许下通过油猴脚本提取 Cookie 来访问 Pixiv，使用者需自行承担账号风险
- 本项目与 Pixiv 官方无任何关联，使用本工具产生的一切后果由使用者自行负责。
- 请合理设置下载间隔，避免对 Pixiv 服务器造成过大压力。

---

## 闲言碎语

说真的我其实并不推荐这个工具的多人模式，因为所有的请求走的都是服务器网络的IP，就算cookie不一样请求量大也有可能封IP，我也在考虑在多人模式下添加一个登录机制，但与项目方便的初衷背道而驰，目前只会继续打磨这个项目

## 友情链接

**[PixivBatchDownloader](https://github.com/xuejianxianzun/PixivBatchDownloader)**
如果您喜欢简约，不想依赖后端程序可以试试这个脚本

功能介绍：

- 超多筛选支持
- 有一些辅助功能，如去除广告、快速收藏、看图模式等 `(可以当作一个 Pixiv 的辅助插件？)`
- 下载不依赖第三方工具 `(与本项目最大的区别！安装十分方便！我也在努力将我的项目的使用变得简洁)`
- 支持多语言

## 开发计划
