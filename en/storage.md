# Storage and backup

PixivDownloader keeps downloaded works, host runtime files and external plugin packages in separate directories. All relative paths are based on the application's **current working directory**, not the JAR's directory. Distribution startup scripts and Windows shortcuts set the working directory to the application root.

## Top-level directories

| Category | Default path | Purpose |
| --- | --- | --- |
| Configuration | `config/` | Host `config.yaml`, plugin business configuration and encrypted credentials |
| State | `state/` | Initialization state, login sessions, queue checkpoints, GUI onboarding markers and plugin state |
| Data | `data/` | SQLite main database, private plugin databases, collection icons, thumbnail / TTS caches and migration / recovery records |
| Plugins | `plugins/` | External plugin packages, provenance and private frozen runtime workspaces |
| Logs | `log/` | GUI and backend runtime logs |
| Works | Expanded save directory template | Final artwork and novel directories; modes can override the default |

Work metadata and per-page media records are stored centrally in the selected database. New downloads no longer create `.meta.json` or `.media.properties` in work directories. When an original Ugoira ZIP is retained, its `.frames.properties` frame timings are still included. Do not put configuration, databases, plugin packages, state or caches in the work download root.

## Runtime file details

### Configuration, state and data

| Path | Content and notes |
| --- | --- |
| `config/config.yaml` | Host configuration and `plugins.{id}.enabled` enablement state |
| `config/plugins/{pluginId}.properties` | Nonsensitive business configuration for the plugin |
| `config/credentials/{pluginId}.properties` | Sensitive credential envelopes, encrypted and maintained by the host and injected per owner |
| `config/image_classifier.properties` | Retained settings for the retired image classifier |
| `config/web-publications.enrolled` | Page editor live-publication enrollment file; do not remove it as cache |
| `state/setup_config.json` | First-time account initialization, operating mode and login state; deleting it requires initialization again |
| `state/download-workbench/` | Download workbench batch-queue checkpoints and layout-feedback state |
| `state/gui/`, `state/download_root_marker.txt` | GUI onboarding markers and the last resolved absolute download-root path |
| `state/{pluginId}/` | Owner state directory obtained by a plugin through `RuntimePathProvider` |
| `data/pixiv_download.db` | Default SQLite main database; runtime may also create `-wal` / `-shm`, so stop the app normally before backing up |
| `data/collection_icons/`, `data/novel/narration-voice/` | Custom collection icons and reference voices for novel multi-character narration |
| `data/gallery_thumbs/`, `data/tts/`, `data/backfill/` | Rebuildable gallery thumbnail cache, Edge TTS version cache and backfill unreachable records |
| `data/install_identity.txt` | Persistent installation UUID generated on first run |
| `data/delete-staging/`, `data/database-migrations/`, `data/environment-transfers/`, `data/web-publications/` | Deletion / publication recovery staging, database-switch and environment-transfer backups and reports, and published-page state; do not delete as cache |
| `data/{pluginId}/` | Persistent plugin data and private SQLite `plugin.db` |

### External plugin directory (`plugins/`)

| Path | Purpose |
| --- | --- |
| `plugins/*.jar`, `plugins/*.zip` | Original installed plugin packages, used for offline reverification and identity trust |
| `plugins/provenance/*.pixiv-plugin-provenance` | Plugin source, digest, signature and reverification records |
| `plugins/runtime/` | Private frozen workspace owned exclusively by each runtime generation; not a shared cache or installation source |
| `plugins/.preparing/`, `.staging/`, `.transaction-cleanup/`, `.pixivdownload-runtime.lock` | Installation transactions, crash recovery and runtime directory locking |

Set the plugin root through the JVM property `pixivdownload.plugins-dir`. Never manually overwrite, move or delete files under `plugins/` while the app is running. Installations, updates and uninstalls all use [plugin management](/en/plugin-management) transactions.

## Downloaded-work directory layout

Illustration and novel save paths are controlled by [work directory templates](/en/configuration?id=directory-templates) (defaulting to `pixiv-download/{artwork_id}` for illustrations and `pixiv-download/novel-{artwork_id}` for novels, with per-mode overrides such as User mode). In the table below, `{root}` denotes the fixed prefix before the first variable in the default illustration template:

