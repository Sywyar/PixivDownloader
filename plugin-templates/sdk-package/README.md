# PixivDownloader 插件 SDK @SDK_VERSION@

这是可直接用 IntelliJ IDEA、VS Code 或 Eclipse 导入的插件开发工作区。`plugin/` 是默认的完整下载类型插件工程，`examples/minimal-feature-plugin/` 是基础功能插件参考。SDK 身份为 `@SDK_RELEASE_ID@`，源码对应主仓库提交 `@SOURCE_SHA@`。

## 立即开始

需要 JDK 17 和可从命令行调用的 Node.js。IDE 打开本目录后会按根 `pom.xml` 自动导入 `plugin/` Maven 模块；导入不会下载或执行 PixivDownloader 宿主。

Windows：

```powershell
.\mvnw.cmd clean verify
```

Linux / macOS：

```bash
sh ./mvnw clean verify
```

产物位于 `plugin/target/example-download-plugin-0.1.0.jar`。它是 thin PF4J JAR：SDK、PF4J、Spring、Servlet 和 Jackson 依赖都保持 `provided`，不得复制进插件包。

## IDE 入口

- IntelliJ IDEA：打开本目录，使用共享的 `Verify Plugin` Maven 配置。
- VS Code：打开本目录，运行任务 `Verify Plugin` 或 `Package Plugin`。
- Eclipse：选择 `File > Import > Existing Maven Projects` 并导入本目录；M2E 会导入 `plugin/` 模块。

## 你可以贡献什么

插件可通过稳定契约贡献 route、static、i18n、navigation、Web UI slot、GUI 配置字段、下载类型、队列操作、计划来源、通知模板和其它已公开 capability。宿主不认识具体插件，插件也不得直接依赖 app、plugin-runtime、installer、签名内部实现或其它具体插件。

完整模板演示五类取得模式、队列取消与 drain、计划来源、作品执行器、凭证策略、Guard、插件自有画廊和 `gallery.type-switch`。画廊页面、API、静态资源、i18n、数据查询和操作全部归下载类型插件自身，不存在通用画廊 provider 或 `/api/gallery/unified/**` 挂载点。

插件配置使用 `GuiConfigContribution` 声明，并通过 owner-bound `RuntimePathProvider` 获取自己的配置、状态和数据路径；私有数据库使用 `PluginDataSource`。不要依赖宿主 `RuntimeFiles`、`ProxyConfig`、`DownloadConfig`、主数据库或 GUI provider 实现。出站 HTTP/WebSocket 使用稳定 factory 与 route 契约。

## 宿主 Developer Mode

先完成 `clean verify`。准备一个与你目标 SDK 主/次版本兼容、且已由你自行验证来源的 PixivDownloader 宿主 JAR，然后从 SDK 根目录的父级或其它受控目录启动宿主，并显式设置：

```text
-Dpixivdownload.plugin-dev.enabled=true
-Dpixivdownload.plugin-dev.root=<本 SDK 工作区绝对路径>
```

宿主会发现 `plugin/target/classes` 并把它物化到隔离开发缓存。SDK 不在 IDE 导入时下载宿主，也不会执行未经校验的远端文件。停止、禁用、卸载、reload 和 publication 换代必须在真实宿主中验证贡献撤回语义。

## 下一步

按 `plugin/README.md` 的替换表修改 artifact id、插件 id、Java 包名、路由、i18n namespace、版本与 provider。Douyin 官方插件仅是完整参考实现，不是 SDK 依赖或特殊契约。

本工作区内的 `sdk-project.json` 记录 SDK 坐标与精确源码身份；Release 旁的 `sdk-release.json` 和 `SHA256SUMS` 记录发行附件摘要。
