# Local artwork import and local import presets

Local artwork import registers existing illustrations, manga and animations on disk into the library in bulk as **read-only references** by parsing artwork IDs from their file paths (original folders and files remain untouched, and deleting a gallery record does not delete source files; titles, authors, tags and other metadata absent from paths can be filled afterward via [metadata backfill](#metadata-backfill)). To import history JSON exported from PixivBatchDownloader, use [import external downloads](/en/pixiv-batch-downloader-import).

?> Local artwork import is an online tool that requires the backend to stay running (it does not enter offline maintenance mode). Both Compose (under "Tools → Quick tools" as a dialog; closing the dialog leaves running tasks active in the background) and Swing open it from the desktop Tools page; paths in the HTTP API likewise refer to the server's local filesystem.

## Path templates and available variables

A **path template** uses curly braces `{variable}` to mark variable segments in a path so the application can extract artwork information in reverse:

- Every template must contain `{artwork_id}` (in either a folder name or a filename) and use an absolute path (`/` or `\` on Windows). Templates only match and parse existing paths; they never create directories or rename files.
- **Folder templates vs. file templates**: a template ending with `/` is a **folder template** (recursively collects supported files under that folder and its subfolders); a template without a trailing `/` is a **single-file template** (must include the filename and `.{extension}`).
- Variable names use exact lowercase `snake_case`; **adjacent variables must be separated by fixed text** (such as `{artwork_id}_{name}`, never `{artwork_id}{name}`), and `*` is not a wildcard.

| Actual file path on disk | Path template entered | Parsed result |
| --- | --- | --- |
| `D:/xxx/12345/pic.png` | `D:/xxx/{artwork_id}/` | Artwork ID `12345`; collects supported files in that folder and subfolders |
| `D:/xxx/12345_xxx.png` | `D:/xxx/{artwork_id}_xxx.{extension}` | Artwork ID `12345`, extension `png` (`_xxx` is literal text) |
| `D:/678/12345/pic.png` | `D:/{author_id}/{artwork_id}/` | Author ID `678`, artwork ID `12345` |
| `D:/xxx/12345_p0.jpg` | `D:/xxx/{artwork_id}_p{page}.{extension}` | Artwork ID `12345`, page `0` |

| Variable | Meaning |
| --- | --- |
| `{artwork_id}` | Required; positive integer artwork ID |
| `{author_id}` | Positive integer author ID |
| `{artwork_title}` | Artwork title present in the path |
| `{author_name}` | Author name present in the path |
| `{page}` | Zero-based page index |
| `{extension}` | File extension without the leading dot |
| `{name}` | Matches another name within one path segment (not stored as the artwork title) |

## Steps

"Import options for this run" (Temporary path templates, Artwork IDs, Specific file) is collapsed by default: when presets are saved, you can leave all three fields blank and click "Scan and preview" directly; when no presets are saved and temporary templates are blank, clicking scan automatically expands the section and highlights the template field in red.

### Method 1: enter temporary templates for a one-off import

1. Open desktop "Tools → Import local artworks" and expand "Import options for this run".
2. Enter absolute path templates in "Temporary path templates" (one per line, such as `D:/xxx/{artwork_id}/`; when filled, only temporary templates are used and configuration files are not modified).
3. **Artwork IDs (optional)**: enter `12345` for a single work, or multiple IDs separated by newlines, spaces or commas; leave blank to scan all matching works.
4. **Specific file (optional)**: enter a file path to register only that single file (still requires a template that matches it).
5. Click "Scan and preview", review the artwork IDs, paths and preview statuses, then click "Start import".

### Method 2: save local import presets (for frequent imports)

**Local import presets** (configuration key `local-import.presets`) store a reusable list of path templates and automatically scan all saved paths:

1. Open "Tools → Import local artworks" and click "Local import presets" inside the dialog.
2. Click "Add path" and enter absolute path templates such as `D:/{author_id}/{artwork_id}/` (one per line; double-click to edit or remove a row; point templates at specific artwork directories to narrow scan scope).
3. Click "Save presets" (applies immediately to the next scan without a restart; Cancel leaves saved paths unchanged).
4. Return to the import dialog and leave all three inputs blank to scan all saved paths—or enter `12345` under "Artwork IDs" to locate that work across author folders under `D:/` without knowing the author ID.

## Preview statuses, cache and import counts

- **Supported formats**: `JPG`, `JPEG`, `PNG`, `APNG`, `GIF`, `WebP`, `WebM`, `MP4` and `ZIP` (validated by file header; ZIP is used only for Ugoira frame packages, not arbitrary archive extraction).
- **Scan index cache**: the latest directory scan index stays in memory for 5 minutes without watching disk changes automatically; after adding, moving or deleting files, select "Rescan directories and refresh index" before previewing.
- **Cancellation and backup**: cancelling waits for the work currently being registered to finish and keeps already registered works. Imported files are read-only external references—moving or deleting source files breaks gallery references, so back up both the database and external source directories.

| Preview status | Meaning | Execution counter (counted per work) |
| --- | --- | --- |
| `NEW` | Register a new work | Counted as `imported` when written, `skipped` if unchanged at execution, or `failed` on write error |
| `SUPPLEMENT` | Add external files or pages to an imported work | Counted as `imported` when written, `skipped` if unchanged at execution, or `failed` on write error |
| `DUPLICATE` | All files are already registered | `skipped` |
| `CONFLICT` | Registration conflict (e.g., two files match the same page; existing records are not overwritten) | `failed` |
| `MISSING` | No matching files found | `failed` |

For example, if 4 works are `CONFLICT`, the result is `imported: 0, skipped: 0, failed: 4`; inspect the preview item's `reason` or the task's `problems` for details.

## HTTP API

Administrators or program keys with `core:local-import.*` permissions can run the same workflow through `/api/local-import` (at most one import task runs per instance, and previews/indexes do not persist across process restarts):

1. `GET /api/local-import/presets`: read saved presets.
2. `POST /api/local-import/previews`: submit a scan request (returns `202` and a preview ID; an empty `templates` array uses all saved presets):

   ```json
   {
     "templates": [],
     "artworkIds": [12345, 12346],
     "refreshIndex": false,
     "selectedFile": ""
   }
   ```

3. `GET /api/local-import/previews/{id}`: poll until `phase` reaches `READY`, and inspect items via `GET /api/local-import/previews/{id}/items?offset=0&limit=20`.
4. `POST /api/local-import/tasks`: submit `{"previewId": "<preview-UUID>"}` to start importing.
5. `GET /api/local-import/tasks/{id}`: query progress and results (cancel a preview or task via `POST /api/local-import/previews/{id}/cancel` or `POST /api/local-import/tasks/{id}/cancel`).

To read an imported illustration's original file by page, call `GET /api/downloaded/rawfile/{artworkId}/{page}` (starting at page `0`; the record's `folder` may be only the template's fixed prefix while pages reside in different subdirectories).

## Fill in metadata after import :id=metadata-backfill

When importing with `{artwork_id}` alone, the work is counted in the gallery immediately, but title, author, tags and series remain empty. To search by title, tag or series, open desktop "Tools → Data backfill" (or enable startup backfill for the next launch), or trigger targeted backfill for the imported artwork IDs via the HTTP API (replace `12345` with actual IDs; omitting `artworkIds` selects candidates across all illustrations and novels up to `limit`):

1. **Check candidate count** (requires `core:backfill.read`): `POST /api/tools/backfill/candidates` with `{"artworkIds": [12345]}`.
2. **Dry-run backfill** (requires `core:backfill.execute`): `POST /api/tools/backfill/tasks` with `{"artworkIds": [12345], "dryRun": true}`; after `202`, poll `Location` until completion.
3. **Run backfill** (requires `core:backfill.execute`): `POST /api/tools/backfill/tasks` with `{"artworkIds": [12345], "dryRun": false}`, polling `Location` until completion.

Backfill checks existing local metadata first and queries Pixiv only for remaining gaps (see [network access and third-party services](/en/network-access)). Only one backfill task runs at a time; terminal records remain queryable at their `Location` for 1 hour (up to 64 completed tasks), after which—or after a restart—lookup by ID returns `404`.
