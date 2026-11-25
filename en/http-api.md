# OpenAPI and program keys

PixivDownloader includes an interactive OpenAPI reference and fine-grained **program access keys** (Program Keys) so scripts, automation tools or AI agents can call download, batch, gallery, translation and maintenance endpoints safely (this application provides an HTTP API only, not a standalone MCP server; for guidelines on adding new endpoints, see [Writing HTTP APIs and permission declarations](/en/http-api-development)).

## Connect a script or agent

1. **Browse the interactive reference**: open `/openapi` on your running instance (default `http://localhost:6999/openapi`). Access instructions are public; signing in as an administrator reveals the live endpoint catalog, request examples, required permissions, and "Related operations" links (`links` / `x-related-operations` / `externalDocs`) below each response list for jumping between follow-up calls and user guides.
2. **Create a program access key**: under desktop **Settings → Access control → Program access keys** (or via `/api/security/program-keys` in an administrator session on headless servers), create a key, configure allowed source IPs/CIDRs and select the required permissions. Grant `core:api-reference.read` as well if the script or agent will fetch the OpenAPI specification itself.
3. **Call endpoints or copy the agent prompt**: send `Authorization: Bearer <program-key>` to `GET /api/openapi.json` (or `HEAD /api/openapi.json` to probe access) to retrieve the machine-readable OpenAPI document. When working with an agent, click **Copy prompt** after creating the key to hand the instance documentation URL to the agent, and configure the secret key separately in the agent's environment.

### Key security, source restrictions and CSRF rules

- **One-time secret and quotas**: the full key is shown only once upon creation while the server stores only its digest (keys do not expire by default and can be edited or revoked at any time; selecting an entire plugin grants only its currently listed permissions, never a wildcard for future permissions). Each installation supports up to 128 keys (names up to 80 characters, up to 512 permissions and 64 source networks per key, and a 128 KiB management request limit).
- **HTTPS and source IP restrictions**: plaintext HTTP requests carrying a program key from outside localhost or the local network are rejected by default (allowing public HTTP requires reviewing and confirming the risk per key, and editing permissions or sources later restores the HTTPS requirement; if a key was already sent over plaintext HTTP, treat it as exposed and revoke it). Source restrictions accept IPv4, IPv6 and CIDR ranges (restricted mode requires at least one entry, while **Any source** must be chosen explicitly; configure `server.trusted-proxy-cidrs` before running behind a reverse proxy).
- **Authentication isolation and status codes**: never combine a program key with an administrator session (`X-Session-Token` / Cookie), invitation code or local GUI token in the same request (acquisition credentials are still passed separately via `X-Acquisition-Credential` or `X-Acquisition-Credentials`).
  - Calling an operation that does not support program keys (including anonymous endpoints) with a valid Bearer key returns `403 auth.program.unsupported` (check `x-program-alternative` for the program-key endpoint); invalid keys or mixed credentials return `401 auth.program.invalid`.
  - `/api/gui/**`, key management and administrator credential changes are restricted to the local GUI or human administrator sessions.
- **Same-origin signals (`x-csrf-origin`)**: Cookie-authenticated writes must pass same-origin checks. Native Bearer clients should also check an operation's `x-csrf-origin`: for example, `POST /api/schedule/tasks` (`origin-or-referer`) requires a same-origin `Origin` or `Referer`, whereas native Bearer clients without browser Cookies may omit both headers when calling `POST /api/download/tasks` and `POST /api/download/batches`.
- **Self-inspection, management API and key rotation**:
  - Call `GET /api/security/program-keys/self` without extra business permissions to inspect the current key's `id` and `permissions` (which may be empty).
  - Administrator sessions manage keys via `GET /api/security/program-keys` (list without secrets or digests), `GET /api/security/program-keys/permissions` (authoritative permission catalog for the current installation), `POST /api/security/program-keys` (create with `name`, `permissions`, `anyIp`, `networks`; returns `key` and one-time `secret`), `PUT /api/security/program-keys/{id}` (edit with `{"revision": 1, "policy": {...}}`) and `DELETE /api/security/program-keys/{id}` (revoke with `{"revision": 1}`).
  - **Rotation workflow**: create a replacement key → switch the client and verify via `/self` → revoke the old key. Because download tasks and batches are isolated by key identity, finish polling tasks created by the old key before revocation or track them with an administrator session (revocation blocks new requests without cancelling already accepted in-flight tasks).