| Default path | Content |
| --- | --- |
| `pixiv-download/{artwork_id}/` | Illustrations / manga / animations from individual-work, URL-batch, search and similar downloads, including generated `_p0.webp` and `_p0_thumb.jpg` |
| `pixiv-download/{username}/{R18}/{artwork_id}/` | Artist batch downloads (default illustration User-mode template) |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | Manga-series cover |
| `pixiv-download/novel-{artwork_id}/` | A single novel's TXT / HTML / EPUB and embedded images |
| `{root}/novel-series-{seriesId}/` | Novel-series cover and compiled book |
| `{root}/douyin/{owner}/...` | Douyin plugin's default download directory, or its configured custom directory |
| `{root}/_archives/{token}.zip` | Short-lived archives for batch export and multi-mode quotas |

Third-party download-type plugins should likewise save works under `{root}/{pluginId}/` or the work directory specified in their settings. Legacy data and metadata import on the Tools page can import historical `<work ID>.meta.json` snapshots from registered directories; if no snapshot exists but paths contain artwork IDs, use [local artwork import](/en/local-import) to re-register file references and backfill metadata.

## Database path prefixes and relocation

The database can refer to a registered prefix as `{N}/relative/path`. When you correct an artwork directory, a `..` segment after the matching prefix makes the database keep the original absolute path. Changing that prefix later leaves the artwork pointing to the directory you entered.

- **`{0}` symbolic root**: when the fixed prefix of the default illustration template is a relative path (such as `pixiv-download` in `pixiv-download/{artwork_id}`), files under that prefix are recorded as `{0}/...` and resolved dynamically against the current working directory at startup. **Moving the entire application directory together with that relative download folder requires no manual database path edits.**
- **Ordinary `{N}` prefixes**: when the download root or a collection uses an absolute path, records refer to an absolute prefix in the `path_prefixes` table. Restoring the original paths keeps each historical prefix ID and its artwork references, even when two IDs point to the same directory. If you change a destination, choose a directory distinct from the other prefixes.

Restore uses the path syntax from the source system to match artwork records. An ordinary prefix cannot be remapped to another ordinary prefix's existing directory, even through a symbolic link. Restore and desktop root migration follow the filesystem when a symbolic link precedes `..`. On POSIX, prefix registration and updates, symbolic root pinning, root synchronization and individual artwork directory repairs preserve trailing spaces in directory names.

Restored artwork keeps literal backslashes in POSIX filenames. For Windows prefixes, backslashes in older artwork references are read as directory separators. When choosing a desktop download root, use a directory name without braces or, on POSIX, literal backslashes. Leading and trailing spaces are preserved on POSIX.

When restoring a POSIX backup on Windows, a filename such as `volume\one.jpg` (a single POSIX name) or `portrait:blue.jpg` stops the restore before the target database is replaced. The restore log identifies the table, column, and row in the archive. Rename the source file, update its registered path, and export again. On supported paths, rescanning a restored work skips pages that refer to the same file and keeps existing references when adding pages.

Restore preserves filename case. If two different source files would have the same path in the destination directory, restore stops before replacing the database. The log lists both works and page numbers. To move an external artwork directory between systems, register it as a path prefix on the source, export again, and map it on the destination. If restore fails, search the application log for the job ID shown on the desktop.

Map each page under its artwork root. You can copy missing pages after the restore. New backups record the identity of page files that can be identified on the source, so multiple references to the same file keep their original spelling. If the target configuration or data changes after confirmation, preview the archive again before confirming.

To move only the work download root:

1. Stop the app normally.
2. Move the work directory to its new location in the filesystem.
3. Start the app, open Migrate download directory on the desktop GUI's Status page, select the actual new location, and confirm updating both `config.yaml` and database path prefixes. The migration tool updates path references only; it does not move disk files for you.
4. After restarting, spot-check historical works, gallery previews and one new download.

Switching database engines between SQLite and PostgreSQL is a separate process; see [main database and engine switching in the configuration reference](/en/configuration#main-database-and-engine-switching).

## Backup and recovery

After stopping the app, a complete backup should include:

1. `config/`, `state/`, `data/` and `plugins/`;
2. `download.root-folder`, custom collection download directories and custom work directories used by third-party plugins;
3. If using PostgreSQL, the server's main database and all private plugin schemas;
4. When using [import external downloads](/en/pixiv-batch-downloader-import) or [import local artworks](/en/local-import), also back up the external source directories referenced read-only;
5. If restoring encrypted credentials in `config/credentials/` across machines or deployments, the corresponding credential master key too; otherwise, re-enter credentials in the destination environment.

After recovery, verify administrator login, gallery and novel-body reading, scheduled-task state and one new download, in that order.
