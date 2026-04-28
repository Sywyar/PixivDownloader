# PixivDownload

中文 | [English](./README_en.md)

本地 Pixiv 图片批量下载工具，由 **Spring Boot 后端** + **桌面 GUI** + **Tampermonkey 油猴脚本** 组成。

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

## 目录

- [简介](#一简介)
- [安装](#二安装)
- [使用](#三使用)
- [开发指南](#四开发指南)
- [免责声明](#免责声明)
- [闲言碎语](#闲言碎语)
- [友情链接](#友情链接)
- [开发计划](#开发计划)

## 一、简介

PixivDownload 是一款本地 Pixiv 图片批量下载工具，支持多种下载方式和便捷的管理功能。

### 功能特点

-  `Pixiv-batch.html`  [一站式下载](#4-网页端批量下载)，支持批量导入作品、用户作品批量下载、搜索作品批量下载，搭配页面批量下载脚本即可无需其他脚本
- 页面批量下载 — 抓取搜索页、关注动态、排行榜等Pixiv全站抓取页面中的所有作品
- `monitor.html` 一站式管理页面，多维度筛选/排序作品，支持按作者搜索、筛选和排序下载历史
- `pixiv-gallery.html` 画廊页面，支持多种查看格式，多种筛选，**收藏夹**，搭配 `pixiv-artwork.html` 查看作品
- 单作品下载 — 作品页一键下载
- 用户主页批量下载 — 批量下载指定用户的所有作品
- 批量导入作品下载 — 粘贴作品链接列表批量下载，格式为 `url | title`，兼容 One-Tab，N-Tab 等标签页管理插件导出格式
- 关键词搜索下载 — 通过网页端搜索 Pixiv 作品并下载
- 动图自动转 WebP — 自动将 Ugoira 动图合成为带延迟的 WebP
- 下载历史管理 — 记录已下载作品和作者信息，支持断点续传，并自动检测作者改名
- GUI 工具页 — 集成图片分类、数据库目录有效检查、数据库数据回填；需要独占 SQLite 的工具会自动停止并恢复后端
- 多人模式速率限制 — 为多用户场景提供配额和限流功能

### 使用截图

> [!NOTE]
> 少许截图设备启用了HDR，颜色效果可能不同

<details>

<summary><strong>展开查看使用截图</strong></summary>

#### pixiv-gallery.html 画廊使用截图

![](./image/9.png)

#### pixiv-artwork.html 作品详细页使用截图

![](./image/10.png)

#### monitor.html 页面截图

![](./image/1.png)

#### pixiv-batch.html 插件安装页面截图

![](./image/2.png)

#### pixiv-batch.html 批量导入作品页面截图（脚本同等效果，但推荐使用网页版更方便）

![](./image/3.png)

#### pixiv-batch.html User 解析并加入队列页面截图 (脚本同等效果，但推荐使用网页版更方便)

![](./image/4.png)

#### pixiv-batch.html Search 解析并加入队列页面截图

![](./image/5.png)

#### Pixiv 页面批量下载器.user.js 页面截图，支持Pixiv全站抓取

![](./image/6.png)

#### 单作品脚本下载截图 (Java后端和Local download)同等效果

![](./image/7.png)

</details>

## 二、安装

### 1. 下载与运行

从 [Releases](../../releases) 下载最新版：

| 类型                                                  | 说明                                                                                        |
|-----------------------------------------------------|-------------------------------------------------------------------------------------------|
| `PixivDownload-vX.X.X.jar`                          | 通用 JAR，需安装 Java 17+                                                                       |
| `PixivDownload-*-win-x64-online-portable.zip`       | Windows 在线便携版，体积最小；如果系统已安装 FFmpeg，下载这个版本即可                                                |
| `PixivDownload-*-win-x64-portable.zip`              | Windows 离线便携版，已内嵌精简 JRE 和 FFmpeg，适合未安装 FFmpeg 或离线环境                                       |
| `PixivDownload-*-win-x64-setup.exe`                 | Windows 安装包，单个安装器支持多语言；不内置 FFmpeg，可在安装界面勾选安装后下载 FFmpeg                                    |

> Windows 安装包现在使用单个 Inno Setup `setup.exe`：
> - 安装器支持多语言界面
> - 不内置 FFmpeg，安装时可选择在应用安装完成后自动下载并安装 FFmpeg
> - 已安装时再次运行会进入维护界面，支持修复、更改和卸载

### 2. 安装油猴脚本（可选）

**方式一：通过网页管理页一键安装（推荐）**

在 `pixiv-batch.html` 点击页面顶部的 [「🧩 油猴脚本」](#pixiv-batchhtml-插件安装页面截图) 卡片展开，点击对应脚本的「⬇ 安装」按钮。
> [!WARNING]
> 需要注意的是，由于油猴插件限制，当后端网址变更时在此安装的脚本会失效

**方式二：从 Releases 或 GitHub 代码区手动下载**

从 [Releases](../../releases) 下载脚本，拖入 Tampermonkey 管理面板安装。Release 附件只保留 `Pixiv All-in-One.user.js` 和 `Pixiv 单作品图片下载器(Local download).user.js`；All-in-One 覆盖的独立脚本不再单独发布到 Release，需要时可通过 `pixiv-batch.html` 的“油猴脚本”卡片安装，或在 GitHub 代码区下载对应 `.user.js` 源文件。


<details>
<summary><strong>通过下载发布文件安装油猴插件需要此步骤（展开）</strong></summary>

**由于 Tampermonkey 的限制，脚本默认只允许连接 `localhost`。若后端部署在其他机器，需要手动修改每个脚本的
`@connect` 声明：**

1. 打开 Tampermonkey 管理面板 → 找到对应脚本 → 点击编辑
2. 将脚本头部的 `// @connect      YOUR_SERVER_HOST` 替换为实际地址
3. 保存脚本（Ctrl+S）

</details>

Release 附件中的脚本：

| 脚本文件                                     | 适用场景                                          |
|------------------------------------------|-----------------------------------------------|
| `Pixiv All-in-One.user.js`               | 推荐。合并页面批量下载、User 批量下载、URL 批量导入和单作品下载（Java后端版） |
| `Pixiv 单作品图片下载器(Local download).user.js` | 单作品页下载（浏览器本地下载，无需 Java 后端）                    |

> **需要 All-in-One 覆盖的独立脚本时**：启动程序后从 `pixiv-batch.html` 安装，或在 GitHub 代码区下载仓库根目录下的对应 `.user.js` 源文件。

单独脚本用途说明：

| 脚本文件                                     | 适用场景                                    | 获取方式                         |
|------------------------------------------|-----------------------------------------|------------------------------|
| `Pixiv 单作品图片下载器(Java后端版).user.js`        | 单作品页下载，下载任务交给 Java 后端处理                 | `pixiv-batch.html` / GitHub 代码区 |
| `Pixiv 单作品图片下载器(Local download).user.js` | 单作品页下载，浏览器本地下载，无需 Java 后端               | Release / `pixiv-batch.html` / GitHub 代码区 |
| `Pixiv User 批量下载器.user.js`               | 用户主页批量下载                                | `pixiv-batch.html` / GitHub 代码区 |
| `Pixiv URL 批量导入作品下载器.user.js`            | 批量导入作品下载，兼容 One-Tab、N-Tab 等标签页管理插件导出格式 | `pixiv-batch.html` / GitHub 代码区 |
| `Pixiv 页面批量下载器.user.js`                  | 页面 DOM 抓取，支持 Pixiv 全站抓取                 | `pixiv-batch.html` / GitHub 代码区 |

> **推荐优先使用网页端**：`pixiv-batch.html` 支持批量导入作品、User 模式、Search 模式，无需安装油猴脚本即可完成批量下载。

## 三、使用

```bash
# JAR 启动
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar

# Windows exe 启动
PixivDownload.exe

# 可选参数
--no-gui    # 禁用 GUI，纯命令行运行（适合服务器/Docker）
--intro     # 启动时打开产品介绍页
```

> 默认会启动桌面 GUI，可在「状态」「配置」「工具」「关于」标签页中管理后端与本地工具；只有服务器 / Docker 场景才建议使用 `--no-gui`。

### 1.首次配置 (完成配置前无法使用其他功能)

首次启动后浏览器自动打开 `setup.html` ，填写用户名密码（至少 6 位）并选择使用模式：

| 模式   | 适用场景                       |
|------|----------------------------|
| 自用模式 | 个人使用，多设备共享状态，需登录           |
| 多人模式 | 多人共享服务器，各访客配置独立保存在浏览器，无需登录 |

配置写入 `state/setup_config.json` 后不再显示。删除该文件并重启可重新初始化。

### 2. 配置代理

后端通过 HTTP 代理访问 Pixiv CDN。请你通过 **GUI -> 配置 -> 代理** 进行设置，编辑代理配置后**重启服务**生效：

> GUI 中的 FFmpeg 下载按钮会优先复用这里的代理配置，便于在线版直接拉取 GitHub 上的 FFmpeg 发布包。

### 3. 可选 获取 Cookie（使用 Search 模式，限制级相关作品，自动收藏需要）

1. 安装 [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm) 扩展
2. 登录 Pixiv 后，点击扩展图标 → 右下角 **Export** → 选择 **Netscape** 格式复制
3. 在页面上方切换格式为 **Netscape**，粘贴并保存

### 4. 网页端批量下载

访问 `pixiv-batch.html` （自用模式需先登录）：

| 模式           | 说明                                         |
|--------------|--------------------------------------------|
| 🎨 批量导入作品    | 粘贴作品链接列表批量下载，兼容 One-Tab，N-Tab 等标签页管理插件导出格式 |
| 👤 User 模式   | 输入用户 ID 批量下载该用户所有作品                        |
| 🔍 Search 模式 | 关键词搜索并预览缩略图后加入队列（需要 Cookie）                |

> [!NOTE]
> 批量导入作品格式说明：<br>
> 每行一条，格式为 `url | title` <br>
> 示例：`https://www.pixiv.net/artworks/12345678 | 示例标题` <br>
>`title` 可留空，下载前会自动获取真实标题 <br>
> 兼容 One-Tab，N-Tab 等标签页管理插件导出格式；导出后的列表也可直接再次导入

### 5. 下载监控

访问 `monitor.html` 查看实时下载进度和历史记录。

历史记录中的作者列支持：

- 模糊搜索作者名 / 作者 ID
- 按 tag 模糊搜索，按 AI 生成筛选
- 勾选式作者筛选
- 点击作者名快速切换过滤
- 按作者 ID 排序

### 6. GUI 工具页

打开桌面 GUI 的「工具」标签页即可直接使用以下工具：

| 工具          | 说明                                                        |
|-------------|-----------------------------------------------------------|
| 图片分类工具      | 打开独立的图片分类窗口，用于整理已下载图片；运行时不会主动停止后端                         |
| 数据库目录有效检查工具 | 检查数据库中的目录字段是否仍然可访问；启动前会自动停止后端，关闭窗口后自动恢复                   |
| 数据库数据回填工具   | 一次性补全因版本更新缺失的数据；支持数据库路径、代理、请求延迟、处理条数和试运行，并可直接打开 HTML 日志页面 |

> [!IMPORTANT]
> 数据库目录有效检查工具和数据库数据回填工具需要独占 SQLite，GUI 会自动托管后端的停止与恢复，无需再手动停服务后执行命令。

> 如果旧版本数据库里存在 `author_id`、`R18`、`is_ai`、`description` 或标签缺失记录，直接从 GUI 启动“数据库数据回填工具”即可。

### 7. 产品介绍页

访问 `intro.html` （无需登录，公开访问）查看项目介绍。

### 8. 已下载的图片画廊

访问 `pixiv-gallery.html` 查看本地已下载作品画廊

画廊页支持：

- 按作品标题 / 画师搜索
- 按时间、作品 ID、图片数、状态、画师 ID、标签数排序，并支持升序 / 降序切换
- 按 R-18、AI、图片格式、收藏夹进行组合筛选
- 标签和作者支持三种筛选模式：必须有、不能有、或者有；正向条件取并集，再与排除条件取交集
- 分页浏览已下载作品，缩略图卡片会显示 R-18、AI、多图等标记
- 点击卡片进入作品详情页 `pixiv-artwork.html?id=<artworkId>`，查看标题、简介、标签、作者信息、相关作品和同作者其他作品
- 多图作品支持“展开全部”和灯箱预览
- 支持将作品加入收藏夹或从收藏夹移除，收藏夹支持新建、重命名、删除、快速建夹以及自定义图标（PNG / JPG / WEBP，最大 1MB）

## 四、开发指南


<details>
<summary><strong>篇幅过长折叠（展开）</strong></summary>

### 1. Fork 与分支

1. 先 Fork 本仓库到自己的账号，再克隆你的 Fork。
2. 添加上游仓库，方便后续同步：

```bash
git remote add upstream <upstream-repo-url>
git fetch upstream
```

3. 基于上游默认分支创建功能分支：

```bash
git checkout -b feat/your-change upstream/<default-branch>
```

### 2. 本地环境

- JDK 17（`pom.xml` 当前使用 Java 17，Windows 打包还会用到 `jlink` 和 `jpackage`）
- Maven 3.9+，或者直接使用仓库自带的 `mvnw` / `mvnw.cmd`
- Windows 打包需要 PowerShell；构建安装包前还需要安装 Inno Setup 6，并确保 `ISCC.exe` 可用。安装包下载地址：https://jrsoftware.org/isdl.php
- `scripts/package-local.ps1` 会优先查找默认安装目录下的 `Inno Setup 6\ISCC.exe`，找不到时再从 `PATH` 查找。

### 3. 日常开发与本地验证

推荐直接走 Maven 生命周期构建：

```powershell
# Windows PowerShell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\mvnw.cmd package -DskipTests
```

```bash
# macOS / Linux
JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8' ./mvnw package -DskipTests

# 运行
java -Dfile.encoding=UTF-8 -jar target/PixivDownload-*.jar
```

`pixiv-batch.html` 里的“油猴脚本”安装卡片会通过 `/api/scripts` 读取内置脚本列表。当前脚本装配分两部分：

- 仓库根目录的独立 `*.user.js`
- `scripts/build-userscript-bundle.ps1` 生成的 `build/generated-userscripts/Pixiv All-in-One.user.js`

`pom.xml` 会在 `generate-resources` 阶段把这两部分都复制到 `target/classes/static/userscripts`。因此，只要你改动了根目录的油猴脚本或 bundle 生成逻辑，就必须至少执行一次 Maven 生命周期阶段（最低 `generate-resources`，推荐直接执行 `package`），不要只依赖 IDE 的直接运行或手工替换文件，否则页面里安装到的仍可能是旧脚本。

提交前建议至少执行一次：

```powershell
# Windows PowerShell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\mvnw.cmd test
```

```bash
# macOS / Linux
JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8' ./mvnw test
```

如果这次修改涉及 `*.user.js`、静态资源装配或脚本安装流程，再额外执行一次 `package`，然后打开 `http://localhost:6999/pixiv-batch.html` 确认“油猴脚本”列表和安装链接都符合预期。

### 4. 构建 Windows 便携版 / EXE / 安装包

仓库自带的 [`scripts/package-local.ps1`](./scripts/package-local.ps1) 会统一完成本地打包。它会先生成 `Pixiv All-in-One.user.js`，再执行 Maven `package`，然后用 `jlink` 生成精简运行时、用 `jpackage` 生成带 `PixivDownload.exe` 的 app-image，并按参数继续产出在线便携版、内置 FFmpeg 的离线便携版和 Inno Setup 安装包。

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'

# 仅生成便携版（包含 PixivDownload.exe），不构建安装包
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipInstaller

# 生成完整 Windows 产物
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local

# 打包前顺便执行测试
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -RunTests
```

常用参数：

- `-SkipPortable`：跳过在线便携版
- `-SkipOfflinePortable`：跳过内置 FFmpeg 的离线便携版
- `-SkipInstaller`：跳过 Inno Setup 安装包，只保留便携版产物（`-SkipMsi` 作为兼容别名仍可用）
- `-RedownloadFfmpeg`：重新下载离线便携版使用的 FFmpeg 载荷
- `-MsiCultures`、`-MsiVariants`：保留兼容参数，当前 Inno Setup 打包流程会忽略这些选项

默认产物会输出到 `build/out/`。如果启用离线便携版，脚本会下载或复用 `build/ffmpeg` 中的 FFmpeg 载荷；如果启用安装包，脚本会检查本机是否已安装并配置好 Inno Setup 6（`ISCC.exe`）。

### 5. 提交与发起 PR

1. 完成修改后先同步上游最新代码，必要时 rebase 或 merge 到你的功能分支。
2. 自检变更，至少确认测试、打包命令和关键页面行为正常。
3. 不要提交 `target/`、`build/` 这类构建产物，除非维护者明确要求。
4. 使用清晰的 commit message 提交，并推送到你的 Fork。
5. 向上游默认分支发起 PR，在描述里写清楚变更动机、主要改动、验证步骤；如果改动了 UI 或打包流程，附上截图或关键输出会更容易 review。

</details>

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

**[PixivBatchDownloader](https://github.com/xuejianxianzun/PixivBatchDownloader)**
如果您喜欢简约，不想依赖后端程序可以试试这个脚本

功能介绍：
- 超多筛选支持 `(我也想实现！)`
- 有一些辅助功能，如去除广告、快速收藏、看图模式等 `(可以当作一个 Pixiv 的辅助插件？)`
- 下载不依赖第三方工具 `(与本项目最大的区别！安装十分方便！我也在努力将我的项目的使用变得简洁)`
- 支持多语言 `(完蛋了...从一开始我就没有想到适配多语言!)`

## 开发计划
