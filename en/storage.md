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
| `config/image_classifier.properties` | Directory configuration for the image-classification tool |
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

Illustration and novel directories follow the [work directory templates](/en/configuration?id=directory-templates). The defaults are `pixiv-download/{artwork_id}` for illustrations and `pixiv-download/novel-{artwork_id}` for novels; modes such as User can override them. In the table below, `{root}` means the fixed prefix before the first variable in the default illustration template.

| Default path | Content |
| --- | --- |
| `pixiv-download/{artwork_id}/` | Illustrations / manga / animations from individual-work, URL-batch, search and similar downloads, including generated `_p0.webp` and `_p0_thumb.jpg` |
| `pixiv-download/{username}/{R18}/{artwork_id}/` | Illustration User |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | Manga-series cover |
| `pixiv-download/novel-{artwork_id}/` | A single novel's TXT / HTML / EPUB and embedded images |
| `{root}/novel-series-{seriesId}/` | Novel-series cover and compiled book |
| `{root}/douyin/{owner}/...` | Douyin plugin's default download directory, or its configured custom directory |
| `{root}/_archives/{token}.zip` | Short-lived archives for batch export and multi-mode quotas |

Third-party download-type plugins should likewise save works under `{root}/{pluginId}/` or the work directory specified in their settings. Legacy data and metadata import on the Tools page can import historical `<work ID>.meta.json` snapshots from registered directories. Without a snapshot, paths containing artwork IDs can be used to [import local artworks](/en/local-import) and rebuild file references; metadata absent from the paths still needs to be filled in.

## Database path prefixes and relocation

The database stores file paths as `{N}/relative/path`:

- **`{0}` symbolic root**: when the default illustration template has a relative fixed prefix, such as `pixiv-download` in `pixiv-download/{artwork_id}`, files within that prefix can be recorded as `{0}/...` and resolved against the working directory at startup. Moving the application and that directory together requires no manual changes to those database paths.
- **Ordinary `{N}` prefixes**: when the download root or a collection uses an absolute path, records refer to an absolute prefix in the `path_prefixes` table.

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
