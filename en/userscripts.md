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

1. Start PixivDownloader and open the current instance's download page, for example `http://localhost:6999/pixiv-batch.html`. Use the port shown by your instance.
2. Expand Userscripts, click Install for the script you want, and confirm in Tampermonkey.
3. Refresh the download page and any open Pixiv pages, then check script status and the floating panels.

Installing through the backend binds update links and detection to that address, including its port. Backend download scripts also use this address for their first connection; a previously saved address takes priority. Scripts supporting remote access replace `@connect YOUR_SERVER_HOST`; the external-import script remains local-only. If you install the **All-in-One bundle**, disable the standalone download scripts it includes to avoid duplicate panels. Their local storage is independent.

### Method 2: install a standalone script from the source repository

Open the Raw content of a committed `.user.js` file in the [source repository](https://github.com/Sywyar/PixivDownloader) to install it through Tampermonkey, then check the backend address in script settings and the `@connect` permission. All-in-One is generated during builds, and the release workflow does not upload standalone userscript Release attachments. Install it directly from the running backend.

## History skipping and remote deployment

Each backend download script's settings panel supports Skip download history. When enabled, you can also select:

- **Check actual directory**: check whether disk files exist and download again if they are missing.
- **Allow deleted works to be downloaded again**: gallery-deleted works retain a [deletion marker](/en/gallery#delete-works) and are skipped as already downloaded by default. Enable this to download again and clear the marker on success.

If the backend runs on another machine or a nondefault port (default: `http://localhost:6999`):

1. Install from the intended backend to use its protocol, host and port for the first connection. If you previously saved another address, change it in script settings.
2. Reinstall the script through that backend's Web page to update its `@connect` host permission. For manually installed scripts, check `@connect` in the script header; changing `@connect` alone does not change the request destination.
3. Refresh the Pixiv page and submit one work, then confirm that the intended backend queue receives it.

## Installation address and runtime detection

For example, installation from `http://localhost:7000` binds update links and download-page detection to port 7000. Detection does not scan other ports. Connection settings used on Pixiv and detection on the download page are separate; changing a connection setting does not expand detection.

| Location | Purpose |
| --- | --- |
| `@updateURL`, `@downloadURL` | Full installation URL, including protocol, host, port and application context path |
| `@include` and injected `origin` / `pages` | Backend pages where the script can respond; `@include` is a regular expression, with escaped slashes in the source |
| `@connect` | Permitted request host; it contains no port and does not select the request destination |

Detection uses `window.postMessage` within the same page and returns the version actually running. It sends no detection request to the backend. The top-right corner of each card combines detected and available versions, showing an identical version only once. Available updates appear first in orange. Detected scripts use green, All-in-One coverage uses blue, and older versions and undetected scripts use a neutral color. Each state also has a text label.

Comparison applies to the same script and supports one to three numeric components, plus `alpha.N`, `beta.N`, and `rc.N` prereleases. Custom formats or conflicting detected versions retain the neutral available-version label. All-in-One versions are not compared with standalone script versions.

### Developers: detection declarations and default addresses

Declare the script through the plugin's `UserscriptContribution`. Add these lines before the userscript metadata end marker:

```javascript
// @pixiv-presence-page /pixiv-batch.html
// @pixiv-presence-page /pixiv-batch-alt.html
```

Place `// @pixiv-presence-bootstrap` after `'use strict'` in the outermost IIFE, before business initialization. Both declarations and the bootstrap marker are required; the marker alone does not generate `@include`. On backend pages, respond only to detection without starting Pixiv operations.

The host's `ScriptController.buildInstallUrl` reads the protocol, host, port and context path from the current HTTP request and rewrites update links. `UserscriptPresenceInstaller` derives detection scope from the same URL. For the root backend scripts, `scripts/shared/installed-server.js` reads `script.downloadURL`, `script.updateURL` or `scriptUpdateURL` from `GM_info` (also supporting `GM.info`) and derives the default backend address. Only an absent or invalid backend installation URL falls back to the source default, `http://localhost:6999`. Read saved settings with `GM_getValue`; initialization must not overwrite them.

Synchronize shared snippets with `scripts/sync-shared-snippets.ps1`; All-in-One is generated during builds. The external-import script has its own `installedServer()` and still accepts only local addresses without a context path. Local Download needs no default backend address.

## Troubleshoot common problems

- **No script panel appears on Pixiv**: confirm that Tampermonkey and the script are enabled, that the current URL matches the script's `@match` rules (User Batch, for example, runs only on `/users/` pages), and refresh the page. Chrome 138+ also requires Allow user scripts in the extension details.
- **The download page says Not detected**: the page has not received an in-page response; this does not prove the script is uninstalled. Check that it is enabled, has site permission, and was installed from the current port. Install an updated copy from this backend when needed, then refresh. If its installation response still lacks `@include`, update the plugin providing the script; reinstalling identical content cannot add missing declarations.
- **Backend not running or Connection failed**: check the backend process, solo-mode login state and backend address in the script. If one-click cookie import fails, check Tampermonkey's HttpOnly cookie permission using [your first download](/en/first-download#method-c-the-userscript39s-one-click-cookie-import).
