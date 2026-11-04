# PixivBatchDownloader Import Support

The official `pixiv-batch-downloader-import` plugin and its companion userscript register completed PixivBatchDownloader downloads (illustrations, manga, animations, and novels) into the local gallery as **read-only references** (original files are neither copied nor moved, and remain intact when gallery records are deleted), and support importing historical downloads. For full setup, limits, and history import instructions, see the [online documentation](https://sywyar.github.io/PixivDownloader/#/en/pixiv-batch-downloader-import).

## 1. Enable Automatic Import

The browser and backend must run on the same machine in solo mode (`solo`):

1. Install and enable **PixivBatchDownloader import support** in Plugin Market (bundled in the full-offline package; on-demand in standard packages), then fully restart the application.
2. Enable PixivBatchDownloader and Tampermonkey in your browser, then install the import userscript from the download page's Userscripts section (or `/api/scripts/pixiv-batch-downloader-import.user.js`).
3. Configure the browser download root in desktop settings, or click **Save folder** in the desktop confirmation dialog after your first completed download, then refresh Pixiv.
4. Download works with PixivBatchDownloader and verify their records in the gallery. Historical downloads can be imported via the Tampermonkey menu (**Read browser download history**) or Desktop **Tools → PixivBatchDownloader history import**.

## 2. Troubleshooting and Statistics

- **Completed work missing from gallery**: Check **Automatic import status** in the Tampermonkey menu on Pixiv: `SOURCE_ROOT_REQUIRED` means the download root needs confirmation or configuration; `SERVER_UNAVAILABLE` means the local backend or port needs checking. Incomplete pages, missing metadata, or skipped downloads are not registered as complete works.
- **Import statistics**: The desktop home overview displays **Imported works**, counting unique `(work_type, work_id)` items registered by this plugin in `data/pixiv-batch-downloader-import/plugin.db` across restarts and gallery deletions.

## 3. Public SDK Contracts Used

- **`DesktopDirectorySuggestionSource`**: Proposes a `HOT_RELOAD` directory candidate for the plugin's non-sensitive `PATH_DIR` setting, saved only after explicit desktop user confirmation.
- **`WorkFileImporter` and `WorkFileImportHandler`**: Validates local files and atomically registers read-only file references (`WorkAssetService.isReadOnly` marks external assets so gallery deletion and media maintenance leave original files untouched).
- **`WebRouteContribution.trustedWriteOrigins`**: Declares trusted HTTPS origins for `LOCAL` POST routes without ambient cookies, combined with solo mode, loopback verification, and short-lived single-use tokens (plus session IDs for browser history import).
- **`UserscriptContribution`, `DownloadObserver`, and `DownloadAdmissionPolicy`**: Contributes plugin-owned userscript translations and synchronous best-effort observation / fail-closed admission hooks via `DownloadLifecycle`.
