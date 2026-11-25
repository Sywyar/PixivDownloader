# Third-party plugin SDK

Start a third-party plugin from an independent project template in the SDK development package. You need only JDK 25, Node.js, and the build tools and runtime manifest included in the package; cloning the main repository is unnecessary.

SDK 2.x is built on JDK 25, Spring Framework 7, Servlet 6.1 and Jackson 3 for the Spring Boot 4 host; SDK 1.x plugins are rejected during installation and startup checks.

- **Key migration items from SDK 1.x to 2.x** (changing only `plugin.requires` is not enough; adapt to the new contracts and recompile):
  - **Child context and descriptor**: `PixivPluginProvider` retains only `featurePlugin()`; declare Spring child-context `@Bean` configuration classes via `pixiv.configuration-classes` in `plugin.properties`.
  - **Database and UI contracts**: replace `PluginDataSource` with dialect-neutral `PluginDatabase` and SQL check strings with neutral `TableSpec.check` expressions; replace `DesktopUiHost.resolveDatabasePath` with the database-target contract; pass an explicit `LocaleBundlePolicy` for resource bundles.
  - **Credentials and scheduled-task views**: pass acquisition credentials in `X-Acquisition-Credential` and bind/revoke scheduled-task credentials via `POST` / `DELETE /api/schedule/tasks/{id}/credential` (satisfying activation-token and authorization checks). `ScheduleTaskView` removes `type`, `lastStatus`, `lastMessage`, `runStartedTime` and `pendingRetryArmed` in favor of `sourceType`, `runState`, `lastOutcome`, `suspendReason`/`suspendCode`, `outcomeCode`/`outcomeMessage` and `lastRunTime`.
  - **Jackson 3 and Spring 7**: Jackson databinding classes move to `tools.jackson.*` (annotations remain in `com.fasterxml.jackson.annotation`); call `HttpHeaders.asMultiValueMap()` when a `Map` view is needed.

