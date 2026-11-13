# HTTP API and program permissions

External programs can use `Authorization: Bearer <program-key>`. Open **Program access keys** from the security page to create a named key with explicit permissions and allowed source IPs. The full key is shown once; the server stores its digest. Keys have no default expiry. You can edit permissions and sources or revoke a key. Selecting a plugin includes only its currently listed permissions, excluding future additions.

Source restrictions accept numeric IPv4, IPv6 and CIDR networks. Restricted mode requires at least one entry; **Any source** is an explicit choice. Configure trusted proxies before using a reverse proxy. Do not combine a program key with administrator sessions, invitations or GUI credentials. Send acquisition credentials separately in `X-Acquisition-Credential`.

Administrator sessions can still use existing endpoints through `X-Session-Token`. Program keys can access only methods and paths explicitly listed in the permission catalog. Other endpoints retain their human-administrator or local workflows. Key management and administrator credential changes require a human administrator. This API does not provide an MCP server.

Cookie-authenticated writes require a same-origin check. Cross-origin browser requests are rejected; plugin management and scheduled-task endpoints also require an explicit same-origin signal. `/api/gui/**` retains its local-machine and GUI-token restrictions. Remote programs use the business endpoints below. Plugin capabilities require an active plugin and available dependencies.

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
2. POST `/api/download/batches` with `name`, `sourceType`, `activationToken`, `definitionJson` and optional `proxy`. `definitionJson` is a JSON string, limited to 1 MiB UTF-8; the whole request is limited to 8 MiB.
3. Query the returned UUID with GET `/api/download/batches/{id}` and `/{id}/items?offset=0&limit=100`. An item is identified by batch UUID plus `workType` and `id`, not an individual download execution UUID.
4. POST `/{id}/pause`, `/cancel`, `/resume` or `/retry` to pause, cancel, resume discovery or retry only failed items. Stopping is terminal only after execution really exits. Retrying discovered failures does not finish an incomplete source scan.
5. DELETE `/{id}` while idle to remove the record. A process retains one batch; delete it before creating another. Batch results do not survive process restarts.

