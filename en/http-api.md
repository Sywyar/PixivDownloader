# HTTP API and program permissions

## Connect a script or agent

1. Open `/openapi` on your PixivDownloader instance (locally, the default is `http://localhost:6999/openapi`). Sign in as an administrator to see the available endpoints, request examples and permissions for each operation.
2. In desktop **Settings → Access control → Program access keys**, create a key for the client’s source IP and the operations it needs. Grant `core:api-reference.read` if the script or agent will read the API reference itself. On a headless server, an administrator can manage keys through `/api/security/program-keys` using a signed-in session.
3. A script can request `GET /api/openapi.json` with `Authorization: Bearer <program-key>` to read the OpenAPI document. For an agent, choose **Copy prompt** after creating the key to share your instance’s documentation URL. Configure the key separately. If the key lacks permission to read the document, provide the relevant operation descriptions yourself.

Anyone can open the access instructions at `/openapi`. Reading the JSON document requires an administrator session or a program key with reference access. Use `HEAD /api/openapi.json` with the same credentials to check access.

Use HTTPS. By default the server rejects program keys sent over HTTP from outside the local network. To allow an exception, review the risk for the individual key, then confirm public HTTP access. Editing its permissions or sources restores the HTTPS requirement. A server rejection cannot protect a key already sent in plaintext.

The full key appears once at creation. The server stores its digest. Keys have no default expiry. You can change a key’s permissions and allowed sources or revoke it. Selecting a whole plugin grants only the permissions currently listed.

Source restrictions accept numeric IPv4, IPv6 and CIDR networks. Restricted mode requires at least one entry; **Any source** is an explicit choice. Configure trusted proxies before using a reverse proxy. Do not combine a program key with administrator sessions, invitations or GUI credentials. Send acquisition credentials separately in `X-Acquisition-Credential`.

Administrator sessions can still use existing endpoints through `X-Session-Token`. Program keys can access only methods and paths explicitly listed in the permission catalog. Other endpoints retain their human-administrator or local workflows. Key management and administrator credential changes require a human administrator. This API does not provide an MCP server.

A valid Bearer key sent on its own to an operation without `programKey` receives `403 auth.program.unsupported`, even when anonymous access is allowed. An invalid key or mixed credentials can return `401 auth.program.invalid`.

Cookie-authenticated writes require a same-origin check. Cross-origin browser requests are rejected; plugin management and scheduled-task endpoints also require an explicit same-origin signal. `/api/gui/**` retains its local-machine and GUI-token restrictions. Remote programs use the business endpoints below. Plugin capabilities require an active plugin and available dependencies.

Use `GET /api/security/program-keys/self` to read the current key’s `id` and `permissions`, including an empty grant list. For writes, check `x-csrf-origin` even when using Bearer authentication. `POST /api/schedule/tasks` requires a same-origin Origin or Referer. `POST /api/download/tasks` and `/api/download/batches` allow a native Bearer client without browser credentials to omit both.

To rotate a key, an administrator creates its replacement on the desktop Security page or through an administrator session. Switch the client, verify the new identity and grants with `GET /api/security/program-keys/self`, then have the administrator revoke the old key. Tasks submitted with the old key remain scoped to it. Finish polling them before revocation or follow them with an administrator session.

## Endpoint index

| Feature | Endpoint |
| --- | --- |
| Settings, defaults and redacted values | GET `/api/settings`; GET `/api/settings/presets` |
| Validate and save settings | POST `/api/settings/validate`; POST `/api/settings` |
| Plugin repositories | GET / PUT `/api/settings/repositories` |
| Database settings and connection test | GET `/api/settings/database`, `/providers`, `/status`; POST `/check` |
| Database migration | POST `/api/settings/database/migration-previews`, `/migrations`; GET `/migration` |
| Environment archives | POST `/api/settings/environment/preview`, `/restore`, `/exports`; GET `/restore`, `/exports`, `/exports/{id}/archive`, `/installation` |
| Metadata backfill | GET `/api/tools/backfill/defaults`, `/tasks/current`, `/tasks/{id}`; POST `/candidates`, `/tasks`, `/tasks/{id}/cancel` |
| Media maintenance and record-file import | GET `/api/tools/media`, `/api/tools/file-import`; under each endpoint: POST `/previews`, `/tasks`, `/tasks/cancel`, GET `/tasks/current` |
| Media capabilities and import acknowledgement | POST `/api/tools/media/capabilities`; POST `/api/tools/file-import/completion/acknowledge` |
| Tool history and log attachments | GET `/api/tools/history`, `/api/tools/logs/{fileName}` |
| Directory references | GET / POST `/api/tools/directories` |
| Path prefixes | `/api/tools/path-prefixes` |
| Image classification | `/api/tools/classifier/settings`, `/folders`, `/images`, `/image`, `/moves` |
| FFmpeg status and installation | GET `/api/tools/ffmpeg`; POST `/api/tools/ffmpeg/tasks` |
| System and control center | `/api/admin/system`, `/api/admin/control-center` |
| AI, mail and push tests | `/api/admin/ai/test`, `/models`; `/api/admin/mail/test`, `/test-all`; `/api/admin/push/test`, `/test-all` |
| Application updates | `/api/admin/update/check`, `/last`, `/download`, `/download/progress`, `/install` |
| Individual downloads and history | `/api/download/tasks`, `/api/download/tasks/history` |
| Background manual batches | `/api/download/batches` |

Short paths in a row share that API family's prefix. Translation already uses `/api/translation/**`; glossary, narration, galleries, collections, schedules, maintenance and the plugin market retain their own endpoints. Desktop windows, file pickers and opening a local browser are client operations.

## Save settings

Read the field catalog first, then submit the returned `owner`, `packageId`, `generation` and `key`. Host fields use null owner/packageId and generation 0. Sensitive fields return only a `stored` flag, never the value or a secret default. `KEEP`, `SET` and `CLEAR` retain, replace and remove values. Presets are field-value maps applied through the same save endpoint. `effect` describes when a setting takes effect; saving does not itself restart the backend or process.