| Development task | Sections |
| --- | --- |
| Create and debug your first plugin locally | [Start from a template](#start-from-a-template) · [Local development](#local-development) |
| Add a downloadable work type or independent gallery | [Complete workflow for a new download type](#complete-workflow-for-a-new-download-type) · [Add a plugin-owned gallery](#add-a-plugin-owned-gallery) |
| Manage settings, encrypted credentials, files and private databases | [Configuration, credentials and files](#configuration-credentials-and-files) · [Credentials and general operation verification without a GUI dependency](#credentials-and-general-operation-verification-without-a-gui-dependency) |
| Declare cross-plugin service contracts or page editor components | [Independent service contracts and composition](#independent-service-contracts-and-composition) · [Ordinary slots and page components](#ordinary-slots-and-page-components) |
| Upgrade the SDK, submit to the community market or distribute from your own repository | [Maintain external projects and upgrade the SDK](#maintain-external-projects-and-upgrade-the-sdk) · [Submit market materials](#submit-market-materials) · [Signing and distribution](#signing-and-distribution) |

`SDK_VERSION`, package names, plugin IDs, paths, SHA-256 values and signatures below are placeholders. Replace them with actual values. Put code snippets into the corresponding template context and add imports and business implementations. Interface availability depends on your selected [SDK Release](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) and [versioned Javadoc](https://sywyar.github.io/PixivDownloader-Plugin-SDK/).

Source references: [Plugin templates](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates) · [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info) · [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api) · [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api) · [Signature tools](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature) · [Complete Douyin example plugin](https://github.com/Sywyar/PixivDownloader-Plugin-Douyin) (demonstrates downloads, configuration, proxies, queues, scheduled tasks, a private database and an independent gallery, but is not part of official distributions; start new projects from `plugin-templates`).

## Plugin licenses and distribution channels

PixivDownloader and official plugins use [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE). Versions accompanied by the [plugin linking exception](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt) grant additional permission: independent third-party plugins developed solely through public SDK / APIs and meeting the exception's conditions may choose licenses such as MIT (retain upstream copyright notices and the exception text when copying and modifying project templates; the host, SDK and official plugins remain under AGPL-3.0).

The [official community plugin repository](https://github.com/Sywyar/PixivDownloader-community-plugins) accepts only open-source plugins, requiring complete public GitHub source, an open-source license, reproducible builds and manual review.

## Trust and SDK architectural boundaries

- **Execution modes and trust boundaries**: `declarative-process` runs in a separate worker JVM (resource and protocol isolation), while `host-process-full-trust` runs in the host JVM. Both share the current OS account and are not OS-level security sandboxes. Ed25519 signatures prove only publisher identity and byte integrity; you must still trust the author and source before installation (see [Plugin management](/en/plugin-management)).
- **Compile-time dependency direction**: third-party plugins declare a single `pixivdownload-sdk` dependency (`provided`), which transitively supplies `pixivdownload-sdk-info`, `pixivdownload-plugin-api`, `pixivdownload-core-api`, and host-provided PF4J, Spring, Servlet and Jackson compile dependencies (the three API modules and BOM may also be used separately). **Do not depend on `pixivdownload-app`, internal host implementation classes, private official-plugin classes, the host `DataSource` or private frontend globals**.
- **Capability publication and absence handling**: plugins contribute capabilities through `PixivFeaturePlugin` and declare independent Spring child-context configuration classes through `plugin.properties` / `pixiv.configuration-classes`. When a plugin is disabled, unloaded or replaced by a new generation, the host withdraws routes, static resources, i18n, navigation, download types, queues and scheduling capabilities by publication. Callers must handle absent capabilities without null-pointer errors or partially completed tasks.
- **Desktop UI boundaries**: the host aggregates configuration, onboarding and status through toolkit-independent pure-data contracts such as `GuiConfigContribution`. `gui-compose` (default) and `gui-swing` (fallback) render their own interfaces; feature plugins do not return Swing / Compose components.

### Main `PixivFeaturePlugin` contribution methods

| Method | Contribution |
| --- | --- |
| `id`, `displayName`, `description`, `displayNamespace`, `iconKey`, `colorToken`, `kind` | Plugin identity, i18n display keys and controlled icon/color/category tokens |
| `start`, `stop` | Lifecycle of plugin-owned local resources (`stop` must be idempotent) |
| `privateSchema`, `privateStorageMigrations` | Authoritative private schema for the current owner and ordered data migration declarations |
| `schema`, `sharedMainStorage` | Managed tables participating in host main-database collaboration and requests for public storage domains (no access to other owners' tables) |
| `routes`, `staticResources`, `i18n` | Web route access policies, classpath static-resource mappings and frontend i18n namespaces |
| `navigation`, `startupRoutes`, `landings`, `pageSections`, `uiSlots`, `drilldowns` | Navigation items, startup landing points, page sections, Web UI slots and drilldown links |
| `guiConfigContributions`, `guiOnboardingSteps`, `guiThemes` | Desktop configuration fields/groups/actions/presets, onboarding steps and startup themes |
| `userscripts`, `scheduledSourceDescriptors`, `downloadTypes` | Userscript resources, scheduled-task source descriptors and download-workbench work-type descriptors |

## Start from a template

| Template | Use case | Included |
| --- | --- | --- |
| `minimal-feature-plugin` | Declarative pages, navigation and i18n | `declarative-process` worker, PF4J entry point, route/static/i18n and thin JAR tests (no Spring child context) |
| `download-type-plugin` | Add a downloadable work type and gallery | `host-process-full-trust`, download type descriptor, 5 acquisition modes, queue, scheduled source, Vue slots, independent gallery and frontend/backend tests |
| `typed-service-plugin` | Cross-plugin typed services and remote calls | Independent `api` / `contract` / `provider` / `consumer` multi-module project and Schema/client generation |

Once copied outside the repository, a template is an independent Maven project that does not inherit the main repository's parent. Run `mvn clean verify` to validate it (inside the main repository, use `mvn -f plugin-templates/pom.xml clean verify`).

Maven dependency declaration (if Java controllers use `@PathVariable` / `@RequestParam` without explicit names, set `<maven.compiler.parameters>true</maven.compiler.parameters>` or `-parameters`):

```xml
<dependency>
    <groupId>io.github.sywyar.pixivdownloader</groupId>
    <artifactId>pixivdownload-sdk</artifactId>
    <version>SDK_VERSION</version>
    <scope>provided</scope>
</dependency>
```

For Gradle, use `compileOnly("io.github.sywyar.pixivdownloader:pixivdownload-sdk:SDK_VERSION")`; for sbt, use `"io.github.sywyar.pixivdownloader" % "pixivdownload-sdk" % "SDK_VERSION" % Provided`. After copying the template, replace the Maven `artifactId`, globally unique plugin ID, Java package name and class prefix, version, route prefix, static-resource directory, private table names and i18n namespace consistently.

## Package descriptor and child-context wiring

The JAR root must contain `plugin.properties`:

```properties
plugin.id=example-download
plugin.version=0.1.0
plugin.requires=2.0
plugin.class=com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin
plugin.provider=Example Developer
plugin.description=Example download type.
pixiv.display-namespace=example-download
pixiv.display-name-key=plugin.name
pixiv.description-key=plugin.summary
pixiv.icon-key=download
pixiv.color-token=green
pixiv.execution-mode=host-process-full-trust
pixiv.configuration-classes=com.example.pixivdownload.downloadtype.ExampleDownloadConfiguration
pixiv.lifecycle-policy=process-restart
```

- `plugin.id` must exactly match `PixivFeaturePlugin.id()`. `plugin.requires` declares the required SDK version (stable versions match by `requiredMajor == hostMajor && requiredMinor <= hostMinor`; prereleases use `alpha.N` / `beta.N` / `rc.N` and require an exact match).
- `pixiv.execution-mode` is required (`declarative-process` or `host-process-full-trust`). `pixiv.lifecycle-policy` can be `hot-reload` (default), `backend-restart` or `process-restart`; `pixiv.risk-signals` may declare risk-signal tokens.
- `pixiv.desktop-only` defaults to `false`. Set it to `true` for a desktop-only plugin. During headless startup, the host skips the plugin unless a backend plugin requires it directly or through another required dependency. Skipped packages still appear as installed on the plugin management page.
- `plugin.dependencies` names external PF4J packages. A required dependency on a built-in host component such as `core` is rejected before installation. Use `plugin.requires` to declare the required host SDK version.
- Desktop UI providers can call `DesktopUiHost.stopBackend(afterStop, onFailure)` to request a backend stop. Start tools that need exclusive access to data in `afterStop`. An accepted request invokes one callback according to the outcome. A return value of `false` means the request was rejected immediately and neither callback runs.
- The external entry point implements `PixivPluginProvider` and returns one `PixivFeaturePlugin`. Declare explicit Spring child-context `@Bean` configuration classes in the descriptor property `pixiv.configuration-classes`; do not rely on host package scanning:

```java
public final class ExampleDownloadPf4jPlugin
        extends org.pf4j.Plugin implements PixivPluginProvider {
    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new ExampleDownloadPlugin();
    }
}
```

### Web routes, static resources, i18n, notification templates and survey slots

Every Controller path, static resource and HTML page must have an `AccessPolicy` declared by the same plugin in `routes()` (supports `publicRoute`, `visitor`, `visitorAndInvitedGuest`, `invitedGuest`, `admin`, `local`, `gui`; undeclared `path + method` combinations return 404):

```java
@Override
public List<WebRouteContribution> routes() {
    return List.of(
            WebRouteContribution.admin("/example-download-gallery.html"),
            WebRouteContribution.admin("/example-download-gallery/**"),
            WebRouteContribution.admin("/api/example-download/gallery"));
}
```

- **Business notification templates**: the scenario owner registers a `NotificationTemplateContributor` Bean in its child context, returning pure-value `NotificationTemplateContribution(scenarioId, medium, locale, titleTemplate, bodyTemplate)` (at most 16 KiB per title, 1 MiB per body, and 256 entries / 8 MiB per plugin; HTML-escape untrusted values before substitution).
- **PostHog surveys and inbox messages**: Web plugins can declare `plugin.dependencies=posthog?@1.0`, load `/pixiv-posthog/pixiv-posthog.js` and call `window.PixivPostHog.createSurveyClient` and `captureSurveyWithAck`. For a persistent administrator inbox message, contribute a `notification.inbox` slot using `new SurveyInboxMessage(...).toUiSlotContribution()`.

## Complete workflow for a new download type

The same plugin contributes the parts of a download type together:

1. **Declare `DownloadTypeDescriptor`**: return a descriptor from `PixivFeaturePlugin.downloadTypes()` (`contractVersion = DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION`, globally unique `type`, i18n keys, icon and color tokens, same-origin behavior-module `moduleUrl`, supported acquisition modes `single-import` / `user` / `series` / `search` / `quick`, `cancelSupported`, and filter/settings allowlists).
2. **Implement the frontend behavior module**: call `PixivBatch.queueTypes.registerModule(initializer)` from `moduleUrl`. Implement `process(item, context)` (required; update state through `context.updateItem(patch)` and bind the execution UUID through `context.bindTask(taskId)` when using `DownloadTasks`), plus declared `import`, `acquisition.*`, `filters` and `settings`. Check `AbortSignal` / `isActive()` before writing back from async callbacks, and release resources in `onCleanup()`.
3. **Resolve trusted identity in the backend**: inject the parent context's `RequestOwnerIdentityResolver` into the Controller to resolve the current request identity (never trust an owner UUID self-reported in a request body or parameter). Mark completion only after files are durably written and records committed.
4. **Implement `QueueOperations` and real Quiesce / Drain**: `workKey` is an opaque string (passed by the host in the JSON body of `POST /api/download/queue/{queueType}/cancel`). Any queue with async or background tasks must override `prepareQuiesce`, stop new admissions and return a real `QueueDrain` with a positive generation. Complete the drain only once in-flight tasks reach zero.
5. **UI slots and scheduled-task capabilities (optional)**: contribute `WebUiSlotContribution` through `uiSlots()` (use host `PixivVue.mountUiSlot`; do not bundle the Vue runtime). Contribute scheduling support through `scheduledSourceDescriptors()` and child-context `ScheduledSourceExecutor` / `ScheduledWorkExecutor` Beans (the host manages scheduling, locks, credentials and watermark commits). When a source executor needs to wait for the current batch before advancing pages or switching work categories, call `context.workSink().drain()`, then check `context.isPending(key)` to see whether any work on the current page is still awaiting retry.
6. **Integrate with unified task submission `POST /api/download/tasks` (`DownloadSubmissionHandler`)**: register a `DownloadSubmissionHandler` Bean in the plugin child context:
   - Return the work-type identifier from `workType()` and optionally the absolute classpath path of a JSON Schema from `inputSchemaResource()` for OpenAPI generation.
   - Validate options in `submit` and register the host-supplied `DownloadAttempt` via `DownloadLifecycle.track` before returning.
   - **Structured error and reason propagation**: the host calls the SDK default method `submitAndReportFailure` to propagate failure reasons across the plugin proxy (existing handlers can continue overriding `submit`). Throw `new DownloadTaskException(DownloadTaskException.Code.WORK_NOT_FOUND)` when upstream metadata lookup returns 404 so initial submission returns `404 WORK_NOT_FOUND` (keep resource-level failure semantics when only a single page or attachment is missing); throw `InputFieldException` with a JSON Pointer and stable reason code (such as `/options/pages` and `out_of_range`, without raw input values or credentials) when options are invalid.
   - **Progress reporting**: when the total for the current step is known and greater than `0`, call `DownloadLifecycle.reportProgress(attempt, task, progress)` using the queue handle registered for that attempt (`DownloadTaskProgress` supports `PAGES`, `IMAGES` or `BYTES` with matching completed/total units; final outcome is still determined by the terminal event).

## Add a plugin-owned gallery

Each download-type plugin fully owns its gallery HTML, CSS/JS, routes, i18n and Controller (`/pixiv-gallery.html` and `/api/gallery/**` belong only to the official Pixiv gallery plugin; there is no generic gallery aggregation API).

To join the cross-type switcher at the top of galleries, put this at the top of your gallery HTML:

```html
<nav data-nav-slot="gallery.type-switch"></nav>
```

Contribute a `NavigationPlacements.GALLERY_TYPE_SWITCH` item through `navigation()` (use `gallery.sidebar`, `gallery.sidebar.tools`, `novel.sidebar`, `novel.sidebar.tools` for sidebar entries):

```java
new NavigationContribution(
        "example-gallery-type-switch",
        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
        "example-download", "nav.gallery",
        "/example-download-gallery.html", "images",
        AccessPolicy.ADMIN, 50)
```

## Configuration, credentials and files

| Data category | Disk path | Plugin access |
| --- | --- | --- |
| Host settings and plugin switches | `config/config.yaml` | Read-only through stable Core API ports such as `DownloadSettings` and `OutboundProxySettings` |
| Plugin business settings | `config/plugins/{pluginId}.properties` | Child-context `Environment` (prefer `Binder.get(environment).bind(...)`) and `GuiConfigContribution` |
| Sensitive plugin credentials | `config/credentials/{pluginId}.properties` | Encrypted by the host; only sensitive keys declared by this owner are injected into its child-context `Environment` or `PluginCredentials` |
| Plugin state and persistent data | `state/{pluginId}/`, `data/{pluginId}/` | Inject `RuntimePathProvider` and call `stateDirectory()`, `dataDirectory()` |
| Downloaded work files | `{rootFolder}/{pluginId}/` or a plugin-defined directory | Derive from `DownloadSettings.getRootFolder()`; do not write them to `state/` or `data/` |

- **Database access**: new plugins inject `PluginDatabase`, declare structure through `privateSchema()` and `privateStorageMigrations()`, and use `read(work)` / `transaction(work)` for dialect-independent operations (compatible with SQLite and PostgreSQL). For main-database collaboration, inject `PluginMainDatabase` to access this owner's managed tables or requested `sharedMainStorage()`.
- **Outbound HTTP / WebSocket**: inject `OutboundHttpClientFactory` / `OutboundWebSocketClientFactory` to create clients owned by plugin Beans and `close()` them on destruction (response streams returned by `exchangeStream` must be closed on every branch). Do not create your own `HttpClient` or `ProxySelector`, or depend on host HTTP configuration classes.

## Independent service contracts and composition

SDK 2.x supports cross-plugin typed services (see the `typed-service-plugin` template):

- Contract-carrier plugins declare an original pure-JDK API JAR through `pixiv.contract-api` and `pixiv.contract-sha256` (using `process-restart`). Providers export implementations through `serviceExports()`, while consumers declare dependencies in `serviceImports()` and inject `PluginServices`.
- Call `find(key)`, `require(key)`, `findAll(key)`, or lifecycle bindings `bind(keys, activation)` / `bindAll(keys, activation)` to obtain proxies bound to an exact publication.
- Check the upstream capability through the host API at the point of use and handle a missing capability or a rejected call within the operations that depend on it, so independent operations can continue.
- Use `sdk-tools.jar` to generate JSON Schema and remote-call adapters, and check binary compatibility:

```text
java -jar sdk-tools.jar schema <api-module> <classpath>
java -jar sdk-tools.jar remote <consumer-module> <classpath> example.one.Service example.two.Service
java -jar sdk-tools.jar contract-evolution <old-api.jar> <new-api.jar> <new-report.json>
```

Annotate large-integer record fields with `@ValueProperty(encoding = ValueEncoding.DECIMAL_STRING)`. `UUID`, `Instant`, `Duration`, `float` and `double` use the SDK's fixed lossless string encoding.

## Credentials and general operation verification without a GUI dependency

- **`PluginCredentials`**: plugins can declare sensitive keys under their owner prefix in `credentialKeys()` (at most 128 keys, 64 KiB per value, 1 MiB per batch; same-named GUI fields must be marked sensitive), then inject `PluginCredentials` for atomic `read`, `update` and `delete`.
- **HTTP Hooks**: provide an `OperationHook` Bean implementing `id()`, `definition()`, `generation()` and `invoke(...)`. The host discovers actual Spring mappings, including owner, methods, route templates and matching conditions. Ordinary and dynamic external controllers need no Hook annotations or Guard wrappers. Approved bindings select an endpoint or an explicitly configured endpoint group; `OperationEndpoint` is optional grouping metadata.
- **Bindings and discovery**: administrators read `/api/web/operation-hooks/catalog`, `definitions` and `configuration`. Preserve existing settings, send `{revision, settings}` to `prepare` under the same prefix, review targets, affected pages and permissions, then send `{plan, approvals}` to `publish`. Settings contain `groups` and `bindings`; each binding supplies `id/hook/operation/endpoint/phase/order/completion/configuration/fields`, selecting either `operation` or `endpoint`. `resolveTargets` and `getFormEndpoints(pageId, formId, actionId)` use one resolver. Handle undeclared, ambiguous, forbidden and unavailable results; discovery grants no invocation or binding permission.
- **Execution and data**: `BEFORE_INVOKE` returns `CONTINUE/REJECT/CHALLENGE` after host authentication, CSRF and admission, before the controller. Metadata is the default input. Approved `fields` allowlists enable bounded body, query and result extraction; credential fields are forbidden. Providers receive pure values, never servlet objects or business actions. `AFTER_COMPLETION` filters `SUCCEEDED/FAILED/BLOCKED/TIMED_OUT/CANCELLED` or `ALL`. Success means an HTTP status below 400; it does not establish domain success inside the body, transaction commit or background-task completion. SSE, downloads and asynchronous requests expose completion metadata without buffering the entire stream.
- **Challenges and lifecycle**: the host uses `POST /api/operation-hooks/prepare` and `X-Pixiv-Hook-Ticket` for preparation, publication checks and one-time consumption. Business DTOs need no proof fields; the framework does not blindly replay requests. Missing, failed or timed-out required before providers reject execution. After failures preserve the HTTP result without promising durable delivery. A timeout does not stop Java work: leases retain the exact generation until actual exit. Limits are 16 bindings and 128 form relations per page, 16 approved fields per binding, 64 active HTTP requests, 8 workers, 64 queued tasks, and 10 seconds each for cumulative before waiting and after waiting.
- **Diagnostics and local calls**: default English logs record correlation and invocation IDs, trusted plugin generation/publication, callback entry, binding source, handler/method/route template, phase and outcome. Proofs and request bodies are excluded. `OperationVerification` adapts interactive before Hooks. Keep `OperationGuard` for explicit local synchronous operations; do not call it again at the same HTTP entry. See [Operation CAPTCHA](/en/captcha) for verification components.

### Declare a fixed HTTP target with an annotation

SDK 2.x supports `@HttpHook` on a singleton `@Bean` method in the plugin child context without modifying the target Controller. The example below counts successful requests to the local health probe `GET /api/download/status` (note that this endpoint is a status probe, not a work-download completion event):

```java
import java.util.concurrent.atomic.AtomicLong;
import top.sywyar.pixivdownload.plugin.api.operation.HttpHook;
import top.sywyar.pixivdownload.plugin.api.operation.OperationHook;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

public final class StatusObserver {
    private final AtomicLong completed = new AtomicLong();

    @HttpHook(id = "example-status-observer", targetOwner = "core",
            method = HttpMethod.GET, path = "/api/download/status",
            phase = OperationHook.Phase.AFTER_COMPLETION,
            completion = OperationHook.Completion.SUCCEEDED)
    public void onStatus(OperationHook.Invocation invocation) {
        completed.incrementAndGet();
    }

    public long count() { return completed.get(); }
}
```

- **Method signature and limits**: callbacks must be `public` synchronous instance methods on a singleton Bean taking a single `OperationHook.Invocation` parameter (`BEFORE_INVOKE` returns `Decision`; `AFTER_COMPLETION` may return `void`; `@Async` or continuing work asynchronously after returning is unsupported). Each plugin may declare up to 16 interface or annotated Hooks in total; host logs record the callback class and method.
- **Target resolution**: `targetOwner`, `method` and `path` select a handler from the live route catalog; if multiple candidates share the same path, specify exact condition sets via `params`, `headers`, `consumes` and `produces` (empty lists leave that dimension unfiltered; missing or ambiguous targets cannot be approved, and all route aliases of the resolved handler share the binding).
- **Field extraction and interactive challenges**: plain annotated methods receive metadata only (with `{}` configuration). To read request/response fields, declare a configuration schema, attach resources or issue interactive challenges, implement `OperationHook` and annotate its `invoke` method with `@HttpHook` (keeping `id()` identical to the annotation `id`; declared `fields` still require administrator approval, sensitive-field filtering and budget checks). Dynamic bindings and page-editor bindings continue using the interface directly.
- **Administrator approval and API**: administrators preview and approve declarations under [Plugin management → Plugin details → HTTP Hook declarations](/en/plugin-management#approve-a-plugin39s-http-hook) (or call `GET /api/web/operation-hooks/declarations?provider=<pluginId>` and `POST /api/web/operation-hooks/declarations/prepare` with `{revision, provider, id, fingerprint, remove}`, then submit the resulting plan to `publish`; never reuse stale plans across catalog, plugin or revision changes).

## Ordinary slots and page components

Ordinary pages mount `data-ui-slot` through `PixivUiSlots.connect(...)` from `/js/pixiv-ui-slots.js`. Plugin modules call `registerModule(moduleUrl, mount)` and release DOM nodes, listeners and requests in `onCleanup`. For page editor components and editable pages, see [Page editor and plugin components](/en/page-editor#develop-third-party-plugin-components-and-integrate-pages).

## Maintain external projects and upgrade the SDK

### Local development

The SDK development package can build and start its matching host and complete official plugin set directly from IntelliJ IDEA (`Developer Mode`), VS Code (`Run Plugin` / `Debug Plugin`), Eclipse (`eclipse/Run Plugin.launch`) or the command line. Runtime data is isolated under the project's `.dev/` directory (the first run automatically downloads and verifies pinned runtime packages into `~/.cache/pixivdownloader-sdk/`):

```powershell
.\mvnw.cmd verify exec:exec@sdk-run
.\mvnw.cmd verify exec:exec@sdk-debug
.\mvnw.cmd exec:exec@sdk-stop
```

The default package is a thin PF4J `.jar` with `plugin.properties` at the root and no host-shared classes. For plugin-private third-party dependencies, use PF4J JAR-with-lib (private dependencies go in `lib/*.jar`). To upgrade the SDK, extract the new development package into a separate directory, update contracts and runtime manifests against the Javadoc, and verify start/stop, reload and task draining on the plugin management page.

## Submit market materials

The community submission wizard accepts plugin icons, screenshots, external links, and multilingual `README`, release notes for the update and `CHANGELOG.md` (which can be extracted automatically by version headings such as `## [v1.2.0]`). All documents and images are signature-bound to the same version's Release. Clients display sanitized, statically isolated documents. See [Community repository submission instructions](https://github.com/Sywyar/PixivDownloader-community-plugins#投稿与版本管理).

## Signing and distribution

After building `pixivdownload-plugin-signature` in the main repository, use `PluginSignatureTool` to manage Ed25519 keys and signatures (keep private keys outside the repository and build directories):

```powershell
# 1. Generate a key pair and export the public-key fingerprint
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool keygen --directory "C:\secure\keys"
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool public-key --public-key "C:\secure\keys\public-key.pem" --key-id example-2026 --out "public.json"

# 2. Sign the plugin package and repository manifest.json
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool artifact --artifact "plugin.jar" --plugin-id example-download --version 0.1.0 --key-id example-2026 --private-key "C:\secure\keys\private-key.pem" --out "plugin.jar.sig"
java -cp "signature-tool.jar" top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool manifest --manifest "manifest.json" --repository-id example.plugins --key-id example-2026 --private-key "C:\secure\keys\private-key.pem" --out "manifest.json.sig"
```

A self-hosted plugin repository can publish `manifest.json` + `manifest.json.sig` (the `manifest-v1` protocol) or implement `paged-v2` endpoints. Provide a public HTTPS `repository.json` descriptor of at most 64 KiB for one-click import in desktop settings (supports `DIRECT_STRICT` or `GITHUB_RELEASES` network policies, `repository-update` rotation proofs and `plugin-revocations` revocation manifests).

To contribute SDK contracts or official-plugin fixes to the main repository, read [Main repository development](/en/development) first.
