# PixivDownloader Plugin SDK @SDK_VERSION@

This workspace opens directly in IntelliJ IDEA, VS Code, or Eclipse. `plugin/` is the default full download-type plugin project, while `examples/minimal-feature-plugin/` is the basic feature-plugin reference. The SDK identity is `@SDK_RELEASE_ID@`, built from main-repository commit `@SOURCE_SHA@`.

## Start now

Install JDK 17 and make Node.js available on `PATH`. Opening this directory imports the `plugin/` Maven module from the root `pom.xml`; IDE import never downloads or executes a PixivDownloader host.

Windows:

```powershell
.\mvnw.cmd clean verify
```

Linux / macOS:

```bash
sh ./mvnw clean verify
```

The artifact is `plugin/target/example-download-plugin-0.1.0.jar`. It is a thin PF4J JAR: SDK, PF4J, Spring, Servlet, and Jackson dependencies remain `provided` and must not be copied into the plugin.

## IDE entry points

- IntelliJ IDEA: open this directory and run the shared `Verify Plugin` Maven configuration.
- VS Code: open this directory and run `Verify Plugin` or `Package Plugin` from Tasks.
- Eclipse: choose `File > Import > Existing Maven Projects` and import this directory; M2E imports the `plugin/` module.

## What a plugin can contribute

Stable contracts cover routes, static assets, i18n, navigation, Web UI slots, GUI configuration fields, download types, queue operations, scheduled sources, notification templates, and other published capabilities. The host does not know concrete plugins, and a plugin must not depend directly on the app, plugin-runtime, installer, signature internals, or another concrete plugin.

The full template demonstrates all five acquisition modes, targeted queue cancellation and drain, scheduled sources, work execution, credential policy, guards, a plugin-owned gallery, and the `gallery.type-switch` navigation placement. A download-type plugin owns its gallery page, API, assets, i18n, queries, and actions. There is no generic gallery provider or `/api/gallery/unified/**` extension point.

Declare plugin configuration through `GuiConfigContribution`. Use the owner-bound `RuntimePathProvider` for private config, state, and data paths, and `PluginDataSource` for a private database. Never depend on host `RuntimeFiles`, `ProxyConfig`, `DownloadConfig`, the host database, or a desktop UI provider implementation. Outbound HTTP and WebSocket traffic uses the stable factories and route contracts.

## Host Developer Mode

Run `clean verify` first. Provide a PixivDownloader host JAR compatible with the target SDK major/minor and verify its origin yourself. Start it from a controlled directory with these explicit JVM properties:

```text
-Dpixivdownload.plugin-dev.enabled=true
-Dpixivdownload.plugin-dev.root=<absolute path to this SDK workspace>
```

The host discovers `plugin/target/classes` and materializes it into an isolated development cache. The SDK never downloads a host during IDE import and never executes an unverified remote file. Verify contribution withdrawal on stop, disable, unload, reload, and publication replacement against a real host.

## Continue

Follow the replacement table in `plugin/README_en.md` to change the artifact id, plugin id, Java package, routes, i18n namespace, version, and provider. The official Douyin plugin is a complete reference implementation only; it is not an SDK dependency or a special contract.

`sdk-project.json` records the SDK coordinates and exact source identity. The release-side `sdk-release.json` and `SHA256SUMS` record attachment hashes.
