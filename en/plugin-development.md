# Third-party plugin SDK

Start a third-party plugin from an independent project template in the SDK development package. You need only JDK 17, Node.js, and the build tools and runtime manifest included in the package; cloning the main repository is unnecessary.

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
- **Capability publication and absence handling**: plugins contribute capabilities through `PixivFeaturePlugin` and declare independent Spring child-context configuration classes through `PixivPluginProvider.configurationClasses()`. When a plugin is disabled, unloaded or replaced by a new generation, the host withdraws routes, static resources, i18n, navigation, download types, queues and scheduling capabilities by publication. Callers must handle absent capabilities without null-pointer errors or partially completed tasks.
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
plugin.requires=1.3
plugin.class=com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin
plugin.provider=Example Developer
plugin.description=Example download type.
pixiv.display-namespace=example-download
pixiv.display-name-key=plugin.name
pixiv.description-key=plugin.summary
pixiv.icon-key=download
pixiv.color-token=green
pixiv.execution-mode=host-process-full-trust
pixiv.lifecycle-policy=process-restart
```

- `plugin.id` must exactly match `PixivFeaturePlugin.id()`. `plugin.requires` declares the required SDK version (stable versions match by `requiredMajor == hostMajor && requiredMinor <= hostMinor`; prereleases use `alpha.N` / `beta.N` / `rc.N` and require an exact match).
- `pixiv.execution-mode` is required (`declarative-process` or `host-process-full-trust`). `pixiv.lifecycle-policy` can be `hot-reload` (default), `backend-restart` or `process-restart`; `pixiv.risk-signals` may declare risk-signal tokens.
- The external entry point implements `PixivPluginProvider`, returning one `PixivFeaturePlugin` and explicit `@Bean` configuration classes (do not rely on host package scanning):

```java
public final class ExampleDownloadPf4jPlugin
        extends org.pf4j.Plugin implements PixivPluginProvider {
    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new ExampleDownloadPlugin();
    }
    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(ExampleDownloadConfiguration.class);
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
5. **UI slots and scheduled-task capabilities (optional)**: contribute `WebUiSlotContribution` through `uiSlots()` (use host `PixivVue.mountUiSlot`; do not bundle the Vue runtime). Contribute scheduling support through `scheduledSourceDescriptors()` and child-context `ScheduledSourceExecutor` / `ScheduledWorkExecutor` Beans (the host manages scheduling, locks, credentials and watermark commits).

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

- **Database access**: new plugins inject `PluginDatabase`, declare structure through `privateSchema()` and `privateStorageMigrations()`, and use `read(work)` / `transaction(work)` for dialect-independent operations (compatible with SQLite and PostgreSQL). For main-database collaboration, inject `PluginMainDatabase` to access this owner's managed tables or requested `sharedMainStorage()`. The older `PluginDataSource` supports only SQLite.
- **Outbound HTTP / WebSocket**: inject `OutboundHttpClientFactory` / `OutboundWebSocketClientFactory` to create clients owned by plugin Beans and `close()` them on destruction (response streams returned by `exchangeStream` must be closed on every branch). Do not create your own `HttpClient` or `ProxySelector`, or depend on host HTTP configuration classes.

## Independent service contracts and composition

SDK 1.4+ supports cross-plugin typed services (see the `typed-service-plugin` template):

- Contract-carrier plugins declare an original pure-JDK API JAR through `pixiv.contract-api` and `pixiv.contract-sha256` (using `process-restart`). Providers export implementations through `serviceExports()`, while consumers declare dependencies in `serviceImports()` and inject `PluginServices`.
- Call `find(key)`, `require(key)`, `findAll(key)`, or lifecycle bindings `bind(keys, activation)` / `bindAll(keys, activation)` to obtain proxies bound to an exact publication.
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
