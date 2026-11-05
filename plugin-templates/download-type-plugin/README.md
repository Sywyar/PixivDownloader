# Download Type Plugin Template

This standalone Maven project demonstrates how to contribute a custom download type, five acquisition modes, queue operations, a scheduled source, a Vue UI slot, and a dedicated plugin gallery using the PixivDownloader Plugin SDK. All endpoints and queues in this template use deterministic in-memory sample data and perform no external network requests or disk writes. For template reuse and licensing, see the [Plugin Linking Exception](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt).

> **Execution mode notice:** Because this template uses a Spring child context and behavioral runtime contracts, `plugin.properties` declares `host-process-full-trust` and `process-restart` (with `pixiv.risk-signals=HOST_DATA_ACCESS`). Do not relabel a plugin containing configuration classes, controllers, queues, or scheduled executors as `declarative-process`.

## 1. Build and Run

1. Install JDK 25+, Maven, and Node.js (`java`, `mvn`, and `node` on `PATH`).
2. Run `mvn clean verify` in this directory and confirm `target/example-download-plugin-0.1.0.jar` is generated.
3. Install the JAR via Plugin Management in a compatible host, complete the administrator trust confirmation, and perform a full process restart.
4. Sign in as administrator, test the example download type in the download workbench, and open `/example-download-gallery.html`.

Keep the Git-tracked `.pixivdownloader-plugin-project` marker (`pixivdownloader-plugin-project-v1` + LF) in the project root (excluded from the JAR).

## 2. Customize the Template

Replace all template identities consistently across Java classes, `plugin.properties`, frontend modules, i18n bundles, and tests:

| Template value | Replace with |
| --- | --- |
| `example-download-plugin` | Your Maven `artifactId` |
| `example-download` | Globally unique plugin ID, queue type ID, and i18n namespace |
| `com.example.pixivdownload.downloadtype` | Your Java package and directory path |
| `ExampleDownload*` | Your Java class prefix |
| `0.1.0` | Your plugin artifact version |
| `plugin.requires` | `MAJOR.MINOR` for a stable SDK, or `=FULL_SDK_VERSION` (e.g., `plugin.requires==7.2.3-rc.4`) for a prerelease SDK |
| `plugin.provider=Example Developer` | Your publisher name |
| `/api/example-download/**`, `/example-download/**` | Plugin-owned API and static resource paths |

## 3. Contracts Demonstrated and Boundaries

- **PF4J & Spring child context**: Explicit `@Bean` registration via `PixivPluginProvider.configurationClasses()` and `pixiv.configuration-classes` (no host component scanning).
- **Download type & queue lifecycle**:
  - `DownloadTypeDescriptor` (contract version 1) declaring `single-import`, `user`, `series`, `search`, and `quick` modes, `cancelSupported=true`, filters, settings, and behavior module URL.
  - `DownloadSubmissionHandler`, `DownloadOptionsHook`, and `DownloadObserver` wired through `DownloadLifecycle` / `QueueTaskTracker` (admin submission via `DownloadTasks.submit` or `POST /api/download/tasks`).
  - `QueueOperations` with opaque `workKey` cancellation (sent by the host bridge in JSON to `POST /api/download/queue/{queueType}/cancel`). Real asynchronous downloaders must override `prepareQuiesce` to return and drain a positive generation `QueueDrain` and report completion only after files and records are durably committed.
- **Trusted request identity**: Controllers resolve the caller via the host-provided `RequestOwnerIdentityResolver` and never trust caller-supplied owner UUIDs.
- **Frontend isolation & independent gallery**:
  - Behavior and UI-slot modules interact only through the scoped `context` (`updateItem`, `publishWorks`, `supports`, `dispatchQuickAction`, `acquisitionInput`, `restoreAcquisition`) and host `PixivVue` without bundling Vue or touching private host globals.
  - Dedicated gallery at `/example-download-gallery.html` contributing its own routes, static assets, controller, i18n, and `gallery.type-switch` navigation entry (never mounting into `/pixiv-gallery.html`).
