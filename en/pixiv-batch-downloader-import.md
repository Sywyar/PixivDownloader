# PixivBatchDownloader import support

The official `pixiv-batch-downloader-import` plugin and its companion userscript automatically add new PixivBatchDownloader downloads to the local gallery: illustrations, manga, animations and novels. Configure the download directory once; subsequent downloads need no manual import. Original files are referenced in place and retained when gallery records are deleted.

## Setup

Standard distributions do not preinstall this plugin. The full offline bundle includes it.

1. Install the official **PixivBatchDownloader import support** plugin from this app's plugin market, then fully exit and restart the app. Skip this step if the plugin is already installed and enabled.
2. Install and enable PixivBatchDownloader and Tampermonkey in your browser, and allow Tampermonkey to run on Pixiv. In Chrome 138+, open **Extensions → Tampermonkey → Details** and enable **Allow User Scripts**. For other versions, follow [Tampermonkey's permission instructions](https://www.tampermonkey.net/faq.php?ext=dhdg&q=Q209).
3. In this app's desktop **PixivBatchDownloader import support** settings, choose the browser download root used by PixivBatchDownloader, save and restart the backend. Files may be in subdirectories. Alternatively, set the absolute `pixiv-batch-downloader-import.source-root` in `config/plugins/pixiv-batch-downloader-import.properties`. When editing this file on Windows, use forward slashes, for example `D:/Downloads`.
4. On this app's download page, open **More → Userscripts** and separately install **PixivBatchDownloader import support**. Confirm installation in Tampermonkey and leave the script enabled.
5. Refresh any open Pixiv pages. The default server is `http://localhost:6999`. If you use another port, change it once through **Set local server address** in the userscript menu on Pixiv.
6. Keep the local backend running, then crawl and download with PixivBatchDownloader. Once every file in a work finishes downloading, the work is added to its gallery automatically. No JSON copying or pasting is needed.

The browser and server must run on the same machine, with the server in solo mode. The script connects only to the local server and uses userscript storage for pending observations. **Allow access to file URLs** is not required. Downloads completed before installation are not scanned.

After installing, enabling or disabling the script, check its status in Tampermonkey and refresh both the download page and Pixiv. If a completed download is missing from the gallery, open **Automatic import status** in the userscript menu on Pixiv. `SOURCE_ROOT_REQUIRED` means you need to configure the download directory and restart the backend. For `SERVER_UNAVAILABLE`, check that the backend is running and the server address is correct.

Pending observations survive page reloads in userscript storage. They retry while a Pixiv page is open; the status menu shows pending, incomplete and blocked work. Missing metadata, skipped pages and conflicting paths are not treated as successful works. Crawl again to collect complete evidence. The script neither scans historical download folders nor changes the downloader's state.

## Files and limits

Files must be ordinary files under the configured root; custom names and subdirectories are supported. Links, junctions and paths outside that root are rejected. Supported formats are JPEG, PNG, WebP and GIF images; WebP, GIF, APNG, WebM, MP4 and ZIP animations; and TXT or EPUB novels. Novel text comes from the same crawl. ZIP thumbnails read only the first image entry.

Novel covers, attachments and original publication dates are not imported. The current novel page displays the registration time as the upload time.

The database stores exact page paths. Source files are never copied, moved or deleted by import or gallery deletion; media maintenance skips them. Existing records, including soft-deleted records, are not overwritten. Moving source files breaks their references until their location is repaired. Missing novel capability leaves work pending.

Each file is limited to 1 GiB, each work to 8 GiB and 1000 pages. Browser metadata is limited to 1000 works and 8 MiB; the persistent pending queue to 1000 works, 5000 files and 8 MiB. A full queue reports the limit and stops accepting new observations. Requests are limited to 12 MiB and JSON depth 20; novel content to three million characters. Sequential retries back off to 60 seconds. Single-use local tokens expire after one minute. No Pixiv cookies or administrator passwords are sent.

Events follow [this upstream revision](https://github.com/xuejianxianzun/PixivBatchDownloader/tree/e3bbdfdd2fb452cc9073450b8aa1ad065f982b64). These internal events require metadata to precede completion within the same batch; compatibility depends on the browser and userscript manager.