## Endpoint index

Short paths in the same row share the first path's prefix. See the [Program permission list](#program-permission-list) below or `/openapi` for full paths covering translation (`/api/translation/**`), glossaries, narration, galleries, collections, schedules, maintenance and the plugin market.

| Feature | Main endpoints |
| --- | --- |
| Settings, defaults, redacted values and presets | `GET /api/settings`, `GET /api/settings/presets` |
| Validate and save settings | `POST /api/settings/validate`, `POST /api/settings` |
| Plugin repositories | `GET` / `PUT /api/settings/repositories` |
| Database settings, connection check and migration | `GET /api/settings/database`, `/providers`, `/status`, `/migration`; `POST /check`, `/migration-previews`, `/migrations` |
| Environment archive export and restore | `POST /api/settings/environment/preview`, `/restore`, `/exports`; `GET /restore`, `/exports`, `/exports/{id}/archive`, `/installation` |
| Metadata backfill | `GET /api/tools/backfill/defaults`, `/tasks/current`, `/tasks/{id}`; `POST /candidates`, `/tasks`, `/tasks/{id}/cancel` |
| Media maintenance and record-file import | `GET /api/tools/media`, `/api/tools/file-import` (`POST /previews`, `/tasks`, `/tasks/cancel`, `GET /tasks/current` under each); `POST /api/tools/media/capabilities`; `POST /api/tools/file-import/completion/acknowledge` |
| Local artwork import and startup cache | `GET /api/local-import/presets`, `POST /previews`, `POST /tasks`; `GET /api/tools/startup-cache`, `POST /tasks`, `POST /tasks/{id}/cancel` |
| Directory references, path prefixes and image classification | `GET` / `POST /api/tools/directories`; `/api/tools/path-prefixes`; `/api/tools/classifier/settings`, `/folders`, `/images`, `/image`, `/moves` |
| FFmpeg status/install and tool history/logs | `GET /api/tools/ffmpeg`, `POST /api/tools/ffmpeg/tasks`; `GET /api/tools/history`, `/api/tools/logs/{fileName}` |
| System, control center and updates | `/api/admin/system`, `/api/admin/control-center`; `/api/admin/update/check`, `/last`, `/download`, `/download/progress`, `/install` |
| AI, mail, push and inbox notification tests | `/api/admin/ai/test`, `/models`; `/api/admin/mail/test`, `/test-all`; `/api/admin/push/test`, `/test-all`; `/api/admin/notifications/test`, `/test-all` |
| Individual downloads, history and manual batches | `/api/download/tasks`, `/api/download/tasks/history`; `/api/download/batches` |

## Save settings

Read the field catalog with `GET /api/settings` first, then submit changes using each field's `owner`, `packageId`, `generation` and `key` (host fields use `null` for `owner`/`packageId` and `0` for `generation`). Sensitive fields return only a boolean `stored` flag, never plaintext or default secrets; use `action` (`KEEP`, `SET` or `CLEAR`) to retain, replace or clear them. The response's `effect` indicates how changes take effect (saving does not automatically restart the backend or process):

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

Apply presets through the same save endpoint. Repository edits (`PUT /api/settings/repositories`) must carry the `revision` from `GET` and include `"trustConfirmed": true` when adding a repository or changing trust, URLs or network policies (reload on revision conflict rather than overwriting concurrent edits). Database connection checks and per-run tool options apply only to that operation and do not write configuration files.

## Background manual batches

Each process retains at most one manual batch at a time (execution continues in the background if the client disconnects, but does not survive a process restart; delete an idle batch before creating a new one):

