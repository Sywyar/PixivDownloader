# Artwork Gallery

The artwork gallery is the central page for viewing, organizing, and managing downloaded works.

URL: `http://localhost:6999/pixiv-gallery.html`

---

## Interface Overview

The gallery page has three main areas:

- **Left sidebar (navigation modules)**: view switching (All Images / By Author / By Series Manga), collection list with new-collection button, navigation links (Gallery / Download / Monitor / Statistics / Duplicates / Invite Management)
- **Central grid**: artwork thumbnail list
- **Top toolbar**: search box, sort, filter, view switching

Two buttons protrude from the right edge of the sidebar:

- **Nav**: expand / collapse the left sidebar.
- **Tasks** (admin only): shows background archive tasks (batch export, packaging) with progress and remaining validity. After a task completes you can download the archive manually from the list; expired tasks are cleaned up automatically, and the list is cleared after a server restart. Starting a batch export auto-opens this panel.

The top toolbar also has gallery type switch tabs: **Manga** (current illustration / manga / ugoira gallery) and **Novels** (jumps to the novel gallery `pixiv-novel-gallery.html`).

---

## Search

The search box supports multiple scopes (switch via dropdown to the left of the search box):

| Scope | Description |
|-------|-------------|
| **General** | Title + Author name (default) |
| **Title** | Search by artwork title |
| **Author Name** | Search by author name |
| **Artwork ID** | Exact match by artwork ID |
| **Author ID** | Exact match by author ID |
| **Description** | Search by artwork description |
| **Tags (Fuzzy)** | Tags containing keywords |
| **Tags (Exact)** | Exact tag match |

---

## Filtering

Click the "**Filter**" button on the toolbar to expand the filter panel:

- **Content rating**: All / All ages / R-18 / R-18G
- **AI-generated**: All / Exclude AI / AI only
- **Image format**: JPG / PNG / GIF / WebP etc.
- **Collections**: Filter by collection

### Tag / Author Tri-State Filter

This is the gallery's most powerful filter feature. Each tag or author can be individually set to one of three states:

| State | Meaning |
|-------|---------|
| ✅ Must have | The work must contain this tag / be from this author |
| ❌ Must not have | The work must not contain this tag / be from this author |
| 🔵 Or have | Satisfying any one "or have" condition is enough |

Multiple "must have" conditions use **intersection**, multiple "or have" conditions use **union**, then "must not have" conditions are excluded.

---

## Sorting

| Sort Option | Description |
|-------------|-------------|
| Download Time | Sort by ingestion time (default, newest first) |
| Artwork ID | Sort by Pixiv artwork ID |
| Image Count | Sort by total image count of the artwork |
| Status | Sort by download / archive status |
| Author ID | Sort by author ID (works by the same author cluster together) |
| Tag Count | Sort by tag count |
| Manga Series | Works in series are sorted by series and clustered together |

Each sort supports ascending / descending toggle.

---

## Viewing Artwork Details

Click any artwork thumbnail to enter the `pixiv-artwork.html` detail page:

- Title, author, publication time, tags
- Artwork description
- Multi-image browsing (left/right navigation, fullscreen lightbox)
- Related artwork recommendations
- Other works by the same author
- Jump to original Pixiv page button

---

## Batch Management (Admin)

Click "**Batch Management**" in the top toolbar to enter multi-select mode. Select works, then execute batch actions from the "**Actions**" menu in the bottom bar. Selection options:

- **Select Page / Clear**: applies to the current page only.
- **Select all results**: selects **every** work matching the current filters (you can still untick individual works); the selection survives pagination.

The "Actions" menu provides three operations:

- **Export**: a dialog lets you pick the **packaging** (group by author / work, or one folder per work ID), the **archive format** (currently ZIP), and whether to **delete source files after export**. Confirming starts background packaging, auto-opens the "Tasks" panel to show progress, starts download automatically when ready, and the archive can also be downloaded manually from the task list. The archive includes a `manifest.json` recording work metadata and original paths. With "delete after export" checked, source files and download records are removed **only after the archive is created successfully**.
- **Add to collection**: batch-add selected works to a collection.
- **Delete**: batch-delete selected works.

### Deleting Artworks

> ⚠️ Deletion removes the artwork's local image files and derived data (tags, collection links, and thumbnail cache); files are **unrecoverable**. Admin only.

- **Delete a single artwork**: on the detail page, click the red "**Delete**" button and confirm in the dialog.
- **Batch delete**: use batch management to multi-select, then choose "**Delete**" from the "Actions" menu and confirm.

The novel gallery provides the same batch management entry, and the novel reading page provides the same single deletion entry.

**Deletion mark**: deleted works keep a "deletion mark" in the download history — they are invisible to the gallery, filters, statistics, and all browsing entry points, but batch downloads, userscripts, and scheduled tasks with "skip downloaded works" still recognize them as "**downloaded before, but deleted**" and won't re-download them by default. If you actually want to re-download, check "**Allow re-downloading deleted works**" in download settings; a successful re-download clears the deletion mark and the work reappears in the gallery.

---

## Collection Management

Collections are for locally categorizing works (independent of Pixiv bookmarks; only effective within PixivDownloader).

### Create a Collection

Click "**+ New Collection**" at the bottom of the left sidebar and enter a name.

### Custom Settings

Right-click a collection or enter the collection edit page:
- **Rename**
- **Custom icon** (supports PNG / JPG / WEBP, max 1MB)
- **Custom download directory** (downloads are automatically stored here)
- **Delete**

### Add Works to Collection

Hover over a gallery thumbnail and click the "❤️" icon that appears; or enter the detail page and click the "Bookmark to..." button.

---

## Artwork Showcase

Click the "**Artwork Showcase**" button on the detail page to enter `pixiv-showcase.html` — immersive fullscreen browsing of the current work, distraction-free.

---

## Guest Invite

Admins can create invite codes to share gallery viewing access with others (view only — cannot download or modify):

1. Click "**Invite Guests**" at the top-right of the gallery page
2. Set expiration time, content rating (can restrict to SFW range), tag / author allowlist
3. Copy the generated invite link and share

Guests access via `http://host:port/invite?code=xxx` and can only see works within the allowlist scope.

For detailed invite management, see `http://localhost:6999/pixiv-invite-manage.html`.

---

## Statistics Dashboard

Visit `http://localhost:6999/pixiv-stats.html` (admin only):

- Overview cards: total artworks / images / authors / tags count
- Monthly downloads line chart
- Top authors by download count ranking
- Popular tag word cloud (click to jump to gallery filtered by that tag)