Example body for POST `/api/settings/validate` or `/api/settings`:

```json
{
  "changes": [
    {
      "owner": null,
      "packageId": null,
      "generation": 0,
      "key": "server.port",
      "action": "SET",
      "value": "6999"
    }
  ]
}
```

Repository edits must carry the `revision` returned by GET. Adding a repository or changing its trust, address or network policy requires `trustConfirmed:true`. Refresh after a revision conflict instead of overwriting another edit. Connection tests and temporary tool options do not save configuration.

## Background manual batches

1. GET `/api/schedule/sources` for the current `sourceType` and `activationToken`. The source plugin defines its input format.
2. POST `/api/download/batches` with `name`, `sourceType`, `activationToken`, `definition` and optional `proxy`. Send `definition` as a JSON object, up to 1 MiB after serialization to UTF-8. The entire request is limited to 8 MiB.
3. Query the returned UUID with GET `/api/download/batches/{id}` and `/{id}/items?offset=0&limit=100`. An item is identified by batch UUID plus `workType` and `id`, not an individual download execution UUID.
4. POST `/{id}/pause`, `/cancel`, `/resume` or `/retry` to pause, cancel, resume discovery or retry only failed items. Stopping is terminal only after execution really exits. Retrying discovered failures does not finish an incomplete source scan.
5. DELETE `/{id}` while idle to remove the record. A process retains one batch; delete it before creating another. Batch results do not survive process restarts.

On submission, a 403 response includes `programPermission` when the required grant is known. For `auth.program.unsupported`, consult the operation’s `x-program-alternative`. After a 202, follow `Location` with `core:batches.read`. A failed batch can report the missing executor grant in `programPermission`.

`explicit-list` accepts mixed artwork and novel entries. Put this object in `definition`. Work IDs must be positive decimal integer strings, with no duplicate ID within a type:

```json
{
  "kind": "mixed",
  "source": {
    "acquisitionMode": "single-import",
    "works": [
      {
        "kind": "illust",
        "id": "123"
      },
      {
        "kind": "novel",
        "id": "456"
      }
    ]
  },
  "filters": {
    "content": "safe",
    "aiFilter": "exclude",
    "tagsExact": [],
    "tagsFuzzy": []
  },
  "download": {
    "concurrent": 1,
    "intervalMs": 1000,
    "bookmark": false
  }
}
```

Replace the example work IDs with your targets. Other sources cover author posts, requests, search, series, bookmarks, followed works and collections. Filters cover content rating, AI, tags, artwork type, pages, novel word count and bookmark count. An unknown bookmark count cannot pass an active bookmark filter. Resuming does not rerun completed or filtered-out items.

A batch accepts at most 5,000 items and reserves at most 64 MiB of encoded content, whichever limit is reached first; this is not a JVM heap guarantee. Pages contain at most 200 items. PUT `/{id}/credentials` with `{"expectedRun":1}` replaces credentials from the request header; omitting the header clears them. The batch must be idle at that run number, and credentials are probed again before resuming. POST `/{id}/resolve` takes `expectedRun`, `workType`, `workId`, `reasonCode`, `userAction` and `rememberForRun`. The reason and run must match the pending record. Both bodies are limited to 16 KiB.

## Tools and restart operations

For media maintenance and record-file import, read the catalog, then preview using its exact `identity` (pluginId/packageId/generation/publication) and `options`. Start with the same identity and preview `token`; cancellation also requires `executionId`. Old tokens or UUIDs cannot affect a replacement plugin or a new task started by the desktop. Paths refer to the server filesystem, not the remote client's machine.

Example for POST `/api/tools/backfill/tasks`:

```json
{
  "delayMs": 0,
  "artworkIds": [12345],
  "dryRun": true,
  "alwaysNetwork": false
}
```

`limit:0` means all candidates; the body limit is 4 KiB. A 202 response means accepted, so keep querying that execution. Classification moves report copying, source removal and database registration separately. Inspect partial results before retrying a move.

Confirm database migration with the preview token. Environment restore takes a preview token, selected fields, path mappings and password; `RESTORE_PENDING` means a replacement process will perform the restore. Export requires an absolute server `destination` and `confirmRestart:true`, with optional `password`, `includeCredentials`, `includeDatabases`, `includeState` and `includeResources`. Resources require databases; credentials, databases or state require a password. An existing destination is never overwritten. The backend stops, exports and requests a full restart; reconnect to query the result. If restart fails, start the application manually. Update installation requires the verified package's current SHA-256.

`POST /api/library/artworks/{artworkId}/recover-metadata` fills missing fields from supplied values. A missing, unreadable or empty payload returns `400`. To fetch tags and series through the backend, start a backfill scoped to the imported artwork IDs. [Fill in metadata after import](/en/local-import?id=metadata-backfill)

Use `GET /api/gallery/artworks?fileManagement=EXTERNAL_REFERENCE` to list works that reference external files. MANAGED files are removed when the work is deleted. EXTERNAL_REFERENCE means the current record references external files, which are kept on deletion. After deletion, the library keeps the record with `deleted=true` for duplicate detection.

On `/openapi`, find **Related operations** directly below the list of responses and follow a link. For example, the local import presets link to the scan preview. The server caches the document and its links by language. Reload the page after the catalog changes, such as when a plugin registers new routes.

Open `externalDocs` for the operation’s guide. Response `links` describe a follow-up request with parameters taken from the response. `x-related-operations` prioritizes declared alternatives and response links, then routes in the description and operations sharing resource names or a group. Check the target operation’s inputs and permissions. `x-related-guides` lists guides for these operations.