1. **Discover sources**: `GET /api/schedule/sources` returns available `sourceType` values and their `activationToken` (interpreted by the source plugin; supports `explicit-list`, artist posts, commissions, search, series, bookmarks, followed new works and collections).
2. **Create a batch**: `POST /api/download/batches` with `name`, `sourceType`, `activationToken`, `definition` (JSON object, up to 1 MiB serialized in UTF-8; 8 MiB total request limit) and optional `proxy`. A `403` response reports any known missing grant in `programPermission`; after `202`, follow `Location` with `core:batches.read` (if background execution lacks a grant, the batch result likewise reports `programPermission`).
3. **Poll status and items**: `GET /api/download/batches/{id}` returns batch status (use `state` for lifecycle phase; `code` is an optional reason code such as `batch.work-failed`—inspect `items`—or `schedule.pixiv.discovery-not-found` when an author ID should be checked), and `GET /api/download/batches/{id}/items?offset=0&limit=100` pages through items (up to 200 per page; each item is identified by batch UUID + `workType` + `id`, distinct from an individual download task UUID).
4. **Control and resume**: `POST /api/download/batches/{id}/pause`, `/cancel`, `/resume` and `/retry` pause, cancel, resume discovery or retry discovered failures only (stopping states become terminal only after in-flight execution exits; completed and filter-skipped works are not re-run on resume).
5. **Replace credentials, resolve pending items and delete**:
   - While idle, call `PUT /api/download/batches/{id}/credentials` (`{"expectedRun": 1}`, up to 16 KiB; pass new credentials via request header or omit the header to clear them—credentials are re-probed before resuming).
   - Call `POST /api/download/batches/{id}/resolve` with `expectedRun`, `workType`, `workId`, `reasonCode`, `userAction` and `rememberForRun` (up to 16 KiB; reason and run must match the pending record).
   - While idle, call `DELETE /api/download/batches/{id}` to remove the batch record.

A batch accepts at most 5,000 items and a 64 MiB encoded-content reservation budget (whichever is reached first). Filters support content rating, AI, tags, illustration type, page count, novel word count and minimum bookmark count (works with unknown bookmark counts do not pass an active bookmark filter).

- **Search page delay (`pageDelayMs`)**: search sources accept `download.pageDelayMs` for the minimum interval in milliseconds between the starts of consecutive page requests (defaults to `0` for fixed page counts and `10000` for `maxPages = -1`; pass `0` explicitly to skip the extra wait). Pass the JSON object in `definition` for manual batches (scheduled tasks serialize it into `definitionJson`; shared download profiles use `settings.pageDelayMs`, where `null` selects the default):

  ```json
  {"source":{"word":"landscape","maxPages":3},"download":{"pageDelayMs":5000}}
  ```

