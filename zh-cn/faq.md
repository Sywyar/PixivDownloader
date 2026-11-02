# 常见问题 (FAQ)

按故障症状查找诊断与处理步骤。涉及数据库、账号或目录的操作前，请先确认实际工作目录并保留备份。下文命令中的 `APP.jar` 请替换为发行包内的实际主程序 JAR 名称，并在完整解压目录中执行。

## 安装与启动

### 提示「Java 不是内部或外部命令」

未安装 Java 17+ 或未加入环境变量。从 [Adoptium](https://adoptium.net/) 安装 Java 17+ 后重新打开终端，或检查 `JAVA_HOME` 与 `PATH`。

### 启动后控制台中文乱码

手动启动时加上 `-Dfile.encoding=UTF-8`（发行包自带的 `run.bat` / `run.sh` 已包含该参数）：

```bash
java -Dfile.encoding=UTF-8 -jar APP.jar
```

### 提示端口 6999 已被占用

先检查是否已有同一数据目录的 PixivDownloader 实例在运行（请勿对同一数据目录重复启动多个实例）。若被其他程序占用，停止该程序，或修改 `config/config.yaml` 中的 `server.port` 后重启。

### Windows 安装包安装失败

先关闭正在运行的 `PixivDownload.exe`，再以管理员身份重新运行安装器。

### 如何卸载或重新初始化管理员账号

- **卸载**：Windows 安装版通过安装器或系统「设置 → 应用」卸载；Java 解压版先按[存储与备份](/zh-cn/storage)备份作品目录、`config/`、`state/` 和 `data/`，再删除程序文件（切勿直接删除包含作品和数据库的整个工作目录）。
- **忘记密码或重新初始化**：仅忘记密码时停止服务并执行 `--reset-password`。确需重建初始化状态时，停止服务并备份后移走 `state/setup_config.json`，再运行 `--setup` 或桌面引导（不会清空业务配置或重建数据库，详见[首次配置](/zh-cn/first-setup)）。

### 服务器 / Docker 启动后立即以退出码 78 退出

无头模式（`--no-gui`）未完成首次初始化时会以退出码 `78` 中止。先运行交互式初始化，再启动服务：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

### 管理员忘记密码

停止正在运行的实例后，在本机运行重置命令（无需原密码，密码至少 8 位、建议 12 位以上，重置后旧会话全部失效；记得原密码时可用 `--change-password`）：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --reset-password
```

### 提示「无法识别的启动参数」

启动参数执行严格校验：拼写错误的开关（如 `--no-guii`）、漏写 `=` 的参数（如 `--username admin`）或孤立位置参数均会被拒绝。运行 `--help` 查看受支持选项。

## 代理与网络

### 配置代理后仍无法下载

1. 确认代理已保存（桌面 GUI 保存或 CLI `--setup` 支持热重载；手动编辑 `config/config.yaml` 需重启或在 GUI 点击保存触发重载）。
2. 在浏览器验证实际 Pixiv 作品页可访问，并查看 `log/latest.log` 中的失败主机与错误码。
3. 检查防火墙是否放行代理端口，容器部署确认使用了 `host.docker.internal` 而非 `127.0.0.1`。
4. 区分全局代理、计划任务独立代理、插件自有代理和浏览器直连请求，详见[网络访问与第三方服务](/zh-cn/network-access)。

### 多人模式在反向代理后所有用户共享同一限流

未配置可信代理时，后端按 TCP 源 IP 限流。在 `config/config.yaml` 的 `server.trusted-proxy-cidrs` 中填入实际反代出口或容器网段（如 `172.18.0.0/16`），并让反代传递完整的 `Forwarded` 或 `X-Forwarded-For` + `X-Forwarded-Proto` + `X-Forwarded-Host`，详见[配置参考中的 HTTPS 与反向代理](/zh-cn/configuration#https-与反向代理)。

### 局域网其他设备无法访问

确认防火墙已放行 `server.port`（默认 `6999`）、服务未仅绑定 `127.0.0.1`（Docker 默认仅绑定宿主机回环，需通过反向代理或调整映射开放），并在局域网设备访问 `http://<服务器IP>:6999/`。

## Cookie 与下载

### 搜索无结果或提示 Cookie 格式错误

- 搜索下载、快捷获取与 R-18 作品必须配置含有效 `PHPSESSID` 的 Cookie。
- 粘贴时的格式选择（**Netscape** 或 **Header String**）必须与导出内容一致；普通前端脚本读出的 `document.cookie` 不含 HttpOnly 的 `PHPSESSID`，请按[第一次下载](/zh-cn/first-download#_2-保存-pixiv-cookie（按需）)重新获取。
- 清除 Cookie 时需在下载页明确点击清除；计划任务持有独立的 Cookie 绑定，还需在对应任务中解绑。

### 动图（Ugoira）无法转换或小说 EPUB 无封面

- **动图**：确认 `ffmpeg -version` 可用（或从桌面 GUI「状态」页点击「下载 FFmpeg」），选 MP4 时还需 H.264 编码器。
- **小说封面**：封面来自 `.pximg.net`，需确保后端代理可正常访问图片域，修复代理后重新下载该小说。

### 听书的「在线引擎」没有声音或合成失败

1. 在线引擎（Edge 神经语音）经后端代理连接微软服务，先确认 `proxy.*` 配置可用。
2. 握手返回 403 时，后端会自动刷新 Edge 版本并校正时钟偏差重试；若持续失败，请校准本机系统时间。
3. 可在播放栏切换为「浏览器引擎」。
4. 邀请访客受 `guest-invite.tts-request-limit-minute`（默认每分钟 30 次）限流，超限返回 429；管理员不受此限制。

### 批量下载部分失败、文件名模板不生效或配额消耗快

- **部分失败**：检查作品是否已被作者删除/设为私有、Cookie 是否过期；遇到上游限流时降低并发数并增大作品间隔。
- **文件名模板**：变量须加花括号（如 `{artwork_id}`），且仅对修改后的新下载生效（支持变量见[文件名模板变量](/zh-cn/usage-guide#文件名模板变量)）。
- **多人模式多图配额**：`multi-mode.quota.limit-image` 大于 `0` 时，超过该页数的多图作品会按比例折算多个配额。

## 桌面 GUI 与数据库

### GUI 一直显示「启动中」、更新失败或报错弹窗

- **启动缓慢**：超过 10 秒会显示等待秒数，可能正在执行数据库迁移或加载插件。请查看 `log/latest.log`，保留数据与日志，**切勿通过删除数据库来排查启动慢**。
- **在线更新失败**：检查后端代理连接，或直接从 [Releases](https://github.com/Sywyar/PixivDownloader/releases) 下载新版安装包覆盖安装。
- **错误详情**：点击弹窗中的「打开日志文件」查看 `log/latest.log` 中的完整英文诊断日志。

### 数据库位置、损坏处理与备份恢复

- **位置**：默认 SQLite 位于 `data/pixiv_download.db`（伴随 `-wal` / `-shm`）；PostgreSQL 位于所配置的数据库服务器。
- **数据库损坏**：先停止程序并完整备份原数据库与伴随文件，仅对**备份副本**执行 `PRAGMA integrity_check;`（该命令只检查不修复）。确认损坏后优先恢复已验证备份；作品识别、页数、路径与关联均以数据库为准，**丢失数据库后不能仅凭作品文件夹恢复画廊或保证跳过重下**。
- **备份、迁机与升级**：完整备份范围与目录搬迁见[存储与备份](/zh-cn/storage)。Java 发行包升级时必须完整解压并同步替换主程序与 `plugins/`，保留 `config/`、`state/` 和 `data/`，切勿只替换单个主 JAR。

## 油猴脚本与安全

### 油猴脚本不显示、双面板或连不上后端

- 确认 Tampermonkey 与脚本已启用、当前页面匹配 `@match`（如 User Batch 仅在 `/users/` 生效），并刷新页面。
- **双面板**：All-in-One 与独立下载脚本不要同时启用，保留其一即可。
- **连不上后端**：确认后端已启动、单人模式已登录；非 `localhost:6999` 部署时须同时修改脚本内的后端地址并更新 `@connect` 许可，详见[油猴脚本](/zh-cn/userscripts)。

### 账号安全、身份隔离与 HTTPS

- 本工具使用你提供的 Cookie 请求 Pixiv，请合理设置并发与间隔并遵守平台规则。
- **身份区分**：`multi` 模式下未登录且无邀请码的用户为**游客**（按 UUID 与 `multi-mode.*` 隔离）；持邀请码的用户为**访客**（在 `solo` / `multi` 下均只读访问白名单内容，受 `guest-invite.*` 限流）。
- **启用 HTTPS**：在 `config/config.yaml` 中设置 `server.ssl.enabled: true`、`server.ssl.certificate` 与 `server.ssl.certificate-private-key`（详见[配置参考](/zh-cn/configuration#https-与反向代理)）。

## 提交问题反馈

在 [GitHub Issues](https://github.com/Sywyar/PixivDownloader/issues) 反馈问题时，请附上操作系统、程序版本、复现步骤、相关截图以及去除 Cookie、密码、令牌和私人地址后的 `log/latest.log`。
