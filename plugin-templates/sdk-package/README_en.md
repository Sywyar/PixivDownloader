# PixivDownloader Plugin SDK @SDK_VERSION@

[简体中文](README.md)

The extracted root is a standalone Maven project with sources in `src/`. Its SDK identity is `@SDK_RELEASE_ID@`, built from main-repository commit `@SOURCE_SHA@`. Open `docs/javadocs/index.html` for the API reference.

## Start developing

Install JDK 17 and Node.js, with `java` and `node` on `PATH`. IDE import only resolves the project. Explicit Run / Debug builds the current plugin, prepares the pinned runtime, installs the new artifact, and starts the full application. Build failure stops this sequence. Maven Wrapper obtains the pinned Maven version; no host checkout or manually copied host and plugin JARs are needed. Initial application configuration uses the host's setup flow.

| IDE | Import | Run | Debug |
| --- | --- | --- | --- |
| IntelliJ IDEA | Open the root `pom.xml` | Shared `Run Plugin` configuration | Shared `Debug Plugin` compound configuration |
| VS Code | Open this directory and install the recommended Java Extension Pack | `Tasks: Run Task > Run Plugin` | `Run and Debug > Debug Plugin` |
| Eclipse | `Import > Existing Maven Projects` | `eclipse/Run Plugin.launch` | `eclipse/Debug Plugin.launch` group |

Set a breakpoint inside `ExampleMinimalPlugin.java`'s `routes()`. The default `declarative-process` plugin is debugged in its worker; a `host-process-full-trust` plugin is debugged in the host JVM. The default address is `127.0.0.1:5005`. IntelliJ starts a listener for the tool to connect to. VS Code and Eclipse attach after the host process is created.

Use `Stop Plugin`, or stop the entire run / debug group, to finish. Disconnecting only the remote debugger does not stop the application. The next Run builds and deploys the current artifact again.

## Command line

Windows:

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd verify exec:exec@sdk-debug
.\mvnw.cmd exec:exec@sdk-stop
```

Linux / macOS:

```bash
sh ./mvnw verify exec:exec@sdk-run
sh ./mvnw verify exec:exec@sdk-debug
sh ./mvnw exec:exec@sdk-stop
```

`sdk-debug` waits for an IDE to attach; it does not open a debugger. Use `clean verify` to validate the plugin, or `exec:exec@sdk-prepare` to prepare only the runtime. The default artifact is `target/example-minimal-plugin-0.1.0.jar`.

After successfully building the current artifact, you can call the bundled tool directly:

```text
java -jar tools/sdk-tools.jar run <absolute-project-path> <absolute-current-plugin-JAR> --no-gui
java -jar tools/sdk-tools.jar debug <absolute-project-path> <absolute-current-plugin-JAR> --debug-port=5005
java -jar tools/sdk-tools.jar stop <absolute-project-path>
```

`--debug-connect` connects to an IDE already listening. The tool accepts artifacts inside the project and outside `.dev/`, preserving their declared execution mode. Explicit Run confirms the current JAR's SHA-256 for normal local installation. Official plugins retain signature and provenance checks. Duplicate plugin IDs and `replaces` are rejected; choose a unique ID.

## Independent examples

| Directory | Purpose | Run / debug / stop |
| --- | --- | --- |
| `examples/download-type-plugin/` | Download types, queues, scheduled sources, and a plugin-owned gallery | From the SDK root: `mvnw -f examples/download-type-plugin/pom.xml verify exec:exec@sdk-run`; use `sdk-debug` to debug, or only `exec:exec@sdk-stop` to stop |
| `examples/gradle-plugin/` | Build the basic feature plugin with Gradle | In that directory: `gradlew runPlugin`, `gradlew debugPlugin`, `gradlew stopPlugin` |
| `examples/sbt-plugin/` | Build the same feature plugin with sbt | Install sbt, then run `sbt runPlugin` or `sbt debugPlugin` in that directory; stop from another terminal with `java -jar ../../tools/sdk-tools.jar stop .` |

Use `mvnw.cmd` / `gradlew.bat` on Windows, or `sh ./mvnw` / `sh ./gradlew` on Linux / macOS. Import each example separately. Each owns its `sdk-project.json` and `.dev/`, and uses the root `tools/sdk-tools.jar`. Gradle Wrapper pins 9.5.0; the sbt project pins 1.10.11. Gradle / sbt examples compile, package, and check JavaScript syntax. Maven projects also include JUnit and thin JAR checks.

## One SDK dependency

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>@SDK_VERSION@</version>
    <scope>provided</scope>
</dependency>
```

