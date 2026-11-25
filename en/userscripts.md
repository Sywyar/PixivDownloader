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

1. Start PixivDownloader and open the current instance's download page (default `http://localhost:6999/pixiv-batch.html`; use your actual port and context path).
2. Expand Userscripts, click Install for the script you want, and confirm in Tampermonkey.
3. Refresh the download page and any open Pixiv pages, then check script status and the floating panels.

Installing from the backend automatically binds the script's update URLs, download-page detection scope and initial default backend address to the current instance address (including actual port and context path; any connection address manually saved in the browser takes precedence), and replaces `@connect YOUR_SERVER_HOST` with the current host for remote-capable scripts (the external-import script remains restricted to local loopback). If you install the **All-in-One bundle**, disable the standalone download scripts it includes to avoid duplicate panels (their local storage is independent).

### Method 2: install a standalone script from the source repository

Open the Raw content of a committed `.user.js` file in the [source repository](https://github.com/Sywyar/PixivDownloader) to install it through Tampermonkey, then check the backend address in script settings and the `@connect` permission. All-in-One is generated during builds, and the release workflow does not upload standalone userscript Release attachments. Install it directly from the running backend.

## History skipping and remote deployment

Each backend download script's settings panel supports Skip download history. When enabled, you can also select:

- **Check actual directory**: check whether disk files exist and download again if they are missing.
- **Allow deleted works to be downloaded again**: gallery-deleted works retain a [deletion marker](/en/gallery#delete-works) and are skipped as already downloaded by default. Enable this to download again and clear the marker on success.

If the backend runs on another machine or a nondefault port (default: `http://localhost:6999`):

1. Install the script directly from the target backend's Web download page (the first connection automatically adopts that backend's protocol, host and port, and writes the matching `@connect` host permission).
2. If an older address was saved previously, update the backend address in the script settings panel on a Pixiv page (editing `@connect` in the script header alone does not change the request destination).
3. Refresh the Pixiv page and submit one work, then confirm that the target backend queue receives it.

## Installation address and runtime detection

Download-page detection scope and the script's backend connection setting on Pixiv pages operate independently: for example, installing from `http://localhost:7000` binds update links and download-page detection strictly to port `7000` without scanning other ports, and changing the backend address in script settings on Pixiv does not widen detection scope.

| Script metadata | Purpose |
| --- | --- |
| `@updateURL`, `@downloadURL` | Full installation URL (including protocol, host, port and application context path) |
| `@include` and injected `origin` / `pages` | Restricts which backend pages the script responds to for in-page detection (`@include` is a regular expression with escaped slashes) |
| `@connect` | Declares allowed cross-origin request hostnames (contains no port and does not determine the request destination) |

Detection uses same-page `window.postMessage` to query the running script version without sending backend requests, combining the detected and installable versions at the top right of each card (shown once when identical): available updates appear at the top in orange, detected scripts in green, All-in-One coverage in blue, and older or undetected scripts in neutral gray alongside a status label. Version comparison supports 1–3 numeric segments and `alpha.N`, `beta.N` or `rc.N` prerelease suffixes for the same script; custom version formats or conflicting detected versions display only the installable version, and All-in-One versions are not cross-compared with standalone scripts.

### Developers: detection declarations and default addresses

When contributing a script via `UserscriptContribution`, declare both the presence pages before the metadata end marker and the bootstrap marker after `'use strict'` in the outermost IIFE (both are required—the bootstrap marker alone will not inject `@include`; on backend pages the script must respond only to detection without starting Pixiv business logic):

```javascript
// 1. Place before the end of the userscript metadata header:
// @pixiv-presence-page /pixiv-batch.html
// @pixiv-presence-page /pixiv-batch-alt.html

// 2. Place after 'use strict' in the outermost IIFE, before business initialization:
// @pixiv-presence-bootstrap
```

- **Server-side rewriting**: `ScriptController.buildInstallUrl` extracts protocol, host, port and context path from the current HTTP request to rewrite update links, and `UserscriptPresenceInstaller` injects the detection scope from the same address.
- **Client-side default address**: root backend scripts use `scripts/shared/installed-server.js` to read `script.downloadURL`, `script.updateURL` or `scriptUpdateURL` from `GM_info` (or `GM.info`) as the default backend URL (falling back to `http://localhost:6999` only when no valid installation URL exists, while preserving user settings saved via `GM_getValue`).
- **Build synchronization**: run `scripts/sync-shared-snippets.ps1` after editing shared snippets and rebuild All-in-One (the external-import script uses its own `installedServer()` restricted to local loopback without a context path; `Local Download` does not connect to a backend).

## Troubleshoot common problems

- **No script panel appears on Pixiv**: confirm that Tampermonkey and the script are enabled, that the current URL matches the script's `@match` rules (User Batch, for example, runs only on `/users/` pages), and refresh the page. Chrome 138+ also requires Allow user scripts in the extension details.
- **The download page says Not detected**: the current download page did not receive an in-page message response; this does not mean the script is uninstalled. Check that the script is enabled, has site permissions, and was installed from the same port as the current download page; reinstall from the current backend when updating and refresh (if the install response still lacks `@include`, update the plugin providing the script first).
- **Backend not running or Connection failed**: check the backend process, solo-mode login state and backend address in the script. If one-click cookie import fails, check Tampermonkey's HttpOnly cookie permission using [your first download](/en/first-download#method-c-the-userscript39s-one-click-cookie-import).
