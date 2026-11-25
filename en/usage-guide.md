# CLI and advanced reference

This page covers command-line startup options, filename template variables, media and maintenance tools, and guest invitations. For routine downloads and management, see [Quick access](/en/quick-access), [Batch download from URLs](/en/batch-download), [Batch download by artist](/en/user-download), [Search and download](/en/search), [Novels and narration](/en/novel), [Work gallery](/en/gallery), [Scheduled tasks](/en/scheduled-tasks) and [Userscripts](/en/userscripts).

## Startup and administration options

Run these commands from the fully extracted distribution directory (replace `APP.jar` with the actual main application JAR filename; these single-line commands work in both PowerShell and Bash):

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --help
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

| Option | Purpose |
| --- | --- |
| `--no-gui` | Start without the desktop GUI, for headless servers or Docker (complete `--setup` before the first start; otherwise the application exits with code `78`) |
| `--intro` | Open the product introduction page in a browser at startup |
| `--help`, `-h` | Print command-line help and exit |
| `--setup` | Initialize the administrator account, operating mode and HTTP proxy |
| `--change-password` | Change the administrator password after verifying the old password |
| `--reset-password` | Force a local administrator password reset when the password is forgotten |
| `--web-publications` | Query or recover the page editor's published page state offline (see [Page editor](/en/page-editor#form-verification-and-resource-authorization)) |

Before running administration commands such as `--setup`, `--change-password` or `--reset-password`, stop the running instance that uses the same data directory. Changing or resetting the password invalidates existing sessions. After restarting, verify that you can sign in with the new password (see [First-time setup](/en/first-setup)). The Windows installer edition can run `PixivDownload.exe` directly. Distributions include both Compose (the default) and Swing desktop interfaces; changing `app.gui-provider` requires a full restart.

## Filename template variables

The following variables are available under "Download settings → Filename template" on the download page (invalid filename characters are filtered automatically; a new template applies only to subsequent new downloads):

| Variable | Meaning |
| --- | --- |
| `{artwork_id}` | Work ID |
| `{artwork_title}` | Work title |
| `{author_id}` | Author ID |
| `{collection_name}` | Collection name; empty if none is selected |
| `{collection_id}` | Collection ID; empty if none is selected |
| `{author_name}` | Author name |
| `{timestamp}` | Unix timestamp at download time, in milliseconds |
| `{page}` | Current page index, starting at `0` |
| `{count}` | Total number of pages in the work |
| `{ai}` | `AI` for AI-generated works, or an empty string otherwise |
| `{ai+}` | `AI` or `Human` |
| `{R18}` | `R18`, `R18G` or an empty string |
| `{R18+}` | `SFW`, `R18` or `R18G` |

- `{collection_name}` and `{collection_id}` apply to both illustrations and novels: for example, with local collection `Favorites` (ID `7`) selected, `{collection_name}_{collection_id}_{artwork_id}_p{page}` produces `Favorites_7_42_p0.png`, or `__42_p0.png` when no collection is selected. Renaming a collection later does not rename already saved files.
- Filename templates control only the filename itself—`/` and `\` are replaced with `_` and never create subdirectories. Directory hierarchy is controlled by [work directory templates](/en/configuration?id=directory-templates); for reverse-parsing existing file paths, see [local artwork import](/en/local-import).

## Automatic bookmarking and Ugoira processing

- **Automatic bookmarking**: when "Auto bookmark" is selected on the download page, the backend uses the saved cookie (which must contain `PHPSESSID`) to call the Pixiv API and bookmark a work after its download finishes. The userscript instead uses the browser's current Pixiv session and does not forward `document.cookie` to the backend. Bookmarking is best-effort: a failed bookmark does not turn a completed download into a failure.
- **Ugoira**: after downloading the ZIP of frames and extracting them in frame order, the application uses `ffmpeg` from the system `PATH` or its automatic installation to convert them to the selected format (WebP by default; GIF, APNG, MP4 or the original ZIP are also available; see [Configuration reference](/en/configuration#image-and-ugoira-output-formats)). It also saves the first frame as the `_p0_thumb.jpg` thumbnail.

## Download monitor, duplicate detection and desktop tools

- **Download monitor (`http://localhost:6999/monitor.html`)**: view active download progress, filter history by author / tag / AI status or search it with fuzzy matching, and view download trend charts.
- **Potential duplicate detection (`http://localhost:6999/pixiv-duplicates.html`, administrator only, requires `gallery-tools`)**: find visually similar images using perceptual hashing (dHash), even when filenames or resolutions differ. Adjust the Hamming distance threshold (default `10`; lower is stricter), switch between "Cross-work mode / Full-library mode", and click a thumbnail to open its detail page for handling.
- **Desktop GUI tools page**: grouped in Compose by execution style (plugin tools appear automatically when enabled):
  - **Quick tools** (open in dialogs; closing the dialog leaves running tasks active in the background, and you can reopen the tool to inspect or cancel): **Data backfill**, **[PixivBatchDownloader import support](/en/pixiv-batch-downloader-import)** and **[Import local artworks](/en/local-import)** (all require the backend to remain running and do not enter offline maintenance mode).
  - **Utilities** (dedicated full-page workspace; switching sidebar pages or exiting requests cancellation while keeping completed changes, waiting for any active write to finish): **Image classifier**, **Database directory check**, **Legacy data and metadata import**, **Historical media processing** and **FFmpeg management**.

!> Offline database maintenance tools such as Directory check use an exclusive maintenance session in which the GUI automatically stops and restores the backend. Back up the selected database first (for PostgreSQL, also back up the server-side database), and never start another instance on the same data directory. Afterwards, verify the tool report, backend status and gallery access—do not treat closing the window as completion.

## Guest invitations

Guest invitations are available in both `solo` and `multi` modes. In the gallery's upper-right corner, an administrator can click "Invite Guests", set an expiry time, allowed content ratings (SFW / R18 / R18G) and tag / author allowlists, then copy `http://host:port/invite?code=xxx` to share read-only access with external guests. All invitation codes can be viewed, disabled or deleted centrally at `http://localhost:6999/pixiv-invite-manage.html`.

Opening an invitation link only displays a prefilled redemption page and does not create a session on `GET`. After the guest submits the code and completes any published verification components, the host issues a distinct guest session credential (legacy raw-code cookies cannot sign in and must be redeemed again; signing out revokes the current credential, and paused, deleted or expired codes immediately deny access).
