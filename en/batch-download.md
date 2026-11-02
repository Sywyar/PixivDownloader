# URL batch download

If you have a list of work links or IDs, paste them into Batch import individual works on the download page to download them together. Input can mix illustrations, manga, animations and novels. Novels require the `novel` plugin; restricted works require a saved Pixiv cookie containing `PHPSESSID`.

## Steps

1. Open `http://localhost:6999/pixiv-batch.html` and switch to Batch import individual works.
2. Paste the URL or ID list into the text box, one entry per line, and click Fetch information.
3. Check the recognized work types and count.
4. Set Additional filters and Download settings as needed, add the works to the queue and click Start download.
5. When downloading finishes, check the queue results. Completed works, existing-work skips, filter skips and failures are counted separately.

## Input format

Enter one record per line. The work IDs below are format examples; replace them with actual works:

```text
# Full URLs
https://www.pixiv.net/artworks/12345678
https://www.pixiv.net/novel/show.php?id=87654321

# Numeric IDs (treated as illustrations by default; an optional title may follow)
12345678
12345679 | 作品标题

# Section headers: specify the work type for subsequent numeric IDs
artwork:
11111
22222
novel:
33333
44444 | 小说标题

# OneTab / N-Tab export format
https://www.pixiv.net/artworks/55555 | 标签页标题
```

- Put `artwork:` or `novel:` on its own line. Headers are case-insensitive and accept half-width or full-width colons. Subsequent numeric IDs, including `id | title` entries, are parsed as that type until the next header or the end of input.
- Full links beginning with `https://` are always parsed according to their own link type, regardless of section headers.
- You can paste a `URL | title` list exported from OneTab / N-Tab directly. Links that are not Pixiv work links are skipped automatically.

## Additional filters and download settings

Additional filters supports content rating (All / All ages / R18+ / R-18 / R-18G), AI works (All / Exclude AI / AI only), tags (comma-separated, exact or fuzzy matching, with all tags required), minimum bookmark count, illustration page count, novel word count and work type (Illustration / Manga / Animation). Each work is checked when downloading; works that do not match show a specific skip reason in the queue.

| Download setting | Effect |
| --- | --- |
| Filename template | Use variables such as `{artwork_id}`, `{author_name}` and `{page}`; see [filename template variables](/en/usage-guide#filename-template-variables) |
| Download directory (collection) | Select the local collection to save into |
| Skip downloaded works | Skip immediately when the work is found in download history |
| Check actual directory | Check whether files exist on disk as well as checking history; download again if they are missing |
| Allow deleted works to be downloaded again | Works deleted from the gallery retain a [deletion marker](/en/gallery#delete-works) and are skipped by default; enable this to download them again and clear the marker |
| Auto-bookmark | Bookmark the work through the Pixiv API after downloading; requires a valid cookie |
| Novel format | Save as TXT, HTML or EPUB |

## Troubleshooting and related guides

- **A numeric novel ID is treated as an illustration**: use the full novel URL, or add `novel:` on a separate line above the numeric IDs.
- **Fewer works are recognized than the number of input lines**: check for blank lines, section headers, non-Pixiv links or malformed URLs. Use the preview list as the result.
- **Works are skipped or downloads fail**: inspect the specific reason on the queue entry, confirm that the work is accessible on Pixiv itself, and check the cookie and proxy settings (see [FAQ](/en/faq)).

See [artist batch download](/en/user-download) for a particular artist, [search download](/en/search) for keywords, or [scheduled tasks](/en/scheduled-tasks) for automatic downloads on a schedule.
