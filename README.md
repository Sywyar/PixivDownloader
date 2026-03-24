# PixivDownload

本地 Pixiv 图片批量下载系统，由 **Spring Boot 后端** + **Tampermonkey 油猴脚本** 组成。支持单作品下载、用户主页批量下载、N-Tab 书签批量下载，以及动图（Ugoira）自动转换为 WebP 动图。

---

## 目录

- [功能特性](#功能特性)
- [系统架构](#系统架构)
- [环境要求](#环境要求)
- [安装与配置](#安装与配置)
  - [1. 代理软件](#1-代理软件)
  - [2. ffmpeg（动图支持）](#2-ffmpeg动图支持)
  - [3. 构建并运行后端](#3-构建并运行后端)
  - [4. 安装油猴脚本](#4-安装油猴脚本)
- [使用方法](#使用方法)
  - [单作品下载](#单作品下载)
  - [用户主页批量下载](#用户主页批量下载)
  - [N-Tab 书签批量下载](#n-tab-书签批量下载)
  - [Web 批量下载页面](#web-批量下载页面)
  - [下载监控页面](#下载监控页面)
  - [图片分类工具（Web 版）](#图片分类工具web-版)
- [配置说明](#配置说明)
- [API 接口](#api-接口)
- [目录结构](#目录结构)
- [数据库结构](#数据库结构)
- [旧版数据迁移](#旧版数据迁移)

---

## 功能特性

- **单作品下载**：在 Pixiv 作品页一键下载全部分页图片
- **用户主页批量下载**：自动获取用户全部作品 ID，批量下载，支持断点续传（跳过已下载）
- **N-Tab 批量下载**：解析 [N-Tab](https://github.com/scoful/N-Tab/) 导出的书签 JSON，批量下载收藏作品
- **动图支持（Ugoira）**：自动下载 ZIP 帧包，通过 ffmpeg 合成为带延迟的 WebP 动图
- **R18 分类**：R18 作品自动存入独立子目录
- **实时进度推送**：通过 SSE（Server-Sent Events）实时获取下载进度
- **下载历史管理**：SQLite 数据库记录全部下载历史，支持分页查询
- **监控面板**：Web 页面展示下载历史、统计信息、图片预览
- **移动记录**：支持记录作品文件移动后的新路径
- **图片分类工具（Web 版）**：浏览器内对已下载的作品文件夹进行批量分类移动，支持两阶段原子性移动（失败自动回滚）、配置目标分类目录、键盘快捷键操作

---

## 系统架构

```
Pixiv 网站（浏览器）
    │
    │  油猴脚本
    │  ├── 单作品：Pixiv作品图片下载器（Java后端版）
    │  ├── 用户批量：Pixiv User 批量下载器
    │  └── N-Tab批量：Pixiv N-Tab 批量下载器
    │
    ▼  HTTP POST
┌──────────────────────────────────────────┐
│          Spring Boot 后端 :6999           │
│                                          │
│  /api/download/pixiv   ← 提交下载任务     │
│  /api/download/status  ← 查询下载状态     │
│  /api/sse/download     ← SSE 进度推送     │
│  /api/downloaded/*     ← 历史/图片查询    │
│  /api/pixiv/*          ← 代理 Pixiv API  │
│                                          │
│  DownloadService (异步)                  │
│    ├── 普通图片：直接下载保存              │
│    └── 动图：下载ZIP → ffmpeg → WebP     │
│                                          │
│  SQLite: pixiv_download.db               │
└──────────────────────────────────────────┘
    │
    ▼  HTTP 代理 127.0.0.1:7890
┌──────────┐
│ 代理软件  │  (Clash / v2rayN 等)
└──────────┘
    │
    ▼
i.pximg.net / www.pixiv.net
```

---

## 环境要求

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| Java | 17+ | 运行 Spring Boot 后端 |
| Maven | 3.6+ | 构建项目（或使用自带的 mvnw） |
| ffmpeg | 任意最新版 | 动图合成，需在系统 PATH 中可用 |
| 代理软件 | — | 监听 `127.0.0.1:7890`，用于访问 Pixiv CDN |
| 浏览器扩展 | Tampermonkey | 安装油猴脚本 |

---

## 安装与配置

### 1. 代理软件

后端硬编码通过 `127.0.0.1:7890` HTTP 代理访问 Pixiv。请确保你的代理软件（Clash、v2rayN 等）：

- 已正确配置可访问 Pixiv 的节点
- 本地 HTTP 代理监听端口为 **7890**

> 如需修改代理端口，编辑 `DownloadService.java` 和 `PixivProxyController.java` 中的代理配置：
> ```java
> HttpHost proxy = new HttpHost("127.0.0.1", 7890); // 修改此处端口
> ```

### 2. ffmpeg（动图支持）

下载 ffmpeg 并将其加入系统环境变量 `PATH`，使命令行可直接执行 `ffmpeg`。

**Windows 安装步骤：**

1. 前往 [ffmpeg 官网](https://ffmpeg.org/download.html) 下载 Windows 预编译版本（推荐 BtbN 或 gyan.dev 构建）
2. 解压到任意目录，例如 `C:\ffmpeg`
3. 将 `C:\ffmpeg\bin` 添加到系统环境变量 `PATH`
4. 打开新的命令行窗口，执行以下命令验证：
   ```bash
   ffmpeg -version
   ```

> 不安装 ffmpeg 不影响普通图片下载，仅动图（illustType=2）下载会失败。

### 3. 构建并运行后端

```bash
# 克隆项目
git clone <repository-url>
cd PixivDownload

# 使用 Maven Wrapper 构建（无需本机安装 Maven）
./mvnw clean package -DskipTests

# 运行
java -jar target/PixivDownload-0.0.1-SNAPSHOT.jar
```

或直接使用 IDE（IntelliJ IDEA、Eclipse）导入 Maven 项目后运行 `PixivDownloadApplication.java`。

后端启动后默认监听 `http://localhost:6999`。

**下载目录**默认为程序运行目录下的 `pixiv-download/` 文件夹，可在配置文件中修改（见[配置说明](#配置说明)）。

### 4. 安装油猴脚本

确保浏览器已安装 [Tampermonkey](https://www.tampermonkey.net/) 扩展，然后根据需要安装以下脚本：

| 脚本文件 | 适用场景 |
|---------|---------|
| `Pixiv作品图片下载器（Java后端版）.user.js` | 在单个作品页一键下载 |
| `Pixiv User 批量下载器 (N-Tab UI 风格版).user.js` | 在用户主页批量下载该用户全部作品 |
| `Pixiv N-Tab 批量下载器 (修复版).user.js` | 导入 N-Tab 书签 JSON 批量下载收藏 |

**安装方法：** 打开 Tampermonkey 管理面板 → 新建脚本 → 将 `.user.js` 文件内容粘贴进去保存，或直接将文件拖入 Tampermonkey 管理面板。

---

## 使用方法

### 单作品下载

1. 打开任意 Pixiv 作品页（`https://www.pixiv.net/artworks/{id}`）
2. 页面加载完成后，脚本会在页面中显示下载按钮
3. 点击下载，脚本自动提取图片 URL 并发送给后端
4. 动图（Ugoira）会自动检测并合成为 WebP

### 用户主页批量下载

1. 打开任意 Pixiv 用户主页（`https://www.pixiv.net/users/{userId}`）
2. 页面右侧/角落会出现悬浮控制面板
3. 面板功能：
   - **开始/暂停**：控制批量下载任务
   - **跳过已下载**：自动跳过后端数据库中已存在的作品
   - **仅 R18**：只下载 R18 作品
   - **下载间隔**：设置每个作品之间的等待秒数（默认 2 秒）
   - **并发数**：同时进行的下载任务数（默认 1，最多 5）
4. R18 作品会自动保存在 `{root}/{username}/r18/{artworkId}/` 目录下
5. 普通作品保存在 `{root}/{username}/{artworkId}/` 目录下

### N-Tab 书签批量下载

**前置步骤：** 从 N-Tab 扩展导出收藏列表为 JSON 文件。

1. 在 Pixiv 任意页面，点击 Tampermonkey 菜单中的 **「导入 N-Tab JSON」**
2. 粘贴 N-Tab 导出的 JSON 内容并确认
3. 悬浮控制面板出现后，点击 **开始** 开始批量下载
4. 支持跳过已下载、R18 过滤等选项（同用户批量下载）

### Web 批量下载页面

访问 `http://localhost:6999/pixiv-batch.html`

- 输入 Pixiv 用户 ID 和 Cookie，在网页端直接触发批量下载
- 后端通过代理 Pixiv API（`/api/pixiv/*`）获取作品列表，无需油猴脚本

**获取 Cookie 的方法：**

页面支持三种 Cookie 格式，推荐使用 **Cookie-Editor** 插件导出：

**方法一（推荐）：使用 Cookie-Editor 插件**

1. 安装 [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm) 浏览器扩展
2. 浏览器登录 Pixiv，打开任意 Pixiv 页面
3. 点击 Cookie-Editor 图标 → 点击右下角 **Export** → 选择 **Netscape** 格式复制
4. 在页面 Cookie 输入框上方切换格式为 **Netscape**，粘贴内容并保存

**方法二：从开发者工具复制 Header String**

1. 浏览器登录 Pixiv，打开开发者工具（F12）→ 网络（Network）
2. 刷新页面，找到任意 `www.pixiv.net` 的请求
3. 在请求头中复制 `Cookie` 字段的完整值
4. 在页面 Cookie 输入框上方保持格式为 **Header String**，粘贴并保存

> Cookie 仅保存在本地 `localStorage` 中，不会上传至任何服务器。

### 下载监控页面

访问 `http://localhost:6999/monitor.html`

- 实时显示当前下载任务进度
- 分页浏览全部下载历史
- 点击作品可预览缩略图
- 显示统计信息（总作品数、总图片数、已移动数）

### 图片分类工具（Web 版）

访问 `http://localhost:6999/classifier.html`

对已下载好的作品文件夹进行分类整理，功能与桌面版 `ImageClassifier.java` 一致。

> **浏览器要求**：需要支持 [File System Access API](https://developer.mozilla.org/en-US/docs/Web/API/File_System_API) 的浏览器，推荐 **Chrome / Edge** 最新版。Firefox 暂不支持。

**基本工作流：**

1. 点击 **「浏览」** 选择包含作品子文件夹的父目录（如 `pixiv-download/`）
2. 页面自动读取所有子文件夹，按数字顺序排列，并显示当前文件夹的 10 张缩略图预览
3. 在右侧分类列表或编号输入框中选择目标类别编号
4. 点击 **「分类整个文件夹」** 将当前文件夹的图片移至对应目标目录：
   - 单张图片：直接移入目标目录根目录
   - 多张图片：在目标目录下自动创建编号子文件夹（`0/`、`1/`、`2/`…）并移入
5. 移动完成后自动载入下一个文件夹；若源文件夹已空则自动删除
6. 点击 **「跳过此文件夹」** 可跳过当前文件夹（不移动任何文件）
7. 后端服务在线时，分类操作会自动通过 `/api/downloaded/move/{artworkId}` 向后端上报新路径

**目标文件夹配置（设置面板）：**

| 字段 | 说明 |
|------|------|
| 备注/名称 | 分类标签，显示在右侧列表和编号提示中 |
| 磁盘路径 | 文件实际移动目标的绝对路径，也用于向后端服务上报 |
| 绑定目录 | 为该条目授权浏览器实际读写权限（每次启动需重新绑定，不可跨会话持久化） |

**键盘快捷键：**

| 按键 | 功能 |
|------|------|
| `←` / `→` | 切换缩略图组 |
| `0`–`9` | 快速设置目标编号 |
| `Enter` | 执行分类 |
| `Space` | 跳过当前文件夹 |

**移动失败自动回滚：** 移动采用两阶段执行（先复制全部 → 再删除源文件），任一阶段失败时自动撤销已复制的文件，源文件夹内容保持不变。

---

## 配置说明

编辑 `src/main/resources/application.properties`：

```properties
# 服务端口
server.port=6999

# 图片保存根目录（相对路径或绝对路径）
download.root-folder=pixiv-download

# 每张图片下载后的等待时间（毫秒），防止请求过快
download.delay-ms=1000
```

---

## API 接口

所有接口前缀为 `http://localhost:6999`，支持跨域（CORS）。

### 下载管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/download/pixiv` | 提交下载任务 |
| `GET` | `/api/download/status` | 检查服务是否正常 |
| `GET` | `/api/download/status/{artworkId}` | 查询指定作品下载状态 |
| `GET` | `/api/download/status/active` | 获取所有进行中的任务 ID 列表 |
| `POST` | `/api/cancel/{artworkId}` | 取消下载任务 |
| `GET` | `/api/sse/download/{artworkId}` | SSE 实时进度推送 |

**提交下载任务请求体示例：**
```json
{
  "artworkId": 12345678,
  "title": "作品标题",
  "imageUrls": ["https://i.pximg.net/img-original/..."],
  "referer": "https://www.pixiv.net/artworks/12345678",
  "cookie": "PHPSESSID=...",
  "other": {
    "isUserDownload": false,
    "username": "",
    "isR18": false,
    "isUgoira": false,
    "ugoiraZipUrl": null,
    "ugoiraDelays": null
  }
}
```

### 下载历史查询

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/downloaded/{artworkId}` | 查询单个作品下载记录 |
| `POST` | `/api/downloaded/batch` | 批量查询（请求体为 ID 数组） |
| `GET` | `/api/downloaded/statistics` | 获取统计信息 |
| `GET` | `/api/downloaded/history` | 获取所有已下载 ID 列表 |
| `GET` | `/api/downloaded/history/paged?page=0&size=10` | 分页获取下载历史 |
| `GET` | `/api/downloaded/thumbnail/{artworkId}/{page}` | 获取缩略图（Base64） |
| `GET` | `/api/downloaded/image/{artworkId}/{page}` | 获取完整图片（Base64） |
| `GET` | `/api/downloaded/rawfile/{artworkId}/{page}` | 获取原始图片文件字节流 |
| `POST` | `/api/downloaded/move/{artworkId}` | 记录文件移动路径 |

### Pixiv API 代理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/pixiv/user/{userId}/artworks` | 获取用户全部作品 ID |
| `GET` | `/api/pixiv/user/{userId}/meta` | 获取用户名称 |
| `GET` | `/api/pixiv/artwork/{artworkId}/meta` | 获取作品基本信息 |
| `GET` | `/api/pixiv/artwork/{artworkId}/pages` | 获取作品所有分页图片 URL |
| `GET` | `/api/pixiv/artwork/{artworkId}/ugoira` | 获取动图 ZIP 地址和帧延迟 |

> 代理接口需通过请求头 `X-Pixiv-Cookie` 传入 Pixiv Cookie。

---

## 目录结构

```
PixivDownload/
├── src/main/java/top/sywyar/pixivdownload/
│   ├── config/CorsConfig.java              # 全局跨域配置
│   ├── download/
│   │   ├── DownloadService.java            # 核心下载逻辑（异步）
│   │   ├── DownloadStatus.java             # 下载状态模型
│   │   ├── DownloadProgressEvent.java      # SSE 进度事件
│   │   ├── config/
│   │   │   ├── AsyncConfig.java            # 启用异步
│   │   │   └── DownloadConfig.java         # rootFolder/delayMs 配置
│   │   ├── controller/
│   │   │   ├── DownloadController.java     # 下载 REST API
│   │   │   ├── PixivProxyController.java   # Pixiv API 代理
│   │   │   └── SSEController.java          # SSE 推送端点
│   │   ├── db/
│   │   │   ├── ArtworkRecord.java          # 数据库行记录（record）
│   │   │   ├── DatabaseConfig.java         # SQLite DataSource
│   │   │   └── PixivDatabase.java          # 所有数据库操作
│   │   ├── request/DownloadRequest.java    # 下载请求 DTO
│   │   └── response/                       # 各种响应 DTO
│   ├── imageclassifier/
│   │   ├── ImageClassifier.java            # 图片分类工具
│   │   └── ThumbnailManager.java           # 缩略图生成/缓存
│   ├── migration/
│   │   ├── JsonToSqliteMigration.java      # 旧 JSON 迁移工具
│   │   └── MigrationController.java        # 迁移触发接口
│   └── logback/MdcColorConverter.java      # 日志颜色扩展
├── src/main/resources/
│   ├── application.properties             # 配置文件
│   ├── logback.xml                        # 日志配置
│   └── static/
│       ├── monitor.html                   # 下载监控面板
│       ├── pixiv-batch.html               # Web 批量下载页面
│       └── classifier.html                # 图片分类工具（Web 版）
├── Pixiv作品图片下载器（Java后端版）.user.js  # 单作品油猴脚本
├── Pixiv User 批量下载器 (N-Tab UI 风格版).user.js  # 用户批量油猴脚本
├── Pixiv N-Tab 批量下载器 (修复版).user.js  # N-Tab 批量油猴脚本
└── pom.xml
```

**下载文件保存结构：**
```
pixiv-download/
├── pixiv_download.db          # SQLite 数据库
├── {artworkId}/               # 普通作品
│   ├── {artworkId}_p0.jpg
│   └── {artworkId}_p1.jpg
├── {username}/                # 用户批量下载
│   ├── {artworkId}/
│   │   └── {artworkId}_p0.png
│   └── r18/                   # R18 作品
│       └── {artworkId}/
│           └── {artworkId}_p0.jpg
└── {artworkId}/               # 动图
    ├── {artworkId}_p0.webp    # 合成的 WebP 动图
    └── {artworkId}_p0_thumb.jpg  # 第一帧缩略图
```

---

## 数据库结构

数据库文件位于 `{download.root-folder}/pixiv_download.db`，使用 WAL 模式。

**artworks 表：**

| 列名 | 类型 | 说明 |
|------|------|------|
| `artwork_id` | INTEGER PK | Pixiv 作品 ID |
| `title` | TEXT | 作品标题 |
| `folder` | TEXT | 下载目录绝对路径 |
| `count` | INTEGER | 图片数量 |
| `extensions` | TEXT | 文件扩展名（逗号分隔，如 `jpg` 或 `jpg,png`） |
| `time` | INTEGER UNIQUE | 下载时间戳（用于排序） |
| `moved` | INTEGER | 是否已移动（0/1） |
| `move_folder` | TEXT | 移动后的目录路径 |
| `move_time` | INTEGER | 移动时间戳 |

**statistics 表：**

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | INTEGER PK | 固定值 1 |
| `total_artworks` | INTEGER | 总作品数 |
| `total_images` | INTEGER | 总图片数 |
| `total_moved` | INTEGER | 已移动作品数 |

---

## 旧版数据迁移

如果之前使用过基于 JSON 文件的旧版本（`download_history.json`、`statistics.json`、`timeArtwork.json`），可通过以下接口将数据迁移至 SQLite：

**普通模式（返回 JSON 结果，无进度）：**
```bash
curl -X POST http://localhost:6999/api/migration/json-to-sqlite
```

**流式模式（实时显示进度，推荐数据量大时使用）：**
```bash
curl -N http://localhost:6999/api/migration/json-to-sqlite/stream
```

`-N` 参数禁用 curl 缓冲，使进度信息实时输出。示例输出：

```
data: 共找到 3500 条记录，开始迁移...
data: 进度: 100/3500 (2%)，已迁移 98 条，跳过 2 条
data: 进度: 200/3500 (5%)，已迁移 195 条，跳过 5 条
...
data: 迁移完成：成功迁移 3450 条，跳过 50 条
```

两个接口均为幂等操作，可多次调用，不会产生重复数据。
