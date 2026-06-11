# Search Mode

"Search Mode" has a built-in Pixiv search proxy. Search by keyword, preview thumbnails, then add to the download queue — no need to open the Pixiv website.

!> Search Mode **requires a saved Pixiv cookie** (containing `PHPSESSID`). Without one, the search button is unavailable.

---

## Usage Steps

1. Open `http://localhost:6999/pixiv-batch.html`
2. Switch to the "**🔍 Search Mode**" tab
3. Enter keywords in the search box (Chinese / Japanese / English all work)
4. Choose sort order and content rating
5. Click "**Search**"
6. Browse thumbnail previews, check the works you want to download
7. Click "**Add Selected to Queue**" or "**Add Current Page All to Queue**"
8. Start download

---

## Search Submodes

Two submodes at the top:

| Submode | Description |
|---------|-------------|
| **🔍 Search** | Only shows the first page of results; good for quick browsing |
| **📦 Batch Fetch** | Set an end page (default 3 pages); fetches multiple pages at once |

In "Batch Fetch" mode, setting the "End page" to `-1` means "keep paging back through all results, fetching everything" (with about 10 seconds between pages to avoid excessive requests).

---

## Sort Options

| Sort | Description | Premium Required |
|------|-------------|:---:|
| **Newest** (date_d) | Sort by publication time, descending | No |
| **Popular** (popular_d) | Sort by popularity | **Yes** |

!> "Popular" sorting requires a Pixiv Premium member account's cookie; otherwise, the results are identical to "Newest."

---

## Content Rating

| Rating | Description |
|--------|-------------|
| **All Ages** | Only non-R-18 content |
| **R-18** | R-18 only |
| **R-18G** | R-18G only |
| **All** | Mixed display (requires Pixiv account age verification) |

---

## Extra Filters

The "Extra Filters" card **live-filters the preview page** after search results load, so you can quickly find matching works without paging through many results:

- Exclude AI-generated
- Minimum bookmark count (show only high-quality works)
- Tag filtering
- Page count range

---

## Save as Scheduled Task

If you want to periodically download new works under a certain keyword, save the current search configuration as a scheduled task:

Scroll down to the "⏰ Save as Scheduled Task" card → set trigger frequency → create.

The task will search and download newly published works on the set schedule. Tasks using "Newest" sort + page count `-1` also use watermark-based incremental discovery to avoid re-downloading historical content each time.

See [Scheduled Tasks](/en/scheduled-tasks) for details.
