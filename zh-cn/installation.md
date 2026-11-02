# 安装与启动

从 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 选择与环境匹配的发行包。下方文件名中的 `*` 或 `x.x.x` 表示版本号；命令中的 `APP.jar` 请替换为实际主程序 JAR 名称，并在完整解压目录内执行。

| 使用场景 | 发行包 | 额外准备 |
| --- | --- | --- |
| Windows x64 桌面 | `*-win-x64-setup.exe` | 内置 JRE；按需安装 FFmpeg |
| Windows / Linux / macOS 运行 Java | `*-java.zip` | Java 17+；按需安装 FFmpeg |
| 预置可选数据库、导入与验证码等官方插件 | `*-full-offline.zip` | Java 17+；按需安装 FFmpeg |
| Docker 常驻服务 | 使用仓库 `Dockerfile` 与完整 Java 分发目录构建 | Docker Engine、Compose、可写挂载目录 |

Java 标准包与 Windows 安装包预置默认官方插件集合；离线全量包额外包含 `database-postgresql`、`pixiv-batch-downloader-import` 等按需官方插件。两种 Java 包均不含 JRE、FFmpeg 或第三方 `douyin` 插件；“离线全量”指预置官方可选插件，访问 Pixiv、在线 AI 或在线 TTS 仍需网络。

## 方式一：Java 标准包 / 离线全量包

