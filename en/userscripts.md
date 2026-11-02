# Userscripts

Userscripts let you operate directly on Pixiv pages — while browsing, when you see a work you want to download, one click adds it to the queue without switching to the PixivDownloader page.

The host serves the script list and installation API independently of the download workbench. Only scripts declared by active plugins are available. Disabling the workbench removes its download helpers from the catalog while other plugins retain their scripts. Solo mode still requires sign-in; a backend `@updateURL` does not guarantee that Tampermonkey background requests carry a session.

?> Userscripts are optional. Most download operations can be done through `pixiv-batch.html` without installing any scripts.

---

## Prerequisites

1. [Tampermonkey](https://www.tampermonkey.net/) extension installed in the browser
2. For Java-backend downloaders or the toolbox, keep the backend running and sign in in solo mode
3. Local Download needs no backend and does not create gallery records

---

## Script List

| Script Name | Purpose |
|-------------|---------|
| **All-in-One Bundle** | Includes the built-in download helpers; scripts supplied separately by plugins are not included |
| Page Scrape Downloader | Batch check and download from search pages, ranking pages, etc. |
| User Batch Downloader | One-click batch download all works from an artist profile page |
| URL Batch Importer | Open a panel on any page to paste links for batch download |
| Single Artwork Downloader (Java Backend) | One-click download from a single artwork page (via backend) |
| Single Artwork Downloader (Local Download) | One-click download from a single artwork page (browser local, **no Java backend needed**) |
| Enhancement Toolbox | Downloaded artwork border markers, one-click cookie import, etc. |

---

## Scripts supplied by plugins

[PixivBatchDownloader import support](/en/pixiv-batch-downloader-import) automatically adds works completed by the external downloader to the gallery. Install the official plugin and restart the app, then install its companion userscript separately. All-in-One does not include this script. The plugin guide covers installation, the download directory and permissions.

## Installation

### Method 1: Install via Web Management Page (Recommended)

1. Start PixivDownloader, open `http://localhost:6999/pixiv-batch.html`
2. Open "**🧩 Userscripts**" on the download page
3. Click the "**⬇ Install**" button for the desired script
4. Tampermonkey shows an install confirmation page — click "Install"
5. Refresh both the download page and Pixiv page, then check script detection and the actual floating panel

?> When installed this way, the script's `@connect` is automatically configured for the current backend address.

### Method 2: Install a Standalone Script from the Repository

1. Select a committed standalone `.user.js` file in the [source repository](https://github.com/Sywyar/PixivDownloader) and open its Raw content.
2. Let Tampermonkey install it, or download and import it into the dashboard.
3. Configure the backend address and host permission, then refresh Pixiv to verify.

All-in-One is generated during the build; install it from the current backend. The current release workflow does not upload standalone userscript attachments, so do not rely on the Release asset list.

---

## Script Usage Details

### All-in-One Bundle (Recommended)

Install this package to get the built-in download helpers. If you already have standalone versions of its scripts installed, disable those versions after installing All-in-One to **avoid duplicate panel conflicts**. Keep the import script supplied separately by the plugin enabled.

### Page Scrape Downloader

Effective on Pixiv **search pages**, **following feed pages**, **ranking pages**, and other list pages:

- **Checkboxes** appear in the corner of each artwork thumbnail
- Check the works you want
- Click "Start Download" on the floating panel

Artworks already added to the download queue show border markers on thumbnails.

### User Batch Downloader

Shows a floating panel on Pixiv **artist profile pages** (`/users/<id>` and subpages):

- One-click add all of that artist's **works** to the download queue
- Supports illustrations and novels separately

### URL Batch Importer

Shows a floating panel on **any Pixiv page**, functionally equivalent to the "Batch Import Single Artworks" tab in `pixiv-batch.html`:
- Paste URL lists for batch download

### Single Artwork Downloader (Java Backend)

Shows a download button on **single artwork pages** (`/artworks/<id>`):
- One-click download via backend, including metadata, auto-bookmark, etc.

### Single Artwork Downloader (Local Download)

Shows a download button on **single artwork pages**:
- Download directly through the browser, **no Java backend needed**
- No metadata recording, not added to gallery database

### Enhancement Toolbox

Shows a toolbar on the right side of **any Pixiv page** (each feature can be toggled individually):

| Feature | Description |
|---------|-------------|
| **Downloaded Artwork Borders** | Add colored borders to thumbnails of works that are in the server database, helping distinguish what's already downloaded |
| **One-Click Cookie Import** | Read current Pixiv cookies and save to the server in one click (requires special Tampermonkey permission) |

### Skip Downloaded History & Deleted Works

Each download script's settings panel has a "**Skip downloaded history**" toggle. When enabled, already-downloaded works are automatically skipped. Expanding reveals two sub-options:

- **Actual folder detection**: besides checking history, also checks if files actually exist on disk; missing files trigger re-download
- **Allow re-downloading deleted works**: works deleted through the gallery keep a [deletion mark](/en/gallery?id=deleting-artworks); by default (unchecked) they're treated as downloaded and skipped (reason: "downloaded before, but deleted"); check to treat as not-downloaded and re-download; the deletion mark is cleared on success

---

## Extra Configuration for Non-localhost Deployments

Scripts default to connecting to `http://localhost:6999`. If your backend is deployed on a different machine or port:

1. Set the actual backend protocol, host, and port in the script settings.
2. Reinstall from that backend's web page so the installation response configures `@connect` permission for the current host. For manually installed scripts, check the host permissions in the header.
3. Refresh Pixiv, submit one work, and confirm that the intended backend receives it.

`@connect` grants access; it does not set the backend address. Changing it alone does not redirect requests to another server.

---

## FAQ

### Script is installed but nothing changes on Pixiv pages

1. Confirm Tampermonkey is enabled (the extension icon shows the script count)
2. In the Tampermonkey dashboard, confirm the script's status is "Enabled"
3. Refresh the Pixiv page (scripts inject on page load)
4. Confirm the current page matches the script's `@match` rules (e.g., User Batch only shows on `/users/` pages)

### All-in-One and standalone scripts both enabled, two panels appear

Disable the standalone versions and keep only All-in-One, or vice versa. The two versions have independent storage — it's recommended to use only one.

### The Download Page Says "Not Detected"

The current page received no response from the script. This alone cannot distinguish a missing script from a disabled script, site permissions, or a different installation origin. Install from the current backend and refresh. The available version and the version reported by the running script are separate facts; clicking Install does not prove it runs.

### Script shows "Backend Unavailable" or "Connection Failed"

Confirm the backend is running and the script uses the correct address and port. For backend downloads, check queue completion and open the file; for Local Download, check the browser download. To import cookies, follow [First Download](first-download.md) for HttpOnly permissions. Ordinary `document.cookie` is not a complete login credential.
