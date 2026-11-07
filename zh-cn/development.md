# 主仓库开发

本页说明主程序与官方外置插件的开发环境准备、模块边界、构建测试、开发模式调试、本地打包与 PR 规范。开发独立第三方插件请阅读[第三方插件 SDK](/zh-cn/plugin-development)；插件安装与排障见[插件管理](/zh-cn/plugin-management)。

## 环境要求

宿主、SDK 与官方插件统一使用 JDK 25，Java/Kotlin 编译目标为 25；第三方插件开发也需要 JDK 25。使用 Temurin 25 运行 ProGuard 构建时，还需下载与 JDK 完整版本一致的 JMOD 并放入其 `jmods/`；CI 通过 `actions/setup-java` 的 `java-package: jdk+jmods` 准备，保留摘要和签名校验。

框架基线为 Spring Boot 4.1.2、Spring Framework 7、Jackson 3 和 SDK 2.0.0。升级插件前请完成[SDK 迁移](/zh-cn/plugin-development)，SDK 1.x 二进制不能直接加载到此宿主。

| 工具 | 版本要求 | 用途 |
| --- | --- | --- |
| JDK | 25 | 编译与运行 Java 模块，CI 主机测试使用 25 |
| Maven | 3.9+ 或仓库内置 `mvnw` | Reactor 构建与测试 |
| Git | 当前受支持版本 | 版本控制与分支管理 |
| Node.js / npm | 与当前 CI 一致 | 前端测试、i18n 门禁与边界守卫 |
| PowerShell | 5.1+ | Windows 本地打包脚本 |
| Inno Setup | 6.x（可选） | 构建 Windows `setup.exe` 安装包 |

所有 Java 命令均须显式使用 UTF-8。Windows PowerShell 可设置：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

### 多 JDK 环境的选择

根 POM 在 Maven 的 `validate` 阶段要求 JDK `[25,26)`。当前 Maven JVM 已是 25 时直接复用；否则先使用 `~/.m2/toolchains.xml` 中匹配的登记，再尝试 Maven 的本机自动发现。编译、测试、Maven 开发启动、AOT 和 Maven 调用的 Gradle / ProGuard 共用该选择；找不到 25 时构建失败，不下载 JDK，也不回退到 17 或 26。

自定义安装目录建议登记一次，所有 clone 共用。将下面的 `jdkHome` 换成已有 JDK 25 的绝对路径，保存到 `~/.m2/toolchains.xml`；已有文件时只追加 `toolchain` 元素，保留其他登记。自动发现支持 `JAVA25_HOME` 等环境变量及常见安装目录，但不会搜索任意磁盘路径；旧 JDK 的本地化输出也可能导致发现失败，此时使用显式登记。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0">
  <toolchain>
    <type>jdk</type>
    <provides><version>25</version></provides>
    <configuration><jdkHome>C:/path/to/jdk-25</jdkHome></configuration>
  </toolchain>
