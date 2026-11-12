# PixivBatchDownloader import support

The official `pixiv-batch-downloader-import` plugin and its dedicated userscript register illustrations, manga, animations and novels downloaded by PixivBatchDownloader in the local gallery as **read-only references**. Source files are neither copied nor moved, and deleting a gallery record retains the original files. You can also batch-import existing PixivBatchDownloader history into the gallery.

The browser and this app must run on the same machine, with the app in solo mode (`solo`). The script connects only to the local loopback service and does not need the browser's Allow access to file URLs permission.

## Enable automatic import

Ordinary distributions do not preinstall this plugin; the full-offline package includes it.

1. Install and enable PixivBatchDownloader import support in the plugin market, then fully exit and restart the app.
2. Enable PixivBatchDownloader and Tampermonkey in the browser. Chrome 138+ requires Allow user scripts under Extensions → Tampermonkey → Details; for other versions, see [Tampermonkey permissions](https://www.tampermonkey.net/faq.php?ext=dhdg&q=Q209).
3. Set the external download root in desktop Settings → PixivBatchDownloader import support, or enter the absolute path `pixiv-batch-downloader-import.source-root=D:/Downloads` in `config/plugins/pixiv-batch-downloader-import.properties`. Use forward slashes on Windows; saving applies immediately. Alternatively, confirm a suggested directory in the dialog after the first download finishes.
4. Sign in to the local service in your browser, then open `http://localhost:6999/api/scripts/pixiv-batch-downloader-import.user.js`, or use Userscripts on the download page, to install and enable the import script. Refresh the Pixiv page afterward.
5. Keep the local backend running and download normally with PixivBatchDownloader. Once all files for a complete work are on disk, it is registered automatically in the corresponding gallery.

If no directory was configured in advance, the first complete work's absolute path triggers a desktop dialog to confirm a suggested directory. The suggestion is the common parent of all files in that work and may be only a single-work subdirectory. Choose the top-level download root after removing PixivBatchDownloader's relative path. For example, if a file is at `D:/Downloads/pixiv/作者/123_p0.png` and the relative path is `pixiv/作者`, choose `D:/Downloads`. Click Save directory to apply it and retry automatically. Cancelling does not write configuration, and the same suggestion does not trigger repeated dialogs.

## Automatic import status and statistics

If a finished download does not appear in the gallery, open Automatic import status from the Tampermonkey menu on the Pixiv page:

- `SOURCE_ROOT_REQUIRED`: confirm the suggested directory in the desktop dialog, or specify the download root in settings.
- `SERVER_UNAVAILABLE`: check that the local backend is running, that you are signed in in solo mode, and that the port under Set local service address in the script menu is correct.

When the backend is temporarily unavailable, pending records stay in Tampermonkey storage, up to 1,000 works, 5,000 files or 8 MiB. Opening a Pixiv page retries them automatically with backoff. Works with missing pages, unknown metadata, skipped downloads or path conflicts are not registered as complete works. Upstream events are based on [this pinned PixivBatchDownloader source version](https://github.com/xuejianxianzun/PixivBatchDownloader/tree/e3bbdfdd2fb452cc9073450b8aa1ad065f982b64); metadata for a batch must arrive before its successful-download events.

Imported works on the desktop home page counts this plugin's successful imports, deduplicated by `(work type, work ID)`. The count is stored in `data/pixiv-batch-downloader-import/plugin.db`; retain it in backups. Deleting gallery records does not decrease the count.

## File formats, read-only references and limits

- Actual files must be inside the configured root. Symbolic links, junctions, out-of-root paths, missing files and invalid file headers are rejected.
- Images support JPEG, PNG, WebP and GIF. Animations support WebP, GIF, APNG, WebM, MP4 and ZIP; a ZIP thumbnail uses its first image entry, while the original remains ZIP. Novels support TXT and EPUB. For live automatic import, their body comes from metadata fetched in the same operation; novel covers, attachments and original publication times are not currently imported, and upload time displays the registration time.
- Existing records, including soft-deleted ones, are neither overwritten nor registered again. If the novel plugin is unavailable, pending records are retained and success is not returned. Media maintenance tools skip external read-only works. Moving source files requires repairing their paths; the app does not guess their new location.
- Limits are 1 GiB per page file, 8 GiB / 1,000 pages per work, 12 MiB per HTTP request, JSON depth 20, and 3 million characters per novel body.

## Import history

There are two ways to register downloads made before installing the plugin. Both follow the file-format and read-only-reference rules above and skip existing works, including soft-deleted ones. After import, backfill tools can fill supported fields but cannot restore missing source files.

History import processes only local records and files. Page counts reflect contiguous local page records; import cannot verify the current page count on Pixiv. After successful registration, the running desktop GUI requests focus and asks whether to start metadata backfill. Choosing Later or closing the prompt leaves backfill stopped; you can also start it from Tools → Data backfill. Backfill checks all works with missing information in the gallery, fills fields such as title, author and tags through the backend, and saves a curated metadata snapshot in `metadata_json` without overwriting existing values. Fetch failures do not undo imports. Original novel files remain accessible, but history import and metadata backfill do not restore the body required for online reading or translation.

### Method 1: read browser history directly through the userscript (no export file)

1. Keep the local backend running, save External download directory in plugin settings, and refresh a signed-in Pixiv page.
2. Open the original Pixiv site in the same browser profile used by PixivBatchDownloader, pause external downloads, then select Read browser download history → Read and check in the Tampermonkey menu. The script scans the current site's history database read-only in batches, up to 1 GiB per operation; do not modify or clear history during the scan.
3. Check the pending, existing and missing / conflicting counts. Up to 100 details are shown; if the pending count is `0`, the check ends there. Click Start import. The script sends history records to the local service to verify files and register works. This operation neither requests Pixiv metadata nor reads or sends Pixiv cookies.

Keep the page open during import. 100% means processing is finished, not that every item succeeded. Cancelling, leaving the page or switching servers stops further processing; registered works remain. Temporary data is stored in `data/pixiv-batch-downloader-import/browser-history/` and cleaned up after normal completion or about 15 minutes of inactivity.

### Method 2: import exported JSON through desktop tools

1. Open PixivBatchDownloader import support in the desktop GUI's Tools section. In Compose, it opens as a dialog under Quick tools.
2. Export history JSON from PixivBatchDownloader. If there are multiple parts, put them in one dedicated directory and select it. Each file is limited to 512 MiB, with up to 64 files and 1 GiB total.
3. Select the source-file root. Combining it with the relative path `n` in each record must locate the actual file. This selection does not overwrite automatic-import configuration. Click Check and preview.
4. Click Start import and check successful, skipped and failed counts. No Pixiv cookie is needed. Temporary preparation files are stored in `data/pixiv-batch-downloader-import/history-import/` and cleaned up when the task ends or the application exits normally.

The PixivBatchDownloader import support userscript makes importing past downloads easier: use method 1 above to read browser history directly. Desktop import also works independently. Closing its dialog leaves the task running; use Cancel to stop it.
