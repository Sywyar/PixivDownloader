# Quick Fetch

"Quick Fetch" is the first tab shown by default when opening `pixiv-batch.html`. Using your saved Pixiv cookie it auto-detects the current account and one-clicks your account-related data into the download queue.

?> This feature requires a saved cookie containing `PHPSESSID`. Without one the buttons show as disabled with a hint.

---

## Available Data Sources

| Data Source | Description |
|-------------|-------------|
| **My Bookmarks** | Illustrations/manga + novels, including private bookmarks |
| **My Works** | Your own submitted illustrations/manga + novels, including private ones |
| **My Commission Works** | Your completed, public commission (リクエスト) deliverables (handled as illustrations) |
| **My Follows** | All artists you follow, including private follows; supports live filtering by username / user ID |
| **New Works by Followed Users** | Latest illustrations/manga/ugoira from all artists you follow (Pixiv "フォロー新着"); paginated |
| **My Collections** | Works in your Pixiv collections (コレクション) |

---

## Usage Steps

1. Open `http://localhost:6999/pixiv-batch.html`
2. Confirm the page opens on the "**⚡ Quick Fetch**" tab by default
3. Click the corresponding button ("My Bookmarks", "My Works", etc.)
4. Wait for data to load; the work list appears below
5. Click "**Add All to Queue**" or check individual works then "**Add Selected to Queue**"
6. Once the queue appears, click "**Start Download**"

---

## Followed Users — Second-Level Expansion

Click a followed user in the list to expand their work preview below (illustrations/novels toggleable).

You can:
- Click the "Add to Queue" button next to a single image to add one work
- Click "Add Page to Queue" to add all works on the current page
- Navigate pages and continue adding

---

## Collections

Click a collection to expand its mixed illustrations + novels list, operated the same way as the followed users list.

---

## Extra Filters (Real-Time Preview Filtering)

After expanding any work list, the "**Extra Filters**" card above allows real-time filtering of the current preview page by content rating / AI / tags / work type / page count / word count / bookmark count:

- "Add Page to Queue" adds only the **filtered** works;
- "Add All to Queue" will still add everything — non-matching works are individually skipped during the actual download with the reason noted.

When no specific work list is expanded (e.g., staying on the followed user / collection selection page), the card prompts you to expand a work list first.

---

## Save as Scheduled Task (Admin)

**Every source** in Quick Fetch can be saved as a background [Scheduled Task](/en/scheduled-tasks) for automatic download:

| Source | Scheduled Task Effect |
|--------|----------------------|
| My Bookmarks (illustrations/novels × public/private) | Periodically download newly added bookmarks |
| My Works | Periodically download your own new submissions |
| My Commission Works | Periodically download your own newly completed commission deliverables |
| New Works by Followed Users | Periodically download new works from followed artists (フォロー新着) |
| Click into a followed user | Periodically download that artist's new works |
| Click into a collection | Periodically download newly added illustrations and novels in that collection |

After expanding a source, the "**⏰ Save as Scheduled Task**" card at the bottom of the page shows the source description. Fill in the name and trigger method to create the task; if no source is expanded, it will prompt you to expand one first.

?> Bookmarks / followed users' new works / collections are account-private content; the corresponding tasks must have a cookie containing `PHPSESSID` authorized to run (in Solo mode new tasks are auto-bound). When editing these types of tasks, the source is read-only — to change the source, delete and recreate.

---

## Related Features

| Need | Recommended Entry |
|------|------------------|
| Download works by specific URLs | [Batch Download](/en/batch-download) |
| Download all works by an artist | [User Download](/en/user-download) |
| Search by keyword then download | [Search](/en/search) |
