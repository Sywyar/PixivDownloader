# Configuration reference

For routine changes, use the desktop GUI's "Configuration / Settings" page, save, then apply immediately or restart as prompted. Before editing a configuration file manually, identify the setting's file and owner:

| Configuration type | Path | Owner |
| --- | --- | --- |
| Host settings and plugin enabled states | `config/config.yaml` | Application host |
| Plugin business settings | `config/plugins/{pluginId}.properties` | Respective plugin |
| Sensitive plugin credentials | `config/credentials/{pluginId}.properties` | Respective plugin (encrypted and maintained by the host) |

The default `config/config.yaml` is generated on first startup. Upgrades append missing host keys without overwriting existing values. Manual edits must use UTF-8 flat `key: value` lines. Do not comment out settings instead of leaving values empty, or put keys belonging to externalized plugins (such as `mail.*`, `push.*`, `notification.*`, `download.novel-*`) back into `config.yaml`.

## Credential keys and backups

Each installation generates an independent random master key, stored in protected form at `config/credentials.key`. Windows uses the current user's DPAPI by default; run a service under the same account each time. Before changing Windows users or computers, export a password-protected `.pdbackup` that includes credentials, then import it on the target. Copying the configuration directory alone is insufficient.

Linux, macOS and Docker require a separate protection key file before credentials are first saved or upgraded. It must contain the standard Base64 encoding of 32 random bytes, be readable only by the application account, and stay outside the configuration directory and its backups. With OpenSSL installed, run this once in Bash to create the file and set the launch environment. An existing file will not be overwritten:

```bash
umask 077
mkdir -p "$HOME/.config/pixivdownload-secrets"
( set -C; openssl rand -base64 32 > "$HOME/.config/pixivdownload-secrets/credentials.key" )
export PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE="$HOME/.config/pixivdownload-secrets/credentials.key"
```

Start the application from the same terminal; background services need the variable in their service environment. For Docker, mount the file read-only at `/run/secrets/pixivdownload-credentials` and set the variable to that container path. Java also accepts `-Dpixivdownload.credential-key-file=/absolute/path`. Keep a separate secure backup of the protection key; do not regenerate it at each startup. If the key is lost or does not match, the application preserves the credentials and refuses to decrypt them.

Normal credential reads and writes automatically upgrade legacy files, including credentials for disabled plugins. Legacy keys are used only for this upgrade; once every file has migrated, replayed legacy files are rejected. Damaged files remain for repair and retry while other plugins can continue. Migration does not change the protection of existing offline backups. Use an encrypted `.pdbackup` for transfers between environments; import re-encrypts credentials with the target installation key.

The repository's Compose template mounts the protection key automatically. Keep the host variable set to the host file path; Compose refuses to start if it is unset. On a Linux host, give only the container account, UID/GID `10001:10001`, read access. After generating the file, for example, run `sudo chown 10001:10001 "$PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE"` and `sudo chmod 400 "$PIXIVDOWNLOAD_CREDENTIAL_KEY_FILE"`. File-based Compose secrets retain host permissions.

## Configuration and environment migration

Both Swing and Compose settings provide "Configuration and environment transfer", which reads only saved configuration (save changes before export and resolve unsaved drafts before import):

1. **Export `.pdbackup`**: includes host and registered plugin settings, plugin switches and desktop preferences by default. Optionally include credentials, databases, saved state and user resources (collection icons and novel reference audio). An archive password is mandatory when including credentials, state or databases; the backend stops temporarily during export.
2. **Preview and import**: prepare compatible plugins and database connections in the target environment, then select the archive to preview it (settings and files for uninstalled plugins are not written; the archive neither installs plugins nor restores trust grants). Select fields, databases and file groups to import (resources must be imported together with databases). After confirmation, the target is backed up first, and migration runs after a full process restart.

Import preserves the target environment's database connection, database provider selection and installation identity, and re-encrypts credentials with the target master key. Importing administrator credentials replaces the login account and signs out old sessions. Scheduled tasks are restored disabled and manual queues paused. The application itself, plugin packages, downloaded works and caches are not in the archive; copy work directories separately (path mapping updates only declared path fields and database prefixes; `{0}` is fixed to the confirmed absolute directory). Task backups and per-database reports are saved in `data/environment-transfers/<task ID>/` (see `pending.json` for the latest state). If "Recovery required" appears, the application blocks startup. Check the reports and backups; do not delete logs to force startup.

