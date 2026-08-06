# 开发指南

本页面向主仓库贡献者。第三方插件作者请直接阅读[第三方插件 SDK](/zh-cn/plugin-development)；插件安装、启停和故障处理见[插件管理](/zh-cn/plugin-management)。

## 环境准备

| 工具 | 要求 | 用途 |
| --- | --- | --- |
| JDK | 17 | 编译和运行 |
| Maven | 3.9+，或仓库内的 Maven Wrapper | 构建与测试 |
| Git | 当前受支持版本 | 版本控制 |
| PowerShell | 5.1+ | Windows 打包脚本 |
| Inno Setup | 6.x，可选 | Windows 安装包 |

所有 Java 命令都应显式使用 UTF-8。在 Windows PowerShell 中：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

## 多模块结构

仓库根 `pom.xml` 是 Maven Reactor 聚合器。主要边界如下：

| 目录 | 职责 |
| --- | --- |
| `pixivdownload-plugin-api/` | 第三方插件的稳定扩展契约 |
| `pixivdownload-core-api/` | 稳定的宿主语义端口和值模型 |
| `pixivdownload-plugin-signature/` | 插件和仓库清单的签名、验签工具 |
| `pixivdownload-plugin-runtime/` | PF4J、Spring 子上下文和安装生命周期 |
| `pixivdownload-plugin-*/` | 各官方外置插件 |
| `pixivdownload-plugin-douyin/` | 官方 Douyin 下载类型示例 |
| `pixivdownload-app/` | 宿主应用、适配器和可执行 Spring Boot JAR |
| `pixivdownload-official-plugins/` | 官方插件聚合与开发模式入口 |
| `plugin-templates/` | 可复制的第三方插件模板 |

插件 API 与宿主实现之间必须保持依赖方向：插件依赖 `plugin-api`，需要稳定宿主能力时再依赖 `core-api`；第三方插件不得依赖 `pixivdownload-app` 或宿主实现类。

## Fork 与分支

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/your-change upstream/master
```

提交 PR 前，将分支更新到 `upstream/master`，并向上游 `master` 分支发起 PR。

## 构建、测试与运行

```powershell
# 构建全部模块但跳过测试
.\mvnw.cmd package -DskipTests

# 运行完整 Maven 测试
.\mvnw.cmd test

# 运行一个模块及它在 Reactor 中依赖的模块
.\mvnw.cmd -pl pixivdownload-plugin-api -am test

# 运行全部 JavaScript 测试和 Web 标准检查
npm run test:js
npm run test:web-standards

# 运行打包后的应用
java -Dfile.encoding=UTF-8 -jar pixivdownload-app/target/PixivDownload-*-boot.jar
```

如果使用 `-Dtest=...` 对带 `-am` 的 Reactor 做聚焦测试，请同时添加 `-Dsurefire.failIfNoSpecifiedTests=false`，避免没有该测试类的上游模块误报失败。

## 官方外置插件开发模式

从仓库根目录运行：

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

IDE 调试请使用仓库提交的共享配置：IntelliJ IDEA 的 `.run/Developer Mode.run.xml`、VS Code 的 `.vscode/launch.json` 或 Eclipse 的 `eclipse/Developer Mode.launch`。这些入口会先编译所需 Reactor 模块，再启动 `GuiLauncher`。

缺失必装插件的恢复模式可使用相应的 `Missing Required Plugin` 共享配置，或运行：

```powershell
mvn -pl pixivdownload-app -am -Precovery-mode process-classes -Dexec.skip=true
```

## 用户脚本资源

`pixiv-batch.html` 通过 `/api/scripts` 读取已经物化的脚本目录。独立 `*.user.js` 与 `scripts/build-userscript-bundle.ps1` 生成的整合脚本会在 Maven `generate-resources` 阶段复制到应用资源。因此修改用户脚本后至少运行一次 Maven 生命周期，不能只依赖 IDE 的旧输出目录。

## i18n 国际化工作流

`i18n/locales.json` 是语言清单；中文（`zh-CN`）是开发源语言，英文（`en-US`）是全局回退语言。常用命令：

```bash
npm run setup:hooks
npm run doctor:hooks
npm run i18n:check
npm run i18n:generate-static
npm run test:i18n
```

新增中文文案必须同时提交英文翻译。静态资源发生变化时，重新生成并提交 `pixivdownload-app/src/main/resources/static/i18n-static`。基线接受和 hooks 细节见[仓库 i18n 工作流](https://github.com/Sywyar/PixivDownloader/blob/master/docs/i18n-workflow.md)。

## 本地 Windows 打包

`scripts/package-local.ps1` 会构建应用壳、官方插件输入、在线/离线便携包及可选的 Inno Setup 安装包。正式产物要求每个官方插件都有可复验的签名：可以传入已经带 `.sig` sidecar 的 `-PrebuiltPluginsDir`，或对本地模块产物传入 `-OfficialKeyId`、仓库外的 `-PrivateKeyFile` 和可选的 `-SignatureToolJar`。产物默认写入 `build/out/`。

```powershell
# 本机运行验收：从当前源码构建隔离的 unsigned 测试安装器
.\scripts\package-installer-with-plugins.ps1 -Version 0.0.1-local -PluginSource Local -AllowUnsignedLocalPlugins

# 恢复/开发用 core-shell-only 便携包，不含任何插件
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipPlugins -SkipInstaller

# 使用已经签名并复验过的官方插件输入构建正式产物
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -PrebuiltPluginsDir C:\path\to\signed-plugin-inputs -SignatureToolJar C:\path\to\signature-tool.jar
```

常用参数包括 `-PrebuiltJar`、`-PrebuiltPluginsDir`、`-SkipPlugins`、`-RunTests`、`-SkipPortable`、`-SkipOfflinePortable`、`-SkipInstaller` 和 `-RedownloadFfmpeg`。`-SkipPlugins` 产物只能进入恢复/补齐流程。unsigned 测试安装器写入隔离的 `build/out-local-unsigned/`，不得分发；私钥也不得放入仓库、构建输出或日志。

## 提交与 PR

提交前应按改动风险运行聚焦测试，再扩大到受影响模块或完整门禁。至少检查：

```bash
git diff --check
git diff --staged
```

PR 描述应说明动机、边界、验证命令和实际结果。涉及 UI 时附截图；涉及打包时说明验证过的产物。不要提交 `target/`、`build/`、运行期配置、凭据或下载数据。

## CI 与发布

- 普通变更由质量门禁工作流检查 Java、JavaScript、i18n、依赖和分发边界。
- `v*` 标签触发官方插件发布、应用壳构建、Java 分发包、Windows 安装器和 GitHub Release。
- `workflow_dispatch` 用于在质量门禁通过后创建指定标签的草稿 Release。
- 文档站点由独立的 `gh-pages` 分支维护，不是应用静态资源的标签预览。

工作流会持续演进；执行发布操作前，以仓库当前 `.github/workflows/` 与 `scripts/` 为准。

## 代码边界

- 公共字符串进入 i18n 流水线；网页遵循现有暗色模式、CSS 变量和 HTML/CSS/JavaScript 分离规则。
- 公共 HTTP API 使用显式 DTO，并复用项目既有鉴权和异常映射。
- 修改数据库建表语句时同步更新受管 schema 规格和迁移测试。
- 插件私有配置、凭据、状态、数据和依赖只归对应插件所有；不得重新耦合进应用壳。
- 新增或修改第三方扩展契约时，先更新 `plugin-api`/`core-api` 的契约与守卫，再更新模板、示例和 SDK 文档。
