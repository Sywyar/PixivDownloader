# Independent contract service example

[简体中文](README.md)

This project requires JDK 17, Maven, and PixivDownloader SDK 1.4. Three plugins share an independent text metrics contract without adding its business interface to the host.

| Module | Artifact and purpose |
| --- | --- |
| api | Interface and records with JDK-only runtime dependencies; can be published as a separate Maven API |
| contract | Installable wrapper containing the original API JAR and its SHA-256 |
| provider | Explicitly exports the local `textMetrics` bean |
| consumer | Imports the service and exposes an administrator HTTP endpoint |

Run in this directory:

```sh
mvn package
```

To include JSON Schema, pass the absolute path to `tools/sdk-tools.jar` from the SDK development package:

```sh
mvn package "-Dpixivdownload.sdk.tools=/absolute/path/to/tools/sdk-tools.jar"
```

The tool reads `@ValueSchema` records and explicit `@ValueProperty` constraints, then writes JSON Schema 2020-12 files to `api/target/classes/META-INF/pixivdownload/schema/` for inclusion in the API JAR. These annotations are used only when compiling source; the runtime contract still requires only the JDK. Rebuild after changing the Java definitions; do not edit generated files. Run `java -jar /absolute/path/to/tools/sdk-tools.jar schema-check <schema.json> <data.json>` to validate a local sample; exit code 0 means it passed. The tool does not add HTTP validation or export services automatically. See the `ValueSchema` and `ValueProperty` Javadocs for supported types and constraints.

After generating schemas, keep passing the tool path on subsequent `package` builds. Omitting it or disabling the `schemas` profile fails with `SDK_SCHEMA_GENERATION_REQUIRED`. To build an API JAR without schemas, run `mvn clean package` without that parameter.

Use the SDK signing tool to sign each `target/*.jar` from `contract`, `provider`, and `consumer`. Install through the normal plugin installer and confirm trust. Do not install the `api` JAR as a plugin or bundle its classes into either participant.

Install `text-metrics-contract`, restart the host process, then install the provider and consumer. The consumer can start without a provider; its endpoint returns HTTP 503 with `UNAVAILABLE` while no provider is active. Both participants require the contract plugin at runtime. Contract installation, updates, and removal take effect after a full process restart. Providers and consumers support normal stop/start operations.

After signing in as an administrator, run this in the developer console of a same-origin page:

```js
fetch('/api/text-metrics', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({texts: ['abc', '你好']})
}).then(response => response.json()).then(console.log);
// {lengths: [3, 2], total: 5}
```

Lengths count Java UTF-16 code units. The endpoint enforces administrator access and same-origin write checks.

The service key's `contractId` is the contract plugin ID; its interface identifies a service within that contract. Only declared local beans are exported. Constructors and `PostConstruct` may retain `PluginServices`; resolve a service when a business call arrives. Multiple providers produce `AMBIGUOUS`. Old proxies never switch providers; resolve again after either participant stops. Cancellation does not prove that a method has exited, so draining still waits for its actual completion.

After a consumer restarts, use its newly injected `PluginServices`; the previous accessor is no longer active.

For subscriptions, listeners, or temporary registrations owned by this plugin, inject `PluginResources`, call `openScope()`, and create cleanup handles through `acquire(factory)`. Wrap auxiliary event callbacks with the scope's `guard(callback)`; late callbacks are skipped after withdrawal. The host releases resources after requests, services, and tasks have drained. If local `close()` reports `BUSY` or `CLOSE_FAILED`, retain the scope and retry later. See the `PluginResourceScope` Javadoc for the full contract.

This channel supports synchronous pure values. Service interfaces must not be sealed. Interfaces and DTOs must come from the same original API JAR; records use default canonical constructors. See the SDK's `PluginServices` Javadoc for the type and resource limits. Asynchronous tasks, streams, and resource handles keep their dedicated contracts.

Failure responses contain a stable `code` and an `error` message in the request language. Branch on `code`, such as `UNAVAILABLE`, rather than on the message text.

Only one version of a contract ID can be active. Installation rejects duplicate contract classes, split packages, multi-release API JARs, and implicit JAR `Class-Path` or indexes in a shared contract class space. Participants compile with `provided` dependencies; runtime checks still verify their actual class sources.