</toolchains>
```

Maven 启动 JVM 仍由终端 `JAVA_HOME` / IDE Maven Runner 决定。IDE 原生 `Developer Mode` 的运行与调试使用项目 SDK，须在 IDE 中选择已安装的 25。直接调用 AOT 脚本时，`--java-home` 优先于 `JAVA_HOME`。Compose 的 Java/Kotlin 工具链要求 25，禁止自动下载。

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

依赖方向：插件依赖 `plugin-api`（及按需依赖 `core-api`），严禁反向依赖 `pixivdownload-app` 或宿主内部实现类。`douyin` 插件源码在[独立仓库](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin)维护，专属测试仅由该仓库的 `Host compatibility` CI 对固定宿主提交执行；主仓库 PR 保留通用 SDK 验收。

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

## 可选的 JDK 25 AOT Cache

仓库的 `scripts/aot-cache.mjs` 为最终 boot JAR 准备并使用 JVM 启动缓存，需要 Node.js 和已有 JDK 25。它不下载 JDK，也不启用 Spring AOT。安装器保持原有启动方式；CI 的产物验证会执行真实缓存训练与 strict 启动检查。只对可信 JAR 执行训练；训练会运行应用，但使用独立临时配置，不读取当前实例的用户数据。

直接依次运行共享配置 `AOT Prepare` 和 `AOT Packaged Run`，无需先手工打包。IntelliJ IDEA 使用 `.run`，VS Code 使用同名任务，Eclipse 导入 `eclipse/` 下的同名 launch 配置。Prepare 使用 Maven Toolchains 选定的 JDK 25，自动构建主程序和官方插件、装配独立运行目录，再准备缓存。Windows 使用系统 PowerShell；Linux / macOS 需要 PATH 中有 PowerShell 7（`pwsh`）。以下是等价命令：

```powershell
.\mvnw.cmd -N -Paot '-Daot.command=prepare' validate
if ($LASTEXITCODE -ne 0) { throw 'AOT preparation failed' }
.\mvnw.cmd -N -Paot validate
```

默认运行目录为 `target/aot-runtime`，可用 `-Daot.runtime.directory=...` 指定新目录。`artifacts/` 保存主程序和完整官方分发插件集合（含可选插件，不含恢复测试探针），`cache/` 保存按 JAR 和 JDK 内容区分的缓存，`instance/` 是应用的工作目录，保存独立配置和数据。`mvn clean` 会删除 `target/` 下的默认运行目录；需要长期保留数据时，指定 `target/` 以外的目录，并在 Prepare 和 Run 使用同一个值。首次 Run 按正常流程初始化；不会读取或覆盖仓库根目录已有实例。代码变化后再次执行 Prepare，成功后才切换到新产物，配置和数据保留。相同产物校验后复用；只修改插件而主程序和 JDK 不变时可复用主程序缓存。 单实例锁位于该运行目录的 `instance/ipc/`，可与其他开发或正式实例隔离；同时运行仍需配置不同服务端口。

Prepare 的 Maven 构建跳过单元测试，测试和 CI 门禁仍单独执行。插件通过既有本地未签名来源规则装配，仅用于本机开发，不能作为官方签名发行物分发。Run 不重新编译；准备或运行占用同一目录时会拒绝并发操作。失败保留上一份可运行记录，损坏内容拒绝复用；可选择新运行目录重新准备。旧产物与缓存不自动清理，会占用磁盘；停止相关进程后可删除不再使用的内容，保留 `instance/` 中需要的数据。

`AOT Prepare` 和 `AOT Packaged Run` 可直接使用普通 Run。即使通过 IDE Debug 启动，入口也只在 AOT 子进程的环境中移除 JDWP 和 IDEA 调试代理参数，保留堆大小、代理、证书等其他参数；应用断点调试仍使用 `Developer Mode`。Windows 下停止 Prepare 或其启动器，会一并结束这次构建和训练的子进程并释放锁，不影响其他应用实例。遇到锁占用时，先停止同一运行目录的 Prepare 或 Run 任务，再重试，不要删除 `.lock` 文件。其他锁错误按输出的底层原因排查。

`Developer Mode` 继续用于源码运行和断点调试。JDK 25 AOT Cache 不能以非空编译目录生成缓存，当前链接缓存也不能用于 JDWP 调试。单元测试、JaCoCo、Maven 与 Gradle 构建进程不使用应用 AOT 缓存；缓存训练成本应按实际重复启动次数衡量。

下列底层 CLI 面向已有 JAR，也供 CI 直接复用，不自动构建或装配插件。从仓库根目录执行，替换 `<version>`、JDK 路径及已初始化的实例目录。新缓存使用未创建的目录，已有有效缓存可重复校验复用。运行使用所选实例的配置、插件和数据；没有插件的核心 JAR 仅提供恢复模式。

```powershell
$repo = (Get-Location).Path
$jar = (Resolve-Path 'pixivdownload-app/target/PixivDownload-<version>-boot.jar').Path
$jdk = 'C:/path/to/jdk-25'
$cache = Join-Path $repo 'target/aot-cache'
node "$repo/scripts/aot-cache.mjs" prepare --jar $jar --java-home $jdk --cache-dir $cache
if ($LASTEXITCODE -ne 0) { throw 'AOT preparation failed' }

Set-Location 'C:/path/to/initialized-instance'
node "$repo/scripts/aot-cache.mjs" run --jar $jar --java-home $jdk --cache-dir $cache -- --no-gui
if ($LASTEXITCODE -ne 0) { throw 'AOT application failed' }
```

底层首次准备成功输出 `AOT prepare: ready`，有效缓存复用输出 `AOT prepare: reusing verified cache`。启动前核对原 JAR、解包依赖、JDK 和缓存的 SHA-256；损坏或不完整的缓存保留原目录，用 `--cache-dir` 指定新目录。训练数据结束后清理。清单是本机一致性检查，不能替代发行签名或插件准入。

正常启动保留当前环境，并由 JVM 在缓存不兼容时回退。验证命中时在 `run` 的 `--` 之前加 `--strict`，检查 JVM 的 `Using AOT-linked classes: true` 诊断；不能仅凭进程成功退出认定命中。训练成本需由多次启动收益抵消，部署前用相同 JAR、JDK、插件、数据和启动目标测量。相关机制见 [Spring Boot AOT Cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html)。

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
