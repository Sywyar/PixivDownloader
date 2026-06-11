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

```bash
# JAR launch
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar

# Windows EXE launch
PixivDownload.exe

# Common parameters
--no-gui    # Disable desktop GUI (suitable for server/Docker)
--intro     # Open product intro page on startup
--help, -h  # Print help and exit
```

?> Desktop GUI (Swing) launches by default. `--no-gui` is only recommended for server scenarios.

### CLI Admin Commands

| Command | Purpose |
|---------|---------|
| `--setup` | First-time initialization (account + mode + proxy) |
| `--change-password` | Change admin password |
| `--reset-password` | Force reset when password is forgotten |

See [First-Time Setup](/en/first-setup) for details.

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

Check "**Auto-bookmark**" in download settings — after download completes, the backend automatically bookmarks the artwork via the Pixiv API using cookies.

!> Requires a saved valid cookie containing `PHPSESSID`. Bookmarking is best-effort — a bookmark failure will not cause the download task to fail.

---

## Ugoira Download

Ugoira artworks are automatically detected and processed:

1. Download ZIP frame package
2. Extract frames and sort by filename
3. Call `ffmpeg` to synthesize into a WebP animation
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

!> Database validator and backfill tools require exclusive SQLite access. The GUI automatically handles backend pause & resume — no manual service stop needed.

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
