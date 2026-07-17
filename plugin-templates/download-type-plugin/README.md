# Download type plugin template

This standalone Maven project demonstrates a stable, resilient, and approachable PixivDownloader Plugin API surface for a new download type. It is intentionally deterministic: the endpoints produce mock items, the queue is in memory, and no external website, credential, proxy, database, or download directory is used.

Before publishing a real plugin, replace these identities consistently:

| Template value | Replace with |
|---|---|
| `example-download-plugin` | Your Maven artifact id |
| `example-download` | Globally unique plugin and queue type id |
| `com.example.pixivdownload.downloadtype` | Your Java package |
| `ExampleDownload*` | Your Java class prefix |
| `0.1.0` | Your plugin artifact version |
| `plugin.requires=1.0` | The compatible Plugin API major/minor requirement |
| `plugin.provider=Example Developer` | Your provider name |
| `example-download` i18n namespace | Your unique web i18n namespace |
| `/api/example-download/**` and `/example-download/**` | Plugin-owned API and static paths |

Build from this directory with `mvn clean verify`, or build both repository templates with `mvn -f ../pom.xml clean verify`. The output is a thin PF4J JAR. PixivDownloader supplies Plugin API, PF4J, Spring, and Jackson from its parent classloader, so every such dependency remains `provided` and must not be copied into the JAR.

## Stable examples included

- PF4J entry point, `PixivPluginProvider`, `PixivFeaturePlugin`, and explicit Spring child-context configuration.
- `DownloadTypeDescriptor` contract version 1 with all five acquisition modes, truthful queue/schedule/gallery capability declarations, filters, settings, and UI slots.
- `QueueOperations` with clear-all, owner-scoped clear, optional opaque-string `workKey` cancel, and the default already-drained quiesce sentinel. Queue items keep the raw key in top-level `cancelWorkKey`; the host posts it as JSON to the queue-type endpoint, so it is distinct from the card/display id and is not restricted to one URL path segment. The default drain is correct only because this mock performs no background work; an asynchronous implementation must return and drain a real positive generation.
- A controller that obtains `RequestOwnerIdentity` only from the host-provided `RequestOwnerIdentityResolver`; request bodies and query parameters never choose their own owner.
- A current-script-scoped queue behavior module with `process(item)`, import, user, series, search, quick, filters, settings, and a declarative settings slot. `process` writes only through `context.updateItem`, and quick actions publish results through `context.publishWorks`; plugins do not receive queue persistence or rendering globals.
- A separate UI-slot module that mounts a Vue component through the host `PixivVue` helper and uses owner-scoped `supports` / `dispatchQuickAction`. Do not bundle Vue in a plugin.
- A credential-free schedule source and synchronous work executor using only stable contracts. Its browser hooks read and restore the current single-import value through publication-scoped `context.acquisitionInput(mode)` / `context.restoreAcquisition(mode, value)`; they do not read host DOM ids or call host globals.
- An admin-only independent gallery page rendered with DOM APIs, i18n bundles, separate HTML/CSS/JS files, and dark-mode variables.
- Deterministic controller endpoints returning stable machine codes plus i18n keys.

## Deliberate limits

This template is not a network downloader and does not demonstrate authentication, anti-bot workarounds, DRM access, private content, host database access, or a host download-root service. Replace the in-memory domain action with your own lawful blocking download implementation before reporting `ScheduledWorkResult.completed()`.

The stable Plugin API lets a third-party plugin declare `independentPage=true`, which this template demonstrates. `/api/gallery/unified/**` is a deprecated host-internal compatibility API, not a third-party SDK capability, and must not receive new consumers. The maintained `/pixiv-gallery.html` family and its source-owned APIs are not deprecated. Deep asset serving, deletion, visibility, search/filter projection, collections, and statistics remain host-owned. Do not import gallery, app, core implementation, runtime-internal, installer, or signature-internal classes to bypass that boundary.

The schedule contracts are stable, but the host remains responsible for claims, leases, credentials, cancellation, pending work, and checkpoints. A plugin owns only its descriptor, opaque definition/payload schemas, source discovery, and synchronous work execution. The current neutral browser input adapter exposed to third-party source modules covers `single-import`; do not reach into host DOM or private mode functions to emulate other editor modes. This example is credential-free and has no checkpoint.

The queue cancel contract is addressed by `(queueType, workKey)`. `workKey` is an opaque stable string within that queue type; validate it only inside your own implementation. The host bridge calls `POST /api/download/queue/{queueType}/cancel` with JSON containing the raw `workKey` plus the backend manifest's `pluginId`, `packageId`, `generation`, and descriptor `publicationId`. Those fields prove descriptor currentness; they are not the user's authorization identity. The server fixes the matching operation proxy before rechecking that descriptor publication and fails closed if either publication changes. User owner/admin scope is derived separately from the current request. Plugin modules should use the host cancel bridge instead of constructing this control request themselves. Never broadcast a key to every queue type, coerce every site identity to a number, place the key in a path segment, or accept a user owner UUID supplied by caller JSON.