Gradle uses `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:@SDK_VERSION@")`. sbt uses `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "@SDK_VERSION@" % Provided`. Standard Ivy can map its compile configuration:

```xml
<dependency org="io.github.sywyar.pixivdownloader" name="pixivdownload-sdk"
            rev="@SDK_VERSION@" conf="compile->default"/>
```

Do not make Ivy's runtime configuration extend this compile configuration. Standard Maven metadata supplies public APIs and PF4J, Spring, Servlet, and Jackson compile dependencies. Produce a thin PF4J JAR without bundling these host-provided classes. Declare test frameworks separately. The three API modules and BOM remain individually available.

## Runtime, cache, and project data

`sdk-project.json` and the release-side `sdk-release.json` record the same SDK, host, official plugin manifest, and runtime ZIP identities, sizes, and SHA-256 hashes. The runtime ZIP is a separate SDK Release attachment. Explicit preparation downloads it once and reuses verified bytes in `~/.cache/pixivdownloader-sdk/`. Clearing the cache still selects the same bytes. Unavailable resources and hash mismatches fail.

Every launch creates a private runtime copy and checks the host and each official plugin. The cache holds no application state. Project `.dev/` contains configuration, databases, logs, downloads, and run copies. Configuration and state are separated by runtime ZIP hash. Host and official plugin automatic updates are disabled in this environment; your regular installation's data is separate.

After stopping, delete the project's `.dev/` to reset development data, including downloads stored there. Direct tool calls can select a cache with the JVM property `-Dpixivdownload.sdk.cache-dir=<directory>`; project runtime directories remain separate.

Offline use requires both runtime preparation and cached build-tool dependencies. Maven uses `-o`; Gradle uses `--offline`. Incomplete caches fail. To update the SDK, use the new development package and matching manifest, then move your sources into it.

Windows paths containing Chinese characters and spaces, command-line runs, and source breakpoints through IntelliJ's native listener have been tested. One-click startup of the IntelliJ compound configuration and the VS Code and Eclipse GUI flows have not been tested. Deep directories can still exceed Windows' working-directory limit when creating a worker; move to a shorter path if error 267 occurs. Other platforms declared by the runtime require verification on their own operating systems.

## Customize the plugin

The descriptor is `src/main/resources/plugin.properties`. Keep the plugin ID, Java package, routes, i18n namespace, version, and provider consistent. Maven runtime tasks use the actual `finalName`. Update the Eclipse configurations' project name if you rename the imported project.

Stable contracts cover routes, static assets, i18n, navigation, Web UI slots, GUI configuration, download types, queues, scheduled sources, and notification templates. `examples/download-type-plugin/README_en.md` explains the five acquisition modes, cancellation and drain, credential policy, guards, and `gallery.type-switch`. Each plugin owns its gallery pages, APIs, assets, and data operations.

Declare configuration through `GuiConfigContribution`. Use owner-bound `RuntimePathProvider` paths, `PluginDataSource` for private databases, and stable HTTP / WebSocket factories and route contracts. Do not depend on the app, plugin-runtime, installer, signature internals, host database, or concrete GUI provider. Verify contribution withdrawal on disable, unload, and reload in a real host.