An artwork or novel filter applies to all matching works across pages. With `"mode":"filter"`, supply a JSON object for `filter`. ID arrays accept positive JSON integers such as `42`. A decimal such as `42.9`, a string such as `"42"`, zero or a negative number returns HTTP 400. Use `field` to locate the error. An explicit `"filter":{}` or a `filter` containing only pagination selects all works of that type. For deletion or export with source cleanup, confirm an unrestricted selection with `"confirmAll":true`. Program keys need the corresponding delete grant for export with cleanup. When an export returns `archiveToken`, poll `GET /api/archive/status/{token}` (`core:archives.read`). Once `ready`, fetch `GET /api/archive/download/{token}` (`core:archives.download`).

## Errors and history

When you look up illustration or novel metadata or submit a download through `POST /api/download/tasks`, `404 WORK_NOT_FOUND` means the source could not find the work for the current identity. Check the work ID and its visibility. When you look up a task by ID, `404 DOWNLOAD_TASK_NOT_FOUND` means the caller cannot find that task.

When polling a manual batch, use `state` to determine its status. The reason `code` can be null or come from a source or account policy plugin. For `batch.work-failed`, inspect the batch’s `items`. For `schedule.pixiv.discovery-not-found` in an author batch, check the author ID. Continue using `state` if you encounter an unfamiliar code.

Errors contain a machine `code` and localized `error`. 400 means invalid input, 401/403 authentication or authorization failure, 404 an unknown identity, 409 busy/conflicting/disallowed state, 413 an oversized body and 503 an unavailable capability. Handle the business state rather than retrying every non-2xx response.

After `POST /api/download/tasks` returns 202, poll the URL in `Location`. The path parameter `taskId` is `task.attempt.attemptId` in the receipt.

Start polling every 2 seconds. If `revision` stays the same, increase the interval up to 10 seconds. Return to 2 seconds after an update. Stop when `phase` is `COMPLETED`, `FAILED` or `CANCELLED`. If a response includes `Retry-After`, wait at least that long before the next request.

Use `workType` from `x-download-submissions`. An unpublished type returns `400 DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE`. For a body field, `field` is a JSON Pointer. For a query, path, header or multipart parameter, `field` is its name on the wire. Use `in`, `field` and `reason` when present to locate the problem. For example, `/options/pages` with `out_of_range` identifies a page outside the work. An `explicit-list` batch missing `/definition/source/acquisitionMode` returns 400 before the batch is created.

Read `progress.unit`, `completed` and `total` together when present. They measure the current step of this attempt. Use `phase` to detect terminal success. A null `progress` means there is no measurement.

| Value | Where to read or send it |
| --- | --- |
| Pixiv work ID | Gallery `artworkId` and export `ids[]` are JSON integers. Pixiv search `id`, task `workId` and batch `key.id` are strings. Preserve integer precision when converting. Other providers define their own `workId`. |
| Download settings | Single task `options`: `{"bookmark":"true","mediaQuality":"80","mediaWebpLossless":"true"}`. Batch `definition.download`: `{"bookmark":true,"mediaQuality":80,"mediaWebpLossless":true}`. |
| Time and files | Task `updatedAt` is ISO 8601. Batch `createdTime`, gallery `time` and schedule `nextRunTime` use Unix milliseconds. `outputPath` is the server directory planned for this attempt. Gallery `folder` is the recorded library location. Match works by ID. |
| Response shape | `GET /api/authors` returns an array. `GET /api/authors/paged` returns a page with `content`. `GET /api/downloaded/statistics` returns an object with `success` and counts. |

| Endpoint | Pagination | Fetching the next page |
| --- | --- | --- |
| `GET /api/gallery/artworks` | `page`, `size` | Pass `page=0` for the first page, then increment `page`. |
| `GET /api/download/batches/{id}/items` | `offset`, `limit` | Start at `offset=0` and advance by the number of items received. |
| `GET /api/download/tasks/history` | `limit`, `beforeTime`, `beforeId` | Omit both cursor fields on the first request. Send the returned pair together for the next page. |
| `GET /api/pixiv/search` | `page` | Start at page 1. Values below 1 select page 1. |
| `GET /api/plugin-market/catalog` | `cursor`, `limit` | Pass `nextCursor` as `cursor` for the next page. A plugin’s version list returns `nextVersionCursor`. |

Download task history defaults to 50 records per page, up to 200. It lists persisted terminal results and does not restore active downloads. An identical submission with the same caller and `requestId` returns the original receipt while the process retains its idempotency entry. Once the terminal result is saved, the entry can expire five minutes after the task ended. A later submission can start a new task. Tool history and batch results have separate retention rules.

## Program permission list

An administrator can read the authoritative catalog for the current installation with GET `/api/security/program-keys/permissions`. The table lists built-in declarations; consult the current catalog for external plugins. `*` represents one path segment. Keys store explicit permissions, not plugin or path wildcards.

Shared settings, media tools and file imports require both the `core` entry permission and the same permission for the target plugin. Catalogs filter targets by these grants. Individual downloads require `core:downloads.create` plus `downloads.create` for the actual download-type owner. Batches require `core:batches.create` or `core:batches.resume`, plus `batches.execute` for the source and every work plugin in the execution plan. Read source metadata with `core:schedule.sources.read`.

Programs can query, cancel and manage only their own download tasks and batches. Administrators retain installation-wide access. There is still one batch per process; more keys do not add batch slots. Translation, tools, settings, logs, databases and environment permissions apply to the corresponding plugin or installation, not private copies per key. Database migration and environment archives cover selected content across the installation.

Key management requires an administrator session: GET `/api/security/program-keys` lists keys, GET `/permissions` reads the catalog, POST the root creates a key, PUT `/{id}` edits it and DELETE `/{id}` revokes it. Creation accepts `name`, `permissions`, `anyIp` and `networks`. Edits use `{"revision":1,"policy":{...}}`; revocation uses `{"revision":1}`. Use the revision returned by a read. Refresh after a conflict instead of overwriting another edit. Creation returns `key` and a one-time `secret`; lists return neither secrets nor digests.

An installation supports at most 128 keys. Each key allows an 80-character name, 512 permissions and 64 source networks. Management bodies are limited to 128 KiB. Last-used time records successful authentication observed in the current process. Revocation blocks new requests without cancelling already accepted tasks.

