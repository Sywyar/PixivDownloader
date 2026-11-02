# Scheduled tasks

Scheduled tasks automatically **discover and download** new works at fixed intervals or Cron times while the backend is running. Each task saves a snapshot of its source, filters and download settings when created; later changes on the download page do not affect existing tasks. Closing the browser does not stop execution, but stopping the backend does.

The application host provides the scheduler and management API. Disabling `download-workbench` removes its download pages, Pixiv sources and illustration executor. Tasks that depend on these capabilities retain their data and progress and are suspended; tasks fully supported by other plugins continue running. Once the required capabilities return, the scheduler automatically rechecks and resumes tasks suspended because a capability was missing. Manual pauses, invalid credentials and policy suspensions still require explicit action.

## Prerequisites

1. Sign in with an administrator account (unavailable to guests).
2. Keep the backend running with the global `schedule.enabled` switch enabled.
3. Account-private sources (bookmarks, your works, new works from followed users and collections) and restricted content such as R-18 require a cookie containing a valid `PHPSESSID` bound to the task. Without a valid cookie, private sources are suspended rather than running anonymously without results.

## Create your first scheduled task

Create a scheduled task by saving the current download-page configuration as a snapshot (available in both download-page layouts). This example tracks an artist's new works regularly:

1. Open the download page, switch to "User mode", and enter an artist's profile URL or numeric ID (for example, `https://www.pixiv.net/users/123456`; replace it with the actual artist ID).
2. Expand "Download settings" and configure as needed:
   - **Filename template**: defaults to `{artwork_id}_p{page}`.
   - **Collection**, **maximum concurrency** and **interval between works** (milliseconds, such as `1000`; an interval does not guarantee avoiding upstream restrictions).
   - **Allow deleted works to be downloaded again**: works deleted from the gallery retain [deletion markers](/en/gallery#delete-works) and are skipped by default. Selecting this option automatically downloads them again on each run (the setting is saved in the snapshot; tasks upgraded from older versions default to unchecked).
3. Expand "Extra filters" to set content ratings, exclude AI works or add tag conditions as needed.
4. Scroll to the "Save as scheduled task" card at the bottom:
   - Enter a **task name**;
   - Choose a **trigger**: a fixed interval (such as every `60` minutes) or a six-field Cron expression (such as `0 0 3 * * *` for 03:00 daily);
   - Set the [initial fetch limit](#initial-fetch-limit) and [separate proxy / cookie](#set-a-separate-proxy-cookie) as needed;
   - Click "Create task".
5. Switch to the "Scheduled tasks" list and inspect the snapshot. Click "Run now" once, check success, skip and failure reasons in the queue, then confirm the next trigger time. If the task was created but saving its proxy or cookie failed, continue configuring that task instead of creating a duplicate.

## Cron expression format

Use Spring's six-field Cron format (the first field is **seconds**):

```text
sec min hour day month weekday
0   0   3   *   *   *   Every day at 03:00:00
0   30  8   *   *   1   Every Monday at 08:30:00
0   0   */6 *   *   *   Every 6 hours
0   0   9   1   *   *   On the 1st of each month at 09:00:00
```

| Field | Range | Common symbols |
| --- | --- | --- |
| Seconds / minutes | `0–59` | `*` every second/minute; `/n` every n seconds/minutes |
| Hours | `0–23` | `*` every hour; `/n` every n hours |
| Day of month | `1–31` | `*` every day |
| Month | `1–12` | `*` every month |
| Day of week | `0–7` | `0` or `7` means Sunday; `*` every day |

## How source types work

| Source type | Execution |
| --- | --- |
| Artist's new works (User mode) / Your works | The first run fetches eligible works not yet downloaded; subsequent runs use an **incremental watermark**, scanning only as far as the newest work handled on the previous run |
| Artist commissions (User "Commissions" / Quick access "My commissions") | Each run lists completed, publicly available commissions, downloads them through the normal illustration pipeline and skips downloaded works. No watermark is used because the order is not guaranteed to be monotonic |
| Search keywords (Search mode) | Fetches the selected page range; "Newest" ordering with an end page of `-1` also uses an incremental watermark |
| Manga / novel series (Series mode) | Checks the chapter list each run, skips downloaded chapters and downloads new ones |
| My bookmarks / New works from followed users / Collections | Expand the corresponding source in [Quick access](/en/quick-access) before creating the task. Collections download both illustrations and novels. The source is read-only when editing a task; delete and recreate the task to change it |

## Initial fetch limit

For sources with many older works, set an "First-run fetch limit" (`0` means no count limit):

- **First-run cap** (artist's new works, new works from followed users, and searches with "Newest + end page `-1`"): only the first run fetches at most the latest N works and advances the watermark. Later runs track works published after that point and do not backfill older history.
- **Per-run cap** (my bookmarks, collections, commissions, and searches with non-newest ordering and end page `-1`): without a monotonic newest-first order, each run accepts at most N new works, gradually processing the backlog over multiple runs.
- **Fixed-page searches**: the cap applies per run. `0` removes only the count limit; it does not expand the selected page range (selecting page 1 alone does not automatically fetch later pages).

!> Skipping already downloaded works does not consume the acceptance quota; skips caused by additional filters do. Setting `0` for unlimited-page or multi-page sources may fetch a large history, so confirmation is required before saving.

## Manage existing tasks

In the "Scheduled tasks" list, the indicator at the top right of each task card shows its state:

| Indicator | Meaning |
| --- | --- |
| Gray | Waiting for the next trigger, disabled or manually paused |
| Green | Running or the previous run succeeded |
| Yellow | Multiple tasks are due at once; queued for execution |
| Red | Execution failed, the cookie is invalid, execution was interrupted, or an excessive-access pause was triggered (click the card for the reason) |

Cards support **Run now**, **Dedicated proxy/cookie**, **Enable / disable** scheduled triggers, **Pause / resume manually**, **View task snapshot**, **Edit**, **Pending retries** (view failed works awaiting retry) and **Delete** (does not affect downloaded files).

## Set a separate proxy / cookie

When creating a task in solo mode, a currently saved valid cookie is bound automatically if separate settings are not selected. You can also configure these independently during creation or in the task card's "Dedicated proxy/cookie" dialog:

- **Set a separate proxy**: enter `host:port` (such as `127.0.0.1:7890`) to route all of the task's Pixiv requests (discovery, metadata, image/novel downloads and inbox checks for excessive access) through that HTTP proxy. Leave it unchecked to use the global proxy.
- **Set a separate cookie**: paste a cookie containing `PHPSESSID`, or click "Use currently saved cookie" to fill it in. A bound cookie is not displayed; saving an empty field preserves it, while entering a new value replaces it.

Clearing a previously applied checkbox and saving removes that setting: the proxy falls back to the global configuration, and unbinding the cookie puts the task into anonymous "Restricted mode" (no R-18 access, and account-private sources cannot run). When a task detects an invalid cookie, it pauses automatically and shows "Login session expired". Obtain and bind a new cookie to resume (resubmitting the same invalid cookie is rejected).

## Excessive-access pauses and notifications

When the system detects a Pixiv inbox warning about "excessive access", it automatically pauses **all scheduled tasks under the same account**. The banner above the list offers "Ignore the risk and resume now" and "Continue after N minutes" (the minimum and default are 60 minutes; a cooldown is recommended).

After configuring desktop "Configuration → Mail / SMTP" or "Configuration → Push" (supports Bark, DingTalk, Telegram, Feishu, WeCom, PushPlus, ServerChan and Webhook), the following events send notifications in parallel (notification bodies never contain cookies or passwords):

- **Excessive-access pause**: lists affected task names, IDs, count and the warning time.
- **Expired login session / consecutive-failure circuit breaker**: lists the task name, ID, type, trigger rule and next run time.
- **Failed work reaches its retry limit**: also includes the work's Pixiv URL.

## Troubleshooting and related settings

If a task does not run, check the backend process, global switch, task enabled state and suspension reason on the card, in that order. Replacing a cookie with a valid one clears only credential suspension, not suspensions for other reasons. Clearing the global cookie on the download page does not automatically unbind credentials already assigned to tasks. Editing a task's source or filters resets the incremental watermark; the next run rescans and deduplicates against local download records.

| Setting (`config/config.yaml`) | Default | Description |
| --- | --- | --- |
| `schedule.enabled` | `true` | Global switch for scheduled tasks |
| `schedule.tick-interval-ms` | `60000` | Interval between checks for due tasks, in milliseconds; requires a restart |
| `schedule.max-tasks` | `100` | Maximum number of tasks that can be created |
| `schedule.inbox-check-every` | `500` | Number of successfully downloaded works between inbox checks for excessive access |
| `schedule.auth-failure-circuit-breaker` | `5` | Number of consecutive discovery failures that trigger circuit-breaker suspension |
| `schedule.pending-max-attempts` | `5` | Maximum automatic retry count for failed works |
| `schedule.overuse-defer-default-minutes` | `60` | Default cooldown in minutes after an excessive-access pause (minimum 60) |

See [Configuration reference](/en/configuration) for the complete settings.
