# 主仓库开发

本页说明主程序与官方外置插件的开发环境准备、模块边界、构建测试、开发模式调试、本地打包与 PR 规范。开发独立第三方插件请阅读[第三方插件 SDK](/zh-cn/plugin-development)；插件安装与排障见[插件管理](/zh-cn/plugin-management)。

## 环境要求

| 工具 | 版本要求 | 用途 |
| --- | --- | --- |
| JDK | 17 | 编译与运行 Java 模块 |
| Maven | 3.9+ 或仓库内置 `mvnw` | Reactor 构建与测试 |
| Git | 当前受支持版本 | 版本控制与分支管理 |
| Node.js / npm | 与当前 CI 一致 | 前端测试、i18n 门禁与边界守卫 |
| PowerShell | 5.1+ | Windows 本地打包脚本 |
| Inno Setup | 6.x（可选） | 构建 Windows `setup.exe` 安装包 |

所有 Java 命令均须显式使用 UTF-8。Windows PowerShell 可设置：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

## 多模块结构与依赖方向

根目录 `pom.xml` 是 Maven Reactor 聚合器：

| 模块或目录 | 职责 |
| --- | --- |
| `pixivdownload-sdk-info/`、`pixivdownload-plugin-api/`、`pixivdownload-core-api/`、`pixivdownload-sdk/` | SDK 版本事实源、插件扩展契约、宿主稳定语义端口与统一编译入口 |
| `pixivdownload-plugin-signature/` | 插件包、仓库清单与社区操作的 Ed25519 签名与验签工具 |
| `pixivdownload-plugin-runtime/` | PF4J 加载、Spring 子上下文、worker 进程与安装事务生命周期 |
| `pixivdownload-plugin-*/` | 各官方外置插件模块 |
| `pixivdownload-app/` | 应用宿主、适配器与可执行 Spring Boot JAR |
| `pixivdownload-official-plugins/` | 官方外置插件开发聚合与 `dev-mode` 启动入口 |
| `plugin-templates/` | 可复制的独立第三方插件工程模板 |

依赖方向：插件依赖 `plugin-api`（及按需依赖 `core-api`），严禁反向依赖 `pixivdownload-app` 或宿主内部实现类。`douyin` 插件源码在[独立仓库](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)维护，通过固定提交参与 SDK 隔离消费者兼容性验收。

## Fork、构建与测试

Fork 仓库后基于 `upstream/master` 创建功能分支（请替换 `YOUR_USERNAME` 与分支名）：

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/your-change upstream/master
```

常用构建与验证命令：

```powershell
# 跳过测试打包全部模块
.\mvnw.cmd package -DskipTests

# 运行完整 Maven 测试
.\mvnw.cmd test

# 测试单个模块及其 Reactor 上游依赖（结合 -Dtest=... 时请加 -Dsurefire.failIfNoSpecifiedTests=false）
.\mvnw.cmd -pl pixivdownload-plugin-api -am test

# 运行前端测试与 Web 标准守卫
npm run test:js
npm run test:web-standards
```

构建结果以 `BUILD SUCCESS` 和退出码 `0` 为准；跳过测试的打包不代表测试通过。单独运行 `pixivdownload-app/target/PixivDownload-*-boot.jar` 仅启动不含外置插件的核心壳，完整功能联调请使用下方开发模式。

## 官方外置插件开发模式与恢复模式

在仓库根目录通过显式 `dev-mode` profile 启动完整开发环境（不要在 IDE Maven 面板中全局勾选该 profile）：

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

`dev-mode` 会同时发现各官方插件模块的 `target/classes` 与 `plugins/` 目录中已验签的安装包；若同一插件 ID 在两处同时存在，将触发 `plugin-conflict` 拒绝加载并在恢复页提示移除冲突副本后重启。IDE 调试请直接使用仓库自带的共享配置（IntelliJ IDEA `.run/Developer Mode.run.xml`、VS Code `.vscode/launch.json`、Eclipse `eclipse/Developer Mode.launch`）。

验证缺失必装插件或恢复模式时，使用 `Missing Required Plugin` 共享配置或运行：

```powershell
mvn -pl pixivdownload-app -am -Precovery-mode process-classes -Dexec.skip=true
```

## 油猴脚本同步与 i18n 工作流

- **油猴脚本**：根目录 `*.user.js` 与 `scripts/build-userscript-bundle.ps1` 生成的 All-in-One 会在 Maven `generate-resources` 阶段复制到 `download-workbench` 资源目录。修改共享片段或脚本后，请运行 `./scripts/sync-shared-snippets.ps1`、`./scripts/build-userscript-bundle.ps1` 并执行一次 Maven 构建。
- **i18n 国际化**：唯一语言目录为 `pixivdownload-app/src/main/resources/i18n/locales.json`（源语言 `zh-CN`，全局回退 `en-US`，包含 `zh-Hant`、`ja-JP`、`ko-KR`）。新增或修改文案后依次运行：

```bash
npm run setup:hooks
npm run doctor:hooks
npm run i18n:check
npm run i18n:generate-static
npm run test:i18n
```

## 本地 Windows 打包

- **本机无签名安装器验收**（从当前源码构建插件，输出到隔离的 `build/out-local-unsigned/`，严禁对外分发）：

  ```powershell
  .\scripts\package-installer-with-plugins.ps1 -Version 0.0.1-local -PluginSource Local -AllowUnsignedLocalPlugins
  ```

- **不含插件的核心壳便携包**（仅用于恢复模式/开发验证）：

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipPlugins -SkipInstaller
  ```

- **使用已签名插件输入构建正式包**（输出到 `build/out/`，严禁将私钥放入仓库、构建目录或日志）：

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -PrebuiltPluginsDir C:\path\to\signed-plugin-inputs -SignatureToolJar C:\path\to\signature-tool.jar
  ```

## 提交与 PR 规范

1. 按改动风险运行聚焦测试、模块回归与前端/i18n 门禁，提交前检查 `git diff --check` 与 `git diff --staged`，不提交 `target/`、`build/`、运行期配置、凭据或测试数据。
2. 保持用户可见字符串接入 i18n、运行日志固定使用英文、HTTP API 使用显式 DTO 并复用既有鉴权与异常映射、插件私有配置/凭据/数据归各自插件所有。
3. 将分支同步到最新 `upstream/master` 后向 `master` 发起 PR，在描述中说明改动原因、边界及实际执行的验证命令与结果。在线文档由独立 `gh-pages` 分支维护。
