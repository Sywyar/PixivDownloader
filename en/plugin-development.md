# Third-party Plugin SDK

This guide is for developers who want to build, debug, and publish external PixivDownloader plugins. The safest starting point is to copy an official template instead of extracting implementation classes from the application shell or an official plugin.

Relevant source code:

- [Third-party plugin templates](https://github.com/Sywyar/PixivDownloader/tree/master/plugin-templates)
- [SDK Info](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-sdk-info)
- [Plugin API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-api)
- [Core API](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-core-api)
- [Official Douyin example plugin](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-douyin)
- [Plugin signature tool](https://github.com/Sywyar/PixivDownloader/tree/master/pixivdownload-plugin-signature)

> Douyin is the complete official SDK example. It shows how downloads, configuration, proxies, queues, scheduled tasks, private persistence, and a plugin-owned gallery fit together. It depends only on public SDK contracts and can be used to review a complete implementation. Start new projects by copying `plugin-templates` so that site-specific business code is not carried into an unrelated plugin.

## Understand the trust boundary first

External plugins run in the same JVM as the host. They are not isolated by a process-level or OS-level security sandbox. Plugin code carries the same risk as any other in-process code: it may read files accessible to the process, make network requests, or consume resources.

An Ed25519 signature proves only that an artifact came from a trusted key and that its bytes were not modified. It does not prove that the signed code is safe. Before installation, users must trust the publisher, source code, and repository operator. Plugin authors are responsible for legitimate use of cookies, tokens, proxies, artwork directories, and plugin-private data.

The host still validates structure, size, paths, versions, dependencies, SHA-256, signatures, and provenance, then verifies and loads the same frozen bytes. These controls protect supply-chain integrity; they do not create a code sandbox. See [plugin management](/en/plugin-management) for installation behavior.

## SDK boundaries

The SDK consists of `pixivdownload-sdk-info`, `pixivdownload-plugin-api`, and `pixivdownload-core-api`; `pixivdownload-sdk-bom` aligns all three artifact versions. `sdk-info` is the single source of truth for the SDK version, revision, and compatibility rules, independently of the application release version. `plugin-api` provides entry points, contributions, host control surfaces, and owner-scoped storage capabilities. `core-api` provides stable business-semantic ports, value models, and neutral algorithms. Keep dependencies pointing in this direction:

```text
Third-party plugin
  ├─ pixivdownload-sdk-info    required: SDK version and compatibility information
  ├─ pixivdownload-plugin-api  required: entry points, contributions, paths, and private data source
  └─ pixivdownload-core-api    optional: stable ports such as download and proxy settings

Do not depend on: pixivdownload-app, host implementation classes,
plugin-runtime/installer/signature internals, private services/mappers/controllers
from official plugins, the host DataSource, or private frontend globals
```

Plugins declare capabilities through descriptors and contributions. The host registers them under a trusted plugin identity, package identity, generation, and publication. The host must not add special cases for a third-party plugin id, package, or work type. When a plugin is stopped, unloaded, damaged, or incompatible, its routes, static resources, i18n, navigation, download types, queues, and scheduling capabilities are withdrawn. Consumers must degrade cleanly when a capability is absent instead of producing a blank page, null failure, or half-completed task.

### What a plugin can contribute

`PixivFeaturePlugin` currently exposes the following entry points. Leave unused entry points at their default empty lists:

| Method | Capability |
| --- | --- |
| `id`, `displayName`, `description`, `displayNamespace` | Plugin identity and i18n display keys |
| `iconKey`, `colorToken`, `kind` | Controlled icon, color, and category tokens |
| `start`, `stop` | Lifecycle of resources directly owned by the plugin; `stop` must be idempotent |
| `schema` | Shared-schema declarations coordinated by the host and official plugins; not a third-party private-persistence entry point |
| `routes` | Access policy for pages, APIs, and static paths |
| `staticResources` | Maps plugin classpath resources to URLs |
| `i18n` | Plugin-owned Web i18n namespace |
| `navigation` | Navigation items and neutral placements |
| `startupRoutes`, `landings` | Default startup destinations and audience-specific business destinations |
| `pageSections`, `uiSlots` | Page sections and controlled Web UI mount points |
| `guiThemes` | Startup-time GUI themes; usually require `process-restart` |
| `guiConfigContributions` | GUI configuration fields, groups, sections, actions, and presets |
| `guiOnboardingSteps` | GUI onboarding steps |
| `drilldowns` | Drill-down links resolved by semantic placement |
| `userscripts` | Stable script ids and exact classpath resources |
| `scheduledSourceDescriptors` | Pure-data descriptors for scheduled sources |
| `downloadTypes` | Download-workbench work-type descriptors |

Spring Beans are not returned from `PixivFeaturePlugin`. An external entry point declares configuration classes through `PixivPluginProvider.configurationClasses()`, and the host creates a separate child `ApplicationContext` for each active plugin.

### Declarative desktop UI boundary

Swing and Compose do not maintain separate copies of each desktop page. The application shell produces the complete toolkit-neutral `DesktopUiDocument` and owns page structure, state, configuration persistence, backend interaction, and typed event handling. `gui-swing` and `gui-compose` are generic `DesktopUiProvider` implementations that render the same document and own only their renderer, windows, tray, theme, and platform integration. A provider must not special-case a page id, plugin id, field key, or i18n key.

A feature plugin declares the complete domain structure of its own configuration section using the pure-data `GuiConfigContribution` field, group, section, layout, action, and preset records. The host merges, validates, and persists contributions under the trusted owner. Plugins must not return Swing or Compose components, own top-level windows, or copy host pages. If a new reusable widget is needed, extend the neutral `DesktopUiNode` contract and implement it generically in every provider instead of adding a provider-only exception.

The official `gui-swing` provider is default-installed and is the default. `gui-compose` is installed on demand. Both use `process-restart`, so switching, installing, upgrading, disabling, or removing one requires a full application restart. Gradle Wrapper performs the Compose plugin's Kotlin / Compose compilation and JAR-with-lib production; the Maven reactor invokes Gradle and connects its artifact to the normal official build, signing, and distribution pipeline.

## Start from a template

### Choose a template

| Template | Use it for | Included |
| --- | --- | --- |
| `minimal-feature-plugin` | A page, API, navigation, i18n, or configuration | PF4J entry point, provider, feature, explicit child context, controller, route/static/i18n contributions, and thin-JAR tests |
| `download-type-plugin` | A new downloadable work type | Download descriptor, five acquisition modes, queue, scheduled source, Vue UI slot, independent gallery, and frontend/backend tests |

Validate both templates inside the repository:

```powershell
mvn -f plugin-templates/pom.xml clean verify
mvn -f plugin-templates/pom.xml -pl minimal-feature-plugin -am verify
mvn -f plugin-templates/pom.xml -pl download-type-plugin -am verify
```

After copying a template outside the repository, it is a standalone Maven project that does not inherit the PixivDownloader root parent. Run this in the template directory:

```powershell
mvn clean verify
```

### Obtain the SDK artifacts

The templates import the SDK BOM and then declare the SDK artifacts supplied by the host:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>top.sywyar.lovepopup</groupId>
            <artifactId>pixivdownload-sdk-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>top.sywyar.lovepopup</groupId>
    <artifactId>pixivdownload-sdk-info</artifactId>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>top.sywyar.lovepopup</groupId>
    <artifactId>pixivdownload-plugin-api</artifactId>
    <scope>provided</scope>
</dependency>
```

The main-repository publication path can build the BOM, all three artifacts, source JARs, module Javadocs, and an aggregate Javadoc site covering every SDK type from an exact trusted source SHA. The designated `PixivDownloader-Plugin-SDK` repository and its receiver workflow do not exist yet, so the `SDK_PUBLISH_ENABLED` repository variable remains disabled and no standalone SDK release is currently available. Once the target is ready, its receiver must build and publish only from the exact source SHA in the dispatch payload. In addition to the four public SDK coordinates, it must publish the supporting `pixivdownload-parent:1.0.0` POM currently inherited by those artifacts so Maven can resolve them. That parent POM is not a plugin runtime SDK dependency and must not be added to plugin projects. For source development, install the SDK from the PixivDownloader repository root:

```powershell
./mvnw.cmd -pl pixivdownload-sdk-info,pixivdownload-plugin-api,pixivdownload-core-api,pixivdownload-sdk-bom -am install -DskipTests
```

Add Core API only when you actually need a stable host semantic port, and keep it `provided`:

```xml
<dependency>
    <groupId>top.sywyar.lovepopup</groupId>
    <artifactId>pixivdownload-core-api</artifactId>
    <scope>provided</scope>
</dependency>
```

`plugin.requires` declares only the SDK `major.minor`. Compatibility requires the same major and a host minor no lower than the plugin requirement; patch and revision do not participate in runtime compatibility checks. A public-contract change must increase the semantic SDK version. Template, documentation, or packaging corrections may increase the revision while retaining the semantic version. The quality gate rejects SDK-surface changes without a corresponding release-identity increase. Only an SDK metadata change triggers publication to the separate repository; an application release does not manufacture a new SDK.

PF4J, Spring, Jackson, Servlet API, and other dependencies supplied by the host parent classloader must also use `provided`. Do not copy shared contracts or framework classes into the plugin JAR; classes with the same name from different classloaders are not assignment-compatible.

### Rename every identity after copying

For the download-type template, replace at least the following values together:

| Template value | Replace in |
| --- | --- |
| `example-download-plugin` | Maven `artifactId` |
| `example-download` | Globally unique plugin id, queue type, URL prefix, and i18n namespace |
| `com.example.pixivdownload.downloadtype` | Java package and matching directories |
| `ExampleDownload` | Java type-name prefix |
| `0.1.0` | Artifact version and `plugin.version` |
| `plugin.requires=1.0` | Compatible SDK major.minor |
| `plugin.provider=Example Developer` | Publisher name |

Also update routes, static paths, frontend constants, private table names, tests, and both-language i18n text. Changing only `plugin.properties` makes the descriptor, feature, and runtime publication identities disagree, so the host will reject the plugin.

## Plugin package and entry points

### `plugin.properties`

The file must be at the root of the JAR. Basic example:

```properties
plugin.id=example-download
plugin.version=0.1.0
plugin.requires=1.0
plugin.class=com.example.pixivdownload.downloadtype.ExampleDownloadPf4jPlugin
plugin.provider=Example Developer
plugin.description=Example download type.
pixiv.display-namespace=example-download
pixiv.display-name-key=plugin.name
pixiv.description-key=plugin.summary
pixiv.icon-key=download
pixiv.color-token=green
pixiv.lifecycle-policy=hot-reload
```

Field rules:

| Field | Rule |
| --- | --- |
| `plugin.id` | Globally unique lowercase kebab-case token; must equal `PixivFeaturePlugin.id()` |
| `plugin.version` | Plugin artifact version |
| `plugin.requires` | Required SDK `major.minor`, not the application release version |
| `plugin.class` | PF4J main class implementing `PixivPluginProvider` |
| `plugin.provider`, `plugin.description` | Publisher and descriptor text |
| `plugin.dependencies` | Optional PF4J plugin dependency expression |
| `pixiv.*` display fields | i18n namespace/key and controlled presentation tokens |
| `pixiv.replaces` | Optional identity of a replaced plugin |
| `pixiv.lifecycle-policy` | Case-sensitive `hot-reload`, `backend-restart`, or `process-restart`; defaults to `hot-reload` |

The SDK currently uses `1.0.0` as its initial contract baseline. Compatibility is `requiredMajor == hostMajor && requiredMinor <= hostMinor`; PATCH does not affect admission. After the first public release, raise MAJOR for breaking contract changes, MINOR for backward-compatible additions, and PATCH for compatible fixes.

### Reusing the PostHog browser client

A Web plugin that publishes a PostHog survey can depend on the official `posthog` plugin and load `/pixiv-posthog/pixiv-posthog.js` before its own page script. This is not a neutral survey abstraction: the publishing plugin still owns the survey ID, question schema, trigger, state, copy, privacy filter, and all four PostHog project parameters.

```properties
plugin.dependencies=posthog?@1.0
```

```js
const posthog = Object.freeze({
  projectToken: 'phc_...',
  surveyId: '...',
  apiHost: 'https://example.invalid',
  uiHost: 'https://us.posthog.com'
});

const client = await window.PixivPostHog?.createSurveyClient({
  ownerKey: 'example-plugin.feedback',
  posthog,
  distinctId: '',
  beforeSend(event) {
    return allowedSurveyEvent(event) ? event : null;
  }
});
if (!client) return; // dependency missing, invalid parameters, SDK failure, or conflict

await window.PixivPostHog.captureSurveyWithAck(
  'example-plugin.feedback',
  'survey sent',
  surveyProperties,
  submissionId
);
```

`ownerKey` must be globally stable. Repeating a call for the same owner on one page reuses the client only when the four parameters, `distinctId`, and the `beforeSend` function object are unchanged; any mismatch fails closed. Different owners may use different project parameters. `submissionId` must be a stable UUID derived by the publishing plugin from the survey ID, campaign version, and survey-scoped anonymous identity. Retries of the same response must reuse it; only a campaign-version change should produce a new value. The adapter sends this value as the event's top-level `uuid` and resolves the Promise only after the fixed ingestion endpoint returns 2xx; a missing or invalid UUID is rejected before any request is sent. The adapter pins and loads the vendored SDK, disables default collection, and creates isolated named instances, but it does not select surveys, generate identities, or decide response fields for a plugin. If `posthog` is disabled at runtime, JavaScript already loaded in an open page cannot be withdrawn; after refresh the resource is absent and the caller must degrade as if the client were unavailable.

To keep a survey in the inbox and let users complete it there, the publishing plugin may additionally contribute a `notification.inbox` slot with no slot module:

```java
new SurveyInboxMessage(
        "example-plugin.feedback-survey",
        "campaign-v1",
        "/example-plugin/survey.html",
        "example-plugin",
        "survey.inbox-title",
        "survey.inbox-body",
        100
).toUiSlotContribution()
```

`messageKey` must be stable and globally unique; change `instanceKey` only for a new survey campaign. The content URL must be an absolute same-origin path owned by the plugin, and the plugin must publish the namespace and the title and summary i18n keys. The route still requires an access declaration; use `ADMIN` when the survey is exposed only through the administrator inbox. HTML remains on the plugin-owned page and does not cross the stable contract as raw content, a Bean, or a ClassLoader. A targeted survey contribution with invalid fields is rejected and logged; do not handcraft its metadata instead of using this wrapper.

This contribution is an optional, best-effort display capability. Absence of the notification plugin does not affect the survey page or the PostHog submission path. The host retains a pure-value active snapshot and does not call the publisher during quiesce. It stores the message idempotently during startup synchronization or on the next inbox request after a slot change. The same instance reuses existing read state and unavailable tombstones; a changed instance key creates a new unread message. Contribution absence, plugin disablement or unload, and publication replacement hide the active message while retaining state for the same instance to recover later.

The host appends `notificationId` and `lang` query parameters to the content URL, and the page may report its height with a `pixiv-content-height` message. Only after confirming that the remote survey is permanently closed or deleted should the page send `{type: 'pixiv-survey-unavailable', notificationId}` to its same-origin parent. Do not send it for a temporary network failure: it creates a local dismissal marker that remains closed when the same instance is published again.

### PF4J provider and Spring child context

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

Each external package must return exactly one non-null feature, whose id matches the descriptor. Use `@Bean` methods in the configuration class to assemble plugin Beans explicitly:

```java
@Configuration(proxyBeanMethods = false)
public class ExampleDownloadConfiguration {

    @Bean
    ExampleDownloadPlugin exampleDownloadPlugin() {
        return new ExampleDownloadPlugin();
    }

    @Bean
    ExampleDownloadController controller(
            ExampleDownloadQueue queue,
            RequestOwnerIdentityResolver ownerResolver) {
        return new ExampleDownloadController(queue, ownerResolver);
    }
}
```

Do not rely on host root-package scanning, and do not scan arbitrary classes from the plugin package. A child context may inject Plugin API, Core API, JDK types, and specification dependencies explicitly exposed by the parent context, but it must not inject app implementation classes.

## Contribute notification templates

Notification templates belong to the plugin that owns the business scenario, not to the mail or push transport plugin. Expose a `top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContributor` Bean from that owner's child context. Each returned `NotificationTemplateContribution` is a pure value containing `scenarioId`, `medium`, `locale`, `titleTemplate`, and `bodyTemplate`:

```java
@Bean
NotificationTemplateContributor notificationTemplates() {
    return () -> List.of(new NotificationTemplateContribution(
            "example.completed",
            "mail",
            Locale.US,
            "Example completed",
            """
            <!doctype html>
            <html><body><p>{{summary}}</p></body></html>
            """));
}
```

Publishing template data does not register a new notification scenario or trigger delivery. Use only scenario ids admitted by the corresponding stable scenario/dispatcher contract, and do not override another plugin's tuple. The mail and push plugins consume the host's read-only `NotificationTemplateCatalog`; each transport still owns its own configuration, render checks, delivery, and failure handling.

The host calls contributors while preparing the plugin publication, copies the records into an immutable snapshot, and removes that exact publication on stop, reload, or unload. Duplicate `(scenarioId, medium, locale)` tuples fail fast. Lookup uses an exact locale first, then a deterministic same-language fallback.

HTML does not cross a network or process boundary between plugins. It is passed in the same JVM as a bounded `String` value and measured in UTF-8 bytes: titles are limited to 16 KiB, bodies to 1 MiB, and one plugin publication to 256 templates and 8 MiB total contribution data. Do not pass an `InputStream`, `Path`, Spring `Resource`, `ClassLoader`, Bean, or deferred callback through this contract; those would retain plugin-owned lifetime or I/O state. Escape untrusted values before inserting them into an HTML placeholder.

If a real use case exceeds these bounds or needs binary data, propose a separate host-owned streaming/blob-handle contract with explicit lifetime and quotas. Do not enlarge this template contract or exchange temporary files between plugins.

## Web routes, static resources, and i18n

Every controller mapping, static directory, and top-level HTML file must be declared by its owning plugin in `routes()`. A controller can be covered only by a declaration from the same plugin owner, not by another plugin's broad prefix. An undeclared `path + HTTP method` returns 404; hiding a frontend entry point is not authorization.

Common named factories:

| Factory | Actual access surface |
| --- | --- |
| `publicRoute` | No authentication; identical in solo and multi modes |
| `visitor` | Available to multi-mode visitors; requires a session in solo mode; not available to invited guests |
| `visitorAndInvitedGuest` | Readable by multi-mode visitors and invited guests |
| `invitedGuest` | Available to administrators and invited guests, and protected by monitor rules |
| `admin` | Administrators only |
| `local` | Local-process flow exception |
| `gui` | Both a trusted local request and GUI token are required |

To restrict HTTP methods, use the standard `WebRouteContribution` constructor with an explicit `HttpMethod` set. When HTTP method sets overlap, matching paths owned by different plugins must use the same `AccessPolicy`; different policies fail fast during registration and report both plugins and path patterns. Plugins may share a namespace under the same policy, and one plugin may still refine its own broad prefix with a more specific declaration.

Complete declaration for an independent administration page:

```java
@Override
public List<WebRouteContribution> routes() {
    return List.of(
            WebRouteContribution.admin("/example-download-gallery.html"),
            WebRouteContribution.admin("/example-download-gallery/**"),
            WebRouteContribution.admin("/api/example-download/gallery"));
}

@Override
public List<StaticResourceContribution> staticResources() {
    return List.of(
            new StaticResourceContribution(
                    "classpath:/static/", "/example-download-gallery.html", true),
            new StaticResourceContribution(
                    "classpath:/static/example-download-gallery/",
                    "/example-download-gallery/"));
}

@Override
public List<I18nContribution> i18n() {
    return List.of(new I18nContribution(
            "example-download", "i18n.web.example-download"));
}
```

Keep page HTML, CSS, and JavaScript in separate files. Put user-visible text in the plugin namespace. Render external data with DOM APIs and `textContent`; do not concatenate unknown text into `innerHTML`.

## Complete workflow for adding a download type

A download type is not one Java class. It is a set of capabilities owned by one plugin and published or withdrawn together:

```text
plugin.properties + provider
        ↓
DownloadTypeDescriptor ──→ the workbench discovers the type and acquisition modes
        ↓
same-origin behavior module → import, discovery, queueing, status, filters, and settings
        ↓
plugin controller/service ─→ parses requests and performs real domain work
        ↓
QueueOperations ─────────→ cancellation, clearing, and lifecycle drain
        ├─ WebUiSlotContribution (optional)
        ├─ ScheduledSourceDescriptor + executor (optional)
        └─ plugin-owned independent gallery (optional)
```

### 1. Declare `DownloadTypeDescriptor`

There is no public `QueueTypeContribution`, `independentPage`, gallery capability bag, or descriptor-level `uiSlots` field. Download types, queues, UI slots, scheduled sources, and independent pages each use their own stable contract.

```java
@Override
public List<DownloadTypeDescriptor> downloadTypes() {
    return List.of(new DownloadTypeDescriptor(
            DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
            "example-download",
            "example-download",
            "batch.kind",
            900,
            "download",
            "green",
            "/example-download/example-download-type.js",
            List.of(
                    DownloadAcquisitionMode.SINGLE_IMPORT,
                    DownloadAcquisitionMode.USER_PROFILE,
                    DownloadAcquisitionMode.SERIES_COLLECTION,
                    DownloadAcquisitionMode.SEARCH,
                    DownloadAcquisitionMode.QUICK),
            true,
            List.of("example-ready-filter"),
            List.of("example-output-setting"),
            "example-download"));
}
```

Field meanings:

| Field | Requirement |
| --- | --- |
| `contractVersion` | Must currently equal `DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION`, which is 1 |
| `type` | Globally unique work type; usually equals `QueueOperations.queueType()`, but registries do not assume a one-to-one mapping |
| `displayNamespace`, `displayI18nKey` | Namespace and plain key for the type name |
| `order` | Stable ordering value |
| `iconKey`, `colorToken` | Controlled host-allowlisted tokens, never URLs, HTML, or arbitrary CSS |
| `moduleUrl` | Required same-origin absolute `.js` path owned by the same plugin's static-resource contribution |
| `acquisitionModes` | Declared subset of `single-import`, `user`, `series`, `search`, and `quick` |
| `cancelSupported` | Whether single-item cancellation is available; when `true`, queue items must contain a top-level `cancelWorkKey` |
| `filters`, `settings` | Allowlisted contract ids implemented by the behavior module |
| `i18nNamespace` | Namespace for behavior-module status and error text |

### 2. Implement the frontend behavior module

The module calls `PixivBatch.queueTypes.registerModule(initializer)` inside the real `<script>` evaluation window created by the host. Do not copy Vue and do not directly read or write host `state`, `saveQueue`, `renderQueue`, `updateStats`, or private DOM ids.

Primary contract-version-1 entry points:

| Entry point | Responsibility | Meaning when absent |
| --- | --- | --- |
| `process(item, context)` | Send one queue item to the plugin API and submit allowlisted state through `context.updateItem(patch)` | Required; the type should not activate without it |
| `import` | Match URLs and construct a single-work queue item and `cancelWorkKey` | Omit when `single-import` is not declared |
| `acquisition.user` | User input, paged discovery, rendering, and queue metadata | Omit when `user` is not declared |
| `acquisition.series` | Series URL, pagination, ordering, and queue metadata | Omit when `series` is not declared |
| `acquisition.search` | Search requests, range requests, rendering, and queue synchronization | Omit when `search` is not declared |
| `acquisition.quick` | Quick actions and work publication; submit results through `context.publishWorks(payload)` | Omit when `quick` is not declared |
| `filters` | Implement only filter ids listed by the descriptor | An empty list means no additional filters |
| `settings` | Implement only setting ids listed by the descriptor | An empty list means no additional settings |
| `slots` | Declarative fragments from the same-origin type module | Omit when unused; independent dynamic slots use `WebUiSlotContribution` |

The initializer receives an `AbortSignal`, `isActive()`, `assertActive()`, and `onCleanup()`. Before writing back any asynchronous result, confirm that the publication is still active. Clean up listeners, timers, and mounted components.

### 3. Resolve the trusted owner on the backend

An HTTP controller injects only `RequestOwnerIdentityResolver` from the parent context and resolves the administrator/user owner from the current request:

```java
RequestOwnerIdentity identity = ownerResolver.resolve(request);
queue.submit(command, identity);
```

Never trust an owner UUID supplied through JSON, query parameters, or custom headers. Descriptor `pluginId/packageId/generation/publicationId` values prove only download-type publication currentness; they are not the authenticated user identity.

A real downloader must report a task as completed only after files are durably written and successful facts such as history or source relationships are committed. The template's in-memory completed response is a deterministic test fixture, not a production implementation.

### 4. Implement `QueueOperations`

```java
public final class ExampleQueue implements QueueOperations {
    @Override public String queueType() { return "example-download"; }
    @Override public void cancel(String workKey, String ownerUuid, boolean admin) { /* ... */ }
    @Override public int clearAll() { /* ... */ }
    @Override public int clearForOwner(String ownerUuid) { /* ... */ }
}
```

`workKey` is an opaque stable string inside that queue type. It need not be numeric and must not be placed in a URL path segment. The host uses `POST /api/download/queue/{queueType}/cancel`, passing the original `workKey` and descriptor publication identity in JSON. Plugin frontend code should call the host bridge instead of constructing a control request itself.

A strictly synchronous implementation with no background work may use the default generation-0 completed drain. If there is queueing, executor handoff, a delayed callback, or any work that outlives the current call stack, it must:

1. Atomically stop accepting new tasks in `prepareQuiesce(registeredQueueType)` and return a real `QueueDrain` with a positive generation.
2. Send cooperative cancellation from `cancelQuiescedTasks()` only after the host has stored that drain.
3. Complete the drain only after every active task has exited.
4. Return the same `queueType + generation` from repeated prepare calls, and use a new generation for a new plugin instance.

Do not use a completed sentinel to pretend that an asynchronous queue has exited. Plugin-owned executors, schedulers, and HTTP/WebSocket clients must belong to the child context and be released when it closes.

### 5. Add UI slots

Slots are published independently and do not belong in the download descriptor:

```java
@Override
public List<WebUiSlotContribution> uiSlots() {
    return List.of(
            new WebUiSlotContribution(
                    "example-download.settings-card",
                    "settings-card",
                    "/example-download/example-download-type.js",
                    900),
            new WebUiSlotContribution(
                    "example-download.quick-actions",
                    "quick-actions-mine",
                    "/example-download/example-download-ui-slot.js",
                    900));
}
```

A dynamic slot module mounts through host `PixivVue.mountUiSlot` and uses only owner-scoped `context.supports(type, mode)`, `context.dispatchQuickAction(action)`, and `context.onCleanup(...)`. Do not bundle the Vue runtime with the plugin.

### 6. Add scheduled-task capabilities

Scheduled tasks are optional. The feature contributes only pure-data `ScheduledSourceDescriptor` values; provide `ScheduledSourceExecutor` and `ScheduledWorkExecutor` as Beans in the plugin child context.

The browser source module is responsible for:

- `capture`: serialize the current acquisition input into a plugin-owned, schema/versioned definition;
- `restore`: restore a saved definition into the editor;
- `summary`: produce a controlled presentation structure.

The browser accesses host-approved input only through publication-scoped `context.acquisitionInput(mode)` and `context.restoreAcquisition(mode, value)`. The current neutral third-party adapter exposes only `single-import`. Do not read host DOM or call private mode globals to imitate other modes.

The backend source executor owns the definition schema, discovery, and checkpoint. The work executor owns its payload schema and synchronous work execution. The host continues to own claim, lease, credentials, guards, pending state, cancellation, and checkpoint CAS. `ScheduledWorkExecutor.execute` may return `COMPLETED` or `ALREADY_COMPLETED` only after work files and success facts are durably committed. If a plugin or executor is absent, task data remains stored and suspended; it must not be deleted or have its checkpoint advanced early.

## Add a plugin-owned gallery

An "independent page" is a design pattern, not an API or descriptor field named `independentPage`. It means the plugin fully owns:

- the top-level HTML and page-specific CSS/JavaScript;
- its route, static-resource, and i18n contributions;
- its controller/API, visibility checks, and data model;
- its own navigation or type-switch entry, when needed.

The standard way to add a third-party gallery is therefore to add a page and API using the independent administration-page example above. The page naturally follows the plugin publication: when the plugin stops, its routes and resources are withdrawn, and the host needs no type-specific branch.

A download-type plugin should also declare an empty slot at the top of its gallery page:

```html
<nav data-nav-slot="gallery.type-switch"></nav>
```

Register only that plugin's type-switch entry through `navigation()`:

```java
new NavigationContribution(
        "example-gallery-type-switch",
        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
        "example-download", "nav.gallery",
        "/example-download-gallery.html", "images",
        AccessPolicy.ADMIN, 50)
```

Each plugin declares only its own entry. A page must not hard-code Pixiv, Douyin, or another plugin id. The host aggregates the slot from active publications, so entries appear and disappear with hot lifecycle changes.

Keep these boundaries explicit:

- `/pixiv-gallery.html` is the maintained official Pixiv main gallery, not a generic mount shell for third-party download types.
- The generic gallery provider/registry/broker, `/api/gallery/unified/**`, and the `unifiedGallery` ABI/wire fields have been removed. There is no compatibility surface for plugins to consume.
- A third-party page must not copy private gallery/novel implementations, connect directly to the host database, or import app implementation classes.
- Asset serving, deletion, visibility, search, collections, and statistics belong to the plugin's own APIs and private data model. If host cooperation is required and the SDK has no suitable capability, propose a neutral public contract instead of bypassing the SDK through an app implementation.

Douyin's `/pixiv-douyin-gallery.html`, detail page, `/api/douyin/gallery/**`, and `gallery.type-switch` contribution form the complete SDK example for this pattern. Third-party projects should still copy the independent gallery in `download-type-plugin` as their baseline.

## Configuration, credentials, and files

### Three configuration owners

| Content | Path | How the plugin obtains it |
| --- | --- | --- |
| Host settings and plugin enablement | `config/config.yaml` | Read only the minimum needed value through an SDK read-only semantic port; do not read or write the file directly |
| Plugin business configuration | `config/plugins/{pluginId}.properties` | Child-context `Environment`, `@Value`, or `@ConfigurationProperties`; use `RuntimePathProvider` when directly managing the file |
| Plugin credentials | `config/credentials/{pluginId}.properties` | The host maintains encrypted envelopes and injects only declared decrypted values for the current owner into that plugin child context |

Prefix business keys with the plugin id, for example:

```properties
example-download.download.directory=
example-download.proxy.mode=inherit
example-download.download.include-cover=false
```

Ordinary read example:

```java
@Bean
ExampleSettings settings(Environment environment) {
    return new ExampleSettings(
            environment.getProperty("example-download.download.directory", ""),
            environment.getProperty("example-download.proxy.mode", "inherit"));
}
```

Declare GUI fields through `GuiConfigContribution`; the host saves them under the trusted owner. Sensitive or `PASSWORD` fields are not written to ordinary properties. A plugin reads only injected values and must not read, decrypt, or rewrite credential envelopes. Owner-scoped directories and encrypted credentials still do not hard-isolate malicious code running in the same JVM.

### Stable paths and artwork directories

The host injects an owner-bound Plugin API `RuntimePathProvider` into each plugin child context. The caller does not supply a plugin id:

```java
Path config = runtimePathProvider.configFile("properties");
Path state = runtimePathProvider.stateDirectory();
Path data = runtimePathProvider.dataDirectory();
```

Use `state/{pluginId}` for rebuildable runtime state and `data/{pluginId}` for plugin-managed data and caches. Do not write artwork files into either directory. Inject `PluginDataSource` when persistence is required. It is a host-lifecycle-managed `javax.sql.DataSource` connected only to `data/{pluginId}/plugin.db`. The plugin owns that private SQLite schema and its migrations. It must not close or unwrap the data source or use it to reach the host database.

By default, inherit the host artwork root from `DownloadSettings.getRootFolder()` and let the plugin manage its own subdirectory:

```java
Path defaultOutput = Path.of(downloadSettings.getRootFolder())
        .resolve("example-download")
        .normalize();
```

Douyin follows the same rule to obtain `{rootFolder}/douyin`, then organizes works by owner. When the user configures a save location in `config/plugins/douyin.properties`, the plugin uses that override instead. Third-party plugins may use the same pattern, but the plugin owns its exact subdirectories, filenames, and migration logic.

Do not depend on app `RuntimeFiles`, `DownloadConfig`, `ProxyConfig`, host mappers, `SqlSessionFactory`, the main `DataSource`, or concrete executor Bean names. Use Plugin API for owner-scoped paths and private data sources, and Core API ports such as `DownloadSettings` and `OutboundProxySettings` for download and proxy semantics. A host implementation that is not covered by the SDK is not an implicit public API.

See [storage principles](/en/storage) for complete path rules and [configuration reference](/en/configuration) for configuration keys.

## Outbound HTTP and WebSocket

Use the stable pure-JDK factory for plugin network access:

```java
@Bean(destroyMethod = "close")
OutboundHttpClient exampleHttpClient(OutboundHttpClientFactory factory) {
    return factory.open(OutboundHttpClientProfile.standard(
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            OutboundHttpRoute.inherit()));
}
```

Every path that uses a live response from `OutboundHttpClient.exchangeStream` must close it. `exchange` buffers the complete response and closes it automatically. A non-2xx status is still a normal response; the business caller interprets it.

For WebSocket, call `OutboundWebSocketClientFactory.open(profile)`. The resulting client is likewise owned by a plugin Bean and closed with the child context. The plugin declares timeouts, redirect behavior, cookies, connection-pool settings, and a neutral route profile; the host owns the transport, global/task proxy resolution, and `ProxySelector`.

Do not construct `java.net.http.HttpClient` or `ProxySelector` yourself, and do not depend on Apache types or app HTTP configuration. Authentication headers, site-specific headers, and protocol messages belong to plugin business logic, not the generic transport. Douyin uses `OutboundHttpClient` as the complete reference implementation.

## Build, test, debug, and install

### Required tests

At minimum, retain the checks included by the template:

- descriptor, provider, feature id, and contribution consistency;
- explicit child-context assembly and controller registration;
- route/static/i18n/schema or download publication;
- queue owner, opaque work keys, clearing, and drain behavior;
- frontend-module `node --check` and executable behavior tests;
- root `plugin.properties` and thin-JAR boundaries.

The standard command for validating templates from the repository root is:

```powershell
mvn -f plugin-templates/pom.xml clean verify
```

When a plugin contributes both backend and frontend code, do not test the frontend contract only by searching script text from Java. Execute it with Node.

### Local development

Baseline flow for a standalone third-party project:

1. Run `mvn clean verify`.
2. Confirm the root `plugin.properties`, classes, and resources inside the JAR.
3. In a formal runtime, install through a configured custom repository. Local upload accepts only a JAR and `.sig` issued by the built-in official trust root.
4. Explicit plugin development mode may omit the local-upload signature; the artifact remains recorded as an unverified development source.
5. For a `hot-reload` plugin, let the transaction replace and activate it immediately.
6. Refresh the page and verify that controllers, routes, static resources, i18n, and download types all belong to the current generation.
7. After changes, rebuild, upload, and use `reload`; do not overwrite the installed JAR by hand while the application is running.

```powershell
jar tf target/example-download-plugin-0.1.0.jar
```

You may also place the JAR in the working-directory `plugins/` while the application is stopped, then start the application. `plugins/runtime/` is a private host freezing workspace, not an installation directory or debugging output directory.

Official plugins in the repository use a dedicated development mode that compiles them and loads the current `target/classes` from each module:

```powershell
mvn -pl pixivdownload-official-plugins -am -Pdev-mode process-classes -Dexec.skip=true
```

Use this entry point when contributing to an official plugin or checking the Douyin example. It is not an auto-discovery mechanism for an external third-party project. IDE users can use the committed IntelliJ IDEA, VS Code, or Eclipse `Developer Mode` shared configuration.

While debugging, check that:

- `/plugin-manage.html` reports `STARTED`, and the generation matches this replacement;
- plugin pages/APIs work, become undeclared after stop, and return after start/reload;
- page scripts, CSS, and i18n changes come from the new artifact rather than browser cache or an old package in `plugins/`;
- `log/` has no route conflict, duplicate id, child-context assembly, version, signature, or drain diagnostics;
- asynchronous work no longer writes files, calls back into pages, or retains an old classloader after stop.

### Artifact formats

Templates build a thin PF4J JAR by default:

- `plugin.properties` is at the JAR root;
- there is no Spring Boot `BOOT-INF/`;
- there is no `lib/*.jar`;
- there are no copies of plugin-api, core-api, PF4J, Spring, Jackson, Servlet API, or host classes.

The host also supports a PF4J JAR-with-lib for private third-party dependencies. Its root still contains the descriptor, plugin classes, and resources; private dependencies go in `lib/*.jar`. Do not shade or bundle shared contracts. Add package-structure and isolated-classloader loading tests when choosing JAR-with-lib. The official default delivery format remains `.jar`, not ZIP.

## Signing and publishing

### Generate an artifact signature

The private key must be an Ed25519 PKCS#8 PEM stored outside both the repository and build output. Repository configuration uses a Base64-encoded X.509 SubjectPublicKeyInfo public key. After building the signature tool, invoke its CLI main class on the classpath:

```powershell
java -cp <signature-tool.jar> `
  top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool `
  artifact `
  --artifact <plugin.jar> `
  --plugin-id example-download `
  --version 0.1.0 `
  --key-id example-2026 `
  --private-key <ed25519-pkcs8.pem> `
  --out <plugin.jar.sig>
```

The output is structured JSON containing `formatVersion`, `algorithm=Ed25519`, `keyId`, and `value`. Also record the exact artifact byte count and SHA-256:

```powershell
$artifact = Get-Item -LiteralPath <plugin.jar>
$artifact.Length
(Get-FileHash -Algorithm SHA256 -LiteralPath $artifact.FullName).Hash.ToLowerInvariant()
```

### Catalog manifest

Repository manifest schema version 1 has top-level `schemaVersion`, `generatedTime`, and `entries` fields. Minimal publishable entry:

```json
{
  "schemaVersion": "1",
  "generatedTime": "2026-08-10T00:00:00Z",
  "entries": [
    {
      "pluginId": "example-download",
      "displayNamespace": "example-download",
      "displayNameKey": "plugin.name",
      "descriptionKey": "plugin.summary",
      "market": {
        "displayName": {"zh": "示例下载", "en": "Example download"},
        "summary": {"zh": "示例下载类型", "en": "Example download type"},
        "description": {"zh": "插件详细说明", "en": "Plugin description"},
        "author": "Example Developer",
        "sourceType": "community",
        "category": "download",
        "tags": ["download"],
        "homepageUrl": "https://example.com/plugin",
        "license": "MIT",
        "latestVersion": "0.1.0",
        "updatedTime": "2026-08-10T00:00:00Z",
        "iconToken": "download",
        "colorToken": "green",
        "recommended": false,
        "officialRequired": false,
        "defaultInstalled": false
      },
      "packages": [
        {
          "version": "0.1.0",
          "packageUrl": "https://plugins.example.com/example-download-0.1.0.jar",
          "expectedSizeBytes": 12345,
          "sha256": "LOWERCASE_SHA256_HEX",
          "signature": {
            "formatVersion": 1,
            "algorithm": "Ed25519",
            "keyId": "example-2026",
            "value": "BASE64_SIGNATURE"
          },
          "signatureUrl": "https://plugins.example.com/example-download-0.1.0.jar.sig",
          "requiredSdk": "1.0",
          "dependencies": [],
          "releasedTime": "2026-08-10T00:00:00Z",
          "changeNotes": ["Initial release"],
          "channel": "stable",
          "deprecated": false
        }
      ]
    }
  ]
}
```

`market` affects presentation, search, and sorting only; it does not make installation security decisions. `packageUrl` and the manifest URL must use HTTPS. Artifact size, SHA-256, structured signature, and the internal descriptor remain authoritative during installation.

Generate a detached signature over the raw manifest bytes:

```powershell
java -cp <signature-tool.jar> `
  top.sywyar.pixivdownload.plugin.signature.cli.PluginSignatureTool `
  manifest `
  --manifest <manifest.json> `
  --repository-id example `
  --key-id example-2026 `
  --private-key <ed25519-pkcs8.pem> `
  --out <manifest.json.sig>
```

Publish `manifest.json`, `manifest.json.sig` at the same URL plus `.sig`, the artifact, and optionally the detached artifact signature. Do not format or rewrite the manifest after signing it.

Verification commands:

```text
verify-manifest --manifest <manifest.json> --signature <manifest.json.sig> --repository-id <id> [--policy official|custom]
verify-artifact --artifact <jar> --signature <sig.json> --plugin-id <id> --version <version> --expected-size <bytes> --sha256 <hex> [--policy official|custom]
```

When verifying a custom root, also pass `--trusted-key-id` and `--trusted-public-key`. Optional fields are `--trusted-algorithm`, `--trusted-state`, `--trusted-publisher`, `--trusted-label`, and `--trusted-official`.

### Let users add a custom repository

Publish a strict UTF-8 JSON `repository.json` no larger than 64 KiB. A user only needs its public HTTPS URL in the plugin market:

```json
{
  "schemaVersion": 1,
  "repositoryId": "example.plugins",
  "displayName": "Example Plugins",
  "publisher": {
    "id": "example",
    "displayName": "Example Publisher",
    "homepageUrl": "https://example.com/plugins"
  },
  "catalog": {
    "protocol": "manifest-v1",
    "endpoint": "https://plugins.example.com/manifest.json"
  },
  "networkProfile": "DIRECT_STRICT",
  "revocationsUrl": "https://plugins.example.com/revocations.json",
  "updateProofUrl": "https://plugins.example.com/repository-update.json",
  "trustedKeys": [{
    "keyId": "example-2026",
    "algorithm": "Ed25519",
    "publicKeySpkiBase64": "BASE64_X509_SUBJECT_PUBLIC_KEY_INFO",
    "state": "ACTIVE",
    "publisher": "Example Publisher",
    "trustLabel": "Example release key"
  }]
}
```

Do not publish or rely on `repository.json.sig` for first import: a new key signing the descriptor that introduces that same key is only self-attestation. The application displays the descriptor digest, publisher text, every network host, and each full `SHA-256(SPKI DER)` fingerprint. Confirmation re-fetches the descriptor and requires byte-for-byte digest equality; the saved configuration becomes active after restart. A custom repository does not inherit the official trust root. `official`, `configured`, and `community` are reserved repository IDs.

`networkProfile` accepts only `DIRECT_STRICT` and `GITHUB_RELEASES`. The first uses only declared public HTTPS hosts and no redirects. The second permits one redirect inside the fixed GitHub host boundary. A descriptor cannot disable SSRF checks, admit private addresses, or supply an arbitrary proxy or redirect allowlist.

Small repositories can keep the signed `manifest-v1` format above. A large repository can set `catalog.protocol` to `paged-v2`, point the endpoint at an API base URL, and implement:

```text
GET {endpoint}/plugins?cursor=&limit=&query=&category=&publisher=&channel=
GET {endpoint}/plugins/{pluginId}?cursor=&limit=
GET {endpoint}/plugins/{pluginId}/versions/{version}
```

The default page size is 24 and the maximum is 100. Responses carry a `generation`; cursors are opaque. List and detail responses are limited to 512 KiB and 256 KiB. Installation still submits only repository ID, plugin ID, and version. The host resolves the version endpoint again and compares remote metadata with the frozen package descriptor, size, SHA-256, and publisher signature.

After first trust, an old trusted key may sign a monotonic `repository-update-v1` proof for descriptor or key rotation. Security revocations use a higher-sequence `revocations-v1` document. Append `.sig` to each JSON URL and use the existing CLI's separate signing domains:

```text
repository-update --document repository-update.json --repository-id example.plugins --sequence 2 --key-id example-2026 --private-key <pem> --out repository-update.json.sig
plugin-revocations --document revocations.json --repository-id example.plugins --sequence 1 --key-id example-2026 --private-key <pem> --out revocations.json.sig
```

Revocation scopes include `PACKAGE_SHA256`, `PLUGIN_VERSION`, `SIGNING_KEY`, and `PUBLISHER`. `YANKED` blocks new installation and update; `REVOKED` also blocks matching installed bytes before the next load. Plugins still run in the same JVM as the host without a code sandbox. Signatures and revocations do not prove that code is safe.

## Contributing to the project

Private or community plugins normally require no host changes. Good contributions to the main repository include:

- fixes for real defects in Plugin API, Core API, plugin runtime, or templates;
- a stable neutral port for semantics needed by multiple plugins;
- improvements to templates, SDK documentation, boundary tests, and failure diagnostics;
- additions or fixes to official external plugins;
- improvements to signing, installation transactions, lifecycle, and absent-capability degradation;
- corrections where documentation has drifted from the current implementation.

If the SDK lacks a capability, do not depend on an app-private class first. Propose a neutral contract that does not recognize a specific site or plugin id, and describe its real consumers, owner, lifecycle, error/absence semantics, and tests. A public-contract change must update the SDK version and revision, BOM, Javadocs, templates, the Douyin example, and this guide together.

Basic flow:

```bash
git clone https://github.com/YOUR_USERNAME/PixivDownloader.git
cd PixivDownloader
git remote add upstream https://github.com/Sywyar/PixivDownloader.git
git fetch upstream
git switch -c feat/plugin-api/your-capability upstream/master
```

Before submission:

1. Run directly related module tests, then the affected template and boundary tests.
2. Keep plugin id, descriptor, feature, routes, static resources, i18n, and tests consistent.
3. Do not commit `target/`, `build/`, runtime configuration, credentials, private keys, or downloaded data.
4. In the PR, explain motivation, stable boundaries, failure/absence behavior, and the exact validation commands run.
5. Submit code, templates, and core development documents to `master` through a PR. The online site lives on the independent `gh-pages` branch and is normally committed and pushed directly from its dedicated worktree.

## Pre-release checklist

- [ ] Import the SDK BOM and depend only on SDK Info, Plugin API, and genuinely needed stable Core API ports; every shared dependency is `provided`
- [ ] `plugin.properties` is at the JAR root, and id/version/requires/class match the code
- [ ] The provider returns exactly one feature, and the child context explicitly assembles only its own Beans
- [ ] Every controller, page, and static directory has the correct `AccessPolicy` route declaration
- [ ] i18n, error codes, and status output do not leak credentials or exception details
- [ ] A download reports success only after files and success facts are durably committed
- [ ] Owner identity comes from `RequestOwnerIdentityResolver`, and `workKey` remains an opaque string
- [ ] Asynchronous queues, tasks, clients, executors, and schedulers can genuinely quiesce/drain/close
- [ ] The plugin owns its gallery page/API/static/i18n and registers only its own switch entry in `gallery.type-switch`
- [ ] Configuration, credentials, state/data, `PluginDataSource`, and artwork directories follow owner and path boundaries
- [ ] `mvn clean verify`, frontend behavior tests, and JAR structure checks pass
- [ ] Published artifact size, SHA-256, signature, and manifest refer to exactly the same bytes
- [ ] The private key is absent from source, build output, logs, plugin packages, and public repository directories
