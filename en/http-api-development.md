# Writing HTTP APIs and permission declarations

When adding or modifying business features across Web, Swing, Compose or CLI, check whether a companion HTTP API, program-key permission declaration and localized OpenAPI documentation are required. For client usage and the built-in permission catalog, see [OpenAPI and program keys](/en/http-api); for plugin extension contracts, see [Third-party plugin SDK](/en/plugin-development).

## When a companion HTTP API is needed

- **Expose business capabilities over HTTP**: querying business status, reading and saving settings, applying presets, downloading, translating, importing, exporting, migrating databases, running maintenance and managing the system should all be achievable over HTTP. Reuse the same business service when an equivalent endpoint already exists instead of duplicating logic per GUI.
- **Separate client presentation from server operations**: window size, focus, theme preview, clipboard access, native file pickers and opening a local browser are client-side presentation concerns that the server need not simulate. When a workflow involves files, remote client files must be uploaded explicitly, whereas server path parameters always refer to the server's local filesystem—never mix the two.
- **Long-running task lifecycle**: asynchronous tasks must provide start (`202 Accepted`), status query, cancellation by execution identity and result query endpoints, and document cancellation draining (requesting cancellation does not mean execution has already exited; a missing task does not guarantee safe resubmission), post-restart state and idempotency scope.

## Declare routes and program permissions

Feature owners declare routes in `PixivFeaturePlugin.routes()`. While `AccessPolicy` governs human administrators, guests and local GUI flows, program-key access is declared per HTTP method via `WebRouteContribution.withProgramPermissions(...)` (undeclared operations reject program keys by default and never inherit administrator access):

```java
WebRouteContribution.admin("/api/example/items")
    .withProgramPermissions(Map.of(
        HttpMethod.GET, new ProgramPermission("items.read", "permission.items.read"),
        HttpMethod.POST, new ProgramPermission("items.create", "permission.items.create")
    ));
```

