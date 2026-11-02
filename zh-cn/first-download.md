# 第一次下载

本教程完成单件 Pixiv 作品的获取、下载及本地文件与画廊确认。开始前请先完成[安装与启动](/zh-cn/installation)和[首次配置](/zh-cn/first-setup)。下文作品 ID 仅为格式示例，请替换为你有权访问的实际作品。

## 1. 打开下载工作台

在浏览器访问 `http://localhost:6999/pixiv-batch.html`（也可从桌面 GUI「状态」页点击「批量下载」打开），使用管理员账号登录。新环境默认打开新版布局 `pixiv-batch-alt.html`；页面顶部可切换新旧布局，偏好会自动保存。

首次以管理员登录时会自动启动跨页面新手引导，依次介绍称呼设置、Pixiv 连通性检测、Cookie 与油猴脚本、单作品入队下载及画廊浏览。引导中填写的称呼用于画廊侧栏卡片和系统通知邮件问候语，留空默认显示「管理员」。引导可随时跳过，也可从页面右下角「操作指引」重新打开。

## 2. 保存 Pixiv Cookie（按需）

公开全年龄插画可不配 Cookie 直接下载；**R-18 / R-18G 作品、搜索下载、快捷获取及需登录可见的作品必须配置含有效 `PHPSESSID` 的 Cookie**（未配置时受限作品会被跳过）。先在浏览器登录 [Pixiv](https://www.pixiv.net/)，再任选以下一种方式获取：

### 方式 A：浏览器开发者工具（推荐，无需额外扩展权限）

1. 在已登录的 Pixiv 页面按 `F12` 打开开发者工具。
2. **Application（应用）面板**：展开左侧 `Storage → Cookies → https://www.pixiv.net`，找到 `PHPSESSID`，复制其 `Value`，拼成 `PHPSESSID=复制的值`。
3. 或在 **Network（网络）面板**：刷新页面，点任意发往 `www.pixiv.net` 的请求，复制 `Request Headers → Cookie` 的完整值。
4. 在下载页展开「Cookie」，格式选择 **Header String**，粘贴内容并点击「保存 Cookie」。

### 方式 B：Cookie-Editor 扩展

1. 安装 [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm)，在已登录的 Pixiv 页面点击扩展图标。
2. 点击 `Export → Netscape` 复制导出内容。
3. 在下载页「Cookie」中选择 **Netscape** 格式，粘贴并保存。

### 方式 C：油猴脚本「一键导入 Cookie」

1. 安装并启用「体验增强工具箱 (Toolbox)」脚本（见[油猴脚本](/zh-cn/userscripts)）。
2. 在 Tampermonkey「设置 → 配置模式：高级 → 安全」中，将「允许脚本访问 Cookie」临时设为 `全部 / All`。
3. 在下载页 Cookie 区域点击「一键导入 Cookie」，脚本读取 `PHPSESSID` 并回传后端。

!> `PHPSESSID` 是 HttpOnly Cookie，Tampermonkey 默认无法读取；但将权限设为 `全部 / All` 后，浏览器内所有油猴脚本都能读取你的登录凭证。建议导入成功后立即改回 `All except HttpOnly`，或优先使用上面的开发者工具方式。请勿将 Cookie 贴入截图、日志或 Issue。

## 3. 添加作品并开始下载

1. 在下载页选择「批量导入单作品」。
2. 粘贴作品链接或纯数字插画 ID（每行一条）：

   ```text
   https://www.pixiv.net/artworks/12345678
   ```

3. 点击「获取信息」，确认预览中的作品标题与类型。
4. 将作品加入队列并点击「开始下载」。
5. 在「下载队列」查看状态：等待中、下载中、已完成、已存在跳过与失败会分别标记；失败条目可点击查看具体原因。

## 4. 核对本地文件与画廊

在桌面 GUI「状态」页查看当前「下载目录」。默认路径为程序工作目录下的 `pixiv-download/`（取决于启动时的工作目录或收藏夹单独指定的目录）：

```text
pixiv-download/
└── 12345678/
    ├── 12345678_p0.jpg
    └── 12345678_p1.jpg
```

下载根目录由 `config/config.yaml` 的 `download.root-folder` 控制；修改配置不会移动已有文件，搬迁目录请按[存储与备份](/zh-cn/storage)操作。启用 `gallery` 插件时，访问 `http://localhost:6999/pixiv-gallery.html` 即可浏览刚入库的作品（详见[作品画廊](/zh-cn/gallery)）。

## 常见问题排查

- **提示需要 Cookie 或 R-18 被跳过**：确认已保存包含有效 `PHPSESSID` 的 Cookie，且对应 Pixiv 账号已在网页端开启 R-18 显示设置。
- **代理连接失败**：确认代理软件已启动，并在桌面设置页核对 `proxy.host` 与 `proxy.port`（浏览器能打开 Pixiv 不代表后端代理配置正确）。
- **提示作品不存在**：在 Pixiv 原站确认作品是否已被删除、设为私有或仅好P友可见。
- **下载完成但找不到文件**：在桌面 GUI「状态」页核对实际工作目录与下载目录，或检查下载时是否选择了自定义目录的本地收藏夹。

需要下载更多内容时，可继续查看[URL 批量下载](/zh-cn/batch-download)、[画师批量下载](/zh-cn/user-download)、[搜索下载](/zh-cn/search)或[小说与朗读](/zh-cn/novel)。