### Move a Windows installation directory

After moving the Windows installation directory, run the installer, select the new directory and confirm migration. It updates the registry installation path, shortcuts, uninstall records and any existing current-user startup entry (it does not enable startup if it was disabled). Repair backups are kept in `state/installation-backups/` in the new directory. Confirm startup and data at the new location before cleaning the old directory, and do not run the old directory's uninstaller. For an older MSI edition, export a backup, uninstall the MSI, then install the new edition and import.

## Host settings (`config/config.yaml`)

### Service, downloads and concurrency limits

| Key | Default | Description |
| --- | --- | --- |
| `server.port` | `6999` | HTTP / HTTPS listening port |
| `debug.enabled` | `false` | Debug mode |
| `download.root-folder` | `pixiv-download/{artwork_id}` | Final illustration directory template; see below |
| `local-import.presets` | `[]` | Reusable path templates; saving from Tools applies them to subsequent scans. [Guide and examples](/en/local-import) |
| `download.mode-templates.<mode>` | See examples below | Override the entire directory per acquisition mode; blank uses the default |
| `download.max-concurrent` | `10` | Download concurrency limit; at most 100 additional works can queue, with 429 returned when full |
| `database.maximum-pool-size` | `28` | Main database connection pool limit (minimum `8`) |
| `ffmpeg.max-concurrent` | `2` | Shared concurrency for FFmpeg transcoding and media probing (range `1–8`; takes effect after a backend restart) |

Fixed safety limits (cannot be raised through configuration): 100 MiB per work image / cover / embedded image and 1 GiB total per illustration or novel download task. Ugoira ZIPs are limited to 100 MiB, 500 frames/entries, 32 MiB extracted per entry, 200 MiB total extracted data and 25,000,000 pixels per frame. A transcode is limited to 10 minutes and 100 MiB output. The service checks `Content-Length`, the actual decoded stream and an image-header allowlist; exceeding limits aborts the operation and removes partial files.

### Main database and engine switching

The default is `database-sqlite` (`data/pixiv_download.db`). To switch to PostgreSQL 17 / 18 (UTF8), first install `database-postgresql` from the plugin market and fully restart. The main database and adapted plugins' private databases share the selected engine (SQLite private databases are at `data/{pluginId}/plugin.db`; PostgreSQL private databases use owner-isolated schemas and restricted roles).

If both the GUI and database are unavailable, open the plugin market from the host recovery window to repair plugins, or use Installed to re-enable disabled plugins. Restart, then check the connection in desktop database settings. These settings remain accessible when the database is unavailable.

Switch under desktop "Settings → Runtime & network → Main database":

1. Select a loaded database plugin, enter connection details (an empty password field retains the saved password), then click "Test connection".
2. Click "Check and continue" to preflight the source, target and handling of the main database and all private plugin databases (a non-empty target is allowed only if its provider supports safe backup and replacement).
3. Confirm the list. The application saves this page's settings and automatically performs a full restart. The new process backs up, imports, verifies each database (typed SHA-256, row counts, generated-key watermarks and NUL-character cleanup), and performs atomic replacement. Connection settings switch only after every check passes. To correct connection parameters for the same database, use "Apply connection and restart".

Migration records and backups are stored in `data/database-migrations/` (they may contain plan credentials; keep them secure and retain them for startup checks and retries). If the selected database plugin is missing, disabled or cannot connect, the application enters recovery mode (business APIs return 503 and background database tasks stop). It does not automatically connect to another engine or create an empty SQLite database.

#### PostgreSQL preparation and example configuration

Create a dedicated database and main schema first, granting permissions for tables, indexes, sequences, functions, triggers, reads and writes. Private plugin databases additionally require `CREATEROLE` and database `CREATE`; do not grant `PUBLIC` access to the main database or other owners' privileges. `currentSchema` accepts only one name made of lowercase letters, digits or underscores (cannot start with a digit; at most 63 characters; defaults to `public`). For remote connections, `sslmode=verify-full` is recommended. Put the password in its own setting or the `DATABASE_PASSWORD` environment variable, never in the JDBC URL (editing `config.yaml` directly does not migrate existing data):

```yaml
database.provider: database-postgresql
database.engine: postgresql
database.jdbc-url: "jdbc:postgresql://db.example.com:5432/pixiv?currentSchema=pixiv&sslmode=verify-full"
database.username: pixiv_app
database.password: "REPLACE_WITH_DATABASE_PASSWORD"
```

