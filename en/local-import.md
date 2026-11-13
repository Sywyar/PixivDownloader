# Import local artworks and save presets

Register images already stored in your own directories by reading artwork IDs from their paths. Importing keeps the files in place. Missing titles, authors and other metadata can be filled in later.

?> Use a version that includes **Import local artworks**, and keep the backend running. Open it from the desktop Tools page in Compose or Swing. Paths sent through the HTTP API also refer to files on the machine running the backend.

In Compose, Import local artworks opens in a dialog under Quick tools. Scans and imports continue when you close it. Reopen the tool to check progress or cancel.

With saved paths, leave all three inputs empty and click **Scan and preview**. Temporary templates, artwork IDs and a specific file are grouped under **Options for this import**. This section stays collapsed until you choose to expand it.

Without saved presets, a path template is required. If it is empty when you click **Scan and preview**, the options expand and the template field shows a red outline and an error message. Artwork IDs and the specific file remain optional.

## Turn a real path into a path template

A **path template** marks changing parts of a path with braces. The application reads information from those positions. Every template must contain `{artwork_id}`, in either a directory name or a filename.

| Existing file | Path template to enter | Parsed information |
| --- | --- | --- |
| `D:/xxx/12345/pic.png` | `D:/xxx/{artwork_id}/` | Artwork 12345; collect supported files in this directory and its subdirectories |
| `D:/xxx/12345_xxx.png` | `D:/xxx/{artwork_id}_xxx.{extension}` | Artwork 12345, extension png; `_xxx` is literal text |
| `D:/678/12345/pic.png` | `D:/{author_id}/{artwork_id}/` | Author 678, artwork 12345 |
| `D:/xxx/12345_p0.jpg` | `D:/xxx/{artwork_id}_p{page}.{extension}` | Artwork 12345, page 0 |

A template without a trailing `/` matches files, not folders. To match a folder and supported files in its subdirectories, keep the trailing `/`; a file template includes the filename and extension. Use absolute paths; Windows accepts `/` or `\`. Templates do not create directories or rename files.

## For an occasional import: enter a temporary template

1. Open **Tools → Import local artworks**.
2. Expand **Options for this import** and enter one template per line under **Path templates for this import**, such as `D:/xxx/{artwork_id}/`.
3. Enter `12345` under **Artwork IDs** to import one artwork, or enter multiple IDs separated by newlines, spaces or commas. Leave the field empty to scan all matching artworks.
4. To register just one file, fill in **Specific file (optional)** as well. A matching template is still required to parse its ID.
5. Click **Scan and preview**, check the IDs, paths and proposed actions, then click **Start import**.

When temporary templates are present, only those templates are used. Presets are used when temporary templates are empty. Tool inputs apply to this operation and are not saved as configuration.

## For repeated imports: save local import presets

**Local import presets** are saved path templates, displayed as the paths you entered. Imports use all saved paths automatically.

1. Open **Tools → Import local artworks**, then click **Local import presets** inside the dialog.
2. Click **Add path** and enter absolute path templates such as `D:/{author_id}/{artwork_id}/`, one per row. Double-click a path to edit it, or remove a row.
3. Click **Save presets**. The next scan uses these paths without a restart. Cancelling keeps the saved paths.
4. Open **Import local artworks** and leave all three inputs empty to scan all saved paths. To limit the scan, expand the options and enter `12345` or a list of IDs.
5. The tool displays and uses all saved paths. Preview the results before starting.

When limited to ID `12345`, this example searches the author directories under `D:/` without requiring the author ID. Point templates at the smallest practical directory to reduce scanning. The configuration key is `local-import.presets`; use the preset editor inside the local import tool to change it.

## Available variables

| Variable | Meaning |
| --- | --- |
| `{artwork_id}` | Required; a positive integer artwork ID |
| `{author_id}` | A positive integer author ID |
| `{artwork_title}` | An artwork title already present in the path |
| `{author_name}` | An author name already present in the path |
| `{page}` | Page number, starting at 0 |
| `{extension}` | Extension without its leading dot |
| `{name}` | Other text within one path segment; not used as the artwork title |

Variable names must match the lowercase snake_case spellings in the table exactly. Separate adjacent variables with literal text, for example `{artwork_id}_{name}`, not `{artwork_id}{name}`. An asterisk is not a wildcard.

## Preview, refresh and results

- Scanning supports JPG, JPEG, PNG, APNG, GIF, WebP, WebM, MP4 and ZIP. Files must still pass import validation; ZIP support does not unpack arbitrary archives.
- Preview distinguishes new artworks, additional files, existing registrations, conflicts and missing files. Resolve conflicts first; existing registrations are not forcibly overwritten.
- The recent scan index stays in memory for 5 minutes and does not watch directories. After adding, moving or deleting files, enable the rescan option before previewing again.
- Cancellation waits for the current artwork registration to finish and keeps completed registrations. Split large scans into smaller directories or ID lists.
- External files are referenced read-only. Deleting an artwork registration in the application does not delete its original files. Moving or deleting those files later can break references; back up both the database and the external directories.
- Paths may not contain titles, authors or the complete page count. Pending metadata does not mean registration failed. See [network access](/en/network-access) for subsequent online metadata requests.

## HTTP API

Administrators can use the same workflow through `/api/local-import`, with the application's authentication and CSRF protection:

1. `GET /api/local-import/presets` reads saved presets.
2. `POST /api/local-import/previews` accepts a JSON request and returns a preview ID.
3. Poll `GET /api/local-import/previews/{id}` until `phase` is `READY`; read details at `/previews/{id}/items?offset=0&limit=20`.
4. Send `{"previewId":"the returned preview UUID"}` to `POST /api/local-import/tasks`.
5. Query `GET /api/local-import/tasks/{id}`; cancel with `POST /api/local-import/tasks/{id}/cancel`.

Request body to scan two artworks using all saved paths:

```json
{
  "templates": [],
  "artworkIds": [12345, 12346],
  "refreshIndex": false,
  "selectedFile": ""
}
```

A `202` response means the request was accepted; keep checking the result. Each instance runs one import task at a time. Previews and the index do not survive a process restart. These are HTTP endpoints, not a claim that a separate MCP tool is available.

For downloader history JSON, see [import external downloads](/en/pixiv-batch-downloader-import).
