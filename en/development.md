# Main repository development

This page covers environment setup, module boundaries, builds and tests, development-mode debugging, local packaging and PR conventions for the main application and official external plugins. For independent third-party plugins, see [Third-party plugin SDK](/en/plugin-development). For installation and troubleshooting, see [Plugin management](/en/plugin-management).

## Environment requirements

The host, SDK and official plugins use JDK 25, with Java and Kotlin compilation targeting 25. Third-party plugin development also requires JDK 25. For ProGuard builds with Temurin 25, download the JMOD archive matching the full JDK version and place its contents in `jmods/`. CI prepares both with `actions/setup-java` and `java-package: jdk+jmods`, retaining checksum and signature verification.

The framework baseline is Spring Boot 4.1.2, Spring Framework 7, Jackson 3 and SDK 2.0.0. Complete the [SDK migration](/en/plugin-development) before upgrading plugins; SDK 1.x binaries cannot load directly into this host.

| Tool | Version requirement | Purpose |
| --- | --- | --- |
| JDK | 25 | Compile and run Java modules; host CI tests use 25 |
| Maven | 3.9+ or the repository's `mvnw` | Reactor builds and tests |
| Git | A currently supported version | Version control and branch management |
| Node.js / npm | Match current CI | Frontend tests, i18n checks and boundary guards |
| PowerShell | 5.1+ | Local Windows packaging scripts |
| Inno Setup | 6.x (optional) | Build Windows `setup.exe` installers |

All Java commands must explicitly use UTF-8. In Windows PowerShell, set:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
```

### Choosing a JDK when several are installed

The root POM requires JDK `[25,26)` during Maven's `validate` phase. It reuses the current Maven JVM if it is 25; otherwise it checks matching entries in `~/.m2/toolchains.xml`, then tries Maven's local discovery. Compilation, tests, Maven development startup, AOT, and Maven-launched Gradle / ProGuard share that choice. If no JDK 25 is found, the build fails without downloading a JDK or falling back to 17 or 26.

Register custom installation paths once for all clones. Replace `jdkHome` below with the absolute path of an existing JDK 25 and save it in `~/.m2/toolchains.xml`. If the file exists, add the `toolchain` element and keep other entries. Discovery supports variables such as `JAVA25_HOME` and common installation locations, but does not search arbitrary disks. Localized output from older JDKs can also break discovery; use an explicit entry in that case.

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

The terminal's `JAVA_HOME` or IDE Maven Runner still selects the JVM that starts Maven. Native IDE `Developer Mode` runs and debugs with the project SDK, which must be an installed JDK 25. For direct AOT script calls, `--java-home` takes precedence over `JAVA_HOME`. Compose requires a Java/Kotlin toolchain of 25 and disables automatic JDK downloads.

## Module structure and dependency direction

The root `pom.xml` is the Maven Reactor aggregator:

| Module or directory | Responsibility |
| --- | --- |
| `pixivdownload-sdk-info/`, `pixivdownload-plugin-api/`, `pixivdownload-core-api/`, `pixivdownload-sdk/` | SDK version source of truth, plugin extension contracts, stable host semantic ports and unified compilation entry point |
| `pixivdownload-plugin-signature/` | Ed25519 signing and verification tools for plugin packages, repository manifests and community operations |
| `pixivdownload-plugin-runtime/` | PF4J loading, Spring child contexts, worker processes and installation transaction lifecycle |
| `pixivdownload-plugin-*/` | Official external plugin modules |
| `pixivdownload-app/` | Application host, adapters and executable Spring Boot JAR |
| `pixivdownload-official-plugins/` | Official external plugin development aggregator and `dev-mode` entry point |
| `plugin-templates/` | Copyable project templates for independent third-party plugins |

Dependency direction: plugins depend on `plugin-api` (and optionally `core-api`). They must not depend on `pixivdownload-app` or internal host implementation classes. The `douyin` plugin source is maintained in a [separate repository](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin) and its dedicated tests run only in that repository’s `Host compatibility` CI against a pinned host commit. Main-repository PRs retain generic SDK acceptance.

## Fork, build and test

After forking, create a feature branch from `upstream/master` (replace `YOUR_USERNAME` and the branch name):

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/your-change upstream/master
```