`explicit-list` accepts mixed artwork and novel entries. Serialize this object as `definitionJson`. Work IDs must be positive decimal integer strings, with no duplicate ID within a type:

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
  "limit": 100,
  "dryRun": true,
  "alwaysNetwork": false
}
```

`limit:0` means all candidates; the body limit is 4 KiB. A 202 response means accepted, so keep querying that execution. Classification moves report copying, source removal and database registration separately. Inspect partial results before retrying a move.

Confirm database migration with the preview token. Environment restore takes a preview token, selected fields, path mappings and password; `RESTORE_PENDING` means a replacement process will perform the restore. Export requires an absolute server `destination` and `confirmRestart:true`, with optional `password`, `includeCredentials`, `includeDatabases`, `includeState` and `includeResources`. Resources require databases; credentials, databases or state require a password. An existing destination is never overwritten. The backend stops, exports and requests a full restart; reconnect to query the result. If restart fails, start the application manually. Update installation requires the verified package's current SHA-256.

## Errors and history

Errors contain a machine `code` and localized `error`. 400 means invalid input, 401/403 authentication or authorization failure, 404 an unknown identity, 409 busy/conflicting/disallowed state, 413 an oversized body and 503 an unavailable capability. Handle the business state rather than retrying every non-2xx response.

GET `/api/download/tasks/history` defaults to 50 items, at most 200. Pass both returned `beforeTime` and `beforeId` for the next page. It lists persisted terminal results and does not restore active downloads. Individual-download deduplication lasts only within the current process retention window. An unknown execution is not permission to resubmit safely. Tool history and batch results have separate retention rules.
## Program permission list

An administrator can read the authoritative catalog for the current installation with GET `/api/security/program-keys/permissions`. The table lists built-in declarations; consult the current catalog for external plugins. `*` represents one path segment. Keys store explicit permissions, not plugin or path wildcards.

Shared settings, media tools and file imports require both the `core` entry permission and the same permission for the target plugin. Catalogs filter targets by these grants. Individual downloads require `core:downloads.create` plus `downloads.create` for the actual download-type owner. Batches require `core:batches.create` or `core:batches.resume`, plus `batches.execute` for the source and every work plugin in the execution plan. Read source metadata with `core:schedule.sources.read`.

Programs can query, cancel and manage only their own download tasks and batches. Administrators retain installation-wide access. There is still one batch per process; more keys do not add batch slots. Translation, tools, settings, logs, databases and environment permissions apply to the corresponding plugin or installation, not private copies per key. Database migration and environment archives cover selected content across the installation.

Key management requires an administrator session: GET `/api/security/program-keys` lists keys, GET `/permissions` reads the catalog, POST the root creates a key, PUT `/{id}` edits it and DELETE `/{id}` revokes it. Creation accepts `name`, `permissions`, `anyIp` and `networks`. Edits use `{"revision":1,"policy":{...}}`; revocation uses `{"revision":1}`. Use the revision returned by a read. Refresh after a conflict instead of overwriting another edit. Creation returns `key` and a one-time `secret`; lists return neither secrets nor digests.

An installation supports at most 128 keys. Each key allows an 80-character name, 512 permissions and 64 source networks. Management bodies are limited to 128 KiB. Last-used time records successful authentication observed in the current process. Revocation blocks new requests without cancelling already accepted tasks.

Startup cache operations use GET `/api/tools/startup-cache`, POST `/api/tools/startup-cache/tasks` and POST `/api/tools/startup-cache/tasks/{id}/cancel`. Start with `{}`. Acceptance does not establish successful training; keep reading status.

| Permission | Purpose | Methods and paths |
| --- | --- | --- |
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
| `core:database.check` | Check database connections | POST `/api/settings/database/check` |
| `core:database.migrate` | Migrate databases for the installation | POST `/api/settings/database/migrations` |
| `core:database.preview` | Preview database migration for the installation | POST `/api/settings/database/migration-previews` |
| `core:database.read` | Read database settings and migration status | GET `/api/settings/database/migration`; GET `/api/settings/database/providers`; GET `/api/settings/database/status`; GET `/api/settings/database` |
| `core:directories.read` | Check artwork directories | GET `/api/tools/directories` |
| `core:directories.write` | Change artwork directory records | POST `/api/tools/directories` |
| `core:downloads.cancel` | Cancel this key's download tasks | POST `/api/download/tasks/*/cancel` |
| `core:downloads.create` | Create download tasks | POST `/api/download/tasks` |
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
| `core:logs.read` | Read application logs | GET `/api/tools/logs/*` |
| `core:media.cancel` | Cancel media processing | POST `/api/tools/media/tasks/cancel` |
| `core:media.execute` | Run media processing | POST `/api/tools/media/tasks` |
| `core:media.preview` | Preview media processing | POST `/api/tools/media/previews` |
| `core:media.read` | Read media tools and tasks | GET `/api/tools/media/tasks/current`; GET `/api/tools/media`; POST `/api/tools/media/capabilities` |
| `core:paths.read` | View path prefixes | GET `/api/tools/path-prefixes` |
| `core:paths.write` | Change path prefixes | POST `/api/tools/path-prefixes/pin`; POST `/api/tools/path-prefixes` |
| `core:repositories.read` | Read plugin repository settings | GET `/api/settings/repositories` |
| `core:repositories.write` | Change plugin repository settings | PUT `/api/settings/repositories` |
| `core:schedule.sources.read` | Read batch and schedule source catalogs | GET `/api/schedule/sources` |
| `core:settings.read` | Read settings and presets | GET `/api/settings/presets`; GET `/api/settings` |
| `core:settings.write` | Validate and save settings | POST `/api/settings/validate`; POST `/api/settings` |
| `core:startup-cache.cancel` | Cancel startup cache training | POST `/api/tools/startup-cache/tasks/*/cancel` |
| `core:startup-cache.execute` | Train the startup cache | POST `/api/tools/startup-cache/tasks` |
| `core:startup-cache.read` | View startup cache status | GET `/api/tools/startup-cache` |
| `core:system.check` | Check connectivity | POST `/api/admin/system/connectivity` |
| `core:system.read` | View system status | GET `/api/admin/system` |
| `core:system.reload` | Reload configuration | POST `/api/admin/system/reload` |
| `core:system.restart` | Restart the application | POST `/api/admin/system/restart` |
| `core:tools.history.read` | View tool history | GET `/api/tools/history` |
| `translation:cancel` | Pause or cancel translation jobs | POST `/api/translation/jobs/*/cancel`; POST `/api/translation/jobs/*/pause` |
| `translation:delete` | Delete translation job records | DELETE `/api/translation/jobs/*` |
| `translation:execute` | Start or resume novel and series translation | POST `/api/translation/jobs/*/resume`; POST `/api/translation/jobs`; POST `/api/translation/novel`; POST `/api/translation/series` |
| `translation:read` | View translation status, languages and jobs | GET `/api/translation/jobs/*`; GET `/api/translation/jobs`; GET `/api/translation/languages`; GET `/api/translation/status`; POST `/api/translation/language` |
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
