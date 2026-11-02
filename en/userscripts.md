# Userscripts

Tampermonkey scripts let you select and fetch works or send tasks to the local backend directly from Pixiv pages. Userscripts are optional; batch downloads are also available at `pixiv-batch.html` without them.

The application host provides the script catalog and installation API independently of `download-workbench`. It lists only scripts declared by currently enabled plugins. Disabling the download workbench removes its download-helper scripts, while scripts declared by other plugins, such as the external-import plugin, remain installable. Connecting to the backend in solo mode requires signing in to this app first; when `@updateURL` points to the backend, Tampermonkey's background update requests are not guaranteed to carry the login session.

## Prerequisites and script list

Install [Tampermonkey](https://www.tampermonkey.net/) in your browser. The Java-backend scripts and Toolbox require a running backend and a solo-mode login. The browser-only Local Download script needs no backend, but it neither writes metadata nor registers works in the gallery.

| Script | Where it runs and what it does |
| --- | --- |
| All-in-One bundle | Combines the built-in download helpers and Toolbox; excludes scripts from other plugins such as external import |
| Page Scrape | Select thumbnails on list pages such as search, following feeds and rankings to queue works in bulk |
| User Batch | Queue all illustrations or novels with one click from an artist profile (`/users/<id>` and its subpages) |
| URL Batch | Open a floating panel on any Pixiv page and paste URLs for batch download |
| Single-work downloader (Java backend) | Download from a work page (`/artworks/<id>`) through the backend, retaining metadata and auto-bookmarking |
| Single-work downloader (Local Download) | Save files directly through the browser from a work page; no Java backend needed, and no gallery database registration |
| Experience Enhancement Toolbox | Provide colored borders marking downloaded works, one-click cookie import and other tools on the right side of Pixiv pages |

The [import external downloads](/en/pixiv-batch-downloader-import) script is provided separately by `pixiv-batch-downloader-import`, outside All-in-One. Install the plugin, restart, then install that script separately.

## Installation steps

### Method 1: install from the backend download page (recommended)

1. Start PixivDownloader and open `http://localhost:6999/pixiv-batch.html`.
2. Expand Userscripts, click Install for the script you want, and confirm in Tampermonkey.
3. Refresh the download page and any open Pixiv pages, then check script status and the floating panels.

When you install through the backend, the response automatically sets the script's `@connect` to the current backend host. If you install the **All-in-One bundle**, disable the standalone download scripts it includes to avoid duplicate panels. Their local storage is independent.

### Method 2: install a standalone script from the source repository

Open the Raw content of a committed `.user.js` file in the [source repository](https://github.com/Sywyar/PixivDownloader) to install it through Tampermonkey, then check the backend address in script settings and the `@connect` permission. All-in-One is generated during builds, and the release workflow does not upload standalone userscript Release attachments. Install it directly from the running backend.

## History skipping and remote deployment

Each backend download script's settings panel supports Skip download history. When enabled, you can also select:

- **Check actual directory**: check whether disk files exist and download again if they are missing.
- **Allow deleted works to be downloaded again**: gallery-deleted works retain a [deletion marker](/en/gallery#delete-works) and are skipped as already downloaded by default. Enable this to download again and clear the marker on success.

If the backend runs on another machine or a nondefault port (default: `http://localhost:6999`):

1. Change the backend address in script settings to the actual protocol, host and port.
2. Reinstall the script through that backend's Web page to update its `@connect` host permission. For manually installed scripts, check `@connect` in the script header; changing `@connect` alone does not change the request destination.
3. Refresh the Pixiv page and submit one work, then confirm that the intended backend queue receives it.

## Troubleshoot common problems

- **No script panel appears on Pixiv**: confirm that Tampermonkey and the script are enabled, that the current URL matches the script's `@match` rules (User Batch, for example, runs only on `/users/` pages), and refresh the page. Chrome 138+ also requires Allow user scripts in the extension details.
- **The download page says Not detected**: the current page has not received an in-page message response from the script; this does not prove it is not installed. Reinstall from the current backend and refresh the download page. The installable version in the list and the script's actual running version are tracked separately.
- **Backend not running or Connection failed**: check the backend process, solo-mode login state and backend address in the script. If one-click cookie import fails, check Tampermonkey's HttpOnly cookie permission using [your first download](/en/first-download#method-c-the-userscript39s-one-click-cookie-import).
