# Scheduled Tasks

Scheduled tasks let the backend **automatically discover and download** new works on a set interval — no manual operation needed each time.

**Typical use cases:**
- Following an artist and wanting to auto-download all their future new works
- Periodically fetching the latest search results for a keyword
- Automatically tracking updates to a manga / novel series
- Regularly syncing your bookmarks / collections / followed users' new works (created from "[Quick Fetch](/en/quick-access)")

?> Scheduled tasks are admin-only. If you're a guest, ask the admin to set them up.

---

## Prerequisites

1. **Logged in as admin**
2. **A cookie containing `PHPSESSID` is configured** (some content requires login state)

!> Tasks without a bound cookie run in "restricted mode", downloading only all-ages public content and unable to access your private follows / bookmarks. To download R-18 content or your full follows list, configure a cookie first.

---

## Creating Your First Scheduled Task

Scheduled tasks are **not created from a standalone form** — they snapshot your current download configuration as a task. Here's how:

### Example: Following an Artist

**Step 1: Enter Artist Download Mode**

In `pixiv-batch.html`, switch to the "**👤 User Mode**" tab.

**Step 2: Enter the Artist Source**

Paste an artist profile URL in the input, e.g.:
```
https://www.pixiv.net/users/123456
```
Or just the numeric ID (`123456`).

**Step 3: Configure Download Settings (Optional)**

Expand the "Download Settings" card and adjust as needed:
- **Filename template**: default `{artwork_id}_p{page}`
- **Collection**: which collection to download into
- **Max concurrent**: how many works to download in parallel (default follows global setting)
- **Artwork interval**: milliseconds to wait between works (suggested 500–2000, to avoid excessive requests to Pixiv)
- **Allow re-downloading deleted works**: works deleted through the gallery keep a [deletion mark](/en/gallery?id=deleting-artworks); by default (unchecked) scheduled tasks treat them as downloaded and skip; check to treat as not-downloaded and auto re-download each round. This setting is snapshotted with "Save as scheduled task"; tasks created by older versions get it backfilled as unchecked on first startup after upgrading

**Step 4: Configure Extra Filters (Optional)**

Expand the "**Extra Filters**" card and set as needed:
- Content rating: all-ages only, or include R-18?
- Exclude AI-generated works?
- Limit to specific tags?

**Step 5: Save as Scheduled Task**

Scroll to the bottom of the page and find the "**⏰ Save as Scheduled Task**" card:

1. Enter a **task name** (to tell tasks apart, e.g. "Artist Sywyar Daily Update")
2. Choose a **trigger**:
   - **Fixed interval**: e.g. every `60` minutes
   - **Cron expression**: precise schedule, e.g. daily at 3 AM → `0 0 3 * * *`
3. (Optional) Set a **first-run fetch limit**: cap how many works the first run fetches to avoid downloading the entire history at once (see "[First-Run Fetch Limit](#first-run-fetch-limit)" below); default `0` = full
4. (Optional) Check "**Use a dedicated proxy**" / "**Use a dedicated cookie**": assign this task its own HTTP proxy (`host:port`, e.g. `127.0.0.1:7890`) and cookie — the cookie input has a "**Use currently saved cookie**" shortcut button on the right; unchecked means using global proxy settings / auto-bound cookie respectively (see "[Dedicated Proxy/Cookie](#dedicated-proxycookie)" below)
5. Click "**Create Task**"

?> After successful creation, the page shows a notification. Switch to the "⏰ Scheduled" tab to see the newly created task.

---

## Cron Expression Format

PixivDownloader uses Spring's **6-field Cron format** (one more "seconds" field than the common 5-field Unix cron):

```
sec  min  hour  day  month  weekday
0    0    3     *    *      *   → daily at 03:00:00
0    30   8     *    *      1   → every Monday 08:30:00
0    0    */6   *    *      *   → every 6 hours
0    0    9     1    *      *   → 1st of each month 09:00
```

| Field | Range | Special Values |
|-------|-------|---------------|
| sec   | 0–59 | `*`=every second, `/n`=every n seconds |
| min   | 0–59 | `*`=every minute, `/n`=every n minutes |
| hour  | 0–23 | `*`=every hour, `/n`=every n hours |
| day   | 1–31 | `*`=every day |
| month | 1–12 | `*`=every month |
| weekday | 0–7 | 0/7=Sunday, `*`=every day |

---

## How Source Types Work

> Beyond User / Search / Series, sources like "Bookmarks / My Works / Followed Users' New Works / Collections" are created directly from the "⏰ Save as Scheduled Task" card at the bottom after expanding the corresponding source in the "[Quick Fetch](/en/quick-access)" tab.

### Artist New Works (User Mode)