Startup cache operations use GET `/api/tools/startup-cache`, POST `/api/tools/startup-cache/tasks` and POST `/api/tools/startup-cache/tasks/{id}/cancel`. Start with `{}`. Acceptance does not establish successful training; keep reading status.

### Permission scope and dedicated workflows

Scheduled tasks require the exact action permission under `core:schedule.*` and the source plugin's `schedule.manage`. Account-policy actions require the policy owner's same permission. Lists are filtered by authorized source. The `*` here groups permissions in documentation; it is not a grant you can save. Schedules are persistent installation settings, and authorization delegates the source's complete scheduling workflow. Revoking the creating key does not delete or disable schedules. Disable or delete a schedule to stop future runs.

Plugin lifecycle, enabled-state and trust changes require both the corresponding `core:plugins.*` action and `<plugin>:plugins.manage` for the target. Dependency consumers also require authorization. Installed, manageable plugins remain selectable while unloaded. Installation grants allow packages and their dependencies to execute server-side code. Global Hook publication grants allow security-policy changes; grant them with that scope in mind.

Shared credentials have separate `core:credentials.read`, `core:credentials.write` and `core:credentials.use` permissions. Reads return information, never secrets. Referencing stored credentials with `X-Acquisition-Credentials` requires `credentials.use` in addition to the business permission. Supply a credential for the current operation directly through `X-Acquisition-Credential`.

Gallery, novel-library, collection, inbox, archive, page-editor, update and maintenance permissions apply to the shared installation, not just data created by the key. Download tasks and manual batches remain isolated by key identity. Local maintenance, recovery and legacy metadata migration still require a local source. Permissions do not replace origin checks, confirmations, revisions or leases.

Identity setup, key management, GUI tokens, CAPTCHA, browser collection tickets, anonymous quotas and page assembly retain their dedicated workflows. Programs use `/api/download/tasks` for downloads and progress, `/api/tools/file-import` for file imports, `/api/library/artworks/{id}` for artwork management and `/api/page-editor` for page operations. Inbox tests use `/api/admin/notifications/test` or `/test-all`. Legacy browser compatibility routes do not receive separate program grants.

