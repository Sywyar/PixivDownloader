# 安装指南

## 环境要求

| 依赖 | 最低版本 | 说明 |
|------|----------|------|
| **Java** | 17+ | JAR 包运行必需；Windows 安装包已内置 JRE |
| **操作系统** | Windows / macOS / Linux | 跨平台支持 |
| **Tampermonkey** | 最新版 | 如需使用油猴脚本 |
| **ffmpeg** | 任意 | Ugoira 动图转 WebP 所需（可选） |

---

## 方式一：JAR 包（跨平台）

### 1. 安装 Java 17+

- **Windows**: 从 [Adoptium](https://adoptium.net/) 下载安装
- **macOS**: `brew install openjdk@17`
- **Linux**: `sudo apt install openjdk-17-jdk`（Debian/Ubuntu）或 `sudo dnf install java-17-openjdk`（Fedora）

验证安装：

```bash
java -version
# 应输出类似：openjdk version "17.0.x" ...
```

### 2. 下载 JAR

从 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下载 `PixivDownload-vX.X.X.jar`。

### 3. 启动

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar
```

> [!IMPORTANT]
> 务必添加 `-Dfile.encoding=UTF-8` 参数，否则在中文 Windows 下可能出现乱码。

### 4. 后台运行（服务器/Docker）

```bash
# 无 GUI 模式（适合 headless 服务器）
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui

# 使用 nohup 后台运行
nohup java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui > app.log 2>&1 &
```

> [!IMPORTANT]
> **无头/`--no-gui` 模式下首次启动必须先完成 setup**：服务器上没有 GUI 引导、`setup.html` 又只允许本机访问，因此从 v1.10.0 起，未完成首次初始化时 `--no-gui` 启动会被中止并提示使用 CLI 命令。请先执行：
>
> ```bash
> # 交互式：依次输入用户名、密码、运行模式 (solo|multi)，并配置 HTTP 代理（是否启用、主机、端口）
> java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup
>
> # 或一行非交互（密码会出现在 shell 历史/进程列表，仅用于自动化脚本）
> java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
>     --username=admin --password='YourPassword123' --mode=solo \
>     --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
> ```
>
> 代理用于后端的全部对外访问（访问 Pixiv 与下载作品、在线更新、下载内置 FFmpeg、在线 TTS）；省略 `--proxy-*` 时会交互式询问，无需代理可加 `--proxy-enabled=false`。后续可用 `--change-password` 修改密码、`--reset-password` 在忘记密码时重置密码。详见 [使用指南 → 启动参数](zh-Usage-Guide)。

---

## 方式二：Windows 安装包（推荐 Windows 用户）

### 1. 下载并运行安装器

从 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下载 `PixivDownload-x.x.x-win-x64-setup.exe`。

### 2. 安装过程

1. 选择安装语言（中文/English）
2. 选择安装目录
3. **可选安装任务**：勾选「下载并安装 FFmpeg」
   - FFmpeg 用于 Ugoira 动图转 WebP
   - 不勾选不影响普通图片下载
   - 安装后也可在 GUI 状态页重新下载

### 3. 维护模式

安装完成后再次运行安装器可进入维护模式，支持：
- **修复** — 重新安装程序文件
- **更改** — 修改安装组件
- **卸载** — 完整卸载程序

### 4. 启动

安装完成后自动启动，也可从开始菜单或桌面快捷方式启动。

> [!NOTE]
> 安装器支持语言选择，并在安装前会检测 `PixivDownload.exe` 是否仍在运行，提示关闭后重试。

---

## 方式三：Docker（服务器常驻）

仓库根目录已提供 `Dockerfile`（multi-stage 构建，运行镜像内置动图所需的 ffmpeg）与 `docker-compose.yml`。

### 1. 环境要求

- Docker 20.10+ 与 Docker Compose（`docker compose` 命令）。

### 2. 首次初始化（务必先做）

容器内没有桌面 GUI、`setup.html` 又只允许本机访问，因此首次初始化只能用 CLI `--setup`：

```bash
# 在仓库根目录（含 Dockerfile / docker-compose.yml）执行
docker compose run --rm app --setup
# 交互式依次输入：用户名、密码、运行模式 (solo|multi)，并配置 HTTP 代理（是否启用、主机、端口）
# 账号写入 state/setup_config.json，代理写入 config/config.yaml
```

> [!WARNING]
> **请勿跳过这一步直接 `up`。** 未完成初始化时容器会以退出码 78 反复重启（日志会打印需运行 `--setup` 的提示）。

### 3. 常驻运行

```bash
docker compose up -d         # 后台常驻
docker compose logs -f app   # 查看日志
docker compose down          # 停止
```

启动后浏览器访问 `http://<宿主IP>:6999/`。登录、监控、画廊等页面均通过会话鉴权远程可用；setup 向导与桌面 GUI 在容器内不可用（如需改配置见下）。

### 4. 代理配置（关键）

本应用的全部对外访问都经此代理：访问 Pixiv 与下载作品、在线更新、下载内置 FFmpeg、在线 TTS。`config.yaml` 默认 `proxy.host: 127.0.0.1`，在容器内指向容器自身、不可达。`docker-compose.yml` 已声明 `host.docker.internal -> 宿主网关`。

推荐在上面第 2 步 `--setup` 时直接配置代理（无需事后编辑文件）：

```bash
# 复用宿主机上运行的代理（端口沿用，如 7890）
docker compose run --rm app --setup --proxy-host=host.docker.internal --proxy-port=7890
# 若无需代理（已有其它出网路径），加：
#   --proxy-enabled=false
```

也可初始化后编辑挂载出的 `config/config.yaml`：

```yaml
proxy.host: host.docker.internal   # 复用宿主机上运行的代理（端口沿用，如 7890）
# 若无需代理（已有其它出网路径）：
# proxy.enabled: false
```

改完执行 `docker compose restart app` 生效。

### 5. 数据持久化

`docker-compose.yml` 已将下列目录挂载到宿主，重启/重建容器数据不丢：

| 宿主路径 | 用途 |
|----------|------|
| `./config/` | 运行时配置 `config.yaml` |
| `./state/` | 登录态、`setup_config.json`、批量下载状态 |
| `./data/` | SQLite 数据库 `pixiv_download.db` |
| `./pixiv-download/` | 下载的作品/小说/系列文件（含临时打包 `_archives`） |
| `./collection_icons/` | 用户自定义收藏夹图标 |
| `./_gui/`、`./_tts/`、`./log/` | 引导标记、TTS 缓存、运行日志（可选） |

### 6. 健康检查

镜像与 compose 均配置了健康探针，指向公开的 actuator 端点：

- `GET /actuator/health` — 返回 `{"status":"UP"}`（无需登录，不泄露内部明细）。
- `GET /actuator/info` — 返回应用名称与版本。

`docker compose ps` 的 `STATUS` 列会显示 `healthy`/`unhealthy`。

> [!NOTE]
> 修改端口、代理、SSL 等需编辑 `config/config.yaml` 后 `docker compose restart app`；若改了 `server.port`，需同步调整 compose 的端口映射与健康检查地址。

---

## 安装油猴脚本（可选）

> [!TIP]
> 推荐优先使用 Web 端 `pixiv-batch.html`，无需安装脚本即可完成批量下载。

### 方式一：通过 Web 管理页一键安装（推荐）

1. 启动 PixivDownloader 后端
2. 访问 `http://localhost:6999/pixiv-batch.html`
3. 点击页面顶部 「🧩 油猴脚本」卡片展开
4. 点击对应脚本的「⬇ 安装」按钮

> [!WARNING]
> 通过 Web 端安装时，脚本更新检查会指向当前后端。当后端地址变更时，需要重新安装或手动修改脚本头部的 `@connect`。

### 方式二：从 Release 下载

从 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下载脚本文件，拖入 Tampermonkey 管理面板安装。

Release 附件中的脚本：

| 脚本文件 | 说明 |
|----------|------|
| `Pixiv All-in-One.user.js` | 整合包（推荐），包含页面批量、User 批量、URL 批量导入、单作品下载（Java 后端版）、体验增强工具箱 |
| `Pixiv 单作品图片下载器(Local Download).user.js` | 浏览器本地下载，无需 Java 后端 |

### 完整脚本列表

| 脚本名称 | 功能 | 获取方式 |
|----------|------|----------|
| 页面批量下载器 (Page Scrape) | 从 Pixiv 页面 DOM 抓取作品 | Web 端 / GitHub 代码区 |
| User 批量下载器 (User Batch) | 从用户主页批量下载 | Web 端 / GitHub 代码区 |
| URL 批量导入单作品下载器 (URL Batch) | 批量导入作品 URL | Web 端 / GitHub 代码区 |
| 单作品图片下载器 (Java后端版) | 单作品页通过后端下载 | Web 端 / GitHub 代码区 |
| 单作品图片下载器 (Local Download) | 浏览器本地下载，无需后端 | Release / Web 端 / GitHub 代码区 |
| 体验增强工具箱 (Toolbox) | 已下载作品标记、Cookie 导入等 | Web 端 / GitHub 代码区 |

### 非 localhost 部署的额外配置

<details>
<summary><strong>点击展开</strong></summary>

Tampermonkey 的 `@connect` 白名单默认只允许连接 `localhost`。如果后端部署在其他机器：

1. 打开 Tampermonkey 管理面板 → 找到对应脚本 → 点击编辑
2. 将脚本头部的 `// @connect      YOUR_SERVER_HOST` 替换为实际地址
3. 保存脚本（Ctrl+S）

如果通过 Web 端 `pixiv-batch.html` 安装，`@connect` 会自动替换为当前后端地址。

</details>

---

## 安装 FFmpeg（可选）

FFmpeg 用于 Ugoira 动图转换为 WebP，普通图片下载不需要。

### 自动安装（推荐）

- **Windows 安装包**：安装时勾选「下载并安装 FFmpeg」
- **GUI 工具**：启动后在 GUI「状态」标签页点击「下载 FFmpeg」按钮

### 手动安装

1. 从 [FFmpeg 官网](https://ffmpeg.org/download.html) 下载
2. 将 `ffmpeg.exe` 所在目录添加到系统 `PATH` 环境变量
3. 验证：`ffmpeg -version`

---

## 目录结构

首次启动后，程序会在工作目录生成以下文件：

```
工作目录/
├── config/                      # 运行时配置目录
│   ├── config.yaml              # 主配置文件
│   └── image_classifier.properties  # 图片分类器配置
├── state/                       # 运行状态目录
│   ├── setup_config.json        # 账号/初始化配置
│   └── batch_state.json         # 批量下载状态
├── data/                        # 数据库目录
│   └── pixiv_download.db        # SQLite 数据库
├── pixiv-download/              # 下载文件存储（默认根目录）
│   ├── artwork-{id}/            # 作品目录
│   ├── novel-{id}/              # 小说目录
│   ├── novel-series-{id}/       # 小说系列
│   └── ...
├── log/                         # 运行日志
├── collection_icons/            # 用户自定义收藏夹图标
├── _gui/                        # GUI 引导状态（onboarding 标记）
└── _tts/                        # TTS 缓存（可选）
```

---

## 验证安装

启动后在浏览器访问：

- `http://localhost:6999/` — 自动跳转到下载页
- `http://localhost:6999/setup.html` — 首次配置向导（如已完成则重定向）
- `http://localhost:6999/intro.html` — 产品介绍页
- `http://localhost:6999/pixiv-batch.html` — 批量下载页
- `http://localhost:6999/monitor.html` — 下载监控页
- `http://localhost:6999/pixiv-gallery.html` — 作品画廊