- **Mixed work list (`explicit-list`) `definition` example** (work IDs must be positive decimal integer strings without duplicates within the same type; replace the sample IDs with actual works—omitting `/definition/source/acquisitionMode` returns 400 before batch creation):

  ```json
  {
    "kind": "mixed",
    "source": {
      "acquisitionMode": "single-import",
      "works": [
        {"kind": "illust", "id": "123"},
        {"kind": "novel", "id": "456"}
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

## Tools, gallery bulk operations and restart operations

All path parameters in tool endpoints refer to the **server's local filesystem**, not the remote client machine:

- **Media maintenance and record-file import**: `GET` the tool catalog first, then preview with the exact `identity` (`pluginId`, `packageId`, `generation`, `publication`) and `options`; start execution with the same `identity` and preview `token`, and include `executionId` when cancelling (old tokens/UUIDs are invalidated if a plugin generation changes or the desktop starts a replacement task).
- **Metadata backfill and manual recovery**: `POST /api/tools/backfill/tasks` (4 KiB body limit; `limit: 0` selects all candidates; `202` indicates acceptance):

  ```json
  {
    "delayMs": 0,
    "artworkIds": [12345],
    "dryRun": true,
    "alwaysNetwork": false
  }
  ```

  To patch missing fields on a single illustration directly, call `POST /api/library/artworks/{artworkId}/recover-metadata` (preserves existing non-empty values and returns 400 on missing/empty payloads; to fetch tags and series from Pixiv, run a backfill scoped to the artwork IDs—see [fill in metadata after import](/en/local-import?id=metadata-backfill)).
- **Gallery file-management filter and bulk export/delete**:
  - `GET /api/gallery/artworks?fileManagement=EXTERNAL_REFERENCE` filters works referencing external read-only files (deletion removes only the database record while keeping source files); `MANAGED` indicates application-managed files (deleted along with the record, retaining `deleted=true` for duplicate checks).
  - Illustration and novel bulk endpoints support `"mode": "filter"` (across all matching pages; `filter` must be a JSON object) or an `ids` array of positive JSON integers such as `42` (decimals, strings, zero or negative numbers return HTTP 400 with the offending `field`). Passing an unrestricted `"filter": {}` (or pagination-only fields) selects all works of that type—deleting or exporting with source cleanup then requires `"confirmAll": true` and the corresponding gallery delete permission.
  - When export returns `archiveToken`, poll `GET /api/archive/status/{token}` (`core:archives.read`) until `ready`, then download via `GET /api/archive/download/{token}` (`core:archives.download`).
- **Image classification, startup cache, database migration and environment archives**:
  - Classifier moves report file copying, source directory removal and database registration separately—inspect partial outcomes before retrying.
  - Train the startup cache with `POST /api/tools/startup-cache/tasks` (`{}`) and poll `GET /api/tools/startup-cache`.
  - Database migration and environment restore both confirm execution using their preview `token` (`RESTORE_PENDING` delegates restoration to a replacement process). Environment export (`POST /api/settings/environment/exports`) requires an absolute server `destination` (never overwrites existing files) and `"confirmRestart": true`, plus a `password` whenever credentials, databases or state are included (`includeResources` also requires `includeDatabases`); export stops the backend and requests a full process restart (start the application manually if automatic restart fails). Installing an online update requires the verified installer package's SHA-256.

## Error handling, polling and pagination

Error responses return a stable machine `code` and localized `error` (`400` invalid input, `401`/`403` authentication or permission denied, `404` target not found, `409` busy/revision conflict/disallowed state, `413` payload too large, `503` plugin or database capability unavailable). Handle errors by business state rather than blindly retrying all non-2xx responses.

- **Single-work download submission and polling (`POST /api/download/tasks`)**:
  - Choose an active `workType` from `x-download-submissions` (unregistered types return `400 DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE`; if the upstream source reports 404 during metadata lookup, initial submission returns `404 WORK_NOT_FOUND`; querying a task ID not visible to the caller returns `404 DOWNLOAD_TASK_NOT_FOUND`).
  - Parameter errors include `in`, `field` (a JSON Pointer such as `/options/pages` for body fields, or the declared parameter name for query/path/header/multipart inputs) and `reason` (such as `out_of_range`).
  - After receiving `202`, poll `Location` (where `taskId` is `task.attempt.attemptId` from the receipt): start at a **2-second** interval, back off gradually up to **10 seconds** while `revision` is unchanged, reset to 2 seconds on change, and stop once `phase` is `COMPLETED`, `FAILED` or `CANCELLED` (always respect `Retry-After`). When `progress` is non-null, read `progress.unit` (`PAGES`, `IMAGES` or `BYTES`), `completed` and `total` together.
  - **Idempotency and history**: retrying the same command with the same caller and `requestId` returns the original receipt while the in-process idempotency entry remains active (entries expire about 5 minutes after a terminal result is saved); `GET /api/download/tasks/history` lists persisted terminal records only (default 50 per page, max 200) and does not resume in-flight downloads.

| Data item | Read and write conventions |
| --- | --- |
| Pixiv work IDs | Gallery `artworkId` and export `ids[]` are JSON integers; Pixiv search `id`, download task `workId` and batch `key.id` are decimal strings (preserve 64-bit integer precision when converting; third-party download plugins define their own `workId` format) |
| Download option types | Single-task `options` use string values (`{"bookmark":"true","mediaQuality":"80","mediaWebpLossless":"true"}`); batch `definition.download` uses native JSON types (`{"bookmark":true,"mediaQuality":80,"mediaWebpLossless":true}`) |
| Timestamps and paths | Task `updatedAt` uses ISO 8601; batch `createdTime`, gallery `time` and schedule `nextRunTime` use Unix milliseconds. Task `outputPath` is the planned server directory while gallery `folder` is the registered library path; correlate works by ID |
| Common response shapes | `GET /api/authors` returns a JSON array; `GET /api/authors/paged` returns a page object with `content`; `GET /api/downloaded/statistics` returns an object with `success` and counts |

| Paginated endpoint | Parameters | How to fetch the next page |
| --- | --- | --- |
| `GET /api/gallery/artworks` | `page`, `size` | Pass `page=0` for the first page, then increment `page` by `1` |
| `GET /api/download/batches/{id}/items` | `offset`, `limit` | Start at `offset=0` and advance `offset` by the number of items returned |
| `GET /api/download/tasks/history` | `limit`, `beforeTime`, `beforeId` | Omit both cursor parameters on the first page; pass the returned `beforeTime` and `beforeId` pair for subsequent pages |
| `GET /api/pixiv/search` | `page` | Start at `page=1` (values below `1` are treated as page 1) |
| `GET /api/plugin-market/catalog` | `cursor`, `limit` | Pass `nextCursor` as `cursor` (plugin version lists return `nextVersionCursor`) |

## Program permission list

Query `GET /api/security/program-keys/permissions` with an administrator session for the current installation's live permission catalog. Keys accept only exact permission IDs, never plugin or path wildcards (`*` in the table below denotes a single path segment):

### Permission scope and dedicated workflows

- **Composite permission checks**:
  - **Shared settings, media maintenance and file import**: require both the `core` entry permission and the target plugin's matching permission (catalogs filter targets to authorized plugins).
  - **Individual downloads and manual batches**: individual downloads require `core:downloads.create` + `<plugin>:downloads.create` for the download-type owner; manual batches require `core:batches.create` (or `core:batches.resume`) + `<plugin>:batches.execute` for the source plugin and every work plugin in the execution plan (reading the source catalog requires `core:schedule.sources.read`).
  - **Persistent scheduled tasks**: require the specific action under `core:schedule.*` + `<plugin>:schedule.manage` for the source plugin (account-policy actions require the policy owner's matching permission; schedules are installation-wide persistent configuration, so revoking the key that created a schedule does not delete or disable it).
  - **Plugin lifecycle and security policies**: require the specific `core:plugins.*` action + `<plugin>:plugins.manage` for the target plugin (plus consumer authorization when reverse dependencies exist; `core:plugins.install` and `plugin-market:install` allow server-side code execution, and `core:hooks.publish` modifies global operation protection policies—grant with care).
  - **Acquisition credentials**: `core:credentials.read` returns credential names and statuses without secrets; referencing stored shared credentials via `X-Acquisition-Credentials` requires `core:credentials.use` in addition to the business permission (supplying a one-off credential directly still uses `X-Acquisition-Credential`).
- **Data isolation vs. installation-wide scope**: download tasks and manual batches are isolated per key identity (a key can query, cancel and manage only its own tasks and batches, while administrators retain global visibility; all keys still share the single per-process manual batch slot). Gallery, novel library, collections, translation, inbox, archives, page editor, settings, logs, updates, maintenance, database migration and environment archives operate directly on the shared installation rather than per-key copies; local-only maintenance and recovery endpoints still enforce local loopback origin checks.
- **Dedicated program endpoints**: identity setup, key management, GUI tokens, CAPTCHA challenges, browser collection tickets, anonymous quotas and page assembly retain their dedicated flows, and legacy browser-compatibility routes do not grant program-key access. Use `/api/download/tasks` (downloads and progress), `/api/tools/file-import` (record-file import), `/api/library/artworks/{id}` (artwork management), `/api/page-editor` (page editing) and `/api/admin/notifications/test` or `/test-all` (inbox notification tests).

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
