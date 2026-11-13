# Writing HTTP APIs and permission declarations

When adding or changing business features in Web, Swing, Compose or CLI, check their HTTP API coverage in the same change. See [HTTP API](/en/http-api) for calls and endpoints, and [Third-party plugin SDK](/en/plugin-development) for plugin contracts.

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

Tests must exercise real filters or HTTP entry points and prove denied operations have no business effects. Cover methods, target plugins, source networks, revocation, replacement and failed persistence. Update the permission catalog, callers, five-language documentation and relevant regressions with the API. Declarations and compilation do not replace interaction and runtime verification.
