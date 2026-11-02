# Artwork gallery

The artwork gallery lets you browse, filter, organize and batch-export illustrations, manga and animations registered in the database. It requires the `gallery` plugin; batch management, export, deletion and collection management require administrator login. The gallery uses database records as its source of truth. Placing images directly into a folder does not register them automatically.

Address: `http://localhost:6999/pixiv-gallery.html`

## Find, filter and sort works

Use the left sidebar to switch views (All images, By author, By manga series), manage local collections and open feature pages. Buttons along its right edge collapse navigation or open the administrator's Task list to show background packaging progress and expiry. The top toolbar provides a gallery-type switcher for illustrations, novels and other enabled gallery plugins, a search box, filters and sorting.

| Search scope | Matches |
| --- | --- |
| Combined | Title + author name, the default |
| Title / Author name / Description | Fuzzy matching on the corresponding text field |
| Work ID / Author ID | Exact numeric ID matching |
| Tags (fuzzy / exact) | Tags containing the keyword or matching the full tag name |

Filters supports content rating (All / All ages / R-18 / R-18G), AI generation (All / Exclude AI / AI only), image format, collection and upload-date range. The range includes its end date and uses the browser's time zone; works with unknown upload times are excluded from date-range results. Tags and authors support three-state combinations:

| State | Rule |
| --- | --- |
| Must include | Intersect multiple Must include conditions |
| Include any | Union multiple Include any conditions |
| Must exclude | Remove matching entries from the results |

Sort ascending or descending by **download time** (newest first by default), **upload time** (unknown times appear last in either direction), **work ID**, **image count**, **status**, **artist ID**, **tag count** or **manga series**.

Click a thumbnail to open `pixiv-artwork.html`, which shows multiple images with previous / next navigation and a lightbox, description, tags, other works by the author, related recommendations and the original-site link. Click Showcase to browse full-screen in `pixiv-showcase.html`.

## Batch management and export (administrator)

Click Batch management at the top to enter multiselect mode:

- **Select page / Clear selection**: affect only the current page.
- **Select all results**: select every work matching the current filters. Selection persists across pages, and you can deselect individual works.

The Actions menu at the bottom offers:

| Action | Result |
| --- | --- |
| Export | Choose the directory structure (by author / work, or separate work-ID directories), format (ZIP) and whether to Delete source files after export; generate an archive containing `manifest.json` in the background |
| Add to collection | Add selected works to a specified local collection in bulk |
| Delete | Delete selected works in bulk |

Starting an export automatically opens the sidebar Task list to show progress. Once packaging finishes, the browser download starts automatically; you can also download manually from the task list before expiry. Tasks are cleaned up when they expire or the service restarts.

!> With Delete after export enabled, the server deletes source files and download records as soon as it successfully creates the archive. It does not wait for the browser download to finish or for someone to inspect the archive.

## Delete works

Administrators can click the red Delete button on a work's detail page or use Batch management → Actions → Delete. For works managed by this app, deletion removes local media and derived data (tags, collection associations and thumbnails), and cannot be undone in the interface. Read-only source files registered through [import external downloads](/en/pixiv-batch-downloader-import) are retained. The novel gallery and reader offer the same deletion and batch-management entries.

**Deletion markers**: deleted works retain a marker in download history but are hidden from the gallery, filters and statistics. When Skip downloaded works is enabled in batch downloads, userscripts or scheduled tasks, they are recognized as previously downloaded but deleted, and skipped by default. To download them again, enable Allow deleted works to be downloaded again in download settings. A successful download clears the marker automatically.

## Collections and guest invitations

Local collections are independent of online Pixiv bookmarks. Click + New collection at the bottom of the left sidebar to create one. Right-click a collection or open its edit page to rename it, upload a custom icon (PNG / JPG / WebP, up to 1 MB), bind a separate download directory or delete it. Hover over a thumbnail and click its heart icon, or click Add to collection on the detail page, to assign a work to a collection.

Administrators can click Invite guests at the top right of the gallery, set an expiry, content rating such as SFW only, and tag / author allowlists, then generate a read-only sharing link in the form `http://host:port/invite?code=xxx`. Manage invitation codes at `http://localhost:6999/pixiv-invite-manage.html`. Guests cannot modify content or use batch management, but they may still save images already displayed in their browser.

## Missing works and the statistics dashboard

If expected works are missing, clear search and filters first, especially the upload-date range and three-state tags. Check the invitation allowlist, connected database, download directory and deletion markers. Moving files on disk alone does not update another database; see [storage and backup](/en/storage) for relocation and recovery.

Administrators can open `http://localhost:6999/pixiv-stats.html` with the `gallery-tools` plugin enabled to view total work and image counts, a monthly download line chart, top-author rankings and a popular-tag word cloud. Click an author or tag to open the corresponding gallery filter.
