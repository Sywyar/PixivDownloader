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

For a selected local collection named `Favorites` with ID `7`, `{collection_name}_{collection_id}_{artwork_id}_p{page}` can produce `Favorites_7_42_p0.png`. With no collection selected, the same template produces `__42_p0.png`. Both illustrations and novels support these variables. The actual filenames are saved at download time, so renaming a collection does not rename existing files.

Filename templates affect filenames only: `/` and `\` become `_` and do not create subdirectories. The global `download.root-folder` does not expand variables; a collection's custom download directory supports `{collection_name}`. These modes differ from [local artwork import path parsing](/en/local-import), which reports both collection variables as known but unavailable in the current mode.

## Automatic bookmarking and Ugoira processing

- **Automatic bookmarking**: when "Auto bookmark" is selected on the download page, the backend uses the saved cookie (which must contain `PHPSESSID`) to call the Pixiv API and bookmark a work after its download finishes. The userscript instead uses the browser's current Pixiv session and does not forward `document.cookie` to the backend. Bookmarking is best-effort: a failed bookmark does not turn a completed download into a failure.
- **Ugoira**: after downloading the ZIP of frames and extracting them in frame order, the application uses `ffmpeg` from the system `PATH` or its automatic installation to convert them to the selected format (WebP by default; GIF, APNG, MP4 or the original ZIP are also available; see [Configuration reference](/en/configuration#image-and-ugoira-output-formats)). It also saves the first frame as the `_p0_thumb.jpg` thumbnail.

## Download monitor, duplicate detection and desktop tools

- **Download monitor (`http://localhost:6999/monitor.html`)**: view active download progress, filter history by author / tag / AI status or search it with fuzzy matching, and view download trend charts.
- **Potential duplicate detection (`http://localhost:6999/pixiv-duplicates.html`, administrator only, requires `gallery-tools`)**: find visually similar images using perceptual hashing (dHash), even when filenames or resolutions differ. Adjust the Hamming distance threshold (default `10`; lower is stricter), switch between "Cross-work mode / Full-library mode", and click a thumbnail to open its detail page for handling.
- **Desktop GUI tools page**: provides **Image classifier**, **Database directory check**, **Data backfill**, **Legacy data and metadata import**, **Historical media processing** and **PixivBatchDownloader import support**.

Compose groups tools by how you use them. **Quick tools** include Data backfill, PixivBatchDownloader import support and Import local artworks. They open in dialogs; closing a dialog leaves its task running. Reopen it from Tools to check progress or cancel. **Utilities** include Image classifier, Directory check, and FFmpeg and its tools. They use a dedicated page; exiting or selecting another sidebar page requests cancellation and keeps completed changes. A tool that is writing may require you to wait for the current operation before exiting. Plugin tools appear when their capabilities are available and arrange themselves within the group.

!> Offline maintenance tools such as Directory check use an exclusive maintenance session; the GUI automatically stops and restores the backend. Back up the selected database first (for PostgreSQL, also back up the server-side database), and do not start another instance using the same data directory. When the operation ends, check the tool report, backend state and gallery access. Closing the tool window does not mean the operation succeeded.

Import local artworks is an online tool that requires the backend to stay running; it does not use the maintenance shutdown flow above. Register files using path templates, or save presets and enter only artwork IDs. See the [guide and examples](/en/local-import).

## Guest invitations

Guest invitations are available in both `solo` and `multi` modes. In the gallery's upper-right corner, an administrator can click "Invite Guests", set an expiry time, allowed content ratings (SFW / R18 / R18G) and tag / author allowlists, then copy `http://host:port/invite?code=xxx` to share read-only access with external guests. All invitation codes can be viewed, disabled or deleted centrally at `http://localhost:6999/pixiv-invite-manage.html`.

Invitation links open a prefilled redemption page; GET requests do not establish sessions. Submitting the code and completing any published verification issues a session credential distinct from the code. Old raw-code cookies cannot sign in and must be redeemed again. Paused, deleted or expired invitations retain their existing access restrictions, and logout revokes the current credential.
