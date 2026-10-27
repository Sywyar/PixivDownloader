# Independent contract service example

[简体中文](README.md)

Requires JDK 17, Maven, PixivDownloader SDK 1.4, and a host implementing its service, resource and remote operation interfaces. Three plugins share a text metrics contract without adding that business interface to the host.

| Module | Purpose |
| --- | --- |
| api | Interfaces and records with JDK-only runtime dependencies, plus generated contract artifacts |
| contract | Installable wrapper containing the original API JAR and its SHA-256 |
| provider | Explicitly exports the local `textMetrics` bean |
| consumer | Imports the service and registers the generated administrator HTTP adapter and client resources |

Build here with the tool from the SDK development package:

```sh
mvn verify "-Dpixivdownload.sdk.tools=/absolute/path/to/tools/sdk-tools.jar"
```

The build consumes `@ValueSchema`, `@ValueProperty` and explicit `@RemoteService` / `@RemoteOperation` declarations to generate JSON Schema, operation metadata, a JS client, type declarations and a Java HTTP adapter. Generated files belong to each module's `target/`. Rebuild after changing the contract; do not edit generated files. Completion markers cover the whole output set. Disabled or failed generation and missing contracts reject packaging, including incremental builds. Use `java -jar sdk-tools.jar schema-check <schema.json> <data.json>` to validate a schema sample.

Sign the contract, provider and consumer JARs, then install through the normal trust confirmation flow. Install the contract and restart the host before installing the participants. The business provider may be absent; the contract is a required dependency. Do not install the API JAR separately or copy its classes into participants. Both participants use provided compile dependencies. Contract installation, updates and removal require a process restart; one contract ID has only one active version.

After signing in as an administrator, run this in the console of a same-origin page:

```js
const {analyzeTexts} = await import('/text-metrics-client/text-metrics.mjs');
await analyzeTexts({texts: ['abc', '你好']});
// {lengths: [3, 2], total: 5}
```

The module imports the generated client and references its types through JSDoc. The server consumes the same contract to validate requests before invocation and responses before returning them. The adapter supplies route declarations; the host checks controller mappings against them. Service imports and exports alone do not expose HTTP. Remote operations currently support exact POST paths and ADMIN access, with login, same-origin writes and current user identity checks. A publication owner is not a user identity.

Metrics count Java UTF-16 units; schema length constraints count Unicode code points. Remote integers must fit both their Java type and ±9,007,199,254,740,991. Declare explicit safe bounds for long. Unknown fields, invalid enum values, out-of-range integers and unpaired surrogates are rejected. Optional fields must allow null; missing fields decode as null. Custom constructors, accessors and serialization annotations are outside the generated subset. Requests and responses are limited to 1 MiB; value traversal allows 16,384 nodes and 32 levels.

Clients do not retry automatically. An unavailable service returns `REMOTE_UNAVAILABLE`; a contract identity mismatch returns `REMOTE_CONTRACT_MISMATCH`; invalid input returns `REMOTE_VALUE_INVALID`; provider failure returns `REMOTE_FAILED`. Server errors contain a stable `code` and localized `error`. Clients branch on `RemoteError.code`. AbortSignal cancellation returns `REMOTE_CANCELLED`, and transport failure returns `REMOTE_TRANSPORT`, including failures while reading the response body. Cancelling fetch does not prove the server invocation exited: draining still waits for actual completion. The handwritten `/api/text-metrics` endpoint remains an example of direct service use.

Register a binding during initialization when a subscription should follow dependency availability:

```java
ServiceKey<TextMetrics> key = new ServiceKey<>("text-metrics-contract", TextMetrics.class);
ServiceBinding binding = services.bind(List.of(key), scope -> {
    TextMetrics current = scope.require(key);
    scope.acquire(() -> eventSource.subscribe(
            scope.guard(() -> refresh(current))));
});
```

Here `eventSource.subscribe` returns a closeable handle and `refresh` handles auxiliary events. Keep business commands and persistent tasks outside activation callbacks. Activation waits for the consumer publication and unique active providers for every dependency. Withdrawal blocks old callbacks and proxies, drains actual execution, releases resources, and only then establishes a replacement round. Old proxies never switch providers.

Read `binding.state()`: WAITING means a dependency is absent; AMBIGUOUS means multiple providers match. Activation passes through ACTIVATING and ACTIVE, then DRAINING on withdrawal. FAILED means activation failed and rollback completed; CLOSE_FAILED retains resources for cleanup. Use `retry()` to retry resource activation or cleanup. Permanent `close()` is asynchronous; only CLOSED proves cleanup completed. A stopped consumer cannot revive when providers return. Synchronous lifecycle mutation from activation, guarded callbacks or disposers is rejected to prevent self-waiting.

Use `PluginResources.openScope()` for local resources that do not follow dependencies. Services carry synchronous pure values only. Tasks, streams and resource handles retain dedicated contracts. Installation still verifies duplicate classes, split packages, implicit classpaths and original API bytes. See the SDK Javadocs for complete constraints.
