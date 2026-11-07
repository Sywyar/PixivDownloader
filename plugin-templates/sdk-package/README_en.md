# PixivDownloader Plugin SDK @SDK_VERSION@

[简体中文](README.md)

The extracted root is a standalone Maven plugin project (sources in `src/`) for SDK release `@SDK_RELEASE_ID@`, built from main-repository commit `@SOURCE_SHA@`. Open `docs/javadocs/index.html` for offline Javadocs, or see the [online plugin development guide](https://sywyar.github.io/PixivDownloader/#/en/plugin-development).

## 1. Quick Start: Run and Debug

Install JDK 25+ and Node.js (`java` and `node` on `PATH`; on Linux / macOS without `unzip`, the Wrapper extracts archives via JDK `jar`). Importing the project into an IDE only resolves dependencies; running an explicit Run / Debug configuration compiles the plugin, downloads the pinned runtime bundle on demand into `~/.cache/pixivdownloader-sdk/`, and starts an isolated host (runtime data under `.dev/`):

| IDE | Import | Run | Debug |
| --- | --- | --- | --- |
| IntelliJ IDEA | Open root `pom.xml` | Run `Developer Mode` | Debug `Developer Mode` (loads `target/classes` in-process) |
| VS Code | Open this folder (with Java Extension Pack) | `Tasks: Run Task > Run Plugin` | `Run and Debug > Debug Plugin` (installs packaged JAR, attaches to `127.0.0.1:5005`) |
| Eclipse | `Import > Existing Maven Projects` | `eclipse/Run Plugin.launch` | `eclipse/Debug Plugin.launch` group |

Command-line build and run (on Linux / macOS, replace `.\mvnw.cmd` with `./mvnw`):

```powershell
# Build and run unit and thin-JAR boundary tests
.\mvnw.cmd clean verify

# Build, prepare runtime, and start the development host (use sdk-debug to debug)
.\mvnw.cmd verify exec:exec@sdk-run

# Stop the development host for this project
.\mvnw.cmd exec:exec@sdk-stop
```

After building the JAR, you can also invoke `tools/sdk-tools.jar` directly:

```text
java -jar tools/sdk-tools.jar run <absolute-project-path> <absolute-plugin-jar-path> --no-gui
java -jar tools/sdk-tools.jar debug <absolute-project-path> <absolute-plugin-jar-path> --debug-port=5005
java -jar tools/sdk-tools.jar stop <absolute-project-path>
```

On first launch, complete administrator setup, verify the plugin is `STARTED` in `/plugin-manage.html`, and open `/example-minimal.html`. Stop the host with `Stop Plugin` (or `sdk-stop`) when finished; delete `.dev/` while stopped to reset development data. If a deep path on Windows triggers worker launch error `267`, move the project to a shorter path.

## 2. Included Standalone Examples

The download-type, Gradle, and sbt examples have their own `.pixivdownloader-plugin-project`, `sdk-project.json`, and `.dev/` runtime data directory while sharing the root `tools/sdk-tools.jar`. Build and install the typed-service example as described in its README; it does not use these standalone host launch configurations.

The commands below use PowerShell. On Linux / macOS, replace `.\mvnw.cmd` with `./mvnw` and `.\gradlew.bat` with `./gradlew`. For the typed-service example, replace the tools path with the absolute path to `tools/sdk-tools.jar` in this SDK package.

| Directory | Purpose | Build and Run |
| --- | --- | --- |
| `examples/download-type-plugin/` | Download type, five acquisition modes, queue, scheduled source, and dedicated gallery (see its `README_en.md`) | From SDK root: `.\mvnw.cmd -f examples/download-type-plugin/pom.xml verify exec:exec@sdk-run` (stop with `exec:exec@sdk-stop`) |
| `examples/typed-service-plugin/` | Cross-plugin contract API, carrier, provider, and remote HTTP consumer (see its `README_en.md`) | From SDK root: `.\mvnw.cmd -f examples/typed-service-plugin/pom.xml package "-Dpixivdownload.sdk.tools=/absolute/path/to/tools/sdk-tools.jar"`; sign, install `contract` and restart first, then install `provider` and `consumer` |
| `examples/gradle-plugin/` | Declarative feature plugin built with Gradle (Wrapper 9.5.0) | In that directory: `.\gradlew.bat runPlugin`; replace the task with `debugPlugin` or `stopPlugin` to debug or stop |
| `examples/sbt-plugin/` | Declarative feature plugin built with sbt (1.10.11) | In that directory: `sbt runPlugin` or `sbt debugPlugin`; stop from another terminal with `java -jar ../../tools/sdk-tools.jar stop .` |

## 3. Dependency, Compatibility, and Architecture Rules

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>@SDK_VERSION@</version>
    <scope>provided</scope>
</dependency>
```

Use `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:@SDK_VERSION@")` in Gradle, `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "@SDK_VERSION@" % Provided` in sbt, or `<dependency org="io.github.sywyar.pixivdownloader" name="pixivdownload-sdk" rev="@SDK_VERSION@" conf="compile->default"/>` in Ivy (do not extend `compile` in your runtime configuration).

- **Versioning and `plugin.requires`**: Use `plugin.requires=major.minor` for a stable SDK, or `plugin.requires==FULL_SDK_VERSION` (e.g., `plugin.requires==7.2.3-rc.4`) for an exact prerelease SDK contract. When upgrading the SDK, extract the new SDK package into a separate folder and update the compile dependency, `plugin.requires`, `tools/`, `contracts/community/v1/`, and `sdk-project.json` together.
- **Customizing identity**: Update `src/main/resources/plugin.properties`, Java packages, routes, i18n namespaces, `plugin.version`, `plugin.provider`, and `pixiv.risk-signals` (see `contracts/community/v1/catalogs.json`) consistently.
- **Core contract boundaries**:
  - **Paths, credentials, and network**: Declare settings with `GuiConfigContribution`, resolve owner paths via `RuntimePathProvider`, manage secrets via `credentialKeys()` and `PluginCredentials`, and create outbound HTTP / WebSocket clients via `OutboundHttpClientFactory` / `OutboundWebSocketClientFactory`.
  - **Databases and migration**: Declare private schemas and ordered conversions via `PixivFeaturePlugin.privateSchema()` and `privateStorageMigrations()`, and query/mutate via `PluginDatabase` (`read` / `transaction` with `Query` / `Expression`, supporting SQLite, PostgreSQL, and offline migration via `DatabaseMigrationProvider`). Use `PluginMainDatabase` only for host-registered tables or `sharedMainStorage()` domains.
  - **Download tasks and file import**: Optionally register `DownloadSubmissionHandler`, `DownloadOptionsHook`, `DownloadAdmissionPolicy`, and `DownloadObserver` beans (using `RequestOwnerIdentityResolver` for HTTP callers), and register read-only local files via `WorkFileImporter`.
  - **Lifecycle verification**: Before publishing, verify installation, `stop` (rejecting new work, draining in-flight tasks, withdrawing routes/capabilities), `reload` (replacing generation and ClassLoader), and full-restart boundaries for `process-restart` plugins.

### Migrating from SDK 1.x

SDK 2.x uses JDK 25, Spring 7, Servlet 6.1, and Jackson 3. The host checks the SDK major version and rejects SDK 1.x packages during installation and startup. Update the source and rebuild; changing `plugin.requires` alone does not complete the migration. Spring and Jackson types come from the host; do not bundle older versions in the plugin.

| Removed entry | Replacement |
| --- | --- |
| `PluginDataSource` | Inject the current owner's `PluginDatabase` and use neutral queries and transactions |
| SQL string CHECK in `TableSpec` | Use `withCheck(Expression)` and declare known historical constraints with `withPreviousChecks` |
| `PixivPluginProvider.configurationClasses()` | Declare configuration classes in `pixiv.configuration-classes` in `plugin.properties` and register explicit `@Bean` methods |
| `DesktopUiHost.resolveDatabasePath` | Use neutral `databaseSettings()` and tool commands without assuming a file database |
| Constructors with an implicit locale policy | Pass an explicit `LocaleBundlePolicy` to `ResourceBundleMessageResolver` |
| `X-Pixiv-Cookie` and schedule `authorize-cookie` / `revoke-cookie` | Use `X-Acquisition-Credential` and `POST` / `DELETE /api/schedule/tasks/{id}/credential`; binding requires the source `activationToken` |
| Schedule view fields `type`, `lastStatus`, `lastMessage`, `runStartedTime`, `pendingRetryArmed` | Use `sourceType`, `runState`, `lastOutcome` / `outcomeCode` / `outcomeMessage`, `suspendReason`, and `lastRunTime`; read credential state from `credentialPolicy` |

Jackson data binding and streaming APIs use `tools.jackson.*`; annotations remain in `com.fasterxml.jackson.annotation`. `HttpHeaders` no longer implements `Map`; call `asMultiValueMap()` when needed. After compilation, verify plugin wiring, JSON input and output, persisted data, stopping, and generation replacement.

### HTTP callbacks and page forms

Use `OperationHook` to declare `BEFORE_INVOKE` checks or `AFTER_COMPLETION` callbacks for HTTP endpoints. For fixed targets, annotate a public synchronous method on a singleton plugin Bean with `@HttpHook`; an administrator must preview and approve the binding. Its only parameter is `OperationHook.Invocation`; before callbacks return `Decision`, while after callbacks may return void. `@Async` is unsupported. For fields, configuration or interactive challenges, implement `OperationHook` and annotate `invoke`.

`OperationVerification` supports interactive before checks. Pages load the shared submission runtime through `attachPage`, and the editor can configure form actions and endpoints. Business controllers and DTOs need no Guard calls or proof fields.

Callbacks receive endpoint and invocation metadata by default. Body, query and result fields require separate allowlists. HTTP completion does not establish background-task or transaction success, and a failed after callback does not replay the business action. Use `OperationGuard` for explicit local synchronous operations; do not consume proofs again at the same HTTP entry point.

The same declaration resumes after re-enabling the plugin; changes require approval again. Missing or ambiguous targets cannot be approved, and aliases of one handler share a binding. Disabling a plugin retains published before guards; an administrator must revoke and publish the binding to remove protection. See the [plugin development reference](https://sywyar.github.io/PixivDownloader/#/en/plugin-development) for configuration, field permissions and lifecycle details.

## 4. Candidate CI and Community Submission

The SDK package includes an initialized Git tree with `.pixivdownloader-plugin-project` and `.github/workflows/candidate.yml`:

1. Add your `LICENSE` and Git remote, push to your public GitHub repository's default branch, and wait for all `Plugin candidate` jobs to pass. To select a subproject or build profile, configure `projectDir` and `profileId` in `tools/candidate-projects.json`; supported profiles are `maven-java25-v1`, `gradle-java25-v1`, and `sbt-java25-v1`.
2. Follow the [community submission guide](https://github.com/Sywyar/PixivDownloader-community-plugins/blob/master/README_en.md#submissions-and-version-management) to run the PowerShell wizard, verify metadata and licenses, and optionally attach a `README`, links, and versioned release notes extracted from `CHANGELOG.md` (`## [vX.Y.Z]`).

## 5. Licensing

The SDK is licensed under [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE). Independent third-party plugins meeting `licenses/pixivdownloader/PLUGIN-LINKING-EXCEPTION.txt` may choose the MIT License (retain the upstream notices under `licenses/pixivdownloader/` when distributing or copying example projects).