Common build and validation commands:

```powershell
# Package all modules without running tests
.\mvnw.cmd package -DskipTests

# Run the full Maven test suite
.\mvnw.cmd test

# Test one module and its upstream Reactor dependencies (add -Dsurefire.failIfNoSpecifiedTests=false when using -Dtest=...)
.\mvnw.cmd -pl pixivdownload-plugin-api -am test

# Run frontend tests and Web standards guards
npm run test:js
npm run test:web-standards
```

A successful build reports `BUILD SUCCESS` and exit code `0`. Packaging with tests skipped does not prove that tests pass. Running `pixivdownload-app/target/PixivDownload-*-boot.jar` alone starts only the core shell without external plugins. Use development mode below to test the complete application.

## Official external plugin development mode and recovery mode

From the repository root, start the full development environment with an explicit `dev-mode` profile (do not enable this profile globally in the IDE's Maven panel):

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

`dev-mode` discovers both official plugin modules' `target/classes` and verified installed packages in `plugins/`. If the same plugin ID exists in both locations, loading is rejected with `plugin-conflict`; the recovery page asks you to remove the conflicting copy and restart. For IDE debugging, use the repository's shared configurations directly (IntelliJ IDEA `.run/Developer Mode.run.xml`, VS Code `.vscode/launch.json`, Eclipse `eclipse/Developer Mode.launch`).

To test a missing required plugin or recovery mode, use the `Missing Required Plugin` shared configuration or run:

```powershell
mvn -pl pixivdownload-app -am -Precovery-mode process-classes -Dexec.skip=true
```

## Userscript synchronization and i18n workflow

- **Userscripts**: root `*.user.js` files and the All-in-One generated by `scripts/build-userscript-bundle.ps1` are copied to `download-workbench` resources during Maven's `generate-resources` phase. After changing shared snippets or scripts, run `./scripts/sync-shared-snippets.ps1`, `./scripts/build-userscript-bundle.ps1`, then a Maven build.
- **Internationalization**: the sole language catalog is `pixivdownload-app/src/main/resources/i18n/locales.json` (source language `zh-CN`, global fallback `en-US`, also includes `zh-Hant`, `ja-JP` and `ko-KR`). After adding or changing text, run these commands in order:

```bash
npm run setup:hooks
npm run doctor:hooks
npm run i18n:check
npm run i18n:generate-static
npm run test:i18n
```

## Optional JDK 25 AOT Cache

The repository's `scripts/aot-cache.mjs` prepares and uses a JVM startup cache for the final boot JAR. It requires Node.js and an existing JDK 25. It does not download a JDK or enable Spring AOT. Installers keep their existing launch path; CI artifact verification trains a real cache and checks strict startup. Train only trusted JARs: training executes the application with isolated temporary configuration and does not read the current instance's user data.

Run the shared `AOT Prepare` and `AOT Packaged Run` configurations in that order; no separate packaging command is needed. IntelliJ IDEA uses `.run`, VS Code provides matching tasks, and Eclipse uses the launch configurations in `eclipse/`. Prepare uses the JDK 25 selected by Maven Toolchains to build the host and official plugins, assemble a separate runtime, and prepare its cache. Windows uses system PowerShell; Linux / macOS requires PowerShell 7 (`pwsh`) on PATH. Equivalent commands:

```powershell
.\mvnw.cmd -N -Paot '-Daot.command=prepare' validate
if ($LASTEXITCODE -ne 0) { throw 'AOT preparation failed' }
.\mvnw.cmd -N -Paot validate
```

The default directory is `target/aot-runtime`; choose another with `-Daot.runtime.directory=...`. `artifacts/` holds the host and full official distribution plugin set, including optional plugins but excluding the recovery test probe. `cache/` separates caches by JAR and JDK contents. `instance/` is the application working directory and holds independent configuration and data. `mvn clean` removes this default runtime under `target/`; use a directory outside `target/` for data you need to retain, with the same override for Prepare and Run. Complete normal setup on the first Run; the repository root instance is not read or overwritten. After changing code, run Prepare again. It switches to new artifacts only after success and preserves configuration and data. Matching artifacts are verified and reused; a plugin-only change can reuse the host cache if the host and JDK remain unchanged. The single-instance lock lives in `instance/ipc/` inside this runtime, separate from other development or installed instances. Concurrent instances still need different server ports.

The Prepare build skips unit tests; tests and CI gates still run separately. Plugins use the existing unsigned local upload policy and are for local development only, not official signed distribution. Run does not rebuild. Concurrent preparation or execution in the same directory is rejected. Failure preserves the previous runnable record; damaged contents are rejected, and a new runtime directory can be prepared instead. Old artifacts and caches are retained and consume disk space. After stopping related processes, remove unused contents while preserving any needed data in `instance/`.

Use ordinary Run for `AOT Prepare` and `AOT Packaged Run`. If launched through IDE Debug, the entry point removes JDWP and IDEA debugger-agent options only from the AOT child process environment, preserving heap, proxy, certificate and other options. Use `Developer Mode` for application breakpoints. On Windows, stopping Prepare or its launcher also ends that operation's build and training children and releases the lock; other application instances are unaffected. If the lock is held, stop the Prepare or Run task using the same runtime directory, then retry. Do not delete `.lock`. For other lock errors, check the underlying cause shown in the output.

Use `Developer Mode` for source execution and breakpoint debugging. JDK 25 AOT Cache cannot train with nonempty class directories, and the linked cache cannot be used with JDWP debugging. Unit tests, JaCoCo, Maven and Gradle build processes do not use the application's AOT cache. Assess training cost against the actual number of repeated launches.

The low-level CLI below consumes an existing JAR and is also used directly by CI. It does not build or stage plugins. Run it from the repository root, replacing `<version>`, the JDK path and the initialized instance directory. Use a new directory for a new cache; an existing valid cache can be verified and reused. Execution uses the selected instance configuration, plugins and data. A core JAR without plugins provides recovery mode only.

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

The low-level tool prints `AOT prepare: ready` for a new cache or `AOT prepare: reusing verified cache` when reusing a valid one. Before launch it checks SHA-256 hashes of the source JAR, extracted dependencies, JDK and cache. A damaged or incomplete cache is preserved; choose a new directory with `--cache-dir`. Temporary training data is removed afterwards. The manifest checks local consistency and does not replace release signatures or plugin admission.

Normal execution preserves the current environment and lets the JVM fall back when the cache is incompatible. To verify a cache hit, add `--strict` before the `run` command's `--` and check the JVM's `Using AOT-linked classes: true` diagnostics; successful exit alone does not prove a hit. Repeated startup savings must offset training cost. Measure with the same JAR, JDK, plugins, data and startup target before deployment. See [Spring Boot AOT Cache](https://docs.spring.io/spring-boot/reference/packaging/aot-cache.html).

## Local Windows packaging

- **Local unsigned installer acceptance** (builds plugins from current source, outputs to isolated `build/out-local-unsigned/`; must not be distributed externally):

  ```powershell
  .\scripts\package-installer-with-plugins.ps1 -Version 0.0.1-local -PluginSource Local -AllowUnsignedLocalPlugins
  ```

- **Core-shell portable package without plugins** (only for recovery-mode/development validation):

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipPlugins -SkipInstaller
  ```

- **Build a formal package from signed plugin inputs** (outputs to `build/out/`; never put private keys in the repository, build directories or logs):

  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -PrebuiltPluginsDir C:\path\to\signed-plugin-inputs -SignatureToolJar C:\path\to\signature-tool.jar
  ```

## Commit and PR conventions

1. Run focused tests, module regressions and frontend/i18n checks according to the change's risk. Before committing, inspect `git diff --check` and `git diff --staged`. Do not commit `target/`, `build/`, runtime configuration, credentials or test data.
2. Keep user-visible strings in i18n, runtime logs in English, HTTP APIs on explicit DTOs with existing authentication and exception mapping, and private plugin configuration/credentials/data owned by their respective plugins.
3. Synchronize the branch with the latest `upstream/master`, then open a PR targeting `master`. Describe the reason, boundaries, and validation commands and results actually obtained. Online documentation is maintained on the separate `gh-pages` branch.
