# Artist Batch Download

"User Mode" lets you enter an artist ID or profile URL to download all of their works (illustrations/manga/ugoira or novels) in one click.

---

## Usage Steps

1. Open `http://localhost:6999/pixiv-batch.html`
2. Switch to the "**👤 User Mode**" tab
3. Paste an artist profile URL or numeric ID
4. Select the download scope (illustrations or novels)
5. Click "**Parse & Preview**"
6. Configure extra filters (optional)
7. Click "**Add All to Queue**" or manually select some works to add
8. Start download

---

## Supported Link Formats

Paste any of the following — the system auto-extracts the numeric ID:

```
https://www.pixiv.net/users/123456
https://www.pixiv.net/users/123456/artworks
https://www.pixiv.net/users/123456/illustrations
https://www.pixiv.net/users/123456/novels
https://www.pixiv.net/users/123456/request/artworks   ← commission page link (auto-switches to "Commission" scope)
https://www.pixiv.net/en/users/123456
123456                                               ← bare numeric ID works too
```

---

## Download Scope

| Scope | Includes |
|-------|---------|
| **Illustrations + Manga + Ugoira** | All of the artist's image-type works |
| **Novels** | All of the artist's novels |
| **Commission Works** | The artist's **completed, public commissions (リクエスト)** — deliverables are ordinary illustrations, handled through the illustration preview / queue / download / dedup pipeline |

The preview area **fetches only one type at a time** — switching types clears the old preview results. But the download queue is unaffected: you can add illustrations to the queue first, then switch to novels, fetch again, and add them — then download everything together.

> Pasting a commission page link (`https://www.pixiv.net/users/{id}/request/artworks`) **auto-switches to the "Commission" scope**, no manual selection needed.

---

## Extra Filters

Expand the "Extra Filters" card to narrow the scope, for example:
- Only download the artist's R-18 works
- Exclude AI-generated works
- Only download works with 100+ bookmarks

Filters apply during both preview and the **actual download**, where each work is filtered individually — non-matching works are automatically skipped.

---

## Save as Scheduled Task

If you want to **automatically pick up new works** from this artist on a recurring basis, save the current configuration as a scheduled task:

Scroll down to the "⏰ Save as Scheduled Task" card → enter a task name → set trigger frequency → click "Create Task."

Once created, the backend will automatically discover and download the artist's new works at the set interval — fully hands-off.

See [Scheduled Tasks](/en/scheduled-tasks) for details.

---

## Data Volume Tips

An artist may have hundreds or even thousands of works, so the first download may take a long time. You can:
- Use extra filters to narrow the scope
- Lower "Max concurrent" to reduce pressure on Pixiv's servers
- Set "Artwork interval" (milliseconds) to wait between works
