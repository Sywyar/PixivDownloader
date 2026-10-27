# Independent contract service example

[简体中文](README.md)

This project requires JDK 17, Maven, and PixivDownloader SDK 1.4. Three plugins share an independent text metrics contract without adding its business interface to the host.

| Module | Artifact and purpose |
| --- | --- |
| api | JDK-only interface and records; can be published as a separate Maven API |
| contract | Installable wrapper containing the original API JAR and its SHA-256 |
| provider | Explicitly exports the local `textMetrics` bean |
| consumer | Imports the service and exposes an administrator HTTP endpoint |

Run in this directory:

```sh
mvn package
```

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

This channel supports synchronous pure values. Service interfaces must not be sealed. Interfaces and DTOs must come from the same original API JAR; records use default canonical constructors. See the SDK's `PluginServices` Javadoc for the type and resource limits. Asynchronous tasks, streams, and resource handles keep their dedicated contracts.

Failure responses contain a stable `code` and an `error` message in the request language. Branch on `code`, such as `UNAVAILABLE`, rather than on the message text.

Only one version of a contract ID can be active. Installation rejects duplicate contract classes, split packages, multi-release API JARs, and implicit JAR `Class-Path` or indexes in a shared contract class space. Participants compile with `provided` dependencies; runtime checks still verify their actual class sources.
