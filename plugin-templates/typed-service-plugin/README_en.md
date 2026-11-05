# Independent Contract Service Example (`typed-service-plugin`)

[简体中文](README.md)

This multi-module example demonstrates how to define a cross-plugin typed service contract, generate JSON Schemas, and expose an administrator HTTP client and adapter using PixivDownloader SDK 1.4+ without adding business interfaces to the host.

| Module | Purpose |
| --- | --- |
| `api` | Pure-JDK interfaces and records (`@ValueSchema`, `@ValueProperty`, `@RemoteService`, `@RemoteOperation`) plus generated schema and client assets |
| `contract` | Installable contract carrier plugin (`process-restart`) embedding the original `api` JAR and its SHA-256 |
| `provider` | Implements and exports the `textMetrics` service bean |
| `consumer` | Imports the service and registers the generated administrator HTTP adapter and client module |

## 1. Build and Install

Requires JDK 25+, Maven, and `sdk-tools.jar` from the matching SDK development package:

```sh
mvn verify "-Dpixivdownload.sdk.tools=/absolute/path/to/tools/sdk-tools.jar"
```

The build generates JSON Schemas, JS clients, `.d.mts` declarations, and Java HTTP adapters under each module's `target/` directory (never edit generated files manually; use `java -jar sdk-tools.jar schema-check <schema.json> <data.json>` to validate standalone samples).

Sign the `contract`, `provider`, and `consumer` JARs, **install `contract` first and fully restart the host**, then install `provider` and `consumer` (do not install the raw `api` JAR separately or bundle its classes into `provider` or `consumer`).

## 2. Browser Remote Calls and Dynamic Service Binding

Sign in as an administrator and run the following in the browser console on a same-origin page:

```js
const {analyzeTexts} = await import('/text-metrics-client/text-metrics.mjs');
await analyzeTexts({texts: ['abc', '你好']});
// {lengths: [3, 2], total: 5}
```

- **Remote operation bounds**: Supports exact POST routes under `ADMIN` access, validating both requests and responses against the shared contract (max 1 MiB, 16,384 nodes, 32 levels; integers must fit both the Java type and `±9,007,199,254,740,991`). Failures return a stable `RemoteError.code` (`REMOTE_UNAVAILABLE`, `REMOTE_CONTRACT_MISMATCH`, `REMOTE_VALUE_INVALID`, `REMOTE_FAILED`, `REMOTE_CANCELLED`, `REMOTE_TRANSPORT`) without automatic retries.
- **Lifecycle-aware binding**: Use `PluginServices.bind` when subscriptions must follow provider availability:

```java
ServiceKey<TextMetrics> key = new ServiceKey<>("text-metrics-contract", TextMetrics.class);
ServiceBinding binding = services.bind(List.of(key), scope -> {
    TextMetrics current = scope.require(key);
    scope.acquire(() -> eventSource.subscribe(
            scope.guard(() -> refresh(current))));
});
```

Activation waits until the consumer and a unique active provider for every dependency are ready; withdrawal blocks new calls, drains in-flight work, and releases resources before activating a replacement round (inspect via `binding.state()`, retry via `retry()`). Use `PluginResources.openScope()` for local resources not tied to external services. See the SDK Javadocs for full contract details.
