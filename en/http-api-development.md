# Writing HTTP APIs and permission declarations

Spring MVC supplies the methods, paths and parameters. Descriptions can come from Javadoc retained at compile time. Use `@Operation`, `@Schema` and OpenAPI request body annotations for examples, manually decoded JSON and response details. Permissions come from active route declarations. Check each new operation in `/openapi` for its inputs, success status, target plugin restrictions and main errors. For asynchronous calls, explain how to retrieve the result.

When adding or changing business features in Web, Swing, Compose or CLI, check their HTTP API coverage in the same change. See [HTTP API](/en/http-api) for calls and endpoints, and [Third-party plugin SDK](/en/plugin-development) for plugin contracts.

In the controller’s HTTP language bundle, set `guide` to the group’s guide URL. Use `<Controller>.<method>.<VERB>.guide` for a more specific page or section. The generated operation exposes its own guide in `externalDocs` and distinct guides from related operations in `x-related-guides`. For a follow-up request whose parameters come from a response, declare `links` on that `@ApiResponse`.

Read the route grant from `x-program-permission`. For operations with additional checks, `x-program-permissions.route` has the same value. `discovery` identifies the call that supplies the source token. Each `submit` entry selects a grant directly, by request header, or from a catalog. In `x-schedule-sources`, use `schedulePermission` to manage schedules and `batchSubmitPermission` to submit a batch. A plugin endpoint that submits a manual batch for a fixed source declares `x-manual-batch-source` with that source type. The caller needs no discovery request on that endpoint. The instance OpenAPI document defines each phase, field and context prefix (`request#`, `task#`, `batch#`, `item#`) in its top-level `x-program-permission-conditions`.

For a conditional grant, read the JSON field at `when.keyFrom` and compare it to the literal `when.value`. For example, gallery export also requires the delete grant when `request#/deleteAfter` is the boolean `true`.

`execution` lists the grants for the work types selected by the plan in `executePermissionsByWorkType`. The worker also rechecks the source grant. A known missing grant appears at the JSON Pointer in `reportedIn.pointer`: read the HTTP 403 body on submission, or follow the 202 response’s `Location` with `core:batches.read` and read the batch result. A typed download rejection exposes the same ID through `DownloadTaskException.programPermission()`. For `auth.program.unsupported`, check `x-program-alternative` for another operation.

`x-csrf-origin` is `origin-or-referer` when a native client must send one of the two same-origin headers. `origin-required` uses the Origin parameter’s own constraints. For `conditional`, follow the parameter description for browser signals and cookies.

For a named JSON SSE event, declare the event DTO as the `text/event-stream` content schema and set the content extension `x-sse-data-event` to the event name. The generated media type keeps its string wire format and maps the event name to the DTO schema under `x-sse-events`. To link a browser operation to its program-key alternative, set `x-program-alternative` on the operation to its HTTP method and path, for example `POST /api/download/tasks`.

## Write an operation description and optional Details section

The controller annotation `@HttpApiGroup("core-batch")` selects `src/main/resources/i18n/http/core-batch.properties` in the module that owns the controller. Add the text there and translations in the same directory, for example in `core-batch_en.properties`. The host reads the bundle for the document language when it generates OpenAPI.

Each key starts with the controller’s simple class name, the Java method name and the uppercase HTTP method. Use `ANY` if the method mapping does not specify exactly one HTTP method. For `POST /api/download/batches`, `ManualBatchController.create.POST.summary` supplies the title, `.description` supplies the introduction, and `ManualBatchController.create.POST.details` holds optional Markdown.

The host appends the Markdown to the OpenAPI operation’s `description`, and `/openapi` shows a collapsed **Details** row. Select the row to read the full text. On `POST /api/download/tasks`, active download providers can also supply per-type text through `schema.DownloadSubmission.<workType>.description`. The page omits the section when neither the operation nor its providers supply text.

Before writing the section, trace every reachable path from the HTTP handler, including authorization, validation, provider dispatch, background work, file writes, result lookup and failure recovery where applicable. State which results the caller gets at submission and which become available when the task finishes. Check each language in `/api/openapi.json`, then open the matching method and path in `/openapi` and check that **Details** starts collapsed and opens from the keyboard to show the full text.

## When an API is required

Business status, settings, presets, downloads, translation, imports, exports, migration, maintenance and system management should be callable over HTTP. Reuse an equivalent API and its business service rather than duplicating execution logic for each GUI.

Window size, focus, theme previews, clipboard access, system file pickers and opening a local browser are client interactions. Expose the business operation separately. A remote client's file needs uploading; a server path refers to the server filesystem.

Long tasks need start, status, cancellation by execution ID and relevant result queries. A 202 response means accepted; requesting cancellation does not mean completion. A missing task does not establish that resubmission is safe. Document restart behavior and the scope of idempotency.

## Declaring permissions

The actual feature owner declares routes through `PixivFeaturePlugin.routes()`. `AccessPolicy` retains human, guest and local access rules. `WebRouteContribution.programPermissions` explicitly grants program access per HTTP method. Undeclared program access is denied and does not inherit administrator privileges.

```java
WebRouteContribution.admin("/api/example/items")
    .withProgramPermissions(Map.of(
        HttpMethod.GET, new ProgramPermission("items.read", "permission.items.read"),
        HttpMethod.POST, new ProgramPermission("items.create", "permission.items.create")
    ));
```

