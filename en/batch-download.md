# URL Batch Download

Paste existing work links into **Batch Import Single Artworks** on the download page. Illustrations and novels can share one list; novels require the enabled `novel` plugin. Works restricted to signed-in Pixiv users also require a valid Pixiv cookie.

---

## Quick Start

1. Open `http://localhost:6999/pixiv-batch.html`
2. Switch to the "**🎨 Batch Import Single Artworks**" tab
3. Paste the URL list in the text box
4. Click "**Fetch Info**"
5. Check the detected work types and count, add the works to the queue as required by the current layout, then click **Start Download**
6. Review the queue after completion: completed, already present, filtered out and failed are different results

---

## Input Format

Enter one item per line; formats can be mixed. The IDs below illustrate syntax. Replace them with real works you want to download:

```
# Full URL (most common)
https://www.pixiv.net/artworks/12345678
https://www.pixiv.net/novel/show.php?id=87654321

# Bare numeric ID only (treated as illustration by default)
12345678
12345679 | Artwork Title (title optional; auto-fetched if blank)

# Section header: specify type for subsequent IDs
artwork:
11111
22222
novel:
33333
44444 | This is a novel title

# Compatible with OneTab / N-Tab export formats
https://www.pixiv.net/artworks/55555 | Tab Title
```

### Section Headers

`artwork:` and `novel:` stand on their own line, case-insensitive, half-width or full-width colon accepted.

Bare numeric IDs (`id | title` format) placed below a section header are parsed as that type until the next section header or end of input.

Full URLs (starting with `https://`) are **always parsed by their own URL type**, regardless of which section they fall under.

---

## Extra Filters

Expand the "**Extra Filters**" card to filter the queue before downloading:

| Filter | Description |
|--------|-------------|
| **Content Rating** | All / All Ages / R18+ / R-18 / R-18G |
| **AI Works** | All / Exclude AI / AI only |
| **Tags** | Exact match / Fuzzy match (comma-separated, all must match) |
| **Bookmark Count** | Minimum bookmark count |
| **Page Count** | Illustration page count range |
| **Word Count** | Novel word count range |
| **Work Type** | Illustration / Manga / Ugoira |

Filters are applied **per-work at download time**. Non-matching works show the skip reason in the queue (e.g. "content rating mismatch", "tags do not match").

---

## Download Settings

Expand the "**Download Settings**" card to adjust:

- **Filename template**: supports variables like `{artwork_id}`, `{author_name}`, `{page}`, etc. See [filename template variables](/en/usage-guide?id=filename-template-variables)
- **Download directory (collection)**: download to a specific local collection
- **Skip downloaded works**: when processing the queue, checks download history and skips already-downloaded works directly. When checked, further expands to:
  - **Actual folder detection**: besides checking history, also checks if files actually exist on disk; missing files trigger re-download
  - **Allow re-downloading deleted works**: works deleted through the gallery keep a [deletion mark](/en/gallery?id=deleting-artworks) and are treated as downloaded & skipped by default (reason: "downloaded before, but deleted"); check this to treat them as not downloaded and re-download; the deletion mark is cleared on success
- **Auto-bookmark**: after download, automatically bookmark the artwork via Pixiv API (needs cookie)
- **Novel format**: TXT / HTML / EPUB

---

## OneTab / N-Tab Export Compatibility

After exporting a tab list from the OneTab or N-Tab browser extension, you can paste it directly — the format is `URL | title`. PixivDownloader auto-parses it.

Lines that are not Pixiv artwork links (e.g., other websites) are automatically skipped.

---

## When input produces unexpected results

- A numeric ID is treated as an illustration: use a full novel URL or put `novel:` on its own line before novel IDs.
- Fewer works appear than input lines: check for section headers, blank lines, invalid links or links to other websites. Review the preview before downloading.
- A queue item is skipped: read the reason before changing filters or download-history detection. A deletion mark is not automatically treated as a new work.
- Access or download fails: check that you can still view the work on Pixiv, then check the cookie and backend network. See [FAQ](/en/faq).

## Related Pages

- Download all works by an artist → [User Download](/en/user-download)
- Search and download → [Search](/en/search)
- Set up scheduled tasks for automatic fetching → [Scheduled Tasks](/en/scheduled-tasks)