#### Offline CLI migration tool (optional)

To schedule downtime independently and migrate to an **empty target database**, stop every process using the source, prepare `source.yaml` and `target.yaml` with explicit `database.provider` entries (a SQLite source can use `database.provider: database-sqlite` and `database.jdbc-url: "jdbc:sqlite:data/pixiv_download.db"`), then run from the fully extracted directory:

```powershell
java '-Dloader.main=top.sywyar.pixivdownload.migration.DatabaseMigration' -cp '<boot.jar>' org.springframework.boot.loader.launch.PropertiesLauncher --source-config source.yaml --target-config target.yaml --backup migration/source.backup --report migration/report.json
```

- If the source has rebuildable novel objects, explicitly add `--rebuild-objects novels_fts,novel_soft_delete_cleanup` (name only objects that actually exist).
- For a plugin you plan to uninstall while retaining its database data, export its declarations with `--export-schema schema.json` before uninstalling, then include them during migration with `--retained-schema schema.json`.
- Copy verification is complete only when `report.json` has status `DATA_VERIFIED` (and all `stores` are `COMMITTED`). Only then switch the active configuration and start the application.

### Image and Ugoira output formats

Select one or more output formats under desktop "Settings → Downloads" or web download settings (keep at least one; defaults are saved under `download-workbench.media.*` in `config/plugins/download-workbench.properties`):

- **Images**: original format (default), PNG, JPG and WebP. If original format is deselected, the original image is deleted only after every selected derivative is generated successfully.
- **Ugoira**: WebP (default), GIF, APNG, MP4 (requires an FFmpeg H.264 encoder) and original ZIP (also saves `.frames.properties` timing data for later offline conversion).
- **Transcoding parameters**: `quality` defaults to `90` (`1–100`), `webp-lossless` to `false`, and `maximum-edge` to `0` (no resizing; may be set to `1–16383`). These apply only to newly encoded copies.

For existing works, desktop "Tools → Historical media processing" detects and generates missing formats and thumbnails for the chosen target formats (at most 500 works and 10,000 candidate files per batch). The gallery prefers "retained original → WebP → PNG → JPG" for display; MP4 uses a video player.

### Plugin market

| Key | Default | Description |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | Global plugin market switch; when disabled, no plugin repository is accessed |
| `plugin-catalog.official-repository-enabled` | `true` | Enable the embedded official repository |
| `plugin-catalog.community-repository-enabled` | `true` | Enable the embedded community repository |
| `plugin-catalog.connect-timeout-ms` | `15000` | Repository connection timeout in milliseconds |
| `plugin-catalog.read-timeout-ms` | `60000` | Repository read timeout in milliseconds |
| `plugin-catalog.max-manifest-bytes` | `1048576` | Maximum size of one manifest (1 MiB) |
| `plugin-catalog.max-package-bytes` | `201326592` | Maximum size of one plugin package (192 MiB) |
| `plugin-catalog.repositories` | Empty list | Custom plugin repository list |

Use desktop "Settings → Plugin market settings" to import a `repository.json` descriptor or enter a custom repository. Manual configuration example:

```yaml
plugin-catalog.enabled: true
plugin-catalog.repositories:
  - id: example
    display-name-key: plugin.market.repository.example.name
    manifest-url: https://plugins.example.com/manifest.json
    enabled: true
    proxy-policy: direct-strict
    trusted-keys:
      - key-id: example-2026
        algorithm: Ed25519
        public-key: BASE64_X509_SUBJECT_PUBLIC_KEY_INFO
        state: ACTIVE
        publisher: Example Publisher
        trust-label: Example repository release key
```

Repository `id` must be unique and cannot use the reserved values `official`, `community` or `configured`. `proxy-policy` supports `direct-strict` (direct access, public HTTPS only, no redirects), `proxy-trusted` (uses the application proxy; at most 5 redirects only for built-in trusted hosts, rechecked at each hop) and `custom`.

### Outbound proxy and browser PAC

| Key | Default | Description |
| --- | --- | --- |
| `proxy.enabled` | `true` | Enable the host's outbound HTTP proxy |
| `proxy.host` | `127.0.0.1` | Proxy host |
| `proxy.port` | `7890` | Proxy port |

To have browser access to `pixiv.net` (for example, when using userscripts) use the same proxy configured in the backend without enabling a global system proxy, set the system or browser's "Automatic proxy configuration script (PAC) URL" to `http://localhost:6999/proxy.pac` (accessible only locally, automatically updated on proxy configuration hot reload, and proxies only Pixiv-related domains).

