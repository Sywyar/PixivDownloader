# 开发指南

## 环境准备

### 必需软件

| 工具 | 版本要求 | 说明 |
|------|----------|------|
| **JDK** | 17 | 编译和运行 |
| **Maven** | 3.9+ | 或使用项目自带的 `mvnw` / `mvnw.cmd` |
| **Git** | 任意 | 版本控制 |
| **PowerShell** | 5.1+ | Windows 打包脚本（仅构建安装包时需要） |
| **Inno Setup 6** | 6.x | Windows 安装包构建（可选） |

### 可选工具

| 工具 | 说明 |
|------|------|
| **IntelliJ IDEA** | 推荐 IDE |
| **VS Code** | 前端资源编辑 |
| **Docker** | 容器化部署 |

---

## 项目结构

```
PixivDownloader/
├── src/main/java/top/sywyar/pixivdownload/
│   ├── ai/             # AI/大模型调用（翻译、连通性探测、多角色朗读等）
│   ├── author/         # 作者元数据持久化
│   ├── cli/            # 启动参数校验与 CLI 管理命令（--setup / --change-password / --reset-password / --help）
│   ├── collection/     # 收藏夹管理
│   ├── common/         # 通用工具类
│   ├── config/         # 应用配置生成与绑定
│   ├── download/       # 核心下载逻辑
│   │   └── db/         # SQLite 数据库访问 (MyBatis)
│   ├── duplicate/      # 疑似重复图片检测
│   ├── ffmpeg/         # FFmpeg 定位与安装
│   ├── gallery/        # 画廊 API
│   ├── gui/            # Swing GUI 桌面管家
│   ├── i18n/           # 国际化
│   ├── imageclassifier/# 图片分类工具
│   ├── logback/        # 自定义日志格式
│   ├── mail/           # 邮件通知（SMTP）
│   ├── maintenance/    # 维护任务框架
│   ├── migration/      # JSON → SQLite 迁移
│   ├── novel/          # 小说下载管线
│   ├── onboarding/     # GUI 引导向导
│   ├── push/           # 推送通知（多通道）
│   ├── quota/          # 配额与限流
│   ├── schedule/       # 计划任务调度
│   ├── scripts/        # 油猴脚本分发
│   ├── series/         # 系列元数据
│   ├── setup/          # 首次配置与鉴权
│   │   └── guest/      # 访客邀请系统
│   ├── stats/          # 统计仪表盘
│   ├── tts/            # 在线 TTS（语音合成）
│   ├── tools/          # CLI 工具
│   └── update/         # 在线更新
├── src/main/resources/
│   ├── static/         # Web 前端资源 (110+ 文件)
│   ├── i18n/           # 国际化资源文件
│   └── application.properties
├── scripts/            # 构建与打包脚本
├── packaging/windows/inno/  # Inno Setup 安装器配置
├── config/             # 默认配置
├── collection_icons/   # 收藏夹图标
└── pom.xml             # Maven 构建配置
```

---

## Fork 与分支

