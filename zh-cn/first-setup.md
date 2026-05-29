# 首次配置

?> 仅首次启动时需要完成此步骤。配置保存后，之后启动会直接进入主界面。

## 配置入口速查

| 启动方式 | 配置入口 |
|---------|---------|
| 桌面 GUI（默认） | GUI「首页」引导向导 |
| 本机浏览器 + `--no-gui` 启动 | 自动打开 `http://localhost:6999/setup.html` |
| 服务器 / Docker（无桌面） | 命令行 `--setup` 参数 |

---

## 方式一：GUI 引导向导（推荐桌面用户）

安装并启动 PixivDownloader 后，GUI 主窗口会停留在「首页」标签，引导向导共 7 步，按提示操作即可。

### 第 1 步：等待服务就绪

首页第一屏显示后端启动状态（实时呼吸灯指示）。

等待状态变为「**运行中**」（通常 5–15 秒）后，点「下一步」继续。

### 第 2 步：设置管理员账号

填写管理员**用户名**和**密码**（密码至少 6 位）。

?> 这是登录 PixivDownloader 网页界面用的账号，**与 Pixiv 账号无关**，可以随便起名。

### 第 3 步：选择运行模式

| 模式 | 适用场景 | 特点 |
|------|---------|------|
| **自用模式 (Solo)** | 自己一人使用 | 需登录，下载设置保存在服务器 |
| **多人模式 (Multi)** | 与他人共用一台服务器 | 访客无需登录，支持配额和限流 |

!> 个人用户几乎都应该选「**自用模式 (Solo)**」。

### 第 4 步：配置 HTTP 代理

PixivDownloader 后端的所有对外请求（下载图片、更新检查、TTS 朗读）都要走这个代理。

- **有代理工具**（Clash、V2Ray 等）：填入代理地址，常见配置：
  - 主机：`127.0.0.1`
  - 端口：`7890`（Clash 默认）
- **网络可直连 Pixiv**（非常少见）：关闭代理即可

?> 代理配置支持热重载，之后可以随时在 GUI「配置」页修改，无需重启。

### 第 5–7 步：引导说明

向导会引导你打开浏览器下载页、查看画廊等，按需操作即可。

走到最后「完成」页后，GUI「首页」标签会自动隐藏，下次启动直接进入「状态」页，引导不再重复运行。

?> 如需重新走一遍向导，删除 `state/gui/` 下的 `wizard-progress` 与 `wizard-finished` 文件即可。

---

## 方式二：浏览器 Setup 向导

适用于以 `--no-gui` 启动、但本机有浏览器的场景（例如只是想省内存、不想打开 GUI 窗口）。

1. 启动服务：`java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui`
2. 浏览器会自动打开 `http://localhost:6999/setup.html`
3. 按页面提示填写账号、选择模式、配置代理，点「完成配置」

!> `setup.html` **只允许本机浏览器**访问，在远程电脑的浏览器里无法打开。

---

## 方式三：CLI 命令（服务器 / Docker）

适用于没有图形界面或桌面浏览器的服务器 / Docker 环境。

### 交互式初始化（推荐）

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup
```

依次输入（密码输入时不显示字符）：
1. 管理员用户名
2. 密码（至少 6 位）
3. 确认密码
4. 运行模式（输入 `solo` 或 `multi`）
5. 是否启用 HTTP 代理（`y`/`n`）
6. 代理主机和端口（选 `y` 时填写）

初始化完成后正常启动服务：

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui
```

### 非交互式（自动化脚本）

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
    --username=admin \
    --password='YourPassword' \
    --mode=solo \
    --proxy-enabled=true \
    --proxy-host=127.0.0.1 \
    --proxy-port=7890
```

!> 密码会出现在 shell 历史和进程列表，只建议用于自动化环境。

### Docker 场景

```bash
# 先初始化
docker compose run --rm app --setup

# 再常驻运行
docker compose up -d
```

初始化完成前不要直接 `docker compose up`，否则容器会因检测到未初始化而以退出码 78 反复重启。

---

## 配置完成后

访问 `http://localhost:6999/pixiv-batch.html` 即可开始使用。

接下来推荐阅读 [第一次下载](/zh-cn/first-download) 了解基本下载流程。

---

## 后续管理

### 修改密码

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --change-password
```

### 忘记密码

先停止正在运行的服务，再执行（不需要旧密码）：

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --reset-password
```

重置成功后所有现存会话都会失效，用新密码重新登录即可。

### 重新初始化

停止服务后删除 `state/setup_config.json`，再重新运行 `--setup` 或在 GUI 走引导向导。

?> 重新初始化**不会**删除已下载的文件和数据库，只重置账号和运行模式。
