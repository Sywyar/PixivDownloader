# Search download

Enter keywords in Search mode on the download page. The backend calls Pixiv's search API, and you can filter the results before adding them to the download queue. This searches online Pixiv works, not the local gallery.

!> Search mode requires a saved, valid Pixiv cookie containing `PHPSESSID`; the search button is unavailable without it.

## Steps

1. Open `http://localhost:6999/pixiv-batch.html` and switch to Search mode.
2. Enter search keywords in Chinese, Japanese or English.
3. Choose a submode, sort order and content rating, then click Search.
4. Select works in the preview grid and click Queue selected, or click Queue current page.
5. Click Start download and check the results in the queue.

## Search scope, sorting and filters

| Submode | Fetch scope |
| --- | --- |
| Search mode | Fetch only page 1, suitable for a quick keyword check |
| Batch work fetch mode | Fetch through the specified last page, defaulting to 3 pages. Set the last page to `-1` to keep paging through all results, about 10 seconds between pages, up to 1,000 pages |

| Sort order | Description |
| --- | --- |
| Newest (`date_d`) | Descending publication time; available to all signed-in accounts |
| Popular (`popular_d`) | Sort by popularity; requires Pixiv Premium. Without permission, use Newest instead and do not treat the returned results as a complete popularity ranking |

Content rating can be All ages, R-18, R-18G or All, subject to the corresponding age settings on your Pixiv account. Once results load, Additional filters can filter the preview page live by excluding AI, minimum bookmark count, tags and page-count range.

## Check results and schedule tasks

Start with one keyword and a small number of pages to check the result, then expand the page range. If results are empty, check keywords, rating, account permissions and additional filters. If searching fails, check whether the cookie has expired and whether the backend proxy connects. A filter skip in the queue does not mean the search request failed.

To save the current search as a scheduled task, set the trigger frequency in the Save as scheduled task card at the bottom of the page. Tasks using Newest sorting with the last page set to `-1` support incremental fetching with a watermark. See [scheduled tasks](/en/scheduled-tasks) for other combinations and limits.