| Permission | Purpose | Method and path |
| --- | --- | --- |
| `ai:connection.test` | Test AI connections (invokes the model) | POST `/api/admin/ai/test` |
| `ai:models.read` | Query available AI models | POST `/api/admin/ai/models` |
| `ai:status.read` | View AI configuration status | GET `/api/admin/ai/status` |
| `core:api-reference.read` | Read or probe the HTTP API reference | GET, HEAD `/api/openapi.json` |
| `core:application.read` | Read application information and UI language resources | GET `/api/app/info`; GET `/api/i18n/messages/*`; GET `/api/i18n/meta` |
| `core:archives.create` | Create artwork export archives | POST `/api/archive/pack-artworks` |
| `core:archives.download` | Download this installation’s export archives | GET `/api/archive/download/*` |
| `core:archives.read` | View this installation’s export archives and status | GET `/api/archive/list`; GET `/api/archive/status/*` |
| `core:assets.export` | Download original artwork files | GET `/api/downloaded/rawfile/*/*` |
| `core:assets.read` | Read artwork previews and images | GET `/api/downloaded/image/*/*`; GET `/api/downloaded/thumbnail-file/*/*`; GET `/api/downloaded/thumbnail/*/*` |
| `core:authors.read` | View all library authors | GET `/api/authors/paged`; GET `/api/authors` |
| `core:authors.refresh` | Refresh Pixiv author metadata | POST `/api/pixiv/user/*/meta/schedule` |
| `core:backfill.cancel` | Cancel record backfill | POST `/api/tools/backfill/tasks/*/cancel` |
| `core:backfill.execute` | Run record backfill | POST `/api/tools/backfill/tasks` |
| `core:backfill.read` | View record backfill candidates and status | GET `/api/tools/backfill/defaults`; GET `/api/tools/backfill/tasks/*`; GET `/api/tools/backfill/tasks/current`; POST `/api/tools/backfill/candidates` |
| `core:batches.cancel` | Cancel this key's batches | POST `/api/download/batches/*/cancel` |
| `core:batches.create` | Create background batches | POST `/api/download/batches` |
| `core:batches.credentials` | Replace credentials for this key's batches | PUT `/api/download/batches/*/credentials` |
| `core:batches.delete` | Delete this key's batch records | DELETE `/api/download/batches/*` |
| `core:batches.pause` | Pause this key's batches | POST `/api/download/batches/*/pause` |
| `core:batches.read` | Read this key's batches and results | GET `/api/download/batches/*/items`; GET `/api/download/batches/*`; GET `/api/download/batches` |
| `core:batches.resolve` | Resolve pending actions in this key's batches | POST `/api/download/batches/*/resolve` |
| `core:batches.resume` | Resume or retry this key's batches | POST `/api/download/batches/*/resume`; POST `/api/download/batches/*/retry` |
| `core:classifier.configure` | Change image classification settings | PUT `/api/tools/classifier/settings` |
| `core:classifier.delete` | Delete image classification folders | DELETE `/api/tools/classifier/folders` |
| `core:classifier.read` | View image classification and images | GET `/api/tools/classifier/folders`; GET `/api/tools/classifier/image`; GET `/api/tools/classifier/images`; GET `/api/tools/classifier/settings` |
| `core:classifier.write` | Move classified images | POST `/api/tools/classifier/moves` |
| `core:collections.delete` | Delete collections | DELETE `/api/collections/*` |
| `core:collections.members.write` | Add and remove works in collections | DELETE `/api/collections/*/artworks/*`; DELETE `/api/collections/*/novels/*`; POST `/api/collections/*/artworks/*`; POST `/api/collections/*/novels/*` |
| `core:collections.read` | Read all library collections and memberships | GET `/api/collections/*/icon`; GET `/api/collections/novels/of/*`; GET `/api/collections/of/*`; GET `/api/collections`; POST `/api/collections/memberships`; POST `/api/collections/novels/memberships` |
| `core:collections.write` | Create and edit collections, icons and download directories | DELETE `/api/collections/*/icon`; POST `/api/collections/*/icon`; POST `/api/collections`; PUT `/api/collections/*/download-root`; PUT `/api/collections/*/sort-order`; PUT `/api/collections/*` |
| `core:control-center.directory` | Open control center directories on the server | POST `/api/admin/control-center/directory` |
| `core:control-center.read` | View control center status | GET `/api/admin/control-center` |
| `core:credentials.read` | Read acquisition credential metadata (no secrets) | GET `/api/acquisition/credentials` |
| `core:credentials.use` | Use shared acquisition credentials in authorized requests | HEADER `X-Acquisition-Credentials` |
| `core:credentials.write` | Edit the shared acquisition credential library | PUT `/api/acquisition/credentials` |
| `core:database.check` | Check database connections | POST `/api/settings/database/check` |
| `core:database.migrate` | Migrate databases for the installation | POST `/api/settings/database/migrations` |
| `core:database.preview` | Preview database migration for the installation | POST `/api/settings/database/migration-previews` |
| `core:database.read` | Read database settings and migration status | GET `/api/settings/database/migration`; GET `/api/settings/database/providers`; GET `/api/settings/database/status`; GET `/api/settings/database` |
| `core:directories.read` | Check artwork directories | GET `/api/tools/directories` |
| `core:directories.write` | Change artwork directory records | POST `/api/tools/directories` |
| `core:downloads.cancel` | Cancel this key's download tasks | POST `/api/download/queue/*/cancel`; POST `/api/download/queue/clear`; POST `/api/download/tasks/*/cancel` |
| `core:downloads.create` | Create download tasks | POST `/api/download/tasks` |
| `core:downloads.extensions.read` | View download extension capabilities | GET `/api/download/extensions` |
| `core:downloads.read` | Read this key's download tasks | GET `/api/download/tasks/*`; GET `/api/download/tasks/history`; GET `/api/download/tasks` |
| `core:environment.export` | Export a full environment archive | GET `/api/settings/environment/exports/*/archive`; POST `/api/settings/environment/exports` |
| `core:environment.preview` | Preview a full environment restore | POST `/api/settings/environment/preview` |
| `core:environment.read` | Read installation and environment transfer status | GET `/api/settings/environment/exports`; GET `/api/settings/environment/installation`; GET `/api/settings/environment/restore` |
| `core:environment.restore` | Restore a full environment | POST `/api/settings/environment/restore` |
| `core:ffmpeg.install` | Install FFmpeg | POST `/api/tools/ffmpeg/tasks` |
| `core:ffmpeg.read` | View FFmpeg status | GET `/api/tools/ffmpeg` |
| `core:file-import.acknowledge` | Acknowledge file import results | POST `/api/tools/file-import/completion/acknowledge` |
| `core:file-import.cancel` | Cancel file imports | POST `/api/tools/file-import/tasks/cancel` |
| `core:file-import.execute` | Run file imports | POST `/api/tools/file-import/tasks` |
| `core:file-import.preview` | Preview file imports | POST `/api/tools/file-import/previews` |
| `core:file-import.read` | Read file import tools and tasks | GET `/api/tools/file-import/tasks/current`; GET `/api/tools/file-import` |
| `core:hooks.prepare` | Preview operation hook configuration changes | POST `/api/web/operation-hooks/declarations/prepare`; POST `/api/web/operation-hooks/prepare` |
| `core:hooks.publish` | Publish global operation hook security policies | POST `/api/web/operation-hooks/publish` |
| `core:hooks.read` | Read global operation hooks and authorization configuration | GET `/api/web/operation-hooks/catalog`; GET `/api/web/operation-hooks/configuration`; GET `/api/web/operation-hooks/declarations`; GET `/api/web/operation-hooks/definitions`; GET `/api/web/operation-hooks/targets` |
| `core:legacy-migration.execute` | Migrate legacy JSON metadata (local only) | GET `/api/migration/json-to-sqlite/stream`; POST `/api/migration/json-to-sqlite` |
| `core:library.move` | Move artwork files on the server | POST `/api/library/artworks/*/move` |
| `core:library.read` | View all artwork records, history and paths | GET `/api/downloaded/by-move-folder`; GET `/api/downloaded/history/paged`; GET `/api/downloaded/history`; GET `/api/downloaded/statistics`; GET `/api/library/artworks/*`; POST `/api/downloaded/batch` |
| `core:library.recover` | Recover artwork metadata | POST `/api/library/artworks/*/recover-metadata` |
| `core:local-import.cancel` | Cancel local import scans and registration | POST `/api/local-import/previews/*/cancel`; POST `/api/local-import/tasks/*/cancel` |
| `core:local-import.execute` | Register local artworks in the database | POST `/api/local-import/tasks` |
| `core:local-import.preview` | Scan local artworks on the server | POST `/api/local-import/previews` |
| `core:local-import.read` | Read local import presets and tasks | GET `/api/local-import/presets`; GET `/api/local-import/previews/*/items`; GET `/api/local-import/previews/*`; GET `/api/local-import/tasks/*/items`; GET `/api/local-import/tasks/*` |
| `core:logs.read` | Read application logs | GET `/api/tools/logs/*` |
| `core:maintenance.read` | View maintenance status (local only) | GET `/api/admin/maintenance/status` |
| `core:maintenance.run` | Run maintenance (local only) | POST `/api/admin/maintenance/run` |
| `core:media.cancel` | Cancel media processing | POST `/api/tools/media/tasks/cancel` |
| `core:media.execute` | Run media processing | POST `/api/tools/media/tasks` |
| `core:media.preview` | Preview media processing | POST `/api/tools/media/previews` |
| `core:media.read` | Read media tools and tasks | GET `/api/tools/media/tasks/current`; GET `/api/tools/media`; POST `/api/tools/media/capabilities` |
| `core:paths.read` | View path prefixes | GET `/api/tools/path-prefixes` |
| `core:paths.write` | Change path prefixes | POST `/api/tools/path-prefixes/pin`; POST `/api/tools/path-prefixes` |
| `core:pixiv.read` | Query online Pixiv data | GET `/api/pixiv/me/uid`; GET `/api/pixiv/thumbnail-proxy`; GET `/api/pixiv/user/*/meta` |
| `core:plugins.enabled` | Change authorized plugins’ enabled state | PUT `/api/plugins/*/enabled-preview`; PUT `/api/plugins/*/enabled` |
| `core:plugins.install` | Install local plugin packages (allows server code execution) | POST `/api/plugins/install` |
| `core:plugins.load` | Load authorized plugins | POST `/api/plugins/*/actions/load`; POST `/api/plugins/*/load` |
| `core:plugins.quiesce` | Quiesce authorized plugins | POST `/api/plugins/*/actions/quiesce`; POST `/api/plugins/*/quiesce` |
| `core:plugins.read` | View plugin status, dependencies and installation operations | GET `/api/plugins/*/impact`; GET `/api/plugins/acquisitions/*`; GET `/api/plugins/acquisitions`; GET `/api/plugins/status`; GET `/api/plugins/updates` |
| `core:plugins.reload` | Reload authorized plugins | POST `/api/plugins/*/actions/reload`; POST `/api/plugins/*/reload` |
| `core:plugins.remove` | Remove authorized plugin packages | POST `/api/plugins/*/actions/remove`; POST `/api/plugins/*/remove` |
| `core:plugins.restart` | Restart authorized plugins | POST `/api/plugins/*/actions/restart`; POST `/api/plugins/*/restart` |
| `core:plugins.revocations.refresh` | Refresh plugin repository revocations | POST `/api/plugins/revocations/*/refresh` |
| `core:plugins.start` | Start authorized plugins | POST `/api/plugins/*/actions/start`; POST `/api/plugins/*/start` |
| `core:plugins.stop` | Stop authorized plugins | POST `/api/plugins/*/actions/stop`; POST `/api/plugins/*/stop` |
| `core:plugins.trust` | Approve and revoke execution trust for authorized plugins | DELETE `/api/plugins/*/trust`; PUT `/api/plugins/*/trust` |
| `core:plugins.unload` | Unload authorized plugin instances | POST `/api/plugins/*/actions/unload`; POST `/api/plugins/*/unload` |
| `core:profile.read` | Read this installation’s display name | GET `/api/onboarding/profile` |
| `core:profile.write` | Change this installation’s display name | POST `/api/onboarding/profile` |
| `core:recovery.read` | View recovery-mode diagnostics | GET `/api/plugins/recovery` |
| `core:repositories.read` | Read plugin repository settings | GET `/api/settings/repositories` |
| `core:repositories.write` | Change plugin repository settings | PUT `/api/settings/repositories` |
| `core:schedule.create` | Create persistent schedules (key revocation does not delete them) | POST `/api/schedule/tasks` |
| `core:schedule.credentials` | Manage schedule credentials and account policies | DELETE `/api/schedule/tasks/*/credential`; POST `/api/schedule/credential-policies/actions`; POST `/api/schedule/tasks/*/credential` |
| `core:schedule.delete` | Delete persistent schedules | DELETE `/api/schedule/tasks/*` |
| `core:schedule.pause` | Pause persistent schedules | POST `/api/schedule/tasks/*/pause` |
| `core:schedule.pending.write` | Resolve and clear pending schedule items | DELETE `/api/schedule/tasks/*/pending`; POST `/api/schedule/tasks/*/pending/resolve` |
| `core:schedule.read` | Read persistent schedules for authorized sources | GET `/api/schedule/tasks/*/pending`; GET `/api/schedule/tasks/*/queue`; GET `/api/schedule/tasks/*`; GET `/api/schedule/tasks` |
| `core:schedule.run` | Enable, run and resume persistent schedules | POST `/api/schedule/tasks/*/enabled`; POST `/api/schedule/tasks/*/resume`; POST `/api/schedule/tasks/*/run` |
| `core:schedule.sources.read` | Read batch and schedule source catalogs | GET `/api/schedule/sources` |
| `core:schedule.write` | Edit persistent schedules and proxies | POST `/api/schedule/tasks/*/proxy`; PUT `/api/schedule/tasks/*` |
| `core:scripts.read` | Read userscripts from installed plugins | GET `/api/scripts/*.user.js`; GET `/api/scripts/*/install`; GET `/api/scripts/*`; GET `/api/scripts` |
| `core:series.read` | View all library artwork series | GET `/api/series/*/cover`; GET `/api/series/*`; GET `/api/series/paged`; GET `/api/series` |
| `core:settings.read` | Read settings and presets | GET `/api/settings/presets`; GET `/api/settings` |
| `core:settings.write` | Validate and save settings | POST `/api/settings/validate`; POST `/api/settings` |
| `core:startup-cache.cancel` | Cancel startup cache training | POST `/api/tools/startup-cache/tasks/*/cancel` |
| `core:startup-cache.execute` | Train the startup cache | POST `/api/tools/startup-cache/tasks` |
| `core:startup-cache.read` | View startup cache status | GET `/api/tools/startup-cache` |
| `core:system.check` | Check connectivity | GET `/api/onboarding/connectivity`; POST `/api/admin/system/connectivity` |
| `core:system.exit` | Exit the application in recovery mode (local only) | POST `/api/plugins/recovery/exit` |
| `core:system.read` | View system status | GET `/api/admin/system` |
| `core:system.reload` | Reload configuration | POST `/api/admin/system/reload` |
| `core:system.restart` | Restart the application | POST `/api/admin/system/restart`; POST `/api/plugins/backend-restart`; POST `/api/plugins/recovery/restart` |
| `core:tools.history.read` | View tool history | GET `/api/tools/history` |
| `core:updates.download` | Download application updates | POST `/api/admin/update/download` |
| `core:updates.install` | Install application updates | POST `/api/admin/update/install` |
| `core:updates.read` | Check application updates and download progress | GET `/api/admin/update/check`; GET `/api/admin/update/download/progress`; GET `/api/admin/update/last` |
| `download-workbench:page.write` | Switch the download workbench page | POST `/api/batch/page` |
| `download-workbench:pixiv.read` | Query online Pixiv data | GET `/api/pixiv/artwork/*/meta`; GET `/api/pixiv/artwork/*/pages`; GET `/api/pixiv/artwork/*/ugoira`; GET `/api/pixiv/me/collection/*/works`; GET `/api/pixiv/me/collections`; GET `/api/pixiv/me/follow-latest`; GET `/api/pixiv/me/following`; GET `/api/pixiv/me/illust-bookmarks`; GET `/api/pixiv/search/range`; GET `/api/pixiv/search`; GET `/api/pixiv/series/*`; GET `/api/pixiv/user/*/artworks`; GET `/api/pixiv/user/*/illust-cards`; GET `/api/pixiv/user/*/request-artworks` |
| `download-workbench:profiles.read` | Read shared download profiles | GET `/api/batch/profiles` |
| `download-workbench:profiles.write` | Edit shared download profiles | PUT `/api/batch/profiles` |
| `download-workbench:state.read` | Read solo-mode workbench state | GET `/api/batch/state` |
| `download-workbench:state.write` | Edit solo-mode workbench state | POST `/api/batch/state` |
| `gallery-tools:duplicates.read` | View duplicate works and scan status | GET `/api/duplicates/groups`; GET `/api/duplicates/scan/status` |
| `gallery-tools:duplicates.scan` | Scan for duplicate works | POST `/api/duplicates/rescan`; POST `/api/duplicates/scan` |
| `gallery-tools:statistics.read` | View statistics for the entire library | GET `/api/stats/dashboard` |
| `gallery:collections.write` | Change artwork collection membership | POST `/api/gallery/artworks/collect` |
| `gallery:delete` | Delete artworks and files from the library | DELETE `/api/gallery/artwork/*`; POST `/api/gallery/artworks/delete` |
| `gallery:export` | Export artwork files from the library | POST `/api/gallery/artworks/export` |
| `gallery:read` | View all library artworks and tags | GET `/api/gallery/artwork/*/by-author`; GET `/api/gallery/artwork/*/by-series`; GET `/api/gallery/artwork/*/related`; GET `/api/gallery/artwork/*/series`; GET `/api/gallery/artwork/*`; GET `/api/gallery/artworks`; GET `/api/gallery/tags/lookup`; GET `/api/gallery/tags` |
| `mail:templates.send` | Send all email templates | POST `/api/admin/mail/test-all` |
| `mail:test.send` | Send a test email | POST `/api/admin/mail/test` |
| `notification:inbox.delete` | Delete inbox messages | DELETE `/api/notifications/*` |
| `notification:inbox.read` | Read this installation’s inbox | GET `/api/notifications/*/content`; GET `/api/notifications/*`; GET `/api/notifications` |
| `notification:inbox.templates.test` | Send all inbox message templates | POST `/api/admin/notifications/test-all` |
| `notification:inbox.test` | Send a test inbox message | POST `/api/admin/notifications/test` |
| `notification:inbox.update` | Mark inbox messages read or surveys unavailable | POST `/api/notifications/*/read`; POST `/api/notifications/*/survey-unavailable`; POST `/api/notifications/read-all` |
| `novel:collections.write` | Change novel collection membership | POST `/api/gallery/novels/collect` |
| `novel:content.read` | Read novel text and embedded images | GET `/api/gallery/novel/*/content`; GET `/api/gallery/novel/*/embed/*` |
| `novel:downloads.read` | Read novel download status for this key | GET `/api/novel/status/*` |
| `novel:glossary.delete` | Delete novel glossaries | DELETE `/api/admin/glossary/*` |
| `novel:glossary.read` | Read novel glossaries | GET `/api/admin/glossary/*`; GET `/api/admin/glossary/novel/*/default`; GET `/api/admin/glossary/series/*/default`; GET `/api/admin/glossary` |
| `novel:glossary.write` | Create and edit novel glossaries | POST `/api/admin/glossary`; PUT `/api/admin/glossary/*/entries`; PUT `/api/admin/glossary/*` |
| `novel:library.delete` | Delete novels and files | DELETE `/api/gallery/novel/*`; POST `/api/gallery/novels/delete` |
| `novel:library.export` | Export novel files | GET `/api/novel/*/export`; GET `/api/novel/series/*/merged`; POST `/api/gallery/novels/export/options`; POST `/api/gallery/novels/export` |
| `novel:library.read` | View all library novels and series | GET `/api/gallery/novel/*/by-series`; GET `/api/gallery/novel/*/cover`; GET `/api/gallery/novel/*/series`; GET `/api/gallery/novel/*`; GET `/api/gallery/novel/series/*/cover`; GET `/api/gallery/novel/series/*`; GET `/api/gallery/novel/translated-titles`; GET `/api/gallery/novels/authors`; GET `/api/gallery/novels/series`; GET `/api/gallery/novels/tags`; GET `/api/gallery/novels`; GET `/api/novel/series/*/novel-ids`; POST `/api/gallery/novels/downloaded-batch` |
| `novel:narration.read` | Read narration casts and voices | GET `/api/narration/availability`; GET `/api/narration/cast/voice/reference`; GET `/api/narration/cast`; GET `/api/narration/casts/*/voices`; GET `/api/narration/casts/novel/*/default`; GET `/api/narration/casts`; GET `/api/narration/narrator-presets` |
| `novel:narration.script` | Generate narration scripts | POST `/api/narration/script` |
| `novel:narration.synthesize` | Synthesize narration and reference audio | POST `/api/narration/cast/voice/reference/generate`; POST `/api/narration/tts/line`; POST `/api/narration/tts/preview` |
| `novel:narration.write` | Edit narration casts and reference audio | DELETE `/api/narration/cast/voice/reference`; POST `/api/narration/cast/voice/reference`; POST `/api/narration/casts`; PUT `/api/narration/cast/voice` |
| `novel:pixiv.read` | Query online Pixiv data | GET `/api/pixiv/me/novel-bookmarks`; GET `/api/pixiv/novel-search/range`; GET `/api/pixiv/novel-search`; GET `/api/pixiv/novel/*/bookmark-count`; GET `/api/pixiv/novel/*/meta`; GET `/api/pixiv/novel/series/*`; GET `/api/pixiv/user/*/novel-cards`; GET `/api/pixiv/user/*/novels` |
| `novel:series.merge` | Generate novel series compilations | POST `/api/novel/series/*/merge` |
| `novel:translation.execute` | Translate novels and series titles | POST `/api/novel/*/translate`; POST `/api/novel/series/*/translate-title` |
| `novel:translation.probe` | Detect novel translation languages | POST `/api/novel/translate-lang-probe` |
| `novel:translation.read` | Read novel auto-translation status | GET `/api/novel/translate-status/*` |
| `page-editor:components.preview` | Preview components | POST `/api/page-editor/components/preview`; POST `/api/page-editor/components/previews/*/close` |
| `page-editor:components.publish` | Publish and disable components | POST `/api/page-editor/components/*/availability`; POST `/api/page-editor/components/*/publish` |
| `page-editor:components.read` | Read the component library | GET `/api/page-editor/components/*/versions/*`; GET `/api/page-editor/components/*`; GET `/api/page-editor/components` |
| `page-editor:components.write` | Save component drafts | POST `/api/page-editor/components/save` |
| `page-editor:pages.preview` | Preview and validate pages | POST `/api/page-editor/documents/*/prepare`; POST `/api/page-editor/documents/*/preview`; POST `/api/page-editor/migrate`; POST `/api/page-editor/styles/parse` |
| `page-editor:pages.publish` | Publish and restore pages | POST `/api/page-editor/documents/*/publish`; POST `/api/page-editor/documents/*/restore` |
| `page-editor:pages.read` | Read page catalogs and documents | GET `/api/page-editor/catalog`; GET `/api/page-editor/documents/*/default`; GET `/api/page-editor/documents/*` |
| `page-editor:pages.write` | Save page drafts | POST `/api/page-editor/documents/*/save` |
| `plugin-market:install` | Install market plugins and dependencies (allows server code execution) | POST `/api/plugin-market/*/*/*/install-preview`; POST `/api/plugin-market/*/*/*/install`; POST `/api/plugin-market/operations/*/execute` |
| `plugin-market:operations.discard` | Discard pending plugin acquisition operations | POST `/api/plugin-market/operations/*/discard` |
| `plugin-market:operations.prepare` | Prepare plugin acquisition operations | POST `/api/plugin-market/operations` |
| `plugin-market:read` | Browse the plugin market and installation previews | GET `/api/plugin-market/*/*/*/install-preview`; GET `/api/plugin-market/catalog`; GET `/api/plugin-market/content/*/*/*/*`; GET `/api/plugin-market/content/*/*/image`; GET `/api/plugin-market/plugins/*/*/*/facts`; GET `/api/plugin-market/plugins/*/*`; GET `/api/plugin-market/repositories` |
| `plugin-market:repositories.preview` | Inspect plugin repositories before import | POST `/api/plugin-market/repositories/import/preview` |
| `plugin-market:repositories.trust` | Trust and import plugin repositories | POST `/api/plugin-market/repositories/import/trust` |
| `push:templates.send` | Send all push notification templates | POST `/api/admin/push/test-all` |
| `push:test.send` | Send a test push notification | POST `/api/admin/push/test` |
| `translation:cancel` | Pause or cancel translation jobs | POST `/api/translation/jobs/*/cancel`; POST `/api/translation/jobs/*/pause` |
| `translation:delete` | Delete translation job records | DELETE `/api/translation/jobs/*` |
| `translation:execute` | Start or resume novel and series translation | POST `/api/translation/jobs/*/resume`; POST `/api/translation/jobs`; POST `/api/translation/novel`; POST `/api/translation/series` |
| `translation:read` | View translation status, languages and jobs | GET `/api/translation/jobs/*`; GET `/api/translation/jobs`; GET `/api/translation/languages`; GET `/api/translation/status`; POST `/api/translation/language` |
| `tts:synthesize` | Synthesize reading audio online | POST `/api/tts/edge/synthesize` |
| `tts:voices.read` | View online reading voices | GET `/api/tts/edge/voices` |
| `<plugin>:settings.read` | Read settings and presets | `core:settings.read` |
| `<plugin>:settings.write` | Validate and save settings | `core:settings.write` |
| `<plugin>:media.read` | Read media tools and tasks | `core:media.read` |
| `<plugin>:media.preview` | Preview media processing | `core:media.preview` |
| `<plugin>:media.execute` | Run media processing | `core:media.execute` |
| `<plugin>:media.cancel` | Cancel media processing | `core:media.cancel` |
| `<plugin>:file-import.read` | Read file import tools and tasks | `core:file-import.read` |
| `<plugin>:file-import.preview` | Preview file imports | `core:file-import.preview` |
| `<plugin>:file-import.execute` | Run file imports | `core:file-import.execute` |
| `<plugin>:file-import.cancel` | Cancel file imports | `core:file-import.cancel` |
| `<plugin>:file-import.acknowledge` | Acknowledge file import results | `core:file-import.acknowledge` |
| `<plugin>:downloads.create` | Create download tasks | `core:downloads.create` |
| `<plugin>:batches.execute` | Allow batches to use this plugin's sources and work executors | `core:batches.create`, `core:batches.resume` |
| `<plugin>:schedule.manage` | Manage persistent schedules and credential policies for this plugin | `core:schedule.*` |
| `<plugin>:plugins.manage` | Allow lifecycle, enabled-state and trust management for this plugin | `core:plugins.*` |