1. 安装 Java 17 或更高版本（Windows 可从 [Adoptium](https://adoptium.net/) 下载，macOS 可用 `brew install openjdk@17`，Debian/Ubuntu 可用 `sudo apt install openjdk-17-jdk`，Fedora 可用 `sudo dnf install java-17-openjdk`），并验证版本：

   ```bash
   java -version
   ```

2. 下载 `PixivDownload-*-java.zip` 或 `PixivDownload-*-full-offline.zip` 并**完整解压**。必须保留同目录的启动脚本与 `plugins/`，程序启动时从工作目录的 `plugins/` 加载外置插件。

   ?> 单独的 `PixivDownload-*.jar` 是不含外置插件的核心壳构建输入，并非普通用户发行包；直接运行不会预置下载工作台或数据库插件。

3. 启动程序：Windows 执行 `run.bat`，Linux / macOS 在解压目录执行 `sh run.sh`，或手动运行：

   ```text
   java -Dfile.encoding=UTF-8 -jar APP.jar
   ```

   启动脚本已内置 `-Dfile.encoding=UTF-8`；手动启动时请保留该参数，避免中文 Windows 控制台乱码。

4. 按[首次配置](/zh-cn/first-setup)创建管理员账号并配置代理。

### 无桌面服务器（`--no-gui`）

服务器上没有桌面引导，且 `setup.html` 仅允许本机访问；未初始化直接以 `--no-gui` 启动会以退出码 `78` 停止。首次启动前先在同目录执行 CLI 初始化：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

需要在自动化脚本中非交互初始化时，可显式传入参数（请将密码和代理替换为实际值；命令行密码会留在 Shell 历史或进程列表中，日常操作优先使用交互模式）：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

无需代理时改传 `--proxy-enabled=false`。Linux / macOS 后台运行示例：

```bash
nohup java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui > app.log 2>&1 &
```

后端代理适用于采用宿主或任务路由的请求（Pixiv 下载、在线更新、FFmpeg 下载和部分在线 TTS）；浏览器直连、SMTP 与数据库连接使用各自路径，详见[网络访问与第三方服务](/zh-cn/network-access)。

## 方式二：Windows 安装包

1. 运行 `PixivDownload-x.x.x-win-x64-setup.exe`，选择安装语言（支持简中、繁中、英、日、韩）与安装目录。
2. 按需勾选「下载并安装 FFmpeg」。普通静态图片无需 FFmpeg，安装后也可在桌面「状态」页下载。
3. 完成安装后启动程序，或从开始菜单、桌面快捷方式启动。

安装前若检测到 `PixivDownload.exe` 仍在运行，安装器会提示先关闭后重试。再次运行安装器可进入修复、更改组件或卸载。安装器、已安装应用和便携启动器默认请求管理员权限；宿主实际以高权限运行时，`host-process-full-trust` 插件会继承同等权限，插件管理页会持续提示该状态。

## 方式三：Docker 部署

仓库 `Dockerfile` 使用已签名的完整 Java 分发目录构建镜像（镜像内含 Java 17、FFmpeg 和 curl）。

1. 将 `*-java.zip` 或 `*-full-offline.zip` 完整解压到仓库 `build/dist/default-downloader/`，确认该目录直接包含主程序 JAR、`plugins/`、`plugins-manifest.json` 和 `SHA256SUMS`，随后构建镜像：

   ```bash
   docker compose build
   ```

2. 容器以 UID / GID `10001` 运行。Linux 新建部署时，先在含 `docker-compose.yml` 的目录创建可写挂载目录：

   ```bash
   sudo install -d -o 10001 -g 10001 config state data pixiv-download log
   ```

3. 执行首次初始化，再启动常驻容器：

   ```bash
   docker compose run --rm app --setup --mode=solo
   docker compose up -d
   docker compose logs -f app
   ```

!> 未完成 `--setup` 直接 `docker compose up -d` 会以退出码 `78` 反复重启。

默认端口只绑定宿主机 `127.0.0.1:6999`，在本机浏览器打开 `http://127.0.0.1:6999/`；远程访问请使用 SSH 隧道或反向代理。停止服务执行 `docker compose down`，保留数据时不要附加删除卷的 `-v`。

### 容器内代理与持久化目录

容器内的 `127.0.0.1` 指向容器自身。`docker-compose.yml` 已将 `host.docker.internal` 映射到宿主机网关；复用宿主机代理时，在 `--setup` 或 `config/config.yaml` 中将代理主机设为 `host.docker.internal`（并确认宿主机代理允许来自容器网段的连接）：

```bash
docker compose run --rm app --setup --mode=solo --proxy-host=host.docker.internal --proxy-port=7890
```

修改挂载的 `config/config.yaml` 后执行 `docker compose restart app` 生效；若修改 `server.port`，需同步调整 `docker-compose.yml` 的端口映射与健康检查地址。

| 宿主挂载或卷 | 持久化内容 |
| --- | --- |
| `./config/` | `config.yaml`、插件业务配置与加密凭据 |
| `./state/` | 登录态、`setup_config.json`、队列断点、实例锁与 SQLite 原生库解压目录 |
| `./data/` | SQLite 数据库、收藏夹图标、缩略图与 TTS 缓存、回填状态、参考音 |
| `./pixiv-download/` | 下载的插画、漫画、动图、小说与临时归档 `_archives` |
| `./log/` | 运行日志 |
| 命名卷 `plugins` | 已安装插件与 provenance |

`state/` 与 `state/instance/` 必须可写且允许加载 SQLite 原生库；容器根文件系统保持只读，`/tmp` 保持 `noexec,nosuid,nodev`。更新镜像不会自动覆盖已有 `plugins` 卷，重建容器后请在插件管理页核对插件版本与兼容状态。

## FFmpeg 与油猴脚本（可选）

- **FFmpeg**：动图（Ugoira）转 WebP / GIF / APNG / MP4 及媒体处理需要 FFmpeg（MP4 需 H.264 编码器）。可在 Windows 安装时勾选，或在桌面 GUI「状态」页点击「下载 FFmpeg」（从项目 `ffmpeg-stable` Release 获取经过签名校验的 Windows x64、Linux x64/arm64、macOS x64/arm64 资产）；也可从 [FFmpeg 官网](https://ffmpeg.org/download.html)安装并加入系统 `PATH`，用 `ffmpeg -version` 验证。
- **油猴脚本**：不安装脚本也可在 `pixiv-batch.html` 完成批量下载。如需在 Pixiv 页面直接操作，启动后端后从下载页「油猴脚本」入口安装，或从源码仓库安装独立 `.user.js`；非本机部署需同步设置脚本中的后端地址与 `@connect`，详见[油猴脚本](/zh-cn/userscripts)。

## 验证安装与插件说明

完成[首次配置](/zh-cn/first-setup)后，登录 `http://localhost:6999/` 并按[第一次下载](/zh-cn/first-download)下载一件作品，确认磁盘文件生成且画廊可打开该作品。`GET /actuator/health` 返回 `{"status":"UP"}` 仅代表 HTTP 服务已启动，不代表插件、数据库、代理和 Pixiv Cookie 均已就绪。

常用入口：

- `http://localhost:6999/`：默认跳转到活动下载页
- `http://localhost:6999/setup.html`：本机首次配置页（初始化后自动重定向）
- `http://localhost:6999/pixiv-batch.html`：下载工作台（由默认安装的 `download-workbench` 提供）
- `http://localhost:6999/pixiv-gallery.html`：作品画廊（由默认安装的 `gallery` 提供）
- `http://localhost:6999/monitor.html`：下载监控页

默认安装的官方插件包括 `download-workbench`、`database-sqlite`、`gui-compose`（默认桌面界面）、`gui-swing`（后备桌面界面）、`gallery`、`gallery-tools`、`novel`、`notification`、`push`、`mail`、`tts`、`ai`、`posthog`、`multi-mode-decision-survey` 等；`database-postgresql`、`pixiv-batch-downloader-import`、`page-editor`、`captcha`、`translation` 等可按需从插件市场或离线全量包获取。当前选定的主数据库插件不可用时会进入恢复模式，只开放登录、插件管理与修复入口；可选插件停用或缺失仅撤回其所属页面与能力，详见[插件管理](/zh-cn/plugin-management)与[存储与备份](/zh-cn/storage)。