- **First round**: downloads all of the artist's works matching filter criteria that haven't been downloaded yet (potentially many)
- **Subsequent rounds**: uses "watermark-based incremental discovery" — each round only scans down to the newest work processed in the previous round and stops there, picking up only the newly posted works without rescanning the entire history
- Effect: the first round may download hundreds, but subsequent rounds only download the few new ones

### Artist Commission Works (User Mode "Commission" scope / Quick Fetch "My Commission Works")

- Each round lists the artist's **completed, public commissions (リクエスト)** and downloads the not-yet-downloaded ones
- Deliverables are ordinary illustrations, handled through the illustration download / dedup / naming pipeline
- **No watermark used** (commission ordering is not reliably monotonic) — performs full discovery + skip-already-downloaded; the first-run fetch limit acts as a **per-run cap**

### Search Keywords (Search Mode)

- Fetches the latest N pages of search results per configuration
- With "Newest" sort and page count `-1`, also uses watermark-based incremental discovery — each round only picks up the newest
- Effect: periodically pulls new works under that keyword

### Manga / Novel Series (Series Mode)

- Each round lists all chapters in the series, skips already-downloaded ones, and fills in new chapters
- Ideal for tracking ongoing manga or novel series

### My Bookmarks / My Works / Followed Users' New Works / Collections (created from "Quick Fetch")

After expanding the corresponding source in the "⚡ Quick Fetch" tab (bookmarks / my works / followed users' new works, or clicking into a followed user / collection), the "⏰ Save as Scheduled Task" card at the bottom of the page can create the task:

- **My Bookmarks**: periodically discovers undownloaded works in bookmarks (public / private, illustrations / novels) and fills them in
- **My Works**: equivalent to "Artist New Works" for your own account
- **New Works by Followed Users**: periodically downloads the latest illustrations / manga / ugoira from followed artists
- **Collections**: lists illustrations and novels in the collection, skips already-downloaded ones and fills in (**both illustrations and novels are downloaded**)

!> These source types are **account-private**; their tasks must have a cookie containing `PHPSESSID` bound (see "[Dedicated Proxy/Cookie](#dedicated-proxycookie)" below), otherwise they'll be marked as "login expired" and suspended (won't run empty in restricted mode). They have no standalone mode tab page, and the **source is read-only when editing** — to change the source, delete and recreate.

---

## First-Run Fetch Limit

For sources that could pull a huge backlog at once, you can fill in a **first-run fetch limit** in the "⏰ Save as Scheduled Task" card to cap how many works are fetched, reducing the risk of triggering Pixiv's "over-access" warning. `0` means full fetch (no cap).

Behavior splits by whether the source has a reliable "newest" ordering (the on-page hint auto-shows only the one matching the current source):

