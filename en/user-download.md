# Artist Batch Download

Enter an artist profile URL or ID in **User Mode** to preview illustrations, manga, animations, novels or completed public commissions. Download all listed works or a selection. Account access, filters and download-history checks still determine which works can be downloaded.

---

## Usage Steps

1. Open `http://localhost:6999/pixiv-batch.html`
2. Switch to the "**👤 User Mode**" tab
3. Paste an artist profile URL or numeric ID
4. Select images, novels or commissions; novels require the enabled `novel` plugin
5. Click "**Parse & Preview**"
6. Configure extra filters (optional)
7. Click "**Add All to Queue**" or manually select some works to add
8. Start downloading, then check successful, skipped and failed entries

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

While the backend is running, the task discovers and downloads new works on its schedule. Bind a valid cookie for content that requires login, and check the task status when authentication or access fails.

See [Scheduled Tasks](/en/scheduled-tasks) for details.

---

## When expected works are missing

Check that the ID belongs to an artist profile rather than an individual work. Then check the image/novel/commission scope, filters and cookie. Changing scope clears the preview, but keeps works already queued. **Add All to Queue** cannot make deleted or inaccessible works downloadable.

## Data Volume Tips

An artist may have hundreds or even thousands of works, so the first download may take a long time. You can:
- Use extra filters to narrow the scope
- Lower "Max concurrent" to reduce pressure on Pixiv's servers
- Set "Artwork interval" (milliseconds) to wait between works
