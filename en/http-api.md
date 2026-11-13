# Administrator HTTP API

External programs can send an administrator login session in `X-Session-Token`. It remains a human session with the existing revocation and maintenance rules; these endpoints do not provide application keys or an MCP server. Send Pixiv acquisition credentials separately in `X-Acquisition-Credential`.

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