### Multi-mode quotas and guest rate limits

`multi-mode.*` applies only to **visitors** in multi mode who are not signed in and have no invitation code. **Invited guests** use separate `guest-invite.*` limits in both `solo` / `multi` modes:

| Key | Default | Description |
| --- | --- | --- |
| `multi-mode.quota.enabled` | `true` | Visitor quota switch |
| `multi-mode.quota.max-artworks` | `50` | Maximum work quota per period |
| `multi-mode.quota.reset-period-hours` | `24` | Quota reset period in hours |
| `multi-mode.quota.archive-expire-minutes` | `60` | Packaged archive expiry in minutes |
| `multi-mode.quota.limit-image` | `0` | Image count above which one work consumes multiple quota units (`0` disables conversion) |
| `multi-mode.quota.max-proxy-requests` | `200` | Proxy request quota |
| `multi-mode.quota.archive-max-concurrent` | `10` | Archive concurrency limit |
| `multi-mode.post-download-mode` | `pack-and-delete` | Options: `pack-and-delete`, `never-delete`, `timed-delete` |
| `multi-mode.delete-after-hours` | `72` | Retention hours for `timed-delete` |
| `multi-mode.limit-page` | `3` | Page limit (`0` means unlimited) |
| `multi-mode.request-limit-minute` | `300` | Visitor requests per minute |
| `multi-mode.static-resource-request-limit-minute` | `1200` | Visitor static resource requests per minute |
| `guest-invite.request-limit-minute` | `300` | Invited guest requests per minute |
| `guest-invite.static-resource-request-limit-minute` | `1200` | Invited guest static resource requests per minute |
| `guest-invite.tts-request-limit-minute` | `30` | Invited guest TTS requests per minute |
| `setup.login-rate-limit-minute` | `10` | Login and invitation-code redemption attempts per minute |

### Maintenance windows, interface, updates and scheduled tasks