- **First-run cap** (artist new works / followed users' latest / "newest sort + end page -1" search): **only the first run** fetches at most the newest N works; the watermark then advances to the newest, and later rounds only pick up newly posted works incrementally — older backlog is not back-filled. Use when you only want the most recent N and then keep tracking new posts.
- **Per-run cap** (my bookmarks / collections / artist commission works / non-newest "end page -1" search): these sources have no reliable "newest" ordering, so the limit acts as a **per-run cap** — each run fetches at most N new works, draining the backlog over successive runs. **Set the limit to `0` if you want it to eventually fetch every work in full** (no per-run cap).

Series and fixed-page search are inherently bounded, so the field is hidden for them.

!> Setting the limit to `0` (full) means the first run may pull the source's entire backlog at once; for large sources this can still trigger an over-access warning, so the page asks you to confirm on save.

---

## Managing Existing Tasks

Switch to the "**⏰ Scheduled**" tab to see all tasks.

Each task card has a **status light** in the top-right corner:

| Color | Status | Meaning |
|-------|--------|---------|
| 🔘 Gray | Waiting / Disabled / Paused | Task is normally idle, or manually paused |
| 🟢 Green | Running / Last success | Task is normal |
| 🟡 Yellow | Queued | Multiple tasks due at once, queued serially |
| 🔴 Red | Failed / Cookie expired / Interrupted / Overuse paused | Task needs attention; click the card for details |

### Common Actions

| Action | Description |
|--------|-------------|
| **Run Now** | Execute once immediately without waiting for the timer |
| **Dedicated Proxy/Cookie** | Open a dialog to bind / replace / clear the task's independent HTTP proxy and cookie |
| **Enable / Disable** | Temporarily stop a task's timed trigger |
| **Manual Pause / Resume** | Similar to "Disable", but visually differentiated on the status light |
| **View Snapshot** | View the source and filter configuration captured when the task was created |
| **Edit** | Modify the task configuration (re-runs the creation flow) |
| **Pending Retries** | View works in this task that have failed and are awaiting retry |
| **Delete** | Completely delete the task (already-downloaded files are unaffected) |

---

## Dedicated Proxy/Cookie

?> If you had a usable cookie when **creating** a task in Solo mode and didn't check "Use a dedicated cookie," the task is automatically bound to the current cookie — no manual action needed.

The "**Dedicated Proxy/Cookie**" button on each task card opens a dialog with two checkboxes:

- **Use a dedicated proxy**: enter `host:port` (e.g. `127.0.0.1:7890`). When set, **all** of this task's Pixiv access during each run (work discovery, metadata, image / novel downloads, inbox overuse check) goes through this HTTP proxy; unchecked uses the global proxy settings. Useful for routing different tasks through different exits to spread the request sources.
- **Use a dedicated cookie**: paste a cookie containing `PHPSESSID`, or click "**Use currently saved cookie**" on the right of the input to quickly fill in the one from the top Cookie card. Useful for running a task under a different Pixiv account. For security, a bound cookie is never echoed back — leaving it empty when already bound means keep unchanged, filling it in means replace.

Unchecking an active checkbox and saving **clears** the corresponding setting (a confirmation dialog first explains the consequences): proxy falls back to global; unbinding the cookie switches the task to "restricted mode" (anonymous run, R-18 and other restricted content not visible; tasks like "My Bookmarks / Followed Users' New Works / Collections" cannot run).

The "**⏰ Save as Scheduled Task**" card has the same two checkboxes, so you can set them when creating / editing the task.

If a task shows "restricted mode", it means no cookie is bound yet: click "**Dedicated Proxy/Cookie**" on the task card, check "Use a dedicated cookie", fill in (or quick-fill the currently saved) cookie, and save.

When a cookie expires (Pixiv re-login or password change):
- The task automatically stops and is marked "login expired" (red light)
- A notification (email / push, requires the corresponding channel configured) is sent
- After obtaining a valid cookie, re-bind it in the "Dedicated Proxy/Cookie" dialog to resume (submitting the exact same expired cookie is rejected)

---

## Overuse Pause

When Pixiv detects your account has been making too many requests, it sends an "overuse" inbox warning. PixivDownloader automatically detects this and pauses **all scheduled tasks for that account** to avoid getting banned.

At that point, a banner appears at the top of the task management tab with two recovery options:

1. **"Ignore the risk, resume immediately"** — mark this warning as explicitly acknowledged and continue (still risky)
2. **"Continue after N minutes"** — cool down for a while before resuming (minimum 60 minutes, default 60 minutes)

Option 2 is recommended — wait some time before continuing.

---

## Notifications (Email / Push)

Scheduled tasks send notifications in the following situations, with email and any configured push channels (Bark / DingTalk / Telegram / Feishu / WeCom / PushPlus / ServerChan / Webhook) delivered **in parallel**:
- Cookie expired (login expired)
- Overuse warning detected
- Circuit-breaker suspension (consecutive failures reached threshold)
- Failed work retries exhausted

Notifications include key details for quick identification:
- **Overuse pause**: lists each suspended task's name and ID, affected task count, warning time
- **Auth expired / Circuit-breaker**: task name and ID, task type, trigger method (every N minutes / Cron), next scheduled run time
- **Failed work retries exhausted**: includes the above plus the work's direct Pixiv link for easy checking

Notification bodies never contain cookies / passwords or any credentials.

!> Email notifications require SMTP account configuration in GUI "Config → Mail / SMTP" first; push notifications require the corresponding channel configured in "Config → Push." Otherwise, notifications for that channel are silently skipped. Both config pages provide test buttons to preview each scenario's actual appearance.

---

## Related Configuration

Scheduled task-related settings are under GUI "Config → Scheduled Tasks" or `schedule.*` in `config.yaml`:

| Config Item | Description | Default |
|-------------|-------------|---------|
| `schedule.enabled` | Master toggle | `true` |
| `schedule.tick-interval-ms` | Interval for checking due tasks (ms), **requires restart** to change | `60000` |
| `schedule.max-tasks` | Maximum number of tasks that can be created | `100` |
| `schedule.inbox-check-every` | Check the overuse inbox every N successfully downloaded works | `500` |
| `schedule.auth-failure-circuit-breaker` | Consecutive fetch failures triggering circuit-breaker suspension | `5` |
| `schedule.pending-max-attempts` | Maximum automatic retry attempts for failed works | `5` |
| `schedule.overuse-defer-default-minutes` | Default minutes for "continue later" after overuse pause (min 60) | `60` |

See [Configuration Reference](/en/configuration) for detailed config descriptions.
