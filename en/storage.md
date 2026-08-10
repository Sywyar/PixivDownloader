# Storage Principles

PixivDownloader separates downloaded works, host runtime files, and installed external plugins. Paths are relative to the process **working directory**, not necessarily the JAR's directory. Distribution launch scripts and Windows shortcuts set the working directory to the distribution directory.

## Top-level directories

| Category | Default path | Contents |
| --- | --- | --- |
| Configuration | `config/` | Host settings, plugin business settings, and encrypted credentials |
| State | `state/` | Setup state, queue checkpoints, GUI markers, and recoverable plugin state |
| Data | `data/` | SQLite, user resources, caches, and persistent plugin data |
| Plugins | `plugins/` | External plugin artifacts, provenance, and frozen runtime copies |
| Logs | `log/` | GUI and backend logs |
| Downloaded works | `{rootFolder}/` | Artifacts selected by `download.root-folder` |

`download.root-folder` defaults to the relative path `pixiv-download`. It contains works, work metadata sidecars, and temporary export archives only. Configuration, databases, plugin packages, state, and caches do not belong there.

## Working-directory layout

### Configuration

| Path | Purpose |
| --- | --- |
| `config/config.yaml` | Host configuration and `plugins.{id}.enabled` state |
| `config/plugins/{pluginId}.properties` | Non-sensitive business settings owned by that plugin |
| `config/credentials/{pluginId}.properties` | Credential envelopes encrypted by the host and injected only into that plugin |
| `config/image_classifier.properties` | Image-classifier target directories |

See [Configuration Reference](/en/configuration) for ownership rules. Do not exchange, merge, or rename files between owners.

### State

| Path | Purpose |
| --- | --- |
| `state/setup_config.json` | Initial setup, runtime mode, and login state |
| `state/download-workbench/batch_state.json` | Download-workbench batch queue checkpoint |
| `state/download-workbench/layout-feedback-state.json` | Download-workbench layout-feedback deduplication state |
| `state/gui/` | GUI onboarding and proxy-step markers |
| `state/download_root_marker.txt` | Previously resolved absolute download root |
| `state/{pluginId}/` | Owner state root obtained through `RuntimePathProvider`; created on demand |

Deleting state does not always mean harmless regeneration. It can require setup or login again, or lose queue checkpoints and plugin state. Identify the owner before cleanup.

### Data

| Path | Purpose |
| --- | --- |
| `data/pixiv_download.db` | Main SQLite database; `-wal` / `-shm` files may exist while running |
| `data/collection_icons/{id}.{ext}` | Custom collection icons |
| `data/gallery_thumbs/{artworkId}/p{n}.{ext}` | Rebuildable gallery thumbnail cache |
| `data/tts/chromium-version.txt` | TTS plugin Edge TTS Chromium-version cache |
| `data/novel/narration-voice/{castId}/{characterId}.{ext}` | Novel-plugin character reference audio |
| `data/backfill/unreachable.json` | Backfill tool's unreachable-work record |
| `data/install_identity.txt` | Installation UUID generated once and permanently reused |
| `data/delete-staging/{operationId}/` | Atomic rollback staging for work deletion |
| `data/{pluginId}/` | Owner data root obtained through `RuntimePathProvider`; created on demand |

The main database stores work facts, path references, history, and domain data written by installed features. Plugin-private tables remain owned by their plugin's schema and lifecycle. Do not copy only the `.db` file while leaving an active WAL behind; shut the application down normally before backup.

### External plugins

| Path | Purpose |
| --- | --- |
| `plugins/*.jar`, `plugins/*.zip` | Installed original artifacts; management identity and offline-verification trust source |
| `plugins/provenance/<artifact>.pixiv-plugin-provenance` | Origin, digest, signature, and last verification result |
| `plugins/runtime/` | Random private frozen workspace for each live generation; not a shared cache or install source |
| `plugins/.preparing/`, `plugins/.staging/`, `plugins/.transaction-cleanup/` | Managed install-transaction and crash-recovery directories |
| `plugins/.pixivdownload-runtime.lock` | Runtime directory lease |

