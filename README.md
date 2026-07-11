# PixivDownloader

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
[![GitHub Repo stars](https://img.shields.io/github/stars/Sywyar/PixivDownloader)](https://github.com/Sywyar/PixivDownloader/stargazers)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/Sywyar/PixivDownloader)](../../releases)

## 功能特点

> [!NOTE]
> 部分功能由官方可选插件提供；Windows 安装包预置必需的 `download-workbench`，离线全量包会携带全部官方可选插件。

- 一站式下载网页，支持快捷获取、批量导入单作品、User 模式、Search 模式、系列模式
- 快捷获取：凭已保存的 Cookie 一键拉取本账户的收藏（插画/小说，含不公开）、自己的作品（含不公开）、关注列表、珍藏集，可钻取查看并加入下载队列
- 页面批量下载脚本 — 抓取搜索页、关注动态、排行榜等 Pixiv 页面中的插画/漫画/动图/小说
- 体验增强工具箱脚本（已下载标记、Cookie 导入）
- 强大的作品/小说画廊，支持搜索范围选择、筛选排序和收藏夹
- 小说画廊支持「正文」全文检索（基于本地全文索引，可与年龄分级/标签/作者等筛选叠加）
- 统计仪表盘：总览卡片、按月下载量折线、下载量 Top 作者、热门标签词云，作者/标签可点击直达画廊筛选
- 疑似重复检测：基于感知哈希（dHash）识别实质重复的已下载图片，支持阈值调节、跨作品/全部范围切换与手动扫描回填
- 插件管理页：卡片列表展示所有插件的状态/来源/版本/依赖，支持外部插件的生命周期操作（加载/启动/停止/卸下/重载）
- 插件市场页：浏览/搜索/筛选受信仓库插件，查看详情并安装（重启生效）；仓库列表可在桌面 GUI 配置页维护
- 计划任务：后台按周期或 Cron 自动发现并下载新作品，支持画师新作/保存的搜索/系列三类来源
- 邮件/推送通知：需人工介入的事件（鉴权失效、熔断等）通过邮件与推送通道告知；可在通知配置页按类型开关
- 小说下载与系列合订（TXT/HTML/EPUB，EPUB 支持多级目录和内嵌图片）
- 小说 AI 翻译（需配置大模型）：把正文或整个系列翻译成指定语言并保存到本地，可在原文与译文之间切换查看
- 小说 AI 多角色朗读（beta）：大模型逐句归属说话人，各角色固定音色合成并连续播放跟随高亮，分析结果可缓存重播
- 动图 (Ugoira) 自动转 WebP
- 自定义文件名模板（11 个变量）
- 已下载校验：数据库与磁盘不一致时自动清理脏记录或反向恢复记录
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

| 类型                                  | 说明                                 |
|-------------------------------------|------------------------------------|
| `PixivDownload-*-win-x64-setup.exe` | Windows 安装包，支持修复/更改/卸载，可选安装 FFmpeg 与官方可选插件 |
| `PixivDownload-*-full-offline.zip`  | 离线全量包，需 Java 17+；包含核心壳、必需的 `download-workbench` 与全部官方可选插件 |

### 安装包与官方插件

当前发布包采用外置插件布局：

- `download-workbench` 是 required 外置插件，提供下载页、下载 API、队列、userscript 入口、Pixiv 插画代理和计划任务宿主。Windows 安装包与离线全量包会随包携带它；缺失、损坏、不兼容或验签失败时，程序进入恢复路径，只开放登录、插件管理和安装修复入口。
- `stats`、`duplicate`、`gallery`、`novel`、`tts`、`ai`、`push`、`mail`、`gui-theme`、`notification` 是官方可选插件。安装并启用后对应页面、API、静态资源、i18n、导航、GUI 配置字段或能力贡献可用；缺失或禁用时这些入口自然缺席，不会触发恢复路径。
- GitHub Release 仅提供 Windows 安装包和 full-offline package（离线全量包）。独立核心壳 JAR 与默认下载器包仍用于构建 / 恢复流程，不作为普通下载附件发布。
- Windows 安装包携带 required `download-workbench`，并可在附加功能页从签名官方清单安装可选插件；full-offline package（离线全量包）在此基础上携带全部官方可选插件与离线验签所需文件。
- `duplicate` 缺失或禁用不影响下载完成后的图片 Hash 写入，也不会删除历史 Hash 数据。
- `gallery` 缺失或禁用只影响本地画廊、作品详情、展示 API、导航和相关静态资源；下载页、下载 API、userscript、Pixiv 插画代理、计划任务宿主、作品元数据、下载事实、Hash 与本地资源索引仍正常保留。
- `novel` 缺失或禁用时，小说下载、小说 Pixiv 代理、小说核心 API、计划任务小说执行器、翻译 / 合订 / 正文保存入口、小说画廊、小说阅读页、导航、静态资源和 i18n 均缺席；历史小说正文、翻译状态、narration 数据、合订结果与元数据保留，重新安装后继续可读。
- TTS / AI / push / mail 插件缺失时，对应能力会显示为不可用或跳过；不会回退到核心内置实现。

### 启动

```bash
# 离线全量包启动：解压后在目录内运行
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar

# Windows EXE 启动
PixivDownload.exe

# 可选参数
--no-gui    # 禁用 GUI，纯命令行运行（适合服务器/Docker）
--intro     # 启动时打开产品介绍页
```

首次启动后按引导完成配置，即可访问 `http://localhost:6999/pixiv-batch.html` 开始下载。

### 让网页版 Pixiv 走后端配置的代理（无需开启系统代理）

后端访问 Pixiv 走配置里指定的代理（默认 `127.0.0.1:7890`），不依赖系统代理。如果你还希望在浏览器里直接打开 `pixiv.net`（例如配合油猴脚本），又不想为此开启 Clash 的「系统代理 / system proxy」，可以使用内置的代理自动配置（PAC）：

在系统或浏览器的「自动代理配置脚本（PAC）URL」处填入 `http://localhost:6999/proxy.pac`（端口与你的配置一致；启用 HTTPS 时为 `https://<域名>:<端口>/proxy.pac`），即可让仅 Pixiv 相关域名走后端配置的同一个代理、其余流量直连。该地址仅本机可访问，代理变更（含热重载）会自动反映到 PAC 内容；不再需要来回切换系统代理。

各浏览器 / 系统的具体设置入口地址（Firefox `about:preferences#general`、Windows `ms-settings:network-proxy` 等）见[配置参考 · 让网页版 Pixiv 走同一个代理](https://sywyar.github.io/PixivDownloader/#/zh-cn/configuration)。

> [!TIP]
> **详细安装步骤、使用指南、配置参考、开发指南等请查阅 [在线文档](https://sywyar.github.io/PixivDownloader/#/zh-cn/)**

---

## 免责声明

- 本项目仅供个人学习和研究使用，请勿用于任何商业用途。
- 使用本工具下载的内容版权归原作者所有，请尊重创作者权益，不得二次传播或商业使用。
- 本工具通过用户自行提供的 Cookie 或在经过用户允许下通过油猴脚本提取 Cookie 来访问 Pixiv，使用者需自行承担账号风险
- 本项目与 Pixiv 官方无任何关联，使用本工具产生的一切后果由使用者自行负责。
- 请合理设置下载间隔，避免对 Pixiv 服务器造成过大压力。

---

## 多人模式部署风险

多人模式下，所有 Pixiv 请求仍从部署服务器的同一个出口 IP 发出；不同 Cookie 只能隔离账号会话，不能隔离出口 IP 风险。并发量或请求频率过高时，Pixiv 可能限制账号或出口 IP。建议仅在可信的小规模环境中使用，启用人均配额与速率限制，设置合理的下载间隔，并持续关注服务端日志；不要把实例作为无约束的公共下载服务开放。

## 相关项目

**[PixivBatchDownloader](https://github.com/xuejianxianzun/PixivBatchDownloader)**

如果只需要无需部署后端的浏览器脚本方案，可以了解该项目：

- 支持丰富的筛选条件
- 提供去除广告、快速收藏和看图模式等浏览辅助功能
- 无需部署独立后端程序
- 支持多语言
