# Storage Principles

This chapter explains in detail where PixivDownloader stores data, how the database records file locations, and what to do when relocating or backing up.

PixivDownloader divides data into two categories, **managed separately**:

- **Artwork files**: the illustrations, manga, ugoira, novels, etc. you download, all stored in the download root directory (`download.root-folder`, default `pixiv-download/`).
- **Runtime data**: configuration, database, cache, logs, etc., stored in four directories under the working directory: `config/`, `state/`, `data/`, `log/`.

> [!NOTE]
> The download root directory contains **only** artwork files. Auxiliary data like the database, thumbnail cache, etc. is never written there, so you can safely place the download root on a high-capacity disk, or sync / back it up independently.

---

## Working Directory Overview

With the program directory (working directory) as root, the complete layout is:

| Path | Contents | Consequence of Deletion |
|------|----------|--------------------------|
| `config/config.yaml` | Main configuration file ([Configuration Reference](/en/configuration)) | Regenerated with defaults on restart; custom config lost |
| `config/image_classifier.properties` | Image classifier directory config | Classifier directory settings lost |
| `state/setup_config.json` | First-run setup wizard result: run mode (solo / multi), login state, etc. | Must re-run the setup wizard |
| `state/batch_state.json` | Batch download queue breakpoint state | Unfinished batch queues lost |
| `state/gui/` | GUI onboarding, proxy wizard, etc. one-time markers | Some onboarding guides reappear |
| `state/download_root_marker.txt` | Last-resolved absolute path of the download root (see "Symbolic Root" below) | Loses one startup check for "config changed but files not moved" |
| `data/pixiv_download.db` | **SQLite main database** (with `-wal` / `-shm` companion files) | All download history, gallery, collections, scheduled tasks, etc. records lost (artwork files unaffected) |
| `data/collection_icons/` | Collection custom icons | Icons revert to default |
| `data/gallery_thumbs/` | Gallery thumbnail cache | Auto-regenerated |
| `data/tts/` | Listen (Edge TTS) version number cache | Auto-reset |
| `data/narration-voice/` | Multi-character narration reference audio | Corresponding characters lose timbre cloning reference audio |
| `data/backfill/` | Unreachable artwork list recorded by backfill tool | Re-detected on next backfill |
| `log/` | GUI and backend runtime logs | Only affects troubleshooting |
| `pixiv-download/` (i.e. `{download root}`) | **All artwork files** | Artworks themselves lost |

---

## Download Root Internal Structure

| Path | Contents |
|------|----------|
| `{root}/{artwork_id}/` | Work directories for single-work, URL batch, search downloads, etc. — one folder per artwork |
| `{root}/{artist_name}/{artwork_id}/` | Works from **artist batch download**; R-18 / R-18G works nest an extra `R18/` or `R18G/` subdirectory. When `download.user-flat-folder: true`, no artist layer is created — same as above |
| `{root}/{artwork_id}/{filename}_p0.webp` + `..._p0_thumb.jpg` | Ugoira animated WebP and first-frame thumbnail |
| `{root}/novel-{novel_id}/` | Downloaded single novels (TXT / HTML / EPUB) and covers |
| `{root}/artwork-series-{series_id}/cover.{ext}` | Manga series cover |
| `{root}/novel-series-{series_id}/` | Novel series covers and compilation files |
| `{root}/_archives/{token}.zip` | Multi-mode quota packaging and gallery batch export archives (have expiry; auto-cleaned when expired) |

Image filenames in work directories default to `{artwork_id}_p{page}.{extension}` and can be customized via "Filename template" in batch download settings (e.g., adding author name, timestamp, etc.).

Additionally, **collections can have their own download directories**: after assigning a dedicated download directory to a collection, works downloaded with that collection checked will land in that directory (which can be outside the download root), with the same directory structure as above.

---

## What the Database Records

`data/pixiv_download.db` is an SQLite database (WAL mode) that stores all records except the artwork files themselves:

- **Download history**: each artwork / novel's title, directory, page count, extension, download time, R-18 flag, author, filename template, etc. — the gallery, dedup ("already downloaded, skip"), and all of that rely on it;
- **Gallery data**: tags, authors, series, collections and their members, statistics;
- **Novel body**: the novel's original content is stored directly in the database (for full-text search, AI translation, re-export); TXT / EPUB on disk are just export artifacts; AI translations and Listen multi-character narration scripts are also in the database;
- **Scheduled tasks**: task definitions, run watermarks, isolation retry queues (bound Pixiv cookies are also stored in the database — keep the database file safe);
- **Suspected duplicates**: perceptual hash for each page image.

> [!WARNING]
> The database and artwork files are **two independent data sets**: deleting the database does not delete artwork files, but all history records, gallery, and dedup info are lost; conversely, deleting only the files without deleting records leaves gallery entries that can't be opened. When backing up, **both** (with `-wal` / `-shm` companion files) should be backed up together.

