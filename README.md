# PixivDownload

中文 | [English](./README_en.md)

本地 Pixiv 图片批量下载工具，由 **Spring Boot 后端** + **Tampermonkey 油猴脚本** 组成。

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

## 简介

PixivDownload 是一款本地 Pixiv 图片批量下载工具，支持多种下载方式和便捷的管理功能。

### 功能特点

- Pixiv-batch.html [一站式下载](#网页端批量下载)，N-Tab批量下载，用户作品批量下载，搜索作品批量下载，搭配页面批量下载脚本即可无需其他脚本
- 页面批量下载 — 抓取搜索页、关注动态、排行榜等Pixiv全站抓取页面中的所有作品
- monitor.html 一站式管理页面，多维度筛选/排序作品
- 单作品下载 — 作品页一键下载
- 用户主页批量下载 — 批量下载指定用户的所有作品
- N-Tab 书签批量下载 — 导入 N-Tab 书签导出文件批量下载
- 关键词搜索下载 — 通过网页端搜索 Pixiv 作品并下载
- 动图自动转 WebP — 自动将 Ugoira 动图合成为带延迟的 WebP
- 下载历史管理 — 记录已下载作品，支持断点续传
- 图片分类工具 — 独立的桌面工具，用于整理已下载的图片
- 多人模式速率限制 — 为多用户场景提供配额和限流功能

### 使用截图

#### monitor.html 页面截图
![](./image/1.png)

#### pixiv-batch.html 插件安装页面截图
![](./image/2.png)

#### pixiv-batch.html N-Tab 解析并加入队列页面截图 (脚本同等效果，但推荐使用网页版更方便)
![](./image/3.png)

#### pixiv-batch.html User 解析并加入队列页面截图 (脚本同等效果，但推荐使用网页版更方便)
![](./image/4.png)

#### pixiv-batch.html Search 解析并加入队列页面截图
![](./image/5.png)

#### Pixiv 页面批量下载器.user.js 页面截图，支持Pixiv全站抓取
![](./image/6.png)

#### 单作品脚本下载截图 (Java后端和Local download)同等效果
![](./image/7.png)

## 安装

### 1. 下载与运行

从 [Releases](../../releases) 下载最新版：

| 类型                                            | 说明                                                  |
|-----------------------------------------------|-----------------------------------------------------|
| `PixivDownload-vX.X.X.jar`                    | 通用 JAR，需安装 Java 17+                                 |
| `PixivDownload-*-win-x64-online-portable.zip` | Windows 在线便携版，体积最小；如果系统已安装 FFmpeg，下载这个版本即可          |
| `PixivDownload-*-win-x64-portable.zip`        | Windows 离线便携版，已内嵌精简 JRE 和 FFmpeg，适合未安装 FFmpeg 或离线环境 |
| `PixivDownload-*-win-x64-<culture>-with-ffmpeg.msi` | Windows 安装包，当前提供 `zh-CN` / `en-US` 两种语言；内置 FFmpeg，开箱即用，但体积更大 |
| `PixivDownload-*-win-x64-<culture>-no-ffmpeg.msi`   | Windows 安装包，当前提供 `zh-CN` / `en-US` 两种语言；不内置 FFmpeg，体积更小，适合系统已安装 FFmpeg 或不需要 Ugoira 转 WebP |

> Windows MSI 现已拆分为固定变体，请在下载时直接选好版本：
> - `zh-CN` / `en-US`：安装器界面语言
> - `with-ffmpeg`：内置 FFmpeg，适合开箱即用
> - `no-ffmpeg`：不内置 FFmpeg，适合系统已安装 FFmpeg 或只下载普通图片
> - MSI 安装向导不提供安装时切换语言，也不再提供 FFmpeg 组件勾选页

```bash
# JAR 启动
java -jar PixivDownload-vX.X.X.jar

# Windows exe 启动
PixivDownload.exe

# 可选参数
--no-gui    # 禁用 GUI，纯命令行运行（适合服务器/Docker）
--intro     # 启动时打开产品介绍页
```

> 首次启动会自动打开浏览器进入配置向导，完成配置前无法使用其他功能。

> 在线便携版默认不内置 FFmpeg，但会优先复用系统 PATH、程序目录或用户目录中的 FFmpeg。若你已经安装 FFmpeg，下载在线便携版即可；仅在系统里没有 FFmpeg 且需要 Ugoira 动图转 WebP 时，再在 GUI 的“状态”页点击“下载 FFmpeg”。普通图片下载不受影响。

### 2. 首次配置

首次启动后浏览器自动打开 `http://localhost:6999/setup.html` ，填写用户名密码（至少 6 位）并选择使用模式：

| 模式   | 适用场景                       |
|------|----------------------------|
| 自用模式 | 个人使用，多设备共享状态，需登录           |
| 多人模式 | 多人共享服务器，各访客配置独立保存在浏览器，无需登录 |

配置写入 `pixiv-download/setup_config.json` 后不再显示。删除该文件并重启可重新初始化。

### 3. 配置代理

后端通过 HTTP 代理访问 Pixiv CDN。启动后在运行目录生成 `config.yaml`，编辑代理配置后**重启服务**生效：

```yaml
proxy.enabled: true
proxy.host: 127.0.0.1
proxy.port: 7890   # 修改为你的代理实际端口
```

> GUI 中的 FFmpeg 下载按钮会优先复用这里的代理配置，便于在线版直接拉取 GitHub 上的 FFmpeg 发布包。

### 4. 安装油猴脚本（可选）

除了油猴脚本，也可以在网页端完成所有操作。如需安装：

<details>
<summary><strong>通过下载发布文件安装油猴插件需要此步骤（展开）</strong></summary>

Tampermonkey 的 `GM_xmlhttpRequest` 受 `@connect` 白名单限制，脚本默认只允许连接 `localhost`。若后端部署在其他机器，需要手动修改每个脚本的 `@connect` 声明：

1. 打开 Tampermonkey 管理面板 → 找到对应脚本 → 点击编辑
2. 将脚本头部的 `// @connect      YOUR_SERVER_HOST` 替换为实际地址
3. 保存脚本（Ctrl+S）

</details>

**方式一：通过网页管理页一键安装（推荐）**

登录后打开 `http://localhost:6999/pixiv-batch.html`，点击页面顶部的「🧩 油猴脚本」卡片展开，点击对应脚本的「⬇ 安装」按钮。部署在非 localhost 服务器时，`@connect` 会自动替换为实际地址。

**方式二：从 Releases 手动下载**

从 [Releases](../../releases) 下载脚本，拖入 Tampermonkey 管理面板安装：

| 脚本文件                            | 适用场景                   |
|---------------------------------|------------------------|
| `Pixiv作品图片下载器(Java后端版).user.js` | 单作品页下载                 |
| `Pixiv User 批量下载器.user.js`      | 用户主页批量下载               |
| `Pixiv N-Tab 批量下载器.user.js`     | N-Tab 书签导入批量下载         |
| `Pixiv 页面批量下载器.user.js`         | 页面 DOM 抓取（支持Pixiv全站抓取） |

> **推荐优先使用网页端**：`http://localhost:6999/pixiv-batch.html` 支持 N-Tab 模式、User 模式、Search 模式，无需安装油猴脚本即可完成批量下载。

## 使用

### 获取 Cookie（使用 Search 模式，限制级相关作品，自动收藏需要）

1. 安装 [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm) 扩展
2. 登录 Pixiv 后，点击扩展图标 → 右下角 **Export** → 选择 **Netscape** 格式复制
3. 在页面上方切换格式为 **Netscape**，粘贴并保存

### 网页端批量下载

访问 `http://localhost:6999/pixiv-batch.html` （自用模式需先登录）：

| 模式           | 说明                          |
|--------------|-----------------------------|
| 🎨 N-Tab 模式  | 粘贴 N-Tab 导出的作品链接批量下载        |
| 👤 User 模式   | 输入用户 ID 批量下载该用户所有作品         |
| 🔍 Search 模式 | 关键词搜索并预览缩略图后加入队列（需要 Cookie） |

### 下载监控

访问 `http://localhost:6999/monitor.html` 查看实时下载进度和历史记录。

### 产品介绍页

访问 `http://localhost:6999/intro.html` （无需登录，公开访问）查看项目介绍。

---

## 免责声明

- 本项目仅供个人学习和研究使用，请勿用于任何商业用途。
- 使用本工具下载的内容版权归原作者所有，请尊重创作者权益，不得二次传播或商业使用。
- 本工具通过用户自行提供的 Cookie 访问 Pixiv，使用者需自行承担账号风险。
- 本项目与 Pixiv 官方无任何关联，使用本工具产生的一切后果由使用者自行负责。
- 请合理设置下载间隔，避免对 Pixiv 服务器造成过大压力。

---

## 闲言碎语

说真的我其实并不推荐这个工具的多人模式，因为所有的请求走的都是服务器网络的IP，就算cookie不一样请求量大也有可能封IP，我也在考虑在多人模式下添加一个登录机制，但与项目方便的初衷背道而驰，目前只会继续打磨这个项目

## 友情链接

**[![](https://raw.githubusercontent.com/xuejianxianzun/PixivBatchDownloader/master/static/icon/logo48.png)PixivBatchDownloader](//github.com/xuejianxianzun/PixivBatchDownloader)**  
如果您喜欢简约，不想依赖后端程序可以试试这个脚本

功能介绍：
- 超多筛选支持 `(我也想实现！)`
- 有一些辅助功能，如去除广告、快速收藏、看图模式等 `(可以当作一个 Pixiv 的辅助插件？)`
- 下载不依赖第三方工具 `(与本项目最大的区别！安装十分方便！我也在努力将我的项目的使用变得简洁)`
- 支持多语言 `(完蛋了...从一开始我就没有想到适配多语言!)`