- **Permission IDs and route constraints**: the host prefixes each permission ID with the registered owner as `<owner>:<permission>` (e.g., `example:items.read`), resolving the label key from the owner's display i18n namespace. Program permissions apply only to explicit `/api/` routes and concrete HTTP methods (a single-segment `*` may represent a resource ID; `**`, `OPTIONS` and GUI/LOCAL-only flows are rejected).
- **Granularity and target checks**: endpoints sharing a permission must share a consistent business purpose and data scope; selecting a plugin group in the UI only selects currently listed permissions and never stores a wildcard for future permissions. Shared endpoints (settings, media maintenance, file import, plugin lifecycle, scheduled tasks, etc.) must verify both the `core` entry permission and the target plugin/source permission before any side effect, filtering read lists to authorized targets (see [OpenAPI and program keys: Permission scope and dedicated workflows](/en/http-api#permission-scope-and-dedicated-workflows)).

| Domain | Permission split | Additional security checks |
| --- | --- | --- |
| Settings and presets | Separate read, validate and save | Verify each field's actual owner; return only `stored` flags for sensitive fields, never plaintext secrets |
| Media maintenance and file import | Separate read, preview, execute, cancel and acknowledge | Verify exact plugin publication, preview token and execution ID |
| Downloads and translation | Separate read, create/resume, cancel and delete records | Verify data scope, acquisition credentials and task ownership |
| Databases and environment archives | Separate read, check, preview, migrate, export and restore | Installation-wide impact; verify explicit confirmation, preview token and write-stop windows |
| Plugins and system administration | Separate read, install, enable/disable, remove and restart | Governs code execution and process lifecycle; check both target plugin and reverse-consumer permissions |
| Program keys and admin credentials | Human administrator sessions only | Program keys must never create/modify keys or escalate their own privileges |

## Localized documentation and OpenAPI extensions

Spring MVC supplies methods, paths and parameters, while compile-retained Javadoc, `@Operation`, `@Schema` and request-body annotations document examples, manually parsed JSON and response codes.

### Localized descriptions and collapsible "Details"

Annotating a controller with `@HttpApiGroup("core-batch")` binds it to `src/main/resources/i18n/http/core-batch.properties` and its localized siblings (such as `core-batch_en.properties`) in the same module. Key prefixes follow `<ControllerClassName>.<javaMethodName>.<UPPERCASE_HTTP_METHOD>` (or `ANY` when not restricted to a single HTTP method):

| Resource key | Purpose |
| --- | --- |
| `guide` / `<prefix>.guide` | Group-wide or operation-specific guide path (exported to `externalDocs`; guides from related operations are deduplicated into `x-related-guides`) |
| `<prefix>.summary` / `<prefix>.description` | Operation title and short summary |
| `<prefix>.details` | Optional Markdown body appended to `description` and rendered as a collapsed "Details" section on `/openapi` by default |
| `schema.DownloadSubmission.<workType>.description` | Adds work-type-specific documentation under `POST /api/download/tasks` for enabled download providers (the collapsible section is omitted when neither operation nor provider supplies details) |

Before writing documentation, trace the full call chain from the HTTP entry through authentication, validation, providers, async execution, disk persistence, result polling and failure recovery, distinguishing immediate submission checks from asynchronous terminal outcomes. Verify `/api/openapi.json` across all languages and test expanding the section via keyboard on `/openapi`.

### OpenAPI extension metadata

| Extension field | Semantics and declaration |
| --- | --- |
| `x-program-permission` / `x-program-permissions` | Single-route permissions appear in `x-program-permission`; operations with extra checks also populate `x-program-permissions` with `route`, `discovery` (token discovery endpoint), `submit` (fixed grants, header-triggered grants or catalog conditions) and `execution` (such as `executePermissionsByWorkType`). Top-level `x-program-permission-conditions` defines `request#`, `task#`, `batch#` and `item#` resolution rules, `when.keyFrom` / `when.value` predicates (e.g., requiring delete permission on export only when `request#/deleteAfter == true`), and `reportedIn.pointer` for missing permission IDs |
| `x-schedule-sources` / `x-manual-batch-source` | `x-schedule-sources` lists `schedulePermission` and `batchSubmitPermission` per source; plugin endpoints that submit a fixed-source manual batch declare the source type via `x-manual-batch-source` |
| `x-csrf-origin` | Declares write-request origin enforcement: `origin-or-referer` (native clients must also send a same-origin `Origin` or `Referer`), `origin-required` or `conditional` (distinguished by browser signals such as Cookies) |
| `x-program-alternative` | Points browser-only operations to their program-key counterpart (formatted as `<METHOD> <path>`, e.g., `POST /api/download/tasks`) |
| `links` (`@ApiResponse`) | Declares follow-up operations and response parameter extraction so `/openapi` can render "Related operations" jumps |
| `x-sse-data-event` / `x-sse-events` | For named JSON SSE events, declare the event DTO schema and `x-sse-data-event` on the `text/event-stream` content; generated docs keep the wire format as string while exporting per-event DTO schemas under `x-sse-events` |

## Parameter validation, error contracts and testing

- **Trust boundaries and error shape**: validate paths, credentials and JSON at the entry boundary; accept program keys only in `Authorization: Bearer` (never in URLs, logs or persistent browser storage); resolve client IPs only after `server.trusted-proxy-cidrs` processing. Error responses preserve a stable machine `code` and localized `error` (download submission authorization failures can expose the missing permission ID via `DownloadTaskException.programPermission()`).
- On a controller annotated with `@HttpApiGroup`, use the SDK’s `@KnownErrorCodes` on the class or method to list the HTTP status and business codes for a standard JSON error. OpenAPI can generate the `application/json` response for that 4xx or 5xx status. Document any additional fields in the response schema.
- **Structured parameter errors (`HttpParameterException`)**: when validating parameters manually at an HTTP entry, plugins can throw `top.sywyar.pixivdownload.core.web.HttpParameterException(in, field, reason)` from JDK-only `pixivdownload-core-api` (for example, `new HttpParameterException("query", "order", "invalid_value")`) and annotate the method with `@KnownErrorCodes(status = "400", value = "error.request.param.invalid")` so the error is listed in OpenAPI:
  - The host returns HTTP 400 with `code = "error.request.param.invalid"`, a localized `error`, and `in`, `field` and `reason` (never echo raw submitted values or credentials in these fields; Spring's own missing-parameter and type-conversion failures are mapped automatically by the host).
  - `in` is `query`, `path`, `header`, `part` or `body`; `field` is the declared parameter/part name for the first four, or a JSON Pointer (such as `/options/pages`) for `body`; `reason` is a stable machine token (such as `required`, `type`, `invalid_value` or `out_of_range`).
- **Verification**: tests must pass through real security filters or HTTP entry points, prove that unauthorized requests produce zero business side effects, and cover HTTP method differences, target-plugin filtering, source-network restrictions, key revocation, plugin generation replacement and failed-save rollbacks. Update the permission catalog, callers, five-language documentation and regression tests together whenever endpoints change.