1. **Fork 仓库**：访问 [GitHub 仓库](https://github.com/Sywyar/PixivDownloader)，点击 Fork
2. **克隆 Fork**：
   ```bash
   git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
   cd PixivDownloader
   ```
3. **添加上游仓库**：
   ```bash
   git remote add upstream https://github.com/Sywyar/PixivDownloader.git
   git fetch upstream
   ```
4. **创建功能分支**：
   ```bash
   git checkout -b feat/your-change upstream/master
   ```

---

## 本地开发

### 构建

```powershell
# Windows PowerShell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\mvnw.cmd package -DskipTests
```

```bash
# macOS / Linux
JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8' ./mvnw package -DskipTests
```

### 运行

```bash
java -Dfile.encoding=UTF-8 -jar target/PixivDownload-*.jar
```

### 运行测试

```powershell
# Windows PowerShell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\mvnw.cmd test
```

```bash
# macOS / Linux
JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8' ./mvnw test

# 运行单个测试类
./mvnw test -Dtest=PixivDownloadApplicationTests
```

> [!IMPORTANT]
> 务必添加 `-Dfile.encoding=UTF-8`，否则在中文 Windows 下可能出现测试乱码。

### 油猴脚本开发注意事项

`pixiv-batch.html` 的油猴脚本安装卡片通过 `/api/scripts` 读取内置脚本列表。脚本通过两个来源装配：
- 仓库根目录的独立 `*.user.js` 文件
- `scripts/build-userscript-bundle.ps1` 生成的 `build/generated-userscripts/Pixiv All-in-One.user.js`

`pom.xml` 在 `generate-resources` 阶段把两者复制到 `target/classes/static/userscripts`。因此改动油猴脚本后，必须执行至少一次 Maven 生命周期（推荐 `package`），不要只依赖 IDE 直接运行。

---

## 本地构建 Windows 包

> [!NOTE]
> Release 流程不再发布 portable zip 包。以下命令仅用于本地调试或自用。

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'

# 仅生成便携版（包含 PixivDownload.exe），不构建安装包
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipInstaller

# 生成完整 Windows 产物
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local

# 打包前顺便执行测试
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -RunTests
```

### 常用参数

| 参数 | 说明 |
|------|------|
| `-Version` | 版本号（如 `0.0.1-local`） |
| `-SkipPortable` | 跳过在线便携版 |
| `-SkipOfflinePortable` | 跳过内置 FFmpeg 的离线便携版 |
| `-SkipInstaller` | 跳过 Inno Setup 安装包，只保留便携版产物 |
| `-RedownloadFfmpeg` | 重新下载 FFmpeg 载荷 |
| `-RunTests` | 打包前先执行测试 |
| `-PrebuiltJar` | 使用已构建好的 JAR（跳过 Maven 构建） |

### 打包流程

1. 生成 `Pixiv All-in-One.user.js` 整合脚本
2. Maven `package`（或使用预构建 JAR）
3. `jlink` 生成精简 JRE（21 个模块）
4. `jpackage` 生成 app-image（含 `PixivDownload.exe`）
5. 在线便携 zip
6. 离线便携 zip（含内置 FFmpeg）
7. Inno Setup 安装包

产物输出到 `build/out/`。

### Inno Setup 安装

构建安装包需安装 [Inno Setup 6](https://jrsoftware.org/isdl.php)。脚本优先查找 `Inno Setup 6\ISCC.exe` 默认安装目录，再从 `PATH` 查找。

---

## 提交与 PR 流程

### 提交前检查

1. **同步上游**：
   ```bash
   git fetch upstream
   git rebase upstream/master
   ```

2. **运行测试**：
   ```bash
   ./mvnw test
   ```

3. **如涉及油猴脚本或静态资源**，执行 `package` 并在 `http://localhost:6999/pixiv-batch.html` 验证脚本列表和安装链接。

4. **检查变更**：
   ```bash
   git diff --staged
   ```

### 提交规范

- 使用清晰的 commit message
- 不要提交 `target/`、`build/` 等构建产物

### 发起 PR

1. 推送分支到你的 Fork
2. 在 GitHub 上向上游 `main` 分支发起 Pull Request
3. PR 描述应包含：
   - 变更动机
   - 主要改动
   - 验证步骤
   - 如涉及 UI/打包，附上截图或关键输出

---

## CI/CD

### Release 流程

推送 `v*` 标签（如 `v1.8.3`）触发自动构建和发布：

1. **build-jar** (Ubuntu)：Maven 打包 JAR + 油猴脚本
2. **build-windows-installer** (Windows)：构建安装包
3. **release** (Ubuntu)：汇总产物，生成更新清单，创建 GitHub Release

### 手动创建草稿 Release

通过 GitHub Actions 的 `workflow_dispatch` 手动触发 `create-draft-release`，指定标签名创建草稿 Release。

### GitHub Pages

推送 `v*` 标签时自动将 `src/main/resources/static/` 部署到 GitHub Pages，提供静态预览。

---

## 代码规范

### 通用规范

- 沿用项目已有的 Spring Boot 模式
- 优先使用构造器注入
- 公开 HTTP API 使用显式 DTO 类
- 异常处理通过 `@RestControllerAdvice` / `@ExceptionHandler` 集中处理
- Lombok 合理使用，纯 Java 写法更清晰时不强制

### 关键不变量

修改后端行为时必须保留：

- **构造 URL 时动态拼装**：scheme 从 `server.ssl.enabled` 推导，主机名从 `ssl.domain` 推导
- `DownloadService.validatePixivUrl()` 必须拒绝非 Pixiv URL
- Solo 与 Multi 模式行为必须区分
- 限流仅作用于 Multi 模式访客，不作用于 Solo 用户或已登录管理员
- 登录暴力破解保护是例外，在所有模式下对 `/api/auth/login` 生效
- 下载后收藏 (bookmark) 是 best-effort，不能导致已完成下载失败
- 动图转换依赖 `PATH` 上的 ffmpeg

### Web 页面规范

- 所有面向用户字符串走 i18n 流水线
- 所有新页面支持深色模式（使用 CSS 变量方案）
- HTML、CSS、JavaScript 拆分为独立文件
- 复用已有 CSS 变量（`--bg`、`--surface`、`--line`、`--text`、`--muted`、`--brand` 等）

### 数据库 Schema

每次修改 Mapper 中的 `CREATE TABLE` 语句时，必须同步更新 `ManagedDatabaseSchema.createSpec()`，否则启动时漂移检查会产生虚假告警。

---

## 技术栈

| 技术 | 用途 |
|------|------|
| Spring Boot 3.5.7 | 后端框架 |
| MyBatis 3.0.4 | ORM |
| SQLite 3.47.1 | 本地数据库 (WAL 模式) |
| Apache HttpClient5 | HTTP 客户端 |
| Lombok | 减少样板代码 |
| FlatLaf 3.5.4 | Swing L&F |
| Spring Security Crypto | 密码安全 |
| Maven Wrapper | 构建工具 |
| jlink / jpackage | JRE 精简与原生打包 |
| Inno Setup 6 | Windows 安装包 |
| Bootstrap + Chart.js | Web 前端 |
