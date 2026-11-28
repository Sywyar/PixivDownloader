# Download works

A download is an asynchronous task. Submitting it only gets a receipt. Poll the task until it reaches a final phase, then confirm the work in the library.

## Submit a task

`POST /api/download/tasks` needs `core:downloads.create`:

```bash
curl -sS -X POST "$BASE/api/download/tasks" \
  -H "Authorization: Bearer $PIXIV_PROGRAM_KEY" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"<new UUID>","workType":"artwork","workId":"<Pixiv work ID>","options":{}}'
```

- `requestId`: generate a new UUID for each new task. When you retry the same request, send the same `requestId`; the receipt then reports `"duplicate": true` instead of creating a second task.
- `workType`: `artwork` covers illustrations, manga and ugoira and comes from the `download-workbench` plugin. `novel` comes from the `novel` plugin. The catalog's top-level `x-download-submissions` lists the types enabled on this instance with each type's permission and input schema: `jq '.["x-download-submissions"]' pixiv-openapi.json`.
- `workId`: the Pixiv work ID as a string, such as `"42"`.
- `options`: string values only, such as `"false"` or `"80"`. Omit an option to use the instance's settings. For `artwork`, omit `pages` to download the whole work; `"0,2"` downloads the first and third pages. Novels are saved as `txt` by default, or `html` or `epub`, and do not accept `pages`.

The response is `202 Accepted` with a `Location` header and a receipt:

```json
{"duplicate": false,
 "task": {"attempt": {"attemptId": "47925123-6ab8-4cb8-ba11-253cdd091a00", "workType": "artwork", "workId": "42"},
          "queueType": "illust", "title": "Example", "phase": "QUEUED", "updatedAt": "2026-11-22T07:36:13Z",
          "revision": 4, "failureReason": null, "progress": null, "outputPath": "/downloads/42"}}
```

`task.attempt.attemptId` is the task ID. `202` means the task was accepted, not that the work was downloaded.

| Response | Meaning |
| --- | --- |
| `400 DOWNLOAD_TASK_INVALID` | The body is not valid. Check it against the operation's schema. |
| `400 DOWNLOAD_TASK_UNSUPPORTED_WORK_TYPE` | The type is not enabled on this instance. No task was created. |
| `404 WORK_NOT_FOUND` | Pixiv could not find the work with the current account. The attempt appears as `FAILED` in the task list. |
| `409 DOWNLOAD_TASK_CONFLICT` | The `requestId` was already used with different content. |
| `413 DOWNLOAD_TASK_INVALID` | The body is larger than 128 KiB. |
| `422 DOWNLOAD_TASK_REJECTED` | An option is not acceptable for this work, such as an invalid page selection. The attempt appears as `FAILED` in the task list. |
| `503 DOWNLOAD_TASK_UNAVAILABLE`, `DOWNLOAD_TASK_CAPACITY_EXCEEDED` | Downloads are unavailable or task capacity is reached. Wait, then send the identical request with the same `requestId`. |

## Follow the task

`GET /api/download/tasks/{taskId}` needs `core:downloads.read`. Poll every 2 seconds. While `revision` stays the same, lengthen the interval step by step up to 10 seconds, and go back to 2 seconds after it changes. Honor `Retry-After` when present.

`phase` moves through `ACCEPTED`, `QUEUED` and `STARTED`. Stop polling when it is `COMPLETED`, `FAILED` or `CANCELLED`. While the task runs, `progress` may give a `unit` with `completed` and `total` counts. After `FAILED`, `failureReason` gives the reason.

- `GET /api/download/tasks` returns a snapshot of the tasks this key can see, with a process `epoch`. The epoch changes when the instance restarts.
- `GET /api/download/tasks/history` pages through finished tasks, including those from before a restart. Omit `beforeTime` and `beforeId` for the first page. To continue, send both values exactly as the previous page returned them.

Tasks belong to the key that submitted them. Another key gets `404 DOWNLOAD_TASK_NOT_FOUND`, the same response as an unknown task ID.

## Cancel a task

`POST /api/download/tasks/{taskId}/cancel` needs `core:downloads.cancel`. Ask the user first. `REQUESTED` means cancellation was requested, `TERMINAL` means the task had already ended, and `409` means the task cannot be cancelled right now. A request is not the end of the task: keep polling until the phase is final, because the task may finish before the cancellation takes effect.

## Confirm the result

`COMPLETED` means the task finished. To confirm that the work is in the library, see [library.md](library.md):

- Illustrations: `GET /api/library/artworks/{artworkId}` returns the download record. An empty `400` means there is no record.
- Novels: `GET /api/gallery/novel/{novelId}` returns the saved novel.

## Many works at once

For more than a few works, a batch keeps them under one record. `POST /api/download/batches` needs `core:batches.create` and an `activationToken` from `GET /api/schedule/sources`, which needs `core:schedule.sources.read`. `GET /api/download/batches/{id}/items` reports each item, paged with `offset` and `limit`. Read these operations in the catalog first, and agree on the number of works with the user before you start.
