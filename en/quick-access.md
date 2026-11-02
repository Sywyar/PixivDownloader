# Quick Fetch

Quick Fetch on the download page uses your saved Pixiv cookie to retrieve the current account's bookmarks, posts, commission results, followed artists, new works by followed artists and collections. Load a preview first, then queue the works and start downloading; fetching a list alone does not save files.

!> Quick Fetch requires a saved cookie containing a valid `PHPSESSID`. All entry buttons stay disabled until one is saved.

## Supported sources and steps

| Source | Fetch scope |
| --- | --- |
| My bookmarks | Illustration / manga and novel bookmarks, both public and private |
| My works | Illustrations / manga and novels posted by the current account, including private works |
| My request works | Completed, public commission (リクエスト) results for the current account, handled through the illustration flow |
| My following | Followed artists, including private follows, with live filtering by username or ID |
| New works by followed users | The latest illustrations / manga / animations by followed artists (フォロー新着作品), browsable by page |
| My collections | Mixed lists of illustrations and novels in Pixiv collections (コレクション) |

1. Open `http://localhost:6999/pixiv-batch.html` and switch to Quick Fetch.
2. Click the desired source button and wait for its work list to appear below.
3. In My following or My collections, click an artist or collection to expand its work preview. For a followed artist, you can switch between illustrations and novels.
4. Queue individual works, choose Queue page or Queue all, then click Start download.

## Additional filters

After expanding a specific work list, use Additional filters to filter the current preview page live by content rating, AI works, tags, work type, page count, word count and minimum bookmark count:

- **Queue page**: queue only works visible on the current page after filtering.
- **Queue all**: queue every work in the source, then check each work against the filters during download. Nonmatching works show a skip reason in the queue.

While viewing the followed-artist list or collection list, open a specific artist or collection before filtering or queuing works.

## Save as a scheduled task (administrator)

After expanding a specific source (bookmarks, your works, your request works, new works by followed users, or a particular followed artist or collection), enter a name and trigger in the Save as scheduled task card at the bottom of the page to save it as an automatic background download task.

?> Bookmarks, new works by followed users and collections are private account sources. Their tasks must have a valid cookie bound to them; new tasks in solo mode bind the current cookie automatically. The source type is read-only after creation. To change it, delete and recreate the task; see [scheduled tasks](/en/scheduled-tasks).

## Check results and troubleshoot

After downloading, check completed, skipped and failed queue entries separately, then confirm the works in the gallery. If the preview is empty, check the account, public / private scope and additional filters first. If buttons are disabled or authentication errors appear, save a valid cookie again. For network errors, check the backend proxy connection; do not treat an empty list as proof that synchronization is complete.

See [URL batch download](/en/batch-download) for specific links, [artist batch download](/en/user-download) for artist IDs, or [search download](/en/search) for keywords.
