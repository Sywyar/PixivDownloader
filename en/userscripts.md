# Userscripts

Userscripts let you operate directly on Pixiv pages — while browsing, when you see a work you want to download, one click adds it to the queue without switching to the PixivDownloader page.

?> Userscripts are optional. Most download operations can be done through `pixiv-batch.html` without installing any scripts.

---

## Prerequisites

1. [Tampermonkey](https://www.tampermonkey.net/) extension installed in the browser
2. PixivDownloader backend is running
3. Logged in (in Solo mode)

---

## Script List

| Script Name | Purpose |
|-------------|---------|
| **All-in-One Bundle** | Includes all scripts below (recommended — install just this one) |
| Page Scrape Downloader | Batch check and download from search pages, ranking pages, etc. |
| User Batch Downloader | One-click batch download all works from an artist profile page |
| URL Batch Importer | Open a panel on any page to paste links for batch download |
| Single Artwork Downloader (Java Backend) | One-click download from a single artwork page (via backend) |
| Single Artwork Downloader (Local Download) | One-click download from a single artwork page (browser local, **no Java backend needed**) |
| Enhancement Toolbox | Downloaded artwork border markers, one-click cookie import, etc. |

---

## Installation

### Method 1: Install via Web Management Page (Recommended)

1. Start PixivDownloader, open `http://localhost:6999/pixiv-batch.html`
2. Find the "**🧩 Userscripts**" card at the top, click to expand
3. Click the "**⬇ Install**" button for the desired script
4. Tampermonkey shows an install confirmation page — click "Install"

?> When installed this way, the script's `@connect` is automatically configured for the current backend address.

### Method 2: Download from Release

1. Go to [Releases](https://github.com/Sywyar/PixivDownloader/releases) and download the script file (`.user.js`)
2. Drag the file into the Tampermonkey dashboard and click "Install"

---

## Script Usage Details

### All-in-One Bundle (Recommended)

Install this package to get all features. If you already have standalone scripts installed, disable them after installing All-in-One to **avoid duplicate panel conflicts**.

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

1. Tampermonkey dashboard → find the script → click "Edit"
2. Modify the `@connect` line at the top of the script, replacing `localhost` with the actual server address

Scripts installed via the web management page already have the current backend address configured automatically.

---

## FAQ

### Script is installed but nothing changes on Pixiv pages

1. Confirm Tampermonkey is enabled (the extension icon shows the script count)
2. In the Tampermonkey dashboard, confirm the script's status is "Enabled"
3. Refresh the Pixiv page (scripts inject on page load)
4. Confirm the current page matches the script's `@match` rules (e.g., User Batch only shows on `/users/` pages)

### All-in-One and standalone scripts both enabled, two panels appear

Disable the standalone versions and keep only All-in-One, or vice versa. The two versions have independent storage — it's recommended to use only one.

### Script shows "Backend Unavailable" or "Connection Failed"

Confirm the PixivDownloader backend service is running, and the address and port configured in the script are correct.