The system property `pixivdownload.plugins-dir` can override the plugin root. The runtime does not create a missing directory automatically; it reports a diagnostic and lets the core shell enter recovery.

Do not overwrite, move, or delete files under `plugins/` while the application is running. Installation, upgrade, removal, and rollback must use plugin management so the artifact and provenance move transactionally. `plugins/runtime/` can be rebuilt from a verified installed artifact, but it is not a download cache for another process to reuse.

## Downloaded-work layout

Common paths are below. A plugin may define a more detailed layout inside its own work directory.

| Path | Contents |
| --- | --- |
| `{root}/{artworkId}/` | Pixiv single-work, URL batch, and search downloads |
| `{root}/{artist}/{artworkId}/` | Artist downloads; omit the artist level when `download.user-flat-folder=true` |
| `{root}/{artworkId}/{workId}.meta.json` | Structural metadata sidecar moved/deleted with the work |
| `{root}/artwork-series-{seriesId}/cover.{ext}` | Pixiv manga-series cover |
| `{root}/novel-{novelId}/` | Single-novel TXT/HTML/EPUB and related work files |
| `{root}/novel-series-{seriesId}/` | Novel-series cover and optional compilation |
| `{root}/douyin/{owner}/...` | Douyin plugin's default output |
| `{root}/_archives/{token}.zip` | Short-lived multi-mode quota and gallery export archives |

Douyin derives its default root from `DownloadSettings.getRootFolder()` plus `douyin`, then isolates output by request owner. A non-empty `douyin.download.directory` plugin setting replaces that root. The obsolete `data/douyin/downloads` path is not used. A collection may also select a work root outside the default download root.

A third-party download type should likewise write works below a plugin-id directory under `download.root-folder`, or to a work directory explicitly chosen in that plugin's settings. `state/{pluginId}` and `data/{pluginId}` are for auxiliary state and data, not downloaded works.

## Database path encoding

The database avoids repeating long absolute paths by storing prefix references:

```text
{N}/relative/path
```

For `N>0`, `N` identifies an absolute prefix in `path_prefixes`. Updating that prefix redirects every reference that uses it.

### `{0}` symbolic root

When `download.root-folder` is relative, records under it can use `{0}/...`. `{0}` resolves on every startup to “current working directory + current relative download root.” Moving the whole distribution together with `pixiv-download/` therefore keeps history references valid.

When the download root is absolute, records use regular `{N}` prefixes. After moving that directory, use “Migrate Download Directory” on the GUI Status page to update references.

`state/download_root_marker.txt` records the previous resolution and detects a configuration change made without moving files. The migration tool updates configuration and database references; it **does not move files on disk**.

## Relocation

### Move the whole distribution

Keep `download.root-folder` relative, shut down the application, and move the entire distribution directory. Start it through the launch script in the new location so the working directory, runtime files, and `{0}` move together.

### Move only the download root

1. Shut the application down normally.
2. Move the work directory in the filesystem.
3. Open “Migrate Download Directory” from the GUI Status page, select the actual new location, and choose whether to update `config.yaml` too.
4. Restart when prompted and spot-check history, gallery entries, and a new download.

Do not edit `download.root-folder` first and expect the application to move files; it does not.

## Backup and restore

A complete backup should include:

- `config/`, including plugin business settings and encrypted credentials;
- `state/`, preserving setup, login, queue, and plugin state;
- `data/`, copied after the application has stopped;
- `plugins/`, preserving third-party/on-demand artifacts, signatures, and provenance;
- `download.root-folder` and any other work directories selected by collections or plugin settings.

`log/` is usually needed only for troubleshooting. `data/gallery_thumbs/` and `plugins/runtime/` are rebuildable, but including them in a whole-directory backup is harmless.

On restore, preserve the relative layout or use the migration tool for absolute roots. Encrypted credentials also depend on the credential master key that produced their envelopes. Confirm key compatibility before restoring across builds or deployments; otherwise re-enter credentials in the target environment.
