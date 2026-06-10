# Usage Guide

## Launch Parameters

```bash
# JAR launch
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar

# Windows EXE launch
PixivDownload.exe

# General parameters
--no-gui    # Disable desktop GUI, CLI-only mode (suitable for server/Docker/headless)
--intro     # Auto-open browser to product intro page on startup
--help, -h  # Print command-line help and exit
```

> [!NOTE]
> The desktop GUI (Swing + FlatLaf) launches by default, with tabs: Home, Status, Configuration, Tools, About. Use `--no-gui` only for server scenarios.

> [!IMPORTANT]
> **Starting with v1.10.0, startup arguments are validated strictly.** Unrecognized arguments (e.g. a misspelled `--foobar`, a bare `--username` missing the `=`, or a plain positional token) abort the normal startup; the offending argument is printed to stderr together with the help table and the process exits with code 64. Arguments shaped as `--key=value` are still forwarded to Spring Boot as property overrides (e.g. `--server.port=7000`) and remain unaffected. Use `--help` at any time to see the full option list.

### CLI Admin Commands (v1.10.0+)

For server / Docker setups without a desktop browser — where `setup.html` only accepts local connections — the launcher accepts CLI commands that perform admin actions previously only available in the GUI / web UI. The process **exits after the command** (no HTTP service is started). Interactive password input is hidden by default.

| Command | Purpose |
|---------|---------|
| `--setup` | First-time setup: admin username + password + run mode (solo/multi) + HTTP proxy (enable / host / port) |
| `--change-password` | Change admin password (current password is verified first) |
| `--reset-password` | Reset admin password (skips current-password check; for forgotten passwords) |

Optional flags (for automation; omit to enter the interactive prompt):

| Flag | Used with | Description |
|------|-----------|-------------|
| `--username=<name>` | `--setup` | Admin username |
| `--password=<pwd>` | `--setup` | Admin password (≥ 6 characters) |
| `--mode=<solo\|multi>` | `--setup` | Run mode |
| `--proxy-enabled=<true\|false>` | `--setup` | Whether to enable the HTTP proxy; prompts interactively when omitted |
| `--proxy-host=<host>` | `--setup` | Proxy host (default `127.0.0.1`) |
| `--proxy-port=<port>` | `--setup` | Proxy port (default `7890`) |
| `--old-password=<pwd>` | `--change-password` | Current password |
| `--new-password=<pwd>` | `--change-password` / `--reset-password` | New password |

> The proxy is used for all of the backend's outbound access: reaching Pixiv and downloading works, online updates, downloading the bundled FFmpeg, and online TTS. In environments that cannot reach a proxy on the host (e.g. Docker), pass `--proxy-enabled=false` to turn it off, or `--proxy-host=host.docker.internal` to point at the host gateway.

Examples:

```bash
# Interactive first-time setup (prompts for account/mode/proxy in turn)
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup

# One-shot first-time setup (password appears in shell history / process list)
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
    --username=admin --password='YourPassword123' --mode=solo \
    --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890

# Change password (interactive)
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --change-password

# Forgotten password -> force reset
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --reset-password
```

> [!WARNING]
> CLI commands need exclusive write access to `setup_config.json`. **Stop the running PixivDownloader instance first.** Detected concurrent instances cause the command to abort with exit code 75.

> [!IMPORTANT]
> In headless / `--no-gui` mode, normal startup is blocked when first-time setup is not complete; the launcher prints the `--setup` hint and exits (to avoid spinning up an instance no external client can configure). GUI mode is unaffected — finish initialization directly in the GUI "Home" wizard.

---

## First-time Setup

> [!IMPORTANT]
> Other features are unavailable until setup is complete.

### GUI Mode

After GUI launch, stay on the "Home" tab and follow the wizard:
1. **Service Status** — Confirm backend startup (real-time breathing indicator)
2. **Initial Setup** — Set admin credentials directly in the GUI, choose Solo/Multi mode
3. **Proxy Setup** — Configure the HTTP proxy used by all backend outbound traffic (can be disabled)
4. **Start Downloading** — Guide to open the browser download page
5. **Browse Gallery** — Guide to gallery operations
6. **Advanced** — Optional notes on userscripts, ffmpeg, etc.
7. **Done** — Wizard complete

> [!TIP]
> The wizard remembers the current page: closing and reopening the GUI brings you back to the same step. Once you reach the final "Done" page, the "Home" tab is hidden on subsequent launches (Status becomes the default tab) and the wizard stops polling the backend for onboarding progress. To go through the wizard again, delete the `wizard-progress` and `wizard-finished` files under `state/gui/`.

### Headless Mode (`--no-gui`)