The host supplies the registered owner. For owner `example`, these become `example:items.read` and `example:items.create`. Put labels in the owner's display namespace. Program permissions require explicit `/api/` routes and methods; `**`, OPTIONS and GUI/LOCAL workflows are excluded. A complete `*` path segment can represent a resource ID.

| Business operation | Separate permissions | Additional checks |
| --- | --- | --- |
| Settings and presets | Read, validate and save | Actual field owner; never return secrets |
| Media tools and file imports | Read, preview, execute, cancel, acknowledge | Exact plugin publication, preview token and execution ID |
| Downloads and translation | Read, create or resume, cancel, delete records | Data scope, credentials and task ownership |
| Databases and environments | Read, check, preview, migrate, export, restore | Installation-wide scope, effects, confirmation and write suspension |
| Plugins and system | Read, install, start/stop, delete, restart | Executable code and process effects |
| Keys and administrator credentials | Human administrators only | Programs cannot create keys or increase their own permissions |

Endpoints sharing a permission must have consistent business purpose and data scope. Selecting a plugin selects its currently listed permissions, not future permissions. The catalog comes from current declarations and capabilities. An administrator session can read IDs, owners, labels and endpoints with GET `/api/security/program-keys/permissions`.

Shared APIs also check the target resource. Settings, media and file imports require both the core entry permission and the corresponding target plugin permission. Filter returned lists by target; check every target before any batch write. Database migration and environment archives affect the whole installation, not just one plugin. Withdrawn or replaced publications cannot be reused, and another owner's identical route cannot supply authorization.

## Input, errors and verification

Document methods, paths, permissions, target scope, request fields and budgets, asynchronous states and major errors. Validate paths, credentials and JSON at trust boundaries. Send keys only in `Authorization: Bearer`, never URLs, logs or browser storage. Use the client address normalized by the trusted proxy filter, not raw forwarding headers. Existing CSRF, recovery mode and plugin leases still apply.

Errors retain a stable `code` and localized `error`. Distinguish invalid input, denial, missing objects, revision conflicts, limits, unavailable capabilities and uncertain results. Query results after network interruption; do not automatically replay writes.

When a plugin validates an HTTP parameter itself, it can throw `top.sywyar.pixivdownload.core.web.HttpParameterException` from the SDK’s JDK-only `pixivdownload-core-api` module. For example, reject an unsupported `order` query value with `new HttpParameterException("query", "order", "invalid_value")`. The host returns HTTP 400 with `code=error.request.param.invalid`, a localized `error`, and `in`, `field` and `reason`. Existing plugins can keep their own error responses. To include this error and its fields in the instance’s OpenAPI document, annotate the operation with `@KnownErrorCodes(status = "400", value = "error.request.param.invalid")`.

Use `query`, `path`, `header`, `part` or `body` for `in`. For the first four, `field` is the parameter or part name on the wire. For a JSON body it is a JSON Pointer such as `/options/pages`. Use a stable `reason`, for example `required`, `type` or `invalid_value`. Keep submitted values out of these three fields. The host identifies missing parameters and conversion failures reported by Spring during binding.

Tests must exercise real filters or HTTP entry points and prove denied operations have no business effects. Cover methods, target plugins, source networks, revocation, replacement and failed persistence. Update the permission catalog, callers, five-language documentation and relevant regressions with the API. Declarations and compilation do not replace interaction and runtime verification.

## Permission scope and dedicated workflows

Scheduled tasks require the exact action permission under `core:schedule.*` and the source plugin's `schedule.manage`. Account-policy actions require the policy owner's same permission. Lists are filtered by authorized source. The `*` here groups permissions in documentation; it is not a grant you can save. Schedules are persistent installation settings, and authorization delegates the source's complete scheduling workflow. Revoking the creating key does not delete or disable schedules. Disable or delete a schedule to stop future runs.

Plugin lifecycle, enabled-state and trust changes require both the corresponding `core:plugins.*` action and `<plugin>:plugins.manage` for the target. Dependency consumers also require authorization. Installed, manageable plugins remain selectable while unloaded. Installation grants allow packages and their dependencies to execute server-side code. Global Hook publication grants allow security-policy changes; grant them with that scope in mind.

Shared credentials have separate `core:credentials.read`, `core:credentials.write` and `core:credentials.use` permissions. Reads return information, never secrets. Referencing stored credentials with `X-Acquisition-Credentials` requires `credentials.use` in addition to the business permission. Supply a credential for the current operation directly through `X-Acquisition-Credential`.

Gallery, novel-library, collection, inbox, archive, page-editor, update and maintenance permissions apply to the shared installation, not just data created by the key. Download tasks and manual batches remain isolated by key identity. Local maintenance, recovery and legacy metadata migration still require a local source. Permissions do not replace origin checks, confirmations, revisions or leases.

Identity setup, key management, GUI tokens, CAPTCHA, browser collection tickets, anonymous quotas and page assembly retain their dedicated workflows. Programs use `/api/download/tasks` for downloads and progress, `/api/tools/file-import` for file imports, `/api/library/artworks/{id}` for artwork management and `/api/page-editor` for page operations. Inbox tests use `/api/admin/notifications/test` or `/test-all`. Legacy browser compatibility routes do not receive separate program grants.
