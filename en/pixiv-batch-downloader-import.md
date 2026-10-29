# PixivBatchDownloader import support

The official `pixiv-batch-downloader-import` plugin and its companion userscript automatically add new PixivBatchDownloader downloads to the local gallery: illustrations, manga, animations and novels. Configure the download directory once; subsequent downloads need no manual import. Original files are referenced in place and retained when gallery records are deleted.

## Setup

Standard distributions do not preinstall this plugin. The full offline bundle includes it.

1. Install the official **PixivBatchDownloader import support** plugin from this app's plugin market, then fully exit and restart the app. Skip this step if the plugin is already installed and enabled.
2. Install and enable PixivBatchDownloader and Tampermonkey in your browser, and allow Tampermonkey to run on Pixiv. In Chrome 138+, open **Extensions → Tampermonkey → Details** and enable **Allow User Scripts**. For other versions, follow [Tampermonkey's permission instructions](https://www.tampermonkey.net/faq.php?ext=dhdg&q=Q209).
3. Choose the browser download root in this app's desktop **PixivBatchDownloader import support** settings, or wait for a candidate directory after the first completed download. Files may be in subdirectories. You can also set the absolute `pixiv-batch-downloader-import.source-root` in `config/plugins/pixiv-batch-downloader-import.properties`. On Windows, use forward slashes, for example `D:/Downloads`. Saving takes effect immediately, without restarting the backend.
4. Sign in to the local service in your browser, then open the [import script installation URL](http://localhost:6999/api/scripts/pixiv-batch-downloader-import.user.js) and confirm installation in Tampermonkey. Replace the port if needed. With the workbench enabled, **More → Userscripts** provides the same script. The script and installation API do not require the download workbench.
5. Refresh any open Pixiv pages. The script uses the local host, protocol and port of its installation source. A manually saved address takes priority; change it through **Set local server address** in the userscript menu. Direct source-file installations default to `http://localhost:6999`.
6. Keep the local backend running, then crawl and download with PixivBatchDownloader. Once every file in a work finishes downloading, the work is added to its gallery automatically. No JSON copying or pasting is needed.

The browser and server must run on the same machine, with the server in solo mode. The script connects only to the local server and uses userscript storage for pending observations. **Allow access to file URLs** is not required. Downloads completed before installation are not scanned.

When no directory is configured, the first complete success report with absolute paths prompts desktop confirmation. The app requests focus and opens a directory dialog in settings. Confirm the candidate or choose a root that will contain future downloads. **Confirm and save** writes the setting and allows automatic retries to import pending works. Cancel saves nothing and the same report does not repeatedly open the dialog; you can choose a directory later in settings. Relative paths, operation without a GUI, or unsafe candidates require manual configuration. Paths are validated before display, at confirmation and during import; symbolic links and junctions are rejected.

After installing, enabling or disabling the script, check its status in Tampermonkey and refresh both the download page and Pixiv. If a completed download is missing from the gallery, open **Automatic import status** in the userscript menu on Pixiv. `SOURCE_ROOT_REQUIRED` means you need to confirm the candidate or choose a download directory in settings. For `SERVER_UNAVAILABLE`, check that the backend is running and the server address is correct.

Pending observations survive page reloads in userscript storage. They retry while a Pixiv page is open; the status menu shows pending, incomplete and blocked work. Missing metadata, skipped pages and conflicting paths are not treated as successful works. Crawl again to collect complete evidence. The script neither scans historical download folders nor changes the downloader's state.

## Files and limits

The desktop home overview shows **Imported works**, the cumulative number of unique works successfully imported by this plugin. A multi-page manga counts as one work; illustrations and novels with the same ID count separately. The total survives restarts and gallery deletion. Existing records, failed imports and skipped downloads do not add to it. Works registered before statistics were enabled are not counted retroactively. Include the plugin's `data/pixiv-batch-downloader-import/plugin.db` in backups to retain this history.

If a statistics write fails temporarily, the overview reports it as unavailable. The next userscript retry saves the pending count before acknowledging success. Gallery registration and statistics use separate transactions; forcibly terminating the process between them can leave an import uncounted.

Files must be ordinary files under the configured root; custom names and subdirectories are supported. Links, junctions and paths outside that root are rejected. Supported formats are JPEG, PNG, WebP and GIF images; WebP, GIF, APNG, WebM, MP4 and ZIP animations; and TXT or EPUB novels. Novel text comes from the same crawl. ZIP thumbnails read only the first image entry.

Novel covers, attachments and original publication dates are not imported. The current novel page displays the registration time as the upload time.

The database stores exact page paths. Source files are never copied, moved or deleted by import or gallery deletion; media maintenance skips them. Existing records, including soft-deleted records, are not overwritten. Moving source files breaks their references until their location is repaired. Missing novel capability leaves work pending.

Each file is limited to 1 GiB, each work to 8 GiB and 1000 pages. Browser metadata is limited to 1000 works and 8 MiB; the persistent pending queue to 1000 works, 5000 files and 8 MiB. A full queue reports the limit and stops accepting new observations. Requests are limited to 12 MiB and JSON depth 20; novel content to three million characters. Sequential retries back off to 60 seconds. Single-use local tokens expire after one minute. No Pixiv cookies or administrator passwords are sent.

Events follow [this upstream revision](https://github.com/xuejianxianzun/PixivBatchDownloader/tree/e3bbdfdd2fb452cc9073450b8aa1ad065f982b64). These internal events require metadata to precede completion within the same batch; compatibility depends on the browser and userscript manager.

## Import download history

With the plugin enabled and the backend running, open **Tools → PixivBatchDownloader history import** in Swing or Compose. This entry does not require the companion userscript.

1. Export download records from PixivBatchDownloader. Select the JSON file. If the export produced multiple shards, place all of them in a separate folder and select that folder; keep unrelated JSON files out of it.
2. Select the original files root. For example, if `n` is `author/123_p0.jpg` and the file is at `D:/Downloads/author/123_p0.jpg`, select `D:/Downloads`. This selection does not change automatic collection settings.
3. Select **Check and preview**. This checks local records, files and existing works without contacting Pixiv or registering works. The preview shows totals and up to 100 samples. Pages and duplicates are grouped by work, including pages in different shards in the selected folder.
4. For works that require sign-in, provide a temporary Pixiv Cookie, then select **Start import**. The plugin fetches metadata sequentially, checks the complete page set and registers files through the existing import service. The Cookie is not saved in configuration, temporary files or results.
5. Check imported, skipped and failed counts. Cancellation waits for the current operation to exit and retains registered works. Closing the interface does not cancel the task. Tasks do not resume after a process restart; preview the same records again to skip existing works, including soft-deleted records.

Authentication failures, access denial, rate limits, server errors and transport failures stop the remaining requests. The tool does not retry automatically. Fix the cause, preview again and start a new import; registered works will be skipped.

History records lack complete metadata, and their date field is not a download timestamp. Missing files or pages, conflicting paths, deleted works and unavailable metadata do not produce completed download records. Novel import requires the novel plugin. After registration, existing backfill tools can fill the fields they support; they cannot replace initial registration or restore missing original files.

Each JSON file is limited to 512 MiB (536870912 bytes). One operation accepts up to 64 JSON files and 1 GiB (1073741824 bytes) in total, reading only `.json` files directly inside the selected folder. Records are staged under `data/pixiv-batch-downloader-import/history-import/`. The plugin keeps at most one preview or running task; replacing a preview, finishing execution or closing normally cleans up its staging files. Forced termination can leave temporary folders, which may be removed while the app is fully stopped. They are never used to resume tasks. An excessively concentrated batch also triggers resource protection; a failed check never starts a partial import.

The same format and read-only reference rules above apply. Matching file content to a work still depends on the exported record and the original file; the importer does not infer identity from image content. Successful imports contribute to the plugin's cumulative statistics. Each execution displays at most the first 100 failures.

## Import history from the userscript

1. Keep the local backend running and save the original files root as **External download directory** in the plugin settings. Install or update the companion script, refresh Pixiv, and sign in to an account that can access the works.
2. Open the original Pixiv site in the same browser and profile used by PixivBatchDownloader. Pause external downloads. In the Tampermonkey menu choose **Read browser download history**, then **Read and check**, and wait for the local preview. No history export or file selection is needed.
3. Check candidate, existing, missing and conflicting counts, then choose **Start import**. The browser retrieves work metadata one item at a time; the local server verifies and registers the files. Pixiv cookies stay in the browser.

The preview shows check results for up to 100 works. With zero candidates, checking is complete and **Start import** stays disabled; there is nothing to wait for. Missing or conflicting results do not necessarily mean files were moved. Check that the configured root plus each record's relative path `n` matches the actual file, and check filenames and page completeness. Once the session is released, **Read and check** is available again. If release fails, the result is shown as unconfirmed.

The script reads the current Pixiv site’s history database in batches and sends up to 1 GiB per session to the local server. Do not clear or change history while reading; batched scanning does not provide a consistent snapshot during concurrent changes. It does not read or upload original work files, or create, upgrade or modify the history database. History cannot be read from another browser profile or site origin, or recovered after being cleared. Original-file, duplicate-work and backfill rules are the same as for desktop import.

Keep this page open during import. Cancellation, navigation away or changing the server address requests a stop; registered works are retained and skipped on another import. History tasks are not stored in the userscript queue and do not resume after a refresh. A connection failure can leave the result unconfirmed. Check the local server; if the session-creation response was lost, wait for roughly 15 minutes of inactivity and for the current operation to exit before retrying. Upload copies and candidate files are staged under `data/pixiv-batch-downloader-import/browser-history/` and cleaned after normal completion or idle expiry. After forced termination, remove leftovers only while the app is fully stopped.