- **Maintenance windows**: `maintenance.enabled` defaults to `true`; `maintenance.monday.enabled=true`, other weekdays default to `false`, and each weekday's `maintenance.<day>.time` defaults to `10:00`.
- **Language and desktop interface**: `app.language` defaults to empty (follows the system and affects only the interface; runtime logs remain English); `app.theme` defaults to `system` (options include `light`, `dark` and extension themes for the current interface); `app.gui-provider` defaults to empty (selects Compose by default; changing it requires a full restart); `app.config-menu-expand-all` (default `false`) applies only to Swing.
- **Online updates**: `update.enabled=true`, `update.auto-check=true`; `update.manifest-url` and `update.nightly-manifest-url` point to official signed manifests; `update.check-nightly` defaults to `false` for stable builds and `true` for nightly builds.
- **Scheduled-task host**: see [Scheduled-task settings](/en/scheduled-tasks#troubleshooting-and-related-settings) for `schedule.*` keys.
- **Plugin switches**: the host manages `plugins.{pluginId}.enabled`; how a change takes effect depends on the plugin lifecycle policy (see [Plugin management](/en/plugin-management)).

### HTTPS and reverse proxies

| Key | Default | Description |
| --- | --- | --- |
| `ssl.domain` | `localhost` | External access domain |
| `ssl.type` | `pem` | `pem` or `jks` (PEM takes precedence when both are configured) |
| `server.ssl.enabled` | `false` | Enable built-in HTTPS |
| `server.ssl.certificate` / `server.ssl.certificate-private-key` | Empty | PEM certificate and private-key paths |
| `server.ssl.key-store-type` / `key-store` / `key-store-password` | `JKS` / empty / empty | JKS keystore settings |
| `ssl.http-redirect` / `ssl.http-redirect-port` | `false` / `80` | Automatic HTTP-to-HTTPS redirect |
| `server.trusted-proxy-cidrs` | Empty | Trusted reverse proxy egress IPv4 / IPv6 CIDRs, comma-separated |

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

With `server.trusted-proxy-cidrs` empty, the server runs in direct-access mode and rejects any request containing `Forwarded`, `X-Forwarded-*` or `X-Real-IP`. When configuring it, include only the actual reverse proxy egress addresses or container subnets connecting to the backend (never `0.0.0.0/0`, `::/0` or client subnets). A trusted reverse proxy must supply complete forwarding headers in one format for every request: RFC `Forwarded` with `for`/`proto`/`host`, or a complete set of `X-Forwarded-For` + `X-Forwarded-Proto` + `X-Forwarded-Host`. Missing or mixed formats, or headerless direct access from a trusted CIDR, return HTTP 400.

## Plugin settings, credentials and extensions

- **Plugin business settings (`config/plugins/{pluginId}.properties`)**: UTF-8 Java properties, presented together and saved through desktop settings. They must not override host keys or contain plaintext credentials.
- **Plugin credentials (`config/credentials/{pluginId}.properties`)**: store passwords, cookies, API Keys, Secrets and Webhook keys, encrypted by the host and injected into plugin contexts by owner. For transfers between environments, use a password-protected `.pdbackup` that includes credentials. A file backup for the same environment also needs `config/credentials.key` and its unlocking requirements.
- **Translation settings (`translation` plugin)**: ordinary settings are in `config/plugins/translation.properties` (`translation.engine` defaults to `ai`, with `youdao` and `libretranslate` also available; `translation.libretranslate-url` excludes `/translate`; `translation.use-proxy` defaults to `false` and controls only Youdao and LibreTranslate; `translation.max-concurrent` defaults to `10`). Save and restart the backend to apply. See [Novels and narration](/en/novel#translation-engines-administrator).
- **Login verification policies**: `setup.login-verification-policies` defaults to empty. Configure at most 16 comma-separated `LoginVerification.policyId()` policy keys, then restart the backend. Confirm that the corresponding verification plugins are enabled before configuring this setting; see [Operation CAPTCHA](/en/captcha) and [Third-party plugin SDK](/en/plugin-development#credentials-and-general-operation-verification-without-a-gui-dependency).

## Work directory templates :id=directory-templates

In Settings → Downloads, edit the illustration and novel directories separately. The expanded path is the final work directory; no author or artwork ID levels are appended. Relative paths resolve from the application working directory, and absolute paths are also supported. Save and restart the application to apply changes.

Each work type has one default template. Use Add mode to override the entire directory for individual works, User, series, search or quick acquisition. Modes that are absent, removed or blank use that work type's default. The mode describes how the current download was requested; belonging to a series does not select series mode.

Illustration templates are stored in the host's `config/config.yaml` under `download.root-folder` and `download.mode-templates.<mode>`. The `novel` plugin stores novel templates in `config/plugins/novel.properties` under `download.novel-directory-template` and `download.novel-mode-templates.<mode>`. `<mode>` is `single-import`, `user`, `series`, `search` or `quick`.

Available variables: `{artwork_id}`, `{artwork_title}`, `{author_id}`, `{author_name}`, `{collection_name}`, `{collection_id}`, `{series_id}`, `{series_name}`, `{series_title}`, `{series_order}`, `{username}` and `{R18}`. Use these exact spellings.

A template must contain a path segment whose only variable is `{artwork_id}`. Fixed text is allowed in that segment, as in `novel-{artwork_id}`. Novel IDs remain numeric; use a prefix such as `novel-` to keep novel and illustration directories separate.

`{username}` is the username from the acquisition source. Missing collection, series and `R18` values expand to empty strings. Entirely empty directory levels are omitted, while fixed text in the same level remains. Missing other required values rejects the download. Local-import variables such as `{page}`, `{extension}` and `{name}` are unavailable in save directories.

Default templates and examples of optional mode overrides:

| Use | Template |
| --- | --- |
| Illustration default | `pixiv-download/{artwork_id}` |
| Illustration User | `pixiv-download/{username}/{R18}/{artwork_id}` |
| Novel default | `pixiv-download/novel-{artwork_id}` |
| Novel User | `pixiv-download/{username}/{R18}/novel-{artwork_id}` |
| Series-mode example | `pixiv-download/{series_id}/{series_order}/{artwork_id}` |

When the selected collection has a custom directory, it replaces the fixed path before the first directory segment containing a variable. That segment and everything after it are retained, including fixed text. For example, `pixiv-download/novel-{artwork_id}` with a collection directory of `D:/Favorites` expands using `D:/Favorites/novel-{artwork_id}`.

These templates always specify download directories and do not require a trailing `/`. Local import uses a separate rule: a trailing `/` matches folders, while no trailing `/` matches files. See [local artwork import](/en/local-import). Template changes affect future downloads and do not move existing files; see [storage and backups](/en/storage) for moving directories.