---

## How Paths Are Recorded: Prefix Encoding & Symbolic Root

The database needs to remember where each artwork is located. Storing tens of thousands of absolute paths directly wastes space, and all become invalid if the directory is moved. PixivDownloader uses **prefix encoding** to solve this:

### `{N}` Prefix Reference

All directory columns (work directory, moved directory, novel directory, series cover directory, collection download directory) are stored as:

```
{N}/relative_path        e.g. {3}/novel-12345
```

`N` points to an absolute path prefix registered in the `path_prefixes` table (e.g. `D:\Pictures\pixiv-classified`). When reading, the prefix is prepended to get the full path. This way each root directory's absolute path is stored only once; using the GUI's "Migrate Download Directory" to rewrite that one entry simultaneously points all records referencing it to the new location.

### `{0}` Symbolic Root: Follows the Software Directory

`{N}` is still pinned to a specific absolute path — copying the entire software folder to a new computer / drive letter would make all records with `D:\old_location\...` invalid. This is solved by the **symbolic root `{0}`**:

- When `download.root-folder` is configured as a **relative path** (the default `pixiv-download` is), records under the download root are encoded as `{0}/relative_path`;
- `{0}` registers no absolute path — on each startup it **dynamically resolves** to "current software directory + configured relative path";
- Therefore, **copying / moving the entire software folder (with the download root inside it) to any new location lets the gallery and download history locate files directly** — no repair actions needed at all. This is the recommended approach for the default installation;
- Absolute-path records left over from older versions are automatically migrated to `{0}` format on first startup after upgrading — no manual action needed.

If you configure `download.root-folder` as an **absolute path** (e.g., pointing to another disk), the symbolic root is not used, and records fall back to `{N}` mode — in that case, relocating the download directory requires the GUI's "Migrate Download Directory" tool.

### Safety Checks: What If You Changed Config but Didn't Move Files?

`state/download_root_marker.txt` records the absolute path the download root resolved to on the last run, supporting two layers of protection:

- **GUI config page interception**: when modifying the path under "Config → Download → Download Root" with old "follow software directory" records in the database, a dialog explains the consequences before saving — continuing will pin old artwork records to the current absolute path (losing the ability to relocate with the software). Click "OK" to auto-pin and save, or "Cancel" to discard the change.
- **Startup check**: if you bypassed the GUI and manually edited `config.yaml` (or changed the launch directory), the resolved location at startup differs from last time:
  - Download root changed to **absolute path** but database still has old "follow software directory" records → after startup the GUI helper shows a fix dialog, guiding you to confirm the old download root where those artworks actually are (pre-filled with the last recorded location); confirming auto-pins old records to that path;
  - Still a relative path but old location has files and new location is empty → likely files weren't moved; startup log keeps warning and suggests using "Migrate Download Directory" to fix, until resolved;
  - New location has full set of files → treated as normal whole-folder relocation, only a note is logged.

### How GUI "Migrate Download Directory" Handles Symbolic Root

The migration tool (GUI Helper → Status page → Migrate Download Directory) prominently shows the "current download root (follows software directory)" with its fully resolved path. After entering a new directory for it, it asks whether to also update `config.yaml` based on the new directory's location:

| New Directory Location | Sync config? | Result |
|------------------------|-------------|--------|
| Still inside software directory | Yes (recommended) | Only changes `config.yaml` to the new **relative path**; database records unchanged, continue following software directory |
| Still inside software directory | No | Database records pinned to new directory's absolute path; config unchanged (new downloads still go to old directory) |
| Outside software directory | Yes | Config changed to new directory's absolute path; database records pinned along with it |
| Outside software directory | No | Only database records pinned to new directory; config unchanged (new downloads still go to old directory) |

> [!NOTE]
> The migration tool only changes database records — it does **not move files on disk**. Please manually move the files to the new directory first (the new directory must already exist), then run migration. After changing the download root, restart the service for it to take effect.

---

## Relocation & Backup Guide

**Whole-folder relocation (recommended)**
Keep `download.root-folder` as a relative path (the default), then simply copy / move the entire software folder to a new location (new computer, new disk — both fine). Start and use — gallery and history records follow automatically, no extra steps needed.

**Relocate only the download directory**
1. Stop the service, move the download root files to the new location;
2. Start GUI Helper → Status page → Migrate Download Directory, choose the appropriate branch per the table above;
3. Restart the service as prompted.

**Backup**
At minimum: `config/` + `data/` (including `-wal` / `-shm`) + download root. `state/` is optional (losing it only means re-running the setup wizard / re-logging in).

> [!WARNING]
> Do not manually edit `download.root-folder` in `config.yaml` without moving the files first. In relative-path mode this would cause all historical records to resolve to a new empty directory (startup log will warn); the correct approach is always "move files first, then use the migration tool to update records / config."