On a server / Docker host without a desktop browser, use the [CLI admin commands](#cli-admin-commands-v1100) for first-time setup:

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup
```

The interactive wizard asks for username, password (hidden input, ≥ 6 characters) and run mode. The two modes:

| Mode | Description |
|------|-------------|
| **Solo Mode** | Personal use, login required, server-side state (cookies, settings, queues) shared across devices |
| **Multi Mode** | Shared server. Guests do not need to log in; per-user settings live in each browser's local storage. Per-user download quotas and rate limiting are optional |

Then start the service normally:

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui
```

If the host does have a desktop environment, the legacy flow still works: a first `--no-gui` start opens `http://localhost:6999/setup.html` in the local browser to complete initialization (local-only).

> [!TIP]
> Configuration is saved to `state/setup_config.json`. To re-initialize, stop the service, delete this file, and rerun `--setup` (downloaded files and the database are not affected).

---

## Configuring Proxy

All of the backend's outbound traffic goes through this HTTP proxy: accessing Pixiv and downloading works, online updates (checking/downloading new versions), downloading the bundled FFmpeg, and online TTS (the Edge neural voices used by "Listen"). The first-run setup wizard (web `setup.html`, desktop GUI onboarding, CLI `--setup`) walks you through configuring the proxy; you can also change it anytime afterwards.

### Via GUI

GUI → "Configuration" → "Proxy" section, edit:
- `proxy.enabled`: `true` / `false`
- `proxy.host`: Proxy address (default `127.0.0.1`)
- `proxy.port`: Proxy port (default `7890`)

Click save after editing. Proxy settings **support hot reload** and take effect immediately — no service restart needed.

### Via Config File

Edit `config.yaml` directly:

```yaml
proxy.enabled: true
proxy.host: 127.0.0.1
proxy.port: 7890
```

---

## Getting Cookies (Optional but Recommended)

Search mode, R-18/R-18G artwork downloads, and auto-bookmark require Pixiv cookies.

Pick any one of the methods below — all of them yield a usable cookie. Log in to [Pixiv](https://www.pixiv.net/) first in your browser regardless of which you choose.

> Only `PHPSESSID` is actually required by the backend (a normal logged-in Pixiv `PHPSESSID` looks like `12345678_xxxxxxxx`). A full cookie works too, but `PHPSESSID` alone is enough.

#### Method 1: Cookie-Editor extension (simplest)

1. Install the [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm) browser extension
2. Click the Cookie-Editor icon → bottom right **Export** → select **Netscape** format
3. Copy all content
4. When pasting, choose the **Netscape** format

#### Method 2: DevTools → Application → Cookies

1. On a Pixiv page press **F12** to open DevTools
2. Go to the **Application** panel → left sidebar Storage → **Cookies** → select `https://www.pixiv.net`
3. Find the row whose **Name** is `PHPSESSID` and copy its **Value**
4. Assemble one line as `PHPSESSID=<the value>` (e.g. `PHPSESSID=12345678_xxxxxxxx`)
5. When pasting, choose the **Header String** format

> To bring more cookies at once, join multiple rows as `name=value; name2=value2` and still use the **Header String** format. `PHPSESSID` is an HttpOnly cookie, but the Application panel still shows its value, so this method needs no userscript permission changes.

#### Method 3: DevTools → Network request header

1. On a Pixiv page press **F12** → open the **Network** panel
2. Refresh the page, then click any request to `www.pixiv.net` in the list (e.g. the top document request or an ajax request)
3. Under **Headers** find **Request Headers → `Cookie`** and copy its full value
4. When pasting, choose the **Header String** format

> The request-header `Cookie` is already in `name=value; name2=value2` form and contains `PHPSESSID`, so just paste the whole string.

#### Method 4: One-Click Cookie Import (Enhancement Toolbox)

Requires the "**Enhancement Toolbox**" userscript:
1. On `pixiv-batch.html`, click "One-Click Import Cookie"
2. The script auto-opens a Pixiv page to read cookies and sends them back to the server — no manual copy-paste or format selection needed

> ⚠️ **This method needs Tampermonkey to grant HttpOnly cookie access.** Pixiv's login token `PHPSESSID` is an HttpOnly cookie that userscripts cannot read by default, so "One-Click Import" will fail with a "PHPSESSID not detected" message.
>
> To enable it, go to **Tampermonkey → Settings → set Config mode to `Advanced` → Security → set "Allow scripts to access cookies" to `All`**.
>
> **Keeping this on permanently is NOT recommended**: once on, **any other userscript you have installed can also read your login credentials**, risking account theft. Unless you trust every userscript you have installed, **only set it to `All` temporarily while using "One-Click Import", then change it back to `All except HttpOnly` right after a successful fetch**. **Prefer Method 2 or 3 above** (DevTools) — they also obtain the HttpOnly `PHPSESSID` and require no userscript permission changes.

### Pasting the Cookie

The manual paste steps apply to Methods 1/2/3 (Method 4 sends it back automatically):

1. On `pixiv-batch.html`, expand the "**Cookie**" card at the top
2. Switch the format to match how you obtained it: **Netscape** for Method 1 (Cookie-Editor export); **Header String** for Methods 2 and 3 (DevTools)
3. Paste the copied content and save

---

## Web Batch Download

Visit `http://localhost:6999/pixiv-batch.html`.

### 🧭 New-user Onboarding

The first time you open the download page as an admin (solo mode / a logged-in admin in multi mode), an interactive **cross-page onboarding** starts automatically and walks you through the whole flow, from downloading to browsing:

1. It first asks for a **name** and checks **connectivity** from the backend to Pixiv (shows the round-trip latency; if unreachable it suggests checking the proxy / network).
2. It introduces the download page region by region — the **Cookie area / userscripts area / download modes (tabs)** — using "Batch Import Single Artworks" as the example.
3. It guides you to **paste the example link → add to the queue → review the extra filters and download settings → start downloading**, highlighting the status bar and download queue so you can watch progress live.
4. When the download finishes it jumps to the **gallery** (walking through views / search / filter / the work grid), then into the **artwork detail page** (images / actions / description / author / series & related).

You can **skip** the guide at any time, or replay it via the **"Guide"** button at the bottom-right of the download page (when a name is already set, it skips the name step and starts from the connectivity check). Guests in multi mode never trigger it.

> The **name** you enter in the guide is saved and used for the user card at the bottom of the gallery / novel-gallery sidebar, and for the greeting in system emails (scheduled-task notifications, mail tests); when left empty it falls back to "administrator".

### ⭐ Quick Fetch

The first tab, shown by default. Using the saved Cookie it auto-detects the current account (via PHPSESSID) and one-clicks your account-related data into the download queue:

- My bookmarks (illust/manga, novel — including private)
- My own works (illust/manga, novel — including private)
- My following list (including private), with live filtering by username / user ID
- My collections (コレクション)

Clicking a followed user or a collection expands a second-level preview **below** the current list: followed users have an illust/novel toggle; a collection shows its illustrations and novels mixed together. Previewed results can be added to the download queue individually, by page, or the whole collection.

> Requires a saved Cookie containing PHPSESSID; the buttons are disabled with a hint until one is saved.

### 🎨 Batch Import Single Artworks

Paste a list of artworks, one per line, in the form `url | title` or `id | title`:

```
https://www.pixiv.net/artworks/12345678 | Example Title
https://www.pixiv.net/novel/show.php?id=12345679 | Novel Title
https://www.pixiv.net/artworks/87654321
12345678 | A bare numeric ID also works; defaults to illustration
novel:
22222
33333 | Under a "novel:" section header, bare IDs / "id | title" are parsed as novels
artwork:
44444
```

- `title` can be left blank; real title fetched before download
- Supports full `artworks/<id>` and `novel/show.php?id=<id>` URLs
- A bare numeric ID (optionally `id | title`) is **parsed as an illustration by default** (equivalent to `https://www.pixiv.net/artworks/{id}`)
- A section header `artwork:` / `novel:` stands on its own line (case-insensitive, half- or full-width colon accepted): every following "bare ID / id | title" line is parsed as that kind until the next section header or end of input
- Full URLs are always parsed by their own kind regardless of which section they fall under
- Compatible with OneTab, N-Tab, and similar tab manager exports
- Novels support format selection: TXT / HTML / EPUB

### 👤 User Mode

Enter a Pixiv user ID, or paste an artist profile URL (e.g. `https://www.pixiv.net/users/123456`, including variants with `/artworks`, `/illustrations`, `/novels` suffixes and locale prefixes), to batch download all their artwork. When a URL is pasted, the numeric ID is extracted automatically and used by the preview and the "Save as scheduled task" flow.

Switch download scope:
- Illustrations + Manga + Ugoira
- Novels

### 🔍 Search Mode

Search by keyword, preview thumbnails, then add to download queue.

> [!IMPORTANT]
> Search mode requires cookies.

### 📚 Series Mode

Paste manga series or novel series links to batch download the entire series:
- Paginated preview support
- Add current page / auto-fill all pages to queue
- Novel series support compiled export (EPUB recommended)

### 🧮 Extra Filters (shared by all modes)

The "Extra filters" card is shown in all four modes — **Single-work import / User / Search / Series** — and is available to every user:

- **Content rating**: All / All ages / R18+(R-18 + R-18G) / R-18 / R-18G. The old "Only download R18 works" toggle from download settings and the content radio at the top of Search mode are now merged here; in Search mode it also decides which content tier is requested from Pixiv.
- **AI works**: All / Exclude AI / AI only
- **Tags**: exact match / fuzzy match (comma-separated, all must match)
- **Range filters** for bookmark count / page count (illustrations) / word count (novels) / work type

In Search / User / Series modes the extra filters **live-filter the current preview page**; and **in every mode, the actual download applies these conditions per-work**, skipping non-matching works and showing the specific skip reason in the download queue (e.g. "content rating mismatch", "tags do not match", "bookmark count mismatch"). A single-work import queue can mix illustrations and novels, so the card shows all fields and applies the matching filter per work according to its actual type at download time.

### ⏰ Scheduled Tasks (admin only)

Once logged in as admin, an "⏰ Scheduled" tab appears on the batch page. The backend automatically discovers and downloads new works (illustrations/manga/ugoira and novels) on a fixed interval or cron schedule, with no browser required.

#### Creating: "Save as scheduled task" from a working mode

A scheduled task is **not created from a separate form** — it snapshots your current download configuration:

1. Switch to **User / Search / Series** mode and configure the source (artist ID / keyword / series link) and download settings (filename template, bookmark-after-download, collection, **max concurrent**, **artwork interval**, **image interval**, **verify actual folder**, novel format & compilation, etc.) just like a normal download. The background runner borrows the download thread pool to download multiple works concurrently per "max concurrent" (shared with manual downloads), paces fetches via "artwork interval / image interval" (stored as integer milliseconds), and uses "verify actual folder" to decide whether dedup also checks that the files exist on disk (illustrations only; missing files trigger a re-download).
2. Configure the "**extra filters**" (content rating, AI works, exact/fuzzy tags, bookmark count, page/word count, work type — see "Extra Filters" above). When an admin saves a scheduled task, this filter set is snapshotted and applied per-work in the background.
3. In the "**⏰ Save as scheduled task**" card at the bottom of the page, enter a task name, pick a trigger (fixed interval minutes / cron expression), optionally tick "**Use a dedicated proxy**" / "**Use a dedicated cookie**" to give this task its own HTTP proxy (`host:port`, e.g. `127.0.0.1:7890`) and cookie (the cookie field has a "**Use currently saved cookie**" shortcut button), and click "Create task". With a dedicated proxy set, **all** Pixiv access of that task during each run (discovery, metadata, image/novel downloads, inbox overuse check) goes through it; unchecked items use the defaults (global proxy settings / the auto-bound cookie).
4. What gets saved is exactly the settings a manual download would use — what you see is what runs; the background run applies the filters above per-work.

> In **solo mode**, creating a scheduled task without ticking "Use a dedicated cookie" automatically binds it to the current Cookie from the top Cookie card (must contain `PHPSESSID`). If no usable Cookie is set at that time, the task is saved in "restricted mode" and a cookie can be bound later via "**Dedicated proxy/cookie**" in the management tab.

> Series mode requires "Parse & preview" first to obtain series info before saving (editing an existing series task auto-fills the link and re-previews it). Single-work import mode has no matching source type, so the card is hidden there.

#### Discovery scope per source type (each round)

- **Artist new works (User mode)**: fetches the artist's works, skipping already-downloaded ones. The first round roughly downloads all matching undownloaded works; later rounds use **watermark-based incremental discovery** — each round only scans down to the newest work processed in the previous round and stops there, picking up just the works above it rather than rescanning the entire history every time.
- **Saved search (Search mode)**: fetches the **latest N pages** of results, where the page count depends on the search submode selected when saving:
    - **🔍 Search submode**: takes only the first page.
    - **📦 Batch-fetch submode**: takes the number of pages from the "End page" field (default 3).
    - Admins can also set the end page to **-1**, meaning "keep paging through older results until catching up with history." The exact behavior depends on the sort order: **newest-first (date_d)** enables **watermark-based incremental discovery** — each round pages from newest toward older, stops once it reaches the newest work processed in the previous round, and picks up only the works above it; the watermark advances only after a round completes fully with no failed works, so even if a round is interrupted midway or an individual work fails, the next round re-fills the gap without missing works. **Non-newest sorts (e.g. popular_d)** have no reliable ordering anchor, so instead each round downloads page by page until it hits the first already-downloaded work and stops. Neither case sets a page cap any more (about 10 seconds is enforced before fetching the next page to avoid hammering Pixiv).
- **Series (Series mode)**: lists **all members of the entire series**; newly added chapters are picked up automatically in the next round. (Series authors can freely reorder and insert chapters in the middle, so **no watermark is used here** — each round does a full discovery + skip-already-downloaded, which is the safest and never misses members.)

In all cases each round is "discover → skip already-downloaded → filter per work → download". **"Skip already-downloaded" is always on**.

#### First-run fetch limit

For sources that could pull a large backlog at once, the "**⏰ Save as scheduled task**" card offers a **first-run fetch limit** to cap how many works are fetched and reduce the risk of triggering Pixiv's "over-access" warning. `0` means a full fetch (no cap). The behavior splits by whether the source has a reliable "newest" ordering (the on-page hint shows only the matching one):

- **First-run cap** (artist new works / followed-users' latest / "newest sort + end page -1" search): **only the first run** fetches at most the newest N works; the watermark then advances to the newest, and later rounds only pick up newly posted works incrementally (older backlog is not back-filled). Use it when you only want the most recent N and then keep tracking new posts.
- **Per-run cap** (my bookmarks / collections / non-newest "end page -1" search): these sources have no reliable "newest" ordering, so the limit acts as a **per-run cap** — each run fetches at most N new works, draining the backlog over successive runs. **Set the limit to `0` if you want it to eventually fetch every work in full** (no per-run cap).

Series and fixed-page search are inherently bounded, so the field is hidden for them.

> Setting the limit to `0` (full) means the first run may pull the source's entire backlog at once; for large sources this can still trigger an over-access warning, so the page asks you to confirm on save.

#### Trigger and running

- **Trigger**: fixed interval (minutes) or a cron expression. Cron uses Spring's **6-field format** "second minute hour day month weekday" (note the leading seconds field — one more than the common 5-field Unix cron), e.g. `0 0 3 * * *` = 03:00:00 daily, `0 0 */6 * * *` = every 6 hours, `0 30 8 * * 1` = 08:30:00 every Monday.
- A single global timer (default every 60s) checks for due tasks and runs them serially; the interval is measured from the last run's completion.
- In the **management tab**, each task can be: run once, set a dedicated proxy/cookie, enable/disable, view task snapshot, edit, delete. `View task snapshot` opens a dialog showing the source, filter, and download settings captured when the task was saved (including the dedicated proxy, if any); to change them, use the task action area's Edit button.
- **Status light**: the top-right corner of each task card shows the live run status — gray (waiting for first run / disabled / manually paused `PAUSED`), green (running / succeeded, waiting for next run), yellow (multiple tasks due at once, queued serially), red (run failed with reason / cookie expired needing re-authorization / the previous run was forcibly interrupted before finishing and has been rescheduled to catch up / **overuse paused `OVERUSE_PAUSED`**). The list auto-refreshes periodically while the "Scheduled tasks" tab is open to reflect these transient states.
- **Dedicated proxy/cookie**: clicking "**Dedicated proxy/cookie**" on a task card opens a dialog with two checkboxes. "Use a dedicated cookie" binds a cookie (must contain `PHPSESSID`) to that task — paste any cookie or click "Use currently saved cookie" to fill in the one from the top Cookie card — so it fetches R18 / restricted works as that account. Without a bound cookie the task runs in "restricted mode" fetching only anonymously visible content. The cookie is used server-side only, never echoed back (when already bound, leaving the field empty keeps it unchanged; filling it replaces it), and is cleared when the task is deleted. Binding also extracts the **non-sensitive** Pixiv userId from the PHPSESSID prefix as `account_id`, used to group overuse pauses by account; that ID is public, but the cookie itself is never exposed. "Use a dedicated proxy" routes all of that task's Pixiv access through its own HTTP proxy (`host:port`); unchecking an active item and saving clears that setting after a confirmation (proxy falls back to the global settings; cookie unbinding switches the task to restricted mode).
- **Login expiry**: when the cookie expires the task is marked "login expired" and actually suspended (no more empty retries each tick); an email notification is sent and the admin is asked to re-bind a valid cookie via the "Dedicated proxy/cookie" dialog (submitting the exact same expired cookie is rejected). Re-binding auto-resumes the task and retries the previously isolated works first.
- **Overuse pause (account-level)**: cookie-bound tasks read the Pixiv message inbox at the start of each run and every N successfully downloaded works (default 500, adjust via `schedule.inbox-check-every`). When an "overuse" warning is detected, all scheduled tasks for that account are paused immediately (to avoid getting the account banned by continuing heavy downloads in a short window) and an email is sent. The management tab shows a banner for that account with two recovery buttons:
  - "Ignore the risk, keep downloading! (may get the account banned)" — marks the current warning as explicitly acknowledged and immediately resumes all tasks for that account (a newer warning will still trigger another pause).
  - "I understand, continue all tasks for this account in N minutes" (N defaults to 60, **minimum 60 minutes**) — defers the resume and also writes the ack as a safety net.
- **Manual pause / resume (task-level)**: each task card has a new "Pause" button that marks just that task as `PAUSED` (does not freeze sibling tasks of the same account, does not send email); after pausing the button becomes "Resume", which clears the suspension and reschedules the next run.
- **Circuit-breaker suspension**: when per-work fetch failures reach `schedule.auth-failure-circuit-breaker` consecutive times within one run (default 5), the session is treated as expired mid-run and the task is suspended with a circuit-breaker email, awaiting re-authorization.
- **Pending retries / needs manual**: recoverable per-work failures are isolated into a "pending retries" list (404 / 403-gone and filter-skipped works are not isolated); they are retried first on the next run after the admin handles the exception. Click "Pending retries" on the card to see this task's entries; works whose attempts reach `schedule.pending-max-attempts` (default 5) are marked as "needs manual" and can be cleared manually. **The watermark still advances even when individual works are isolated** — the pending table tracks them separately, so no single bad work blocks progress long-term.

Scheduled tasks run as the admin and are not subject to guest rate limits / quotas. Related settings (toggle, tick interval, max tasks, fetch delay, inbox check interval, circuit-breaker threshold, pending retry limit, overuse defer minutes) can be adjusted under GUI "Config → Scheduled tasks" or via `schedule.*` in `config.yaml`. Notification emails depend on "Mail / SMTP" configuration — see GUI "Config → Mail / SMTP" and [en-Configuration#mail--smtp](en-Configuration#-mail--smtp).

---

## Download Monitor

Visit `http://localhost:6999/monitor.html` for real-time monitoring:

- **Active Downloads**: Progress of currently downloading tasks
- **History**: List of completed downloads
- **Statistics**: Download trend visualization

History author column supports:
- Fuzzy search by author name / author ID
- Fuzzy search by tag
- Filter by AI-generated
- Checkbox-based author filtering
- Click author name for quick filter switch
- Sort by author ID

---

## Artwork Gallery

Visit `http://localhost:6999/pixiv-gallery.html`.

Two buttons protrude from the right edge of the sidebar (also on the novel gallery, series, and statistics pages):

- **Nav**: expands / collapses the left sidebar.
- **Tasks** (admin only): shows background archive tasks (batch exports / packs) with progress and remaining validity. When a task finishes you can download the archive manually from the list; expired tasks are cleaned up automatically, and the list is cleared after a server restart. Starting a batch export opens this panel automatically.

### Search & Filter

| Search Scope | Description |
|--------------|-------------|
| General | Title + Author name |
| Title | Search by artwork title |
| Author Name | Search by author name |
| Artwork ID | Exact search by artwork ID |
| Author ID | Exact search by author ID |
| Description | Search by artwork description |
| Tags (Fuzzy) | Fuzzy tag matching |
| Tags (Exact) | Exact tag matching |

### Sorting

Sort by time, artwork ID, image count, status, author ID, tag count. Supports ascending/descending.

### Combined Filtering

- R-18 / R-18G / AI-generated / Image format / Collections
- **Tag/Author Tri-state Filter**: Must have / Must not have / May have
- Positive conditions use union, then intersect with exclusion conditions

### Artwork Details

Click a card to enter `pixiv-artwork.html?id=<artworkId>`:
- View title, description, tags, author info
- Related artwork and other works by the same author
- Multi-image artwork supports "Expand All" and lightbox preview
- Jump to original Pixiv page

### Collections

- Create / Rename / Delete collections
- Custom icons (PNG/JPG/WEBP, max 1MB)
- Custom download directories
- Quick collection creation

### Artwork Showcase

Enter `pixiv-showcase.html` from detail page for immersive browsing.

### Batch Management (admin only)

Click **Manage** in the top toolbar to enter multi-select mode, then run batch actions from the **Actions** menu in the bottom bar. Selection options:

- **Select page / Clear**: applies to the current page only.
- **Select all results**: selects **every** work matching the current filters (you can still untick individual works), and the selection survives pagination.

The **Actions** menu offers three operations:

- **Export**: a dialog lets you pick the **packaging** (group by author / work, or one folder per work ID), the **archive format** (currently ZIP), and whether to **delete source files after export**. Packaging runs in the background: the **Tasks** panel opens automatically to show progress, the download starts automatically when ready, and the archive can also be downloaded manually from the task list. The archive includes a `manifest.json` recording work metadata and original paths. With "delete after export" checked, source files and download records are removed **only after the archive is created successfully**.
- **Add to collection**: adds the selected works to a collection in one go.
- **Delete**: batch-deletes the selected works.

#### Deleting artworks

Admins can delete downloaded artworks. On the detail page, click the red **Delete** button and confirm; in the gallery, use batch management and pick **Delete** from the Actions menu. Deletion removes the local files and derived data (tags, collection links and thumbnail cache); the files **cannot be recovered**.

Deleted works keep a **deletion mark** in the download history: they become invisible to the gallery, filters and statistics, but the "skip downloaded works" check in batch downloads, userscripts and scheduled tasks still recognizes them as "downloaded before, but deleted" and will not re-download them by default. To download such a work again, enable **"Allow re-downloading deleted works"** in the download settings (the setting is also captured by "Save as scheduled task"; tasks created by older versions get it backfilled as unchecked on first startup after upgrading). A successful re-download clears the mark and the work reappears in the gallery.

---

## Statistics Dashboard

Visit `http://localhost:6999/pixiv-stats.html` (also reachable from the "Stats" sidebar entry). **Admin-only** (login required in both `solo` and `multi` modes; not visible to regular or invited guests).

Read-only visualizations built from locally downloaded data:

- **Overview cards**: total artworks / images / novels / authors / tags / series / archived (moved) count
- **Downloads by month**: line chart grouped by artwork download time
- **Top authors by downloads**: bar list; click to jump to the gallery filtered by that author
- **Popular-tag cloud**: font size scales with usage; click to jump to the gallery filtered by that tag

Supports English/Chinese and dark mode, with the same sidebar as the gallery.

---

## Suspected Duplicates

Visit `http://localhost:6999/pixiv-duplicates.html` (also reachable from the "Duplicates" sidebar entry). **Admin-only** (login required in both `solo` and `multi` modes; not visible to regular or invited guests).

Beyond exact dedup by artwork ID, this uses **perceptual hashing (dHash)** to spot substantially duplicate downloaded images—similar-looking images are grouped together even if their filenames, dimensions, or compression differ.

- **dHash / aHash threshold**: smaller Hamming distance is stricter; dHash defaults to 10, aHash (secondary confirmation) to 12, both adjustable via slider/input (0–32).
- **Scope switch**:
  - **Cross-artwork** (default): only shows duplicates spread across different artworks (the common "downloaded the same image under different posts" case).
  - **All**: additionally includes similar pages within the same artwork.
- **Scan now**: computes missing hashes for downloaded artworks and shows progress; hashes are also computed automatically in the background after each download, so manual scans are usually unnecessary.
- **Regroup**: recomputes grouping with the current threshold/scope.
- Each group shows thumbnails in a row; click one to open the artwork page and handle it manually.

> Hash computation is best-effort: it never affects download results, and corrupt/undecodable images are skipped. This feature only **identifies and displays** duplicates—it does **not** delete files.

Supports English/Chinese and dark mode, with the same sidebar as the gallery.

---

## Novel Gallery

Visit `http://localhost:6999/pixiv-novel-gallery.html`.

Supports search, filter, and sort with a similar interaction pattern to the artwork gallery. In addition to the artwork gallery's search scopes, it offers a **"Body text"** scope: full-text search over downloaded novel bodies (backed by a local full-text index), combinable with age-rating / AI / tag / author / series filters.

The novel gallery offers the same batch management as the artwork gallery (admin only): **Select all results** for whole-filter selection, and an **Actions** menu with **Export** (packaging, archive format, optional delete-after-export; packaged in the background and tracked in the **Tasks** panel), **Add to collection**, and **Delete**. Novels can also be deleted individually via the **Delete** button on the novel page. Deletion removes the local files (body / cover / etc.); the files **cannot be recovered**, and the same **deletion mark** behavior as artworks applies (deleted novels stay invisible but are skipped as "downloaded before, but deleted" unless "Allow re-downloading deleted works" is enabled).

### Novel Reading

- `pixiv-novel.html?id=<novelId>` — View text, tags, author
- Previous/Next chapter navigation
- Series navigation

### Listen (Text-to-Speech)

Click the **🎧 Listen** button at the top of the novel page to read the text aloud paragraph by paragraph. A playback bar appears at the bottom:

- **Play/Pause, Previous/Next paragraph, Stop**; the current paragraph is highlighted and auto-scrolled into view
- **Progress bar**: shows overall reading progress (the online engine also shows in-paragraph playback progress); click the bar to jump to a paragraph
- **Two switchable voice engines** (the settings panel explains the difference):
  - **Browser engine (default)**: the browser's built-in voices — fully offline, instant, free; quality and available languages depend on the voices installed on your OS
  - **Online engine (Edge neural)**: Microsoft Edge online voices — natural quality, many languages, free; requires network (via the backend-configured proxy)
- **Voice auto-selected by the novel's language**; you can also pick a specific voice and adjust speed. Engine/voice/speed choices are remembered
- **Progress is remembered locally**: reading position is stored per novel in the browser; reopening the same novel restores your last position (press play to continue from there), and it is cleared once you reach the end. Up to 100 novels are remembered
- **Audio cached locally**: audio synthesized by the online engine is persisted in the browser (IndexedDB) and reused across reloads/restarts — pausing/resuming, stopping/replaying, and seeking between paragraphs never re-synthesize. Up to 100 novels are cached

> [!NOTE]
> Visitors using an invite link can also use the online engine, but are rate-limited by `guest-invite.tts-request-limit-minute` (admins and the browser engine are not limited). Guest invites are independent of the solo/multi run mode — they work in both. The browser engine runs entirely in the frontend — no server resources, no network.

### Multi-voice narration (beta · admin only)

> **This feature is currently beta — usable but not yet stable; synthesis failures or inconsistent timbre may occur.**

Click **🎭 Multi-voice narration** on the novel page to have an LLM attribute every sentence of the chapter to a speaker (narrator / individual characters); each character is synthesized line by line with a fixed voice profile and played back continuously — unlike the single-voice **Listen** above. It requires an LLM and a narration engine — either a self-hosted one (e.g. VoxCPM) or one of several cloud TTS services, so you don't need a GPU of your own. **If either is not configured, the Multi-voice narration engine option won't appear under Listen.**

- **Line-by-line playback with follow-along**: sentences are synthesized and played in order (the next one is prefetched); the **paragraph of the current sentence is highlighted and auto-scrolled**, and the bar shows a "Speaker: sentence" subtitle.
- **Narrator voice**: the settings dialog shown before the first analysis lets you pick a voice for this work's narrator (warm female / calm male / neutral, with preview); once confirmed the narrator voice is **locked** so the AI won't rewrite it, avoiding cross-chapter drift. If you don't pick one (keep current), this work's existing / default narrator is used (the default is warm female).
- **Cast & voices**: the 🎭 panel lists each character's gender / age / English voice profile; you can **edit a character's voice and save** it (saving marks it "Locked" so the AI won't overwrite it) and preview it individually. Edited voices take effect on subsequent synthesis **immediately**, without re-analysis.
- **Character reference-voice cloning**: in the panel you can **generate a "standard voice" with one click** or **upload a real reference clip** (wav/mp3, with an optional transcript) for any character (including the narrator). Once a reference exists, that character's lines are synthesized by **cloning that reference's timbre** — more consistent across sentences and chapters, while per-line emotion still applies; the reference can be previewed and deleted, and changing it automatically recomputes cached audio with the new voice. Cloning can be turned off or capped in the configuration.
- **Voice conflicts**: when the text clearly contradicts or outgrows a voice you locked, the panel lists the conflict with **Adopt suggestion / Keep current / Rewrite** choices.
- **Segment size**: same meaning as in AI translation — `0` (default) analyzes the whole chapter in one call (fullest context, most accurate attribution); a number batches by paragraph up to roughly that many characters (fewer tokens per call).
- The per-sentence attribution is **cached** so replays don't re-analyze; use "Re-analyze" in the settings to recompute. Very long text is rejected for one-shot analysis with a prompt to narrow the scope.

### Series Compilation

Generate EPUB compilations from novel series pages or during download:
- Auto-sort and merge by chapter
- EPUB3 spec: multi-level TOC (Series → Chapters)
- Embedded cover image
- Book metadata (description, tags, source link)

You can also re-download the compilation at any time from the novel-series page (`pixiv-series.html?type=novel&seriesId=...`): the toolbar has a **Download merged volume** button that opens a language picker (Original, or any language the series has already been translated into). Picking a non-original language shows an inline warning — only already-translated chapters use that language; untranslated chapters fall back to original. To get a volume fully in the chosen language, run **Translate whole series** first and then re-download. The backend regenerates the EPUB from the current database state and streams it as an attachment.

When the AI translation feature is used, the novel **title** and series **name** are translated along with the body — single-novel translation translates the chapter title in the SAME AI request as the body (so proper nouns and terminology stay consistent); "Translate whole series" also translates the series name afterwards, passing along the same glossary and the already-translated chapter title pairs from this series as naming-style references. Switching the content language on the novel page / series page swaps the displayed novel title, series name and chapter card titles to the translated versions (falling back to original where a translation is missing). The language-variant merged EPUB uses the translated series name and translated chapter titles too.

---

## GUI Desktop Tool

### Status Page

- Real-time service status monitoring
- Pixiv connectivity check (uses the current proxy settings from the backend, showing HTTP status and latency)
- Manage backend start/stop
- Quick page shortcuts (batch download, monitor, gallery)
- Auto-start on boot toggle
- FFmpeg status & download
- Online update check & install

### Configuration Page

- Visual `config.yaml` management
- Grouped display: Proxy, Multi-user, Login Security, Maintenance, SSL, Language, Updates
- Hot-reload / restart-required labels
- Preserves comments and formatting

### Tools Page

| Tool | Description |
|------|-------------|
| **Image Classifier** | Standalone image classification window for organizing downloaded images |
| **Database Directory Validator** | Check if file directories recorded in the database are still accessible |
| **Database Backfill Tool** | Fill in missing data fields from version upgrades |

> [!IMPORTANT]
> The database directory validator and backfill tool require exclusive SQLite access. The GUI automatically handles backend stop/restore — no manual service stoppage needed.

---

## Userscript Usage

### Page Scrape Downloader

On Pixiv search, following feed, ranking, etc. pages, auto-mark downloadable artwork:
- Checkboxes appear on thumbnails
- Click to add to download queue
- Queued artwork shows border markers
- Covers both illustration and novel cards

### User Batch Downloader

On a Pixiv user profile, one-click batch download all their artwork.

### URL Batch Importer

Open the panel on any Pixiv page, paste artwork link list for batch download.

### Single Artwork Downloader (Java Backend)

On a single artwork page, one-click download via backend.

### Single Artwork Downloader (Local Download)

On a single artwork page, browser local download, no Java backend needed.

### Enhancement Toolbox

Toggleable feature modules:
- **Downloaded Artwork Borders**: Add custom borders to thumbnails of server-downloaded artwork
- **One-Click Cookie Import**: Read current Pixiv cookies and save to server in one click

---

## Guest Invite System

> [!NOTE]
> Guest invites are independent of the solo/multi run mode — they **work in both**.

Admins can create invite codes to share galleries with guests.

### Creating Invites

`pixiv-gallery.html` → "Invite Guests" button → configure:
- Expiration time
- Rating control: SFW / R18 / R18G
- Tag/Author allowlist

### Guest Access

Guests access via invite link `http://host:port/invite?code=xxx`, can only browse artwork within the allowlist scope.

### Invite Management

`pixiv-invite-manage.html` — Manage all invite codes:
- Create / Edit / Pause / Resume / Delete
- 24h / 7d / 30d access statistics

---

## Common Operations

### Ugoira Download

Ugoira animations are automatically converted to WebP format. Requires ffmpeg.

### Custom Filenames

Custom filename templates available in batch download settings, supporting 11 variables:

| Variable | Description |
|----------|-------------|
| `{artwork_id}` | Artwork ID |
| `{artwork_title}` | Sanitized artwork title |
| `{author_id}` | Author ID |
| `{author_name}` | Sanitized author name |
| `{timestamp}` | Unix timestamp (milliseconds) |
| `{page}` | Current page index (0-based) |
| `{count}` | Total page count |
| `{ai}` | `AI` if AI-generated, otherwise empty |
| `{ai+}` | `AI` or `Human` |
| `{R18}` | `R18` / `R18G` / empty |
| `{R18+}` | `SFW` / `R18` / `R18G` |

### Auto-Bookmark

Check "Auto-bookmark" during download to automatically add bookmarks via Pixiv API using cookies.

### Collection Auto-Archive

Select a collection during download to automatically add the artwork to it upon completion.
