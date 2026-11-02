# Usage Guide (Advanced Reference)

This page consolidates advanced operations and parameter explanations not covered in separate topic pages.

For detailed tutorials on common features, see the corresponding pages:

| Feature | Documentation |
|---------|--------------|
| Quick Fetch | [Quick Fetch](/en/quick-access) |
| URL Batch Download | [Batch Download](/en/batch-download) |
| Artist Batch Download | [User Download](/en/user-download) |
| Search Download | [Search](/en/search) |
| Novel Download | [Novel](/en/novel) |
| Artwork Gallery | [Gallery](/en/gallery) |
| Scheduled Tasks | [Scheduled Tasks](/en/scheduled-tasks) |
| Userscripts | [Userscripts](/en/userscripts) |

---

## Launch Parameters

Run commands from the fully extracted distribution directory. Replace `APP.jar` with the actual main application JAR filename. These single-line commands work in PowerShell and Bash.

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --help
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

| Parameter | Purpose |
|-----------|---------|
| `--no-gui` | Do not start the desktop GUI; for servers or Docker |
| `--intro` | Open the product introduction at startup |
| `--help`, `-h` | Show help and exit |

The Windows installation provides `PixivDownload.exe`. Distributions include both Compose and Swing; an empty `app.gui-provider` selects the default Compose provider. Fully restart after switching providers. Complete initialization before the first headless launch, or the process exits with code `78`.

### CLI Admin Commands

| Command | Purpose |
|---------|---------|
| `--setup` | First-time initialization (account + mode + proxy) |
| `--change-password` | Change admin password |
| `--reset-password` | Force reset when password is forgotten |

These commands change admin state. Stop other instances using the same data directory first. Password changes and resets invalidate old sessions; verify login with the new password after restarting. See [First-Time Setup](/en/first-setup) for prompts, password requirements, and recovery.

---

## Filename Template Variables

Use the following variables in "Download Settings → Filename Template":

| Variable | Description |
|----------|-------------|
| `{artwork_id}` | Artwork ID |
| `{artwork_title}` | Artwork title (illegal characters auto-removed) |
| `{author_id}` | Author ID |
| `{author_name}` | Author name (illegal characters auto-removed) |
| `{timestamp}` | Unix timestamp (milliseconds) |
| `{page}` | Current page index (0-based) |
| `{count}` | Total page count |
| `{ai}` | `AI` if AI-generated, otherwise empty |
| `{ai+}` | `AI` or `Human` |
| `{R18}` | `R18` / `R18G` / empty |
| `{R18+}` | `SFW` / `R18` / `R18G` |

Example: `{author_name}/{artwork_id}_p{page}` → saves files in subfolders by author name.

---

## Auto-Bookmark

On the download web page, enable "**Auto-bookmark**" to bookmark completed works using the saved cookie. Userscripts perform bookmarking with the browser's Pixiv session instead; they do not forward `document.cookie` to the backend.

!> Requires a saved valid cookie containing `PHPSESSID`. Bookmarking is best-effort — a bookmark failure will not cause the download task to fail.

---

## Ugoira Download

Ugoira artworks are automatically detected and processed:

1. Download ZIP frame package
2. Extract frames and sort by filename
3. Use `ffmpeg` to generate the selected animation or video format; see [Configuration](configuration.md) for supported choices
4. Also save the first frame as a thumbnail (`_p0_thumb.jpg`)

Requires `ffmpeg` available in the system PATH. Windows installer users can click "Download FFmpeg" on the GUI → Status page for automatic installation.

---

## Download Monitor

Visit `http://localhost:6999/monitor.html` for real-time monitoring:

- Current active download progress
- History records (filterable by author / tag / AI, supports fuzzy search)
- Download trend statistics chart

---

## GUI Tools Page

| Tool | Description |
|------|-------------|
| **Image Classifier** | Classify and organize downloaded images |
| **Database Directory Validator** | Check whether file paths in database records are still valid |
| **Database Backfill Tool** | Fill in missing data fields from version upgrades |

!> Database tools need an exclusive maintenance session; the GUI manages backend stop and restart. Back up the selected database first and do not open another instance against the same data directory. The selected database provider must be available; PostgreSQL also requires a server database backup. Afterwards, check the report, backend status, and gallery access. Closing the tool window does not itself prove success.

---

## Suspected Duplicates

Visit `http://localhost:6999/pixiv-duplicates.html` (admin only):

Uses perceptual hashing (dHash) to identify visually similar downloaded images, even when filenames or dimensions differ.

- Adjustable Hamming distance threshold (smaller = stricter, dHash default 10)
- Cross-artwork mode (find duplicates spread across different artworks) / Full-library mode
- Click thumbnails to jump to the detail page for manual handling

---

## Guest Invite System

?> Guest invites work in both solo and multi modes.

Admins can create invite codes to let external users browse the gallery read-only:

1. Gallery page → click "**Invite Guests**"
2. Set expiration time, content rating (SFW / R18 / R18G), tag / author allowlist
3. Copy the invite link `http://host:port/invite?code=xxx` to share

Invite management page: `http://localhost:6999/pixiv-invite-manage.html`
