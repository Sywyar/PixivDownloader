# 常见问题 (FAQ)

## 安装与启动

### Q: 启动提示「Java 不是内部或外部命令」

**A**: 需要安装 Java 17+。从 [Adoptium](https://adoptium.net/) 下载安装后，重启终端或手动配置 `JAVA_HOME` 和 `PATH` 环境变量。

### Q: 启动后中文乱码

**A**: 启动命令必须添加 `-Dfile.encoding=UTF-8` 参数：

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar
```

### Q: 提示端口 6999 已被占用

**A**: 有两种方式解决：
1. 关闭占用 6999 端口的程序
2. 修改 `config.yaml` 中的 `server.port` 为其他端口（需重启生效）

### Q: Windows 安装包安装失败

**A**:
1. 确保安装前已关闭正在运行的 `PixivDownload.exe`
2. 检查是否有旧版 MSI 残留（安装器会自动处理迁移）
3. 以管理员身份运行安装器

### Q: 如何卸载？

**A**: 
- **安装包版本**：再次运行安装器，选择「卸载」；或通过 Windows「设置 → 应用」卸载
- **JAR 版本**：直接删除程序目录即可

### Q: 如何重新初始化（重置所有设置）？

**A**: 停止服务后删除 `state/setup_config.json`，再以 `--setup` 重新初始化（或在 GUI 模式下走「首页」引导）。注意：这不会删除已下载的文件和数据库。

### Q: 服务器 / Docker 上首次启动总是退出，提示要先 setup？

**A**: 从 v1.10.0 起，无头 / `--no-gui` 模式下若未完成首次初始化会拒绝启动，避免起一个没有任何配置入口的服务。请先用 CLI 完成初始化再启动服务：

```bash
# 交互式：依次输入用户名、密码、运行模式
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup

# 自动化脚本：一次性传入（密码会出现在 shell 历史/进程列表）
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
    --username=admin --password='YourPassword123' --mode=solo
```

完成后再正常 `--no-gui` 启动即可。详见 [使用指南 → CLI 管理命令](zh-Usage-Guide#cli-管理命令-v1100)。

### Q: 管理员忘记密码了怎么办？

**A**: 先停止正在运行的 PixivDownloader，然后用 CLI 重置密码（不需要原密码）：

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --reset-password
```

向导会要求确认新密码（至少 6 位）。重置成功后所有现存的登录会话都会被注销，需要使用新密码重新登录。如果还记得当前密码、只是想换一个新的，用 `--change-password`（会校验当前密码）。

### Q: 启动时提示「无法识别的启动参数」？

**A**: 从 v1.10.0 起启动参数会做严格校验，拼错的 flag（如 `--no-guii`）、漏写 `=` 的参数（如 `--username admin`）或纯位置参数都会被拒绝。运行 `--help` 查看完整选项；`--key=value` 形式的参数仍然会作为 Spring 属性覆盖原样转发。

---

## 代理与网络

### Q: 代理配置后仍无法下载

**A**:
1. 确认代理配置已保存（GUI 保存或 CLI `--setup` 配置后，代理**支持热重载**，立即生效，无需重启；直接手动编辑 `config.yaml` 则需重启或在 GUI 点保存触发热重载）
2. 验证代理是否可用：在浏览器中通过代理访问 `https://i.pximg.net/`
3. 检查防火墙是否放行代理端口
4. 提醒：代理用于全部对外访问（Pixiv、在线更新、下载 FFmpeg、在线 TTS），上述问题也会同样影响这些功能

### Q: 多人模式部署在反向代理后，所有用户被限流

**A**: 后端的 API 和静态资源限流都基于 TCP 源 IP（`request.getRemoteAddr()`）。部署在反向代理后，所有请求的源 IP 都是反代节点 IP。

**解决方案**：在反代层根据 `X-Forwarded-For` / `X-Real-IP` 做限流，并将后端限流调高或设为 `0` 关闭：

```yaml
multi-mode.request-limit-minute: 0
multi-mode.static-resource-request-limit-minute: 0
```

### Q: 局域网内其他设备无法访问

**A**:
1. 确认防火墙放行 6999 端口
2. 确认 `config.yaml` 中未限制绑定地址
3. 浏览器访问 `http://<服务器IP>:6999/`

---

## Cookie 相关

### Q: Search 模式搜索不到结果

**A**: Search 模式需要 Pixiv Cookie。请确认：
1. 已在 `pixiv-batch.html` 中正确粘贴 Netscape 格式的 Cookie
2. Cookie 未过期（重新登录 Pixiv 后重新获取）
3. 代理配置正确

### Q: Cookie 格式错误提示

**A**: 请确保：
1. 使用 Cookie-Editor 扩展的 **Netscape** 格式导出
2. 在页面上切换到 **Netscape** 格式标签后再粘贴
3. 不要手动修改 Cookie 内容

### Q: 如何清除已保存的 Cookie？

**A**: 
- Solo 模式：在 `pixiv-batch.html` 点击清除 Cookie 按钮，或退出登录
- 退出登录时会同步清除服务端保存的 Cookie

---

## 下载相关

### Q: 动图 (Ugoira) 下载后无法播放

**A**: 动图需要 ffmpeg 转换为 WebP 格式。请安装 ffmpeg 并确保在系统 PATH 中可用：

```bash
ffmpeg -version
```

Windows 安装包用户可在 GUI「状态」页点击「下载 FFmpeg」按钮。

### Q: 下载的小说 EPUB 封面不显示

**A**: 封面下载可能需要代理。确保：
1. 代理配置正确
2. 封面 URL 的 host 以 `.pximg.net` 结尾（SSRF 安全限制）
3. 重新下载该小说

### Q: 听书的「在线引擎」没有声音 / 提示合成失败

**A**: 在线引擎（Edge 神经语音）需要联网，请依次排查：
1. **网络/代理**：在线合成经后端代理访问微软服务，确保代理可用（与下载共用 `proxy.*` 配置）
2. **握手 403**：版本号过期时后端会自动联网拉取最新 Edge 版本、校正时钟偏差后重试，通常会自愈；若持续失败，检查本机系统时钟是否准确
3. **改用浏览器引擎**：在播放栏「设置」里切换到「浏览器引擎」，完全离线、无需联网（前提是操作系统装了对应语言的语音包）
4. **邀请访客被限流**：通过邀请链接访问的访客受 `guest-invite.tts-request-limit-minute` 约束（访客邀请在 solo/multi 下都可用），过于频繁会返回 429，稍后再试或让管理员调高该值；管理员本人不受限

### Q: 批量下载时部分作品失败

**A**: 常见原因：
1. Cookie 过期 — 重新获取并保存 Cookie
2. Pixiv 服务器限流 — 适当降低下载并发和频率
3. 代理不稳定 — 检查代理服务
4. 作品已被删除或设为私有 — 跳过错过的作品

### Q: 自定义文件名模板不生效

**A**: 检查模板语法：
- 变量使用花括号 `{artwork_id}`
- 不要使用不支持的变量名
- 模板结果不能包含非法文件名字符（会自动 sanitize）
- 修改模板后，新下载的作品才会使用新模板，已有作品文件名不会改变

### Q: 动图下载占用大量配额

**A**: 多人模式下，可在 `config.yaml` 配置 `multi-mode.quota.limit-image`。当一个作品的总图片数超过此值时，按多个配额计算：

```yaml
multi-mode.quota.limit-image: 10  # 超过10张图片的作品按比例消耗配额
```

---

## GUI 相关

### Q: GUI 启动后服务一直显示「启动中」

**A**: 启动超过 10 秒时会显示已等待秒数，可能正在更新数据库。如果长时间无响应：
1. 查看 `log/latest.log` 日志文件
2. 尝试删除数据库（如有备份）后重启
3. 确认 Java 版本为 17+

### Q: GUI 在线更新失败

**A**:
1. 检查网络连接
2. 检查代理配置（更新下载复用代理配置）
3. 可手动从 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下载安装包覆盖安装

### Q: GUI 错误弹窗显示乱码

**A**: GUI 错误弹窗已改为统一提示，详细错误信息记录在日志文件中。点击弹窗中的「打开日志文件」查看详细错误。

---

## 数据库相关

### Q: 数据库文件在哪里？

**A**: 默认位于 `data/pixiv_download.db`。使用 SQLite WAL 模式，可用任意 SQLite 工具打开。

### Q: 数据库损坏怎么办？

**A**: 
1. 停止程序
2. 备份 `pixiv_download.db`、`pixiv_download.db-wal`、`pixiv_download.db-shm`
3. 尝试用 SQLite 工具修复：`sqlite3 pixiv_download.db "PRAGMA integrity_check;"`
4. 如无法修复，删除数据库文件并重启（已下载文件不受影响），但画廊数据会丢失

### Q: 数据库丢了 / 迁移了，已经下载过的作品会被当作未下载重新拉取吗？

**A**: 不会。重新下载某个作品时，若数据库无该作品的记录，但 `{下载根目录}/{作品ID}/` 下已存在按默认文件名模板（`{作品ID}_p{页号}.{扩展名}`，如 `123456_p0.jpg`）命名的图片文件，会按实际页数与扩展名反向恢复一条数据库记录、跳过重新下载，恢复后的作品在画廊中可像正常下载的作品一样浏览。

注意：仅识别默认文件名模板命名的文件。如果你用过自定义文件名模板下载，那些文件不会被识别，仍会被当作未下载重新拉取；元数据（标题、作者、标签等）也无法离线恢复，画廊会按缺字段降级显示，可用「数据库数据回填工具」补全。

### Q: 如何备份数据？

**A**: 备份以下目录：
- `data/` — 数据库文件
- `pixiv-download/` — 下载的文件
- `state/` — 运行状态
- `config/` — 配置文件
- `collection_icons/` — 收藏夹图标

---

## 油猴脚本

### Q: 脚本安装后不生效

**A**:
1. 确认 Tampermonkey 已启用
2. 检查脚本是否在目标页面启用（`@match` 规则）
3. 检查 Tampermonkey 管理面板中脚本是否显示为「已启用」
4. 刷新 Pixiv 页面

### Q: All-in-One 和独立脚本同时启用出现双面板

**A**: 这是 Tampermonkey 平台的固有行为 — All-in-One 和独立脚本是不同脚本，存储互不相通。建议：
- 只启用 **All-in-One 整合包**（推荐）
- 或者只启用需要的独立脚本，禁用 All-in-One

### Q: 脚本提示「后端不可用」或「未登录」

**A**:
1. 确认后端服务正在运行
2. Solo 模式下确认已登录
3. 非 localhost 部署时需修改脚本头部的 `@connect` 声明

---

## 安全相关

### Q: 使用此工具会导致 Pixiv 账号被封吗？

**A**: 本项目通过用户自行提供的 Cookie 访问 Pixiv，使用者需自行承担账号风险。建议：
- 合理设置下载频率和并发数
- 避免短时间内大量请求
- 遵守 Pixiv 使用条款

### Q: 多人模式下访客能看到管理员的数据吗？

**A**: 多人模式下：
- 访客只能看到自己的下载状态
- 管理员和 Solo 模式保留全局视图
- 访客邀请模式下，访客只能浏览白名单范围内的作品
- 收藏夹、历史、统计等数据按访问权限过滤

### Q: 如何启用 HTTPS？

**A**: 在 `config.yaml` 配置 SSL 证书：

```yaml
server.ssl.enabled: true
server.ssl.certificate: /path/to/cert.pem
server.ssl.certificate-private-key: /path/to/key.pem
```

推荐使用 PEM 格式。同时配置 PEM 和 JKS 时 PEM 优先。

---

## 其他

### Q: 如何迁移到新电脑？

**A**: 复制以下目录到新电脑：
1. `data/` — 数据库文件
2. `pixiv-download/` — 下载的文件
3. `state/` — 运行状态
4. `config/` — 配置文件
5. `collection_icons/` — 收藏夹图标（如有）

### Q: 如何升级版本？

**A**: 
- **GUI 模式**：启动后自动检查更新，或在「状态」页手动检查
- **JAR 版本**：下载新版本 JAR 覆盖替换
- **Windows 安装包**：运行新版本安装器选择「修复」或直接覆盖安装
- 数据库会自动迁移，无需手动操作

### Q: 项目如何提供反馈和报告问题？

**A**: 在 [GitHub Issues](https://github.com/Sywyar/PixivDownloader/issues) 提交 issue，请包含：
- 操作系统和版本
- PixivDownloader 版本（GUI「关于」页查看）
- 问题描述和复现步骤
- 相关日志（`log/latest.log`）
- 截图（如有）
