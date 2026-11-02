# Artist batch download

Enter an artist profile link or numeric ID in User mode on the download page to preview their illustrations, manga, animations, novels or published commission results, then download all or selected works. What can actually be fetched depends on the current account's permissions, additional filters and downloaded-work checks.

## Steps

1. Open `http://localhost:6999/pixiv-batch.html` and switch to User mode.
2. Paste an artist profile link or numeric user ID.
3. Select the download scope: Illustrations + Manga + Animations, Novels or Requests. Novels require the `novel` plugin.
4. Click Parse and preview, then configure additional filters as needed.
5. Click Queue all, or select particular works to queue, then click Start download.
6. After downloading, inspect successful, skipped and failed entries in the queue.

The following formats are supported. `123456` is an example; replace it with the actual artist ID:

```text
https://www.pixiv.net/users/123456
https://www.pixiv.net/users/123456/artworks
https://www.pixiv.net/users/123456/illustrations
https://www.pixiv.net/users/123456/novels
https://www.pixiv.net/users/123456/request/artworks
https://www.pixiv.net/en/users/123456
123456
```

## Download scope and additional filters

| Scope | Content |
| --- | --- |
| Illustrations + Manga + Animations | Image works posted by the artist |
| Novels | Novels posted by the artist |
| Requests | Completed, publicly available commission (リクエスト) results, previewed, queued, downloaded and checked for duplicates through the ordinary illustration flow |

Pasting a request-page link (`/users/{id}/request/artworks`) automatically selects Requests. The preview displays one scope at a time. Switching scopes clears the current preview list but leaves the download queue intact; you can queue illustrations, switch to novels and queue them, then download both together.

Additional filters supports content rating, excluding AI, minimum bookmark count, tags and page count, and checks each work during both preview and actual download. For artists with many works, combine these filters with a lower Maximum concurrency and a Work interval in milliseconds to reduce the risk of triggering upstream limits.

## Save as a scheduled task

Enter a task name and trigger in the Save as scheduled task card at the bottom of the page to discover and download the artist's new works periodically. Bind a valid cookie to the task for content that requires login. See [scheduled tasks](/en/scheduled-tasks) for configuration and incremental watermark rules.

## Expected works are missing

First confirm that you entered the artist's user ID rather than an individual work ID, then check the selected scope (Images / Novels / Requests), additional filters and cookie login state. Clicking Queue all does not make deleted or inaccessible works downloadable.
