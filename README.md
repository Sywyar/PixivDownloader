# PixivDownload

中文 | [English](./README_en.md)

本地 Pixiv 图片批量下载工具，由 **Spring Boot 后端** + **Tampermonkey 油猴脚本** 组成。

**功能：** 单作品下载 / 用户主页批量下载 / N-Tab 书签批量下载 / 动图自动转 WebP / 下载历史管理 / 图片分类工具 / 多人模式速率限制

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

---

## 目录

- [环境要求](#环境要求)
- [安装](#安装)
  - [1. 运行后端](#1-运行后端)
  - [2. 首次配置](#2-首次配置)
  - [3. 安装油猴脚本](#3-安装油猴脚本)
  - [4. 配置代理](#4-配置代理)
  - [5. 安装 ffmpeg（可选，仅动图需要）](#5-安装-ffmpeg可选仅动图需要)
- [使用](#使用)
  - [单作品下载](#单作品下载)
  - [用户主页批量下载](#用户主页批量下载)
  - [N-Tab 书签批量下载](#n-tab-书签批量下载)
  - [Web 批量下载页面](#web-批量下载页面)
  - [下载监控页面](#下载监控页面)
- [工具](#工具)
  - [图片分类工具](#图片分类工具)
  - [R18 补全工具](#R18-补全工具)
  - [文件夹路径检查工具](#文件夹路径检查工具)
  - [旧版数据迁移工具](#旧版数据迁移工具)
- [配置说明](#配置说明)
- [免责声明](#免责声明)

---

## 环境要求

| 依赖           | 说明                                             |
|--------------|------------------------------------------------|
| Java 17+     | 运行后端                                           |
| 代理软件         | Clash、v2rayN 等，用于访问 Pixiv（默认 `127.0.0.1:7890`） |
| Tampermonkey | 浏览器扩展，用于安装油猴脚本                                 |
| ffmpeg（可选）   | 仅下载动图（Ugoira）时需要，需加入系统 PATH                    |

---

## 安装

### 1. 运行后端

从 [Releases](../../releases) 下载最新的 `PixivDownload-vX.X.X.jar`，在任意目录执行：

```bash
java -jar PixivDownload-vX.X.X.jar
```

后端默认监听 `http://localhost:6999`，下载文件保存在运行目录下的 `pixiv-download/` 文件夹。

首次启动会自动打开浏览器进入配置向导，**请先完成[首次配置](#2-首次配置)再使用其他功能**。

### 2. 首次配置

首次启动后浏览器会自动打开 `http://localhost:6999/setup.html`，按以下步骤完成配置：

**① 设置管理员账号**

填写用户名和密码（密码至少 6 位），用于后续登录。

**② 选择使用模式**

| 模式   | 适用场景    | 说明                                             |
|------|---------|------------------------------------------------|
| 自用模式 | 个人使用    | 访问任意页面均需登录。Cookie、设置、下载队列保存在服务器端，多设备共享状态。      |
| 多人模式 | 多人共享服务器 | 无需登录。每个访客的配置独立保存在各自浏览器的 `localStorage` 中，互不干扰。 |

**③ 点击「完成配置」**

配置写入 `pixiv-download/setup_config.json` 后不再显示此页面。如需重新初始化，删除该文件并重启服务即可。

> **自用模式登录：** 访问任意页面会自动跳转到 `/login.html`，输入账号密码后勾选「保持登录状态」可记住 30 天登录，否则关闭浏览器后 Session 2 小时过期。

### 3. 安装油猴脚本

确保浏览器已安装 [Tampermonkey](https://www.tampermonkey.net/) 扩展，从 [Releases](../../releases) 下载对应脚本，**拖入 Tampermonkey 管理面板**即可安装：

| 脚本文件                              | 适用场景                    |
|-----------------------------------|-------------------------|
| `Pixiv作品图片下载器(Java后端版).user.js`   | 在单个作品页一键下载              |
| `Pixiv User 批量下载器.user.js`        | 在用户主页批量下载该用户所有作品        |
| `Pixiv N-Tab 批量下载器 (修复版).user.js` | 导入 N-Tab 书签 JSON 批量下载收藏 |

**修改服务器地址：** 三个脚本默认连接 `http://localhost:6999`，三脚本共享同一地址设置。

- **用户批量 / N-Tab 脚本**：悬浮面板底部的「服务器地址」输入框，失去焦点后自动保存。
- **单作品脚本**：点击浏览器地址栏右侧的 Tampermonkey 图标 → 菜单中选择「⚙️ 设置服务器地址」。

> **首次启动提示 / 使用外部服务器时的额外步骤**
>
> 三个脚本**首次运行时**均会弹出一次性提示，说明以下注意事项：
>
> Tampermonkey 的 `GM_xmlhttpRequest` 受 `@connect` 白名单限制，脚本默认只允许连接 `localhost`。**若后端部署在其他机器**（如局域网 IP `192.168.1.100` 或域名），需要手动修改每个脚本的 `@connect` 声明：
>
> 1. 打开 Tampermonkey 管理面板 → 找到对应脚本 → 点击编辑
> 2. 将脚本头部的 `// @connect      YOUR_SERVER_HOST` 替换为实际地址，例如：
>    ```
>    // @connect      192.168.1.100
>    ```
> 3. 保存脚本（Ctrl+S），三个脚本均需执行此操作
>
> 若不想使用油猴脚本，也可以直接在浏览器访问 `http://<服务器地址>/login.html` 登录后通过网页端下载作品。

### 4. 配置代理

后端通过 HTTP 代理访问 Pixiv CDN，启动后会在运行目录自动生成 `config.yaml`，编辑其中的代理配置后**重启服务**生效：

```yaml
proxy.enabled: true
proxy.host: 127.0.0.1
proxy.port: 7890   # 修改为你的代理软件实际监听端口
```

请确保代理软件（Clash、v2rayN 等）已正确配置可访问 Pixiv 的节点，且本地 HTTP 代理端口与此处一致。

### 5. 安装 ffmpeg（可选，仅动图需要）

不安装 ffmpeg 不影响普通图片下载，仅动图（illustType=2）下载时需要。

**Windows 安装步骤：**

1. 前往 [ffmpeg 官网](https://ffmpeg.org/download.html) 下载 Windows 预编译版本（推荐 gyan.dev 构建）
2. 解压到任意目录，例如 `C:\ffmpeg`
3. 将 `C:\ffmpeg\bin` 添加到系统环境变量 `PATH`
4. 打开**新的**命令行窗口，执行以下命令验证：
   ```bash
   ffmpeg -version
   ```

---

## 使用

### 单作品下载

1. 打开任意 Pixiv 作品页（`https://www.pixiv.net/artworks/{id}`）
2. 页面加载完成后，脚本会在图片旁显示下载按钮
3. 点击按钮，脚本自动提取图片 URL 并发送给后端开始下载
4. 动图（Ugoira）会自动识别并通过 ffmpeg 合成为带延迟的 WebP

### 用户主页批量下载

1. 打开任意 Pixiv 用户主页（`https://www.pixiv.net/users/{userId}`）
2. 页面角落会出现悬浮控制面板，包含以下选项：

| 选项      | 说明                           |
|---------|------------------------------|
| 开始 / 暂停 | 控制批量任务                       |
| 跳过已下载   | 自动跳过数据库中已存在的作品，断点续传          |
| 仅 R18   | 只下载 R18 作品                   |
| 下载间隔    | 每个作品之间的等待秒数（默认 2 秒，建议不要设置过短） |
| 并发数     | 同时进行的下载任务数（默认 1）             |

3. 普通作品保存在 `pixiv-download/{username}/{artworkId}/`，R18 作品保存在 `pixiv-download/{username}/R18/{artworkId}/`
4. 下载进度通过 SSE 实时获取，断联时自动轮询兜底，同一作品不会被重复提交

### [N-Tab](https://github.com/scoful/N-Tab/) 书签批量下载

1. 将一个或多个标签页发送成一组或多组标签
2. 点击导航栏的「其他功能 - 导出」
3. 点击「导出」后将导出的链接复制到输入框中
4. 悬浮控制面板出现后，点击「开始」
5. 支持跳过已下载、R18 过滤等选项，操作同[用户主页批量下载](#用户主页批量下载)

### Web 批量下载页面

访问 `http://localhost:6999/pixiv-batch.html` 自用模式需先登录

输入 Pixiv 用户 ID 和 Cookie，在网页端直接触发批量下载，无需油猴脚本。

**获取 Cookie 的方法（推荐使用 Cookie-Editor）：**

1. 安装 [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm) 浏览器扩展
2. 浏览器登录 Pixiv，打开任意 Pixiv 页面
3. 点击 Cookie-Editor 图标 → 右下角 **Export** → 选择 **Netscape** 格式复制
4. 在页面 Cookie 输入框上方切换格式为 **Netscape**，粘贴并保存

> 自用模式下 Cookie 保存在服务器端（`batch_state.json`），多设备共享；多人模式下保存在浏览器 `localStorage`，各访客独立。

### 下载监控页面

访问 `http://localhost:6999/monitor.html`

- 实时显示当前下载任务进度
- 分页浏览全部下载历史，支持点击作品预览图片
- 显示统计信息（总作品数、总图片数、已移动数）

---

## 工具

### 图片分类工具

`ImageClassifier` 是独立的 Java Swing 桌面程序，**不通过浏览器访问**，需单独运行：

```bash
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.imageclassifier.ImageClassifier
```

首次运行时会在当前目录生成 `image_classifier.properties` 配置文件。

**基本工作流：**

1. 在顶部路径框输入父目录路径，或点击「浏览」选择目录，然后按 Enter 或点击「打开」
2. 程序自动加载所有子文件夹，并显示当前文件夹的缩略图（每组 10 张，点击 `<` / `>` 翻页）
3. 在右侧编号框输入目标分类编号（旁边实时显示分类名称），点击「分类整个文件夹」执行移动
4. 移动完成后自动进入下一个文件夹；点击「跳过」跳过当前文件夹

**配置设置（菜单栏「设置」）：**

| 标签页   | 内容                        |
|-------|---------------------------|
| 基本设置  | 配置后端服务器地址（用于上报移动路径）       |
| 目标文件夹 | 新增/编辑/删除分类目录，格式：路径 + 备注名称 |

> 后端服务在线时，分类操作会自动向后端上报新路径；后端离线时仅本地移动文件。

### R18 补全工具

`R18Backfill` 是命令行工具，用于批量补全数据库中 `R18` 字段为 NULL 的作品记录。它通过 Pixiv AJAX 接口（无需登录）查询每个作品的限制级别，自动写入数据库。

**启动方式：**

```bash
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.tools.R18Backfill [选项]
```

**可用选项：**

| 选项                    | 默认值                                | 说明           |
|-----------------------|------------------------------------|--------------|
| `--db <path>`         | `pixiv-download/pixiv_download.db` | 数据库文件路径      |
| `--proxy <host:port>` | `127.0.0.1:7890`                   | HTTP 代理地址    |
| `--no-proxy`          | —                                  | 不使用代理        |
| `--delay <ms>`        | `800`                              | 每次请求间隔（毫秒）   |
| `--dry-run`           | —                                  | 只打印结果，不写入数据库 |

**示例：**

```bash
# 使用默认配置运行
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.tools.R18Backfill

# 试运行（不写入数据库）
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.tools.R18Backfill --dry-run

# 指定数据库路径，不使用代理
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.tools.R18Backfill --db D:/data/pixiv_download.db --no-proxy
```

> 遇到 HTTP 429（触发限流）时工具会自动停止，已处理的结果已写入数据库，重新运行会从未补全的记录继续。

---

### 文件夹路径检查工具

`FolderChecker` 是 Java Swing 桌面工具，用于检查数据库中记录的作品文件夹路径是否仍可访问。当手动移动过文件夹导致数据库路径失效时，可用此工具批量发现并修复。

**启动方式：**

```bash
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.tools.FolderChecker
```

**使用步骤：**

1. 在顶部 **Database** 输入框填写数据库路径，或点击 **Browse...** 选择 `pixiv_download.db` 文件
2. 点击 **Check Folders**，工具扫描所有作品记录并列出路径不可访问的条目（状态栏显示异常数量）
3. 点击列表中的某一行选中，底部 **New Path** 输入框会自动填入当前路径
4. 修改为正确路径（或点击 **Browse...** 选择目录），点击 **Update DB** 写入数据库
5. 更新后列表自动刷新

> 已移动的作品检查 `move_folder` 字段；未移动的作品检查 `folder` 字段。每行的 **Copy ID** 按钮可将作品 ID 复制到剪贴板。

---

### 旧版数据迁移工具

如果之前使用过旧版（基于 JSON 文件：`download_history.json`、`statistics.json`），可通过以下命令将数据迁移至 SQLite 数据库：

**实时进度模式（推荐数据量较大时使用）：**
```bash
curl -N http://localhost:6999/api/migration/json-to-sqlite/stream
```

**普通模式（返回 JSON 结果）：**
```bash
curl -X POST http://localhost:6999/api/migration/json-to-sqlite
```

两个接口均为幂等操作，可多次调用，不会产生重复数据。迁移完成后原 JSON 文件不会被删除，可自行备份后删除。

---

## 配置说明

首次启动自动生成 `config.yaml`，直接编辑后重启服务生效：

```yaml
server.port: 6999                              # 服务监听端口

download.root-folder: pixiv-download           # 下载根目录（相对或绝对路径）
download.user-flat-folder: false               # User 模式目录结构：false=按用户名分目录，true=与 N-Tab 相同的扁平结构

# ---- 代理配置 ----

proxy.enabled: true                            # 是否启用 HTTP 代理
proxy.host: 127.0.0.1                          # 代理服务器地址
proxy.port: 7890                               # 代理服务器端口

# ---- 多人模式配置（仅 multi 模式有效）----

multi-mode.quota.enabled: true                 # 是否启用下载配额限制
multi-mode.quota.max-artworks: 50              # 每用户每周期最多下载作品数
multi-mode.quota.reset-period-hours: 24        # 配额重置周期（小时）
multi-mode.quota.archive-expire-minutes: 60    # 压缩包下载链接有效时间（分钟）
multi-mode.quota.limit-image: 0                # 单作品图片数上限（0=不限制）；超出后按 ceil(图片数/limit-image) 个作品计算配额

# 下载后处理模式（三选一）：
#   pack-and-delete  打包后删除源文件（默认）
#   never-delete     打包后保留源文件；再次下载同一作品直接返回已完成
#   timed-delete     打包后保留源文件；超过 delete-after-hours 后自动删除
multi-mode.post-download-mode: pack-and-delete

multi-mode.delete-after-hours: 72              # timed-delete 模式：下载后多少小时自动删除

multi-mode.request-limit-minute: 300           # 每用户每分钟最大请求次数（0 表示不限制）
```

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
