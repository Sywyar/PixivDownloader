# Search Mode

Enter keywords in **Search Mode** to search Pixiv through the backend. Check the results and filters before adding selected works to the queue. This searches Pixiv, not your local gallery.

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

Popular sorting depends on your Pixiv account permissions. Use Newest when your account lacks access; do not assume the returned results form a complete popularity ranking.

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
- Minimum bookmark count
- Tag filtering
- Page count range

---

## Check search and download results

Start with one keyword and a small page range before expanding the search. For empty results, check the keyword, content rating, account permissions and extra filters. For errors, retain the message and check the cookie and backend proxy. A queue item skipped by a filter does not mean the search failed.

## Save as Scheduled Task

If you want to periodically download new works under a certain keyword, save the current search configuration as a scheduled task:

Scroll down to the "⏰ Save as Scheduled Task" card → set trigger frequency → create.

The task will search and download newly published works on the set schedule. Tasks using "Newest" sort + page count `-1` also use watermark-based incremental discovery to avoid re-downloading historical content each time.

See [Scheduled Tasks](/en/scheduled-tasks) for details.
