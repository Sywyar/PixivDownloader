# Configuration Reference

PixivDownloader separates configuration by owner. These stores are not interchangeable:

| Configuration | Path | Owner |
| --- | --- | --- |
| Host settings and plugin enabled state | `config/config.yaml` | App shell |
| Plugin business settings | `config/plugins/{pluginId}.properties` | That plugin |
| Plugin credentials | `config/credentials/{pluginId}.properties` | That plugin; encrypted by the host |

Prefer the desktop GUI's Configuration page. On first startup, the current default template creates `config/config.yaml`; upgrades append missing host keys without overwriting existing values. If editing manually, use UTF-8 and keep active `key: value` lines rather than commenting out empty values.

## Host configuration

### Service, debug, and downloads

| Key | Default | Description |
| --- | --- | --- |
| `server.port` | `6999` | HTTP/HTTPS service port |
| `debug.enabled` | `false` | Debug mode |
| `download.root-folder` | `pixiv-download` | Downloaded-work root |
| `download.user-flat-folder` | `false` | Flat artist-directory layout |
| `download.max-concurrent` | `10` | Host download concurrency; up to 100 more works queue, then new tasks return 429 |
| `database.maximum-pool-size` | `28` | Host main-database pool limit; minimum 8 |

`download.root-folder` contains downloaded works only. Configuration, databases, plugin state, and caches are stored elsewhere. Private settings for novel, Douyin, and other download types belong to their plugins and are exposed after installation.

Pixiv artwork images, novel covers, and embedded images have a fixed safety limit of 100 MiB each. These responses are limited to 1 GiB in total within one ordinary artwork or novel download task. The service checks both `Content-Length` and the actual decoded response stream; an over-limit transfer is stopped and its partial file is removed. The final extension is selected from an image allowlist using the URL path, response `Content-Type`, and verified file signature; query parameters never become part of the filename. These limits cannot be raised through configuration.

Ugoira ZIP files are limited to 100 MiB and 500 entries/frames, with 32 MiB per expanded entry, 200 MiB expanded in total, and 25,000,000 pixels per frame. Each conversion process is limited to 10 minutes and 100 MiB of output. Exceeding a limit stops processing and removes temporary files. ZIP files and frame timing explicitly selected for retention are kept.

### Main database and existing data

The default installation includes a separate SQLite database plugin and preserves `data/pixiv_download.db` and discovery of its legacy location. To use PostgreSQL 17 or 18 with UTF8, install its database plugin from the market, fully restart, then select the connection target. The main database and adapted private plugin storage use the selected engine. SQLite private databases remain at `data/{pluginId}/plugin.db`; PostgreSQL gives each owner a separate schema and restricted login.

#### Switch databases in settings

Use Swing or Compose Settings → Runtime and network → Main database, or the plugin market's database settings:

1. Select a loaded database plugin and enter the connection details. Leave the password empty to keep the saved value. To remove it, select the clear-password option and complete the save.
2. Select "Test connection" to check the address and account. Success means the connection works; it does not save settings, create a database or migrate data. After editing database settings, select "Check and continue" to inspect the source, destination and planned action for application data and every plugin database.
3. Review the confirmation list. If the destination contains data and its provider supports safe replacement, the list explains that the old data will be backed up before replacement. Destinations that cannot be safely replaced are rejected. "Cancel" returns to editing and keeps your entries; the database connection settings have not been saved.
4. Confirm to save other settings on the page, stop business operations and fully restart the application automatically. The new process migrates and verifies the listed data. Only after every database passes verification does it switch the connection settings and continue startup. When only repairing connection details for the same database, use "Apply connection and restart"; no data is copied.

Connection details show the database currently in use and the one selected for the next startup. If environment variables or startup arguments override connection settings, correct those overrides before continuing.

Existing destination data is backed up before replacement. Imported data is verified before databases are replaced one by one. Backups and migration records remain in the task directory under `data/database-migrations/`. Backups can contain schedule credentials and must be protected as sensitive files. Artwork, reference audio and credential files remain in place.

A failure retains the original database connection settings and reports the actual result for each item. Data already migrated and saved is not automatically undone when another item fails. An unknown outcome means the application cannot yet confirm whether that item was saved; it does not mean the write was rolled back. Keep the original database and backups, correct the reported problem, then run the check again. Retries check for source changes and verify destination contents before reusing data already migrated.

The application enters recovery mode when no database plugin is available, the selected plugin is missing, disabled, incompatible or fails to load, or the database connection cannot be established at startup. Business pages redirect to the market and business APIs return 503. Direct semantic queries return empty results with an unavailable status; writes and dependent background operations stop. Restore the original plugin or repair the connection, then use the checks and confirmation above to restart. The application never selects another engine or creates an empty SQLite database automatically. If the original connection cannot be parsed, the confirmation list explicitly states that only the connection will be repaired; the original database's data will not be migrated.

#### Prepare a PostgreSQL connection

Create a dedicated database and main schema first. The application account needs access to data and permission to create tables, indexes, sequences, functions and triggers. Private plugin storage also requires `CREATEROLE` and `CREATE` on the database to create separate logins and schemas. Do not grant `PUBLIC` access to the main database's or other owners' data; isolation checks reject private connections with those privileges. `currentSchema` accepts one name of at most 63 lowercase ASCII letters, digits or underscores, starting with a letter or underscore; it defaults to `public`. Use `sslmode=verify-full` for remote connections and `sslrootcert` when a custom CA is required. JDBC connects directly to the administrator's chosen server and does not use the Pixiv HTTP proxy.

These flat configuration keys describe a PostgreSQL connection. Use the settings confirmation flow when existing data must be migrated. Editing `config/config.yaml` directly only changes the connection for the next startup; it does not copy history. Keep the password in its separate configuration field or the `DATABASE_PASSWORD` environment variable, never in the JDBC URL.

```yaml
database.provider: database-postgresql
database.engine: postgresql
database.jdbc-url: "jdbc:postgresql://db.example.com:5432/pixiv?currentSchema=pixiv&sslmode=verify-full"
database.username: pixiv_app
database.password: "replace-with-database-password"
```

#### Optional offline tool for administrators

Use the CLI when you need to arrange downtime separately or inspect a migration report. It does not switch the application configuration automatically. Nonempty destinations are rejected; replacement with a backup is available through settings when the provider supports it. Install the source and destination database plugins, then stop every application, plugin and offline writer using the source database. Keep artwork files, private plugin databases, reference audio, configuration and credential files available. Save the source and destination connection settings as `source.yaml` and `target.yaml`, leaving the active configuration unchanged. From the fully extracted application directory, replace `<boot.jar>` with the actual application boot JAR filename and run:

```powershell
java '-Dloader.main=top.sywyar.pixivdownload.migration.DatabaseMigration' -cp '<boot.jar>' org.springframework.boot.loader.launch.PropertiesLauncher --source-config source.yaml --target-config target.yaml --backup migration/source.backup --report migration/report.json
```

The tool acquires the instance lock. The source provider keeps a consistent backup and prepares the structure and data; the destination provider imports into an empty destination and verifies the result. SQLite source backups include committed WAL data, and old structures are upgraded in a working copy. SQLite and PostgreSQL support migration in both directions. Migration preserves IDs, millisecond timestamps, NULLs, soft deletion, metadata, media and file references, and checks table counts, digests and generated key sequences. A nonempty destination, an undeclared source table or an incompatible value stops migration; the tool never clears the destination or silently skips unknown tables.

Explicitly acknowledge regenerable SQLite novel objects with `--rebuild-objects novels_fts,novel_soft_delete_cleanup`, listing only objects that actually exist in the source. On PostgreSQL startup, the database provider recreates the soft deletion trigger from the novel plugin's declaration, and content search reads the migrated text directly. Before acknowledging other plugin objects, confirm that the selected provider supports rebuilding them. This option cannot exclude ordinary history tables.

Installed but disabled plugins still supply migration declarations. Before uninstalling a plugin whose database data must be retained, use this entry point with `--export-schema schema.json` to save its declarations. Add `--retained-schema schema.json` during migration to restore declarations for absent owners; the manifest must match the current SDK. Plugins needed to upgrade private storage must remain installed; an exported declaration cannot replace their upgrade logic. Restore the plugin or provide verified declarations when they are missing, rather than deleting historical tables.

The tool also migrates owner data with compatible private schema declarations. Unknown owners or missing declarations stop migration before copying. The main database and each private database commit separately; partial failure does not roll back databases already committed. Saving a database selection and starting the application recheck private migration receipts and source changes. Plugins that bundle JDBC or use SQLite-specific SQL do not automatically support another database and require adaptation by their authors.

Only `DATA_VERIFIED` means the database copy passed verification. Review the report, objects awaiting recreation and each database's result, then change the active configuration, start the application and check the gallery, novel search, notifications, schedules and file access. Neither `IN_PROGRESS` nor `FAILED_TARGET_NOT_VERIFIED` indicates success. Keep the backup and verify the destination transaction outcome. CLI retries require a new backup and a confirmed empty destination; the CLI has no resume mode. If the destination has accepted new writes since the switch, restoring the old connection directly would omit those changes. Use settings to check and migrate back, or have an administrator handle the data while the application is stopped.

### Image and Ugoira output

Use the desktop Settings / Configuration → Download section to select default formats, quality, lossless WebP, and maximum edge length. Save and restart the backend to apply them. Format dropdowns allow multiple selections and require at least one:

- Images: original format (default), PNG, JPG, WebP. Original can be deselected; it is removed only after every selected output succeeds. Failure or cancellation retains it. Selecting the source format reuses the original file.
- Ugoira: WebP (default), GIF, APNG, MP4, original ZIP. MP4 requires an H.264 encoder in the active FFmpeg. Keeping ZIP also saves frame timing for offline conversion.
- Quality defaults to 90 (1–100), lossless WebP is off, and maximum edge defaults to 0 (keep dimensions), or 1–16,383. These controls affect newly encoded copies; reused originals are not re-encoded.

Both Web download layouts expose format selection for the current download and new schedules. Defaults live in `config/plugins/download-workbench.properties` under `download-workbench.media.image-formats`, `ugoira-formats`, `quality`, `webp-lossless`, and `maximum-edge` (all five use the same prefix).

FFmpeg shared concurrency defaults to 2, adjustable to 1–8 in desktop download settings. Save and restart the backend to apply it. The host key is `ffmpeg.max-concurrent` in `config/config.yaml`; conversion and media probing share this budget.

For existing works, open **Tools → FFmpeg and tools → Process downloaded media** in the Compose desktop UI, select target formats, and detect missing files without entering artwork IDs. Review the results and start explicitly. Processing adds missing formats and thumbnails while keeping existing files; going back does not cancel the task. Swing provides the same detection and processing on its Tools page. Ugoira conversion requires a retained ZIP and frame timing. FFmpeg management can test actual codec support; MP4 requires an H.264 encoder.

Each batch handles up to 500 matching works and 10,000 candidate files, with one task at a time. When the limit is reached, process the batch and detect again. Detection creates no files. Changing options requires another detection; changing download settings never starts historical processing.

The gallery prefers a retained original, followed by WebP, PNG, and JPG. If the original was removed, it uses an existing copy. MP4 opens in a video player.

### Plugin market

| Key | Default | Description |
| --- | --- | --- |
| `plugin-catalog.enabled` | `true` | Market master switch; repositories are contacted when the market is opened/refreshed or a plugin is installed, and never while this switch is off |
| `plugin-catalog.official-repository-enabled` | `true` | Enable the embedded official repository |
| `plugin-catalog.connect-timeout-ms` | `15000` | Global connection timeout |
| `plugin-catalog.read-timeout-ms` | `60000` | Global read timeout |
| `plugin-catalog.max-manifest-bytes` | `1048576` | Manifest size limit |
| `plugin-catalog.max-package-bytes` | `104857600` | Plugin package size limit |
| `plugin-catalog.repositories` | empty list | Custom repositories |

The official repository URL and trust root are embedded. A custom repository must declare its own HTTPS manifest and Ed25519 public key; it does not inherit the official trust root. Prefer the GUI repository editor. Manual example:

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

Repository ids must be unique and cannot be `official` or `configured`. Proxy policies are:

- `direct-strict`: direct HTTPS only, with non-public addresses and redirects rejected.
- `proxy-trusted`: use the application proxy and allow at most five redirects to built-in trusted hosts; every hop is revalidated.
- `custom`: use the entry's `allow-redirects`, `strict-https`, `allow-non-public-addresses`, and `use-proxy` flags.

An entry may override `connect-timeout-ms`, `read-timeout-ms`, `max-manifest-bytes`, and `max-package-bytes`; omitted or zero values inherit the global setting.

### Outbound proxy

| Key | Default | Description |
| --- | --- | --- |
| `proxy.enabled` | `true` | Enable the host outbound HTTP proxy |
| `proxy.host` | `127.0.0.1` | Proxy host |
| `proxy.port` | `7890` | Proxy port |

Plugins that need proxy-aware networking should use the stable HTTP/WebSocket SDK or a `core-api` proxy semantic port, not the host's `ProxyConfig` implementation.

### Multi-mode quotas and rate limits

| Key | Default |
| --- | --- |
| `multi-mode.quota.enabled` | `true` |
| `multi-mode.quota.max-artworks` | `50` |
| `multi-mode.quota.reset-period-hours` | `24` |
| `multi-mode.quota.archive-expire-minutes` | `60` |
| `multi-mode.quota.limit-image` | `0` |
| `multi-mode.quota.max-proxy-requests` | `200` |
| `multi-mode.quota.archive-max-concurrent` | `10` |
| `multi-mode.post-download-mode` | `pack-and-delete` |
| `multi-mode.delete-after-hours` | `72` |
| `multi-mode.request-limit-minute` | `300` |
| `multi-mode.static-resource-request-limit-minute` | `1200` |

`multi-mode.post-download-mode` accepts `pack-and-delete`, `never-delete`, or `timed-delete`. `multi-mode.limit-page=0` means unlimited; its current default is `3`.

Invited guests use separate limits in both solo and multi mode:

| Key | Default |
| --- | --- |
| `guest-invite.request-limit-minute` | `300` |
| `guest-invite.static-resource-request-limit-minute` | `1200` |
| `guest-invite.tts-request-limit-minute` | `30` |
| `setup.login-rate-limit-minute` | `10` |

### Maintenance windows

`maintenance.enabled` defaults to `true`. Daily defaults are:

| Day | Enabled key/default | Time key/default |
| --- | --- | --- |
| Monday | `maintenance.monday.enabled=true` | `maintenance.monday.time=10:00` |
| Tuesday | `maintenance.tuesday.enabled=false` | `maintenance.tuesday.time=10:00` |
| Wednesday | `maintenance.wednesday.enabled=false` | `maintenance.wednesday.time=10:00` |
| Thursday | `maintenance.thursday.enabled=false` | `maintenance.thursday.time=10:00` |
| Friday | `maintenance.friday.enabled=false` | `maintenance.friday.time=10:00` |
| Saturday | `maintenance.saturday.enabled=false` | `maintenance.saturday.time=10:00` |
| Sunday | `maintenance.sunday.enabled=false` | `maintenance.sunday.time=10:00` |

### HTTPS and reverse proxies

| Key | Default |
| --- | --- |
| `ssl.domain` | `localhost` |
| `ssl.type` | `pem` |
| `server.ssl.enabled` | `false` |
| `server.ssl.certificate` | empty |
| `server.ssl.certificate-private-key` | empty |
| `server.ssl.key-store-type` | `JKS` |
| `server.ssl.key-store` | empty |
| `server.ssl.key-store-password` | empty |
| `server.trusted-proxy-cidrs` | empty |
| `ssl.http-redirect` | `false` |
| `ssl.http-redirect-port` | `80` |

Use certificate and private-key paths for `ssl.type=pem`, or a key store for `ssl.type=jks`. Never commit private keys or key-store passwords.

`server.trusted-proxy-cidrs` defines the trust boundary for reverse-proxy deployments. It accepts only comma-separated numeric IPv4/IPv6 CIDRs, for example:

```yaml
server.trusted-proxy-cidrs: 127.0.0.1/32,172.18.0.0/16
```

List only the proxy egress addresses or container subnets that actually connect to the backend. Do not list client networks, and never trust `0.0.0.0/0` or `::/0`. When the value is empty, the application runs in direct mode and rejects every `Forwarded`, `X-Forwarded-*`, or `X-Real-IP` request header.

A trusted proxy must provide exactly one complete header family on every request:

- RFC `Forwarded`: the selected trust-boundary element must contain `for`, `proto`, and `host`;
- legacy headers: `X-Forwarded-For`, `X-Forwarded-Proto`, and `X-Forwarded-Host`, with optional `X-Forwarded-Port`.

The application walks a proxy chain from right to left, selects the first untrusted address as the client, and normalizes the client address plus external scheme, host, and port before authentication, rate limiting, and CSRF same-origin checks. A request receives HTTP 400 if an untrusted peer supplies forwarding headers, the chain contains no untrusted client address, or a trusted proxy supplies missing, mixed, misaligned, or malformed metadata. The proxy must cover every path to the backend: if `127.0.0.1/32` is trusted, direct requests from that same address without forwarding headers are rejected as well.

### Language and desktop UI

| Key | Default | Description |
| --- | --- | --- |
| `app.language` | empty | Follow the system, or use a supported language code |
| `app.theme` | `system` | GUI theme id |
| `app.config-menu-expand-all` | `false` | Expand all configuration groups in Swing. Compose has no such option and saves this setting as disabled when opened |
| `app.gui-provider` | `gui-swing` | Desktop GUI provider id; selectable values come only from active provider plugins |

Available themes are contributed by installed theme plugins. The GUI provider selector is also populated dynamically from active `DesktopUiProvider` plugins rather than a host hard-coded list. The official `gui-swing` provider is default-installed; `gui-compose` is installed on demand. Switching providers requires a full restart. If the configured provider is unavailable, selection may fall back only to the unique default provider, never discovery order.

### Updates

| Key | Default |
| --- | --- |
| `update.enabled` | `true` |
| `update.manifest-url` | official latest-release `update.json` |
| `update.nightly-manifest-url` | official nightly `update.json` |
| `update.auto-check` | `true` |
| `update.check-nightly` | `true` for nightly builds, otherwise `false` |

### Schedule host

| Key | Default |
| --- | --- |
| `schedule.enabled` | `true` |
| `schedule.tick-interval-ms` | `60000` |
| `schedule.max-tasks` | `100` |
| `schedule.inbox-check-every` | `500` |
| `schedule.auth-failure-circuit-breaker` | `5` |
| `schedule.pending-max-attempts` | `5` |
| `schedule.overuse-defer-default-minutes` | `60` |

These keys configure the neutral schedule host. Download sources, authentication, and source-specific options remain owned by their plugins.

### Plugin enabled state

The host owns `plugins.{pluginId}.enabled`, for example:

```yaml
plugins.douyin.enabled: true
```

A required plugin cannot be disabled. Whether a change is immediate depends on the plugin's `pixiv.lifecycle-policy` and the requested lifecycle operation; see [plugin management](/en/plugin-management).

## Plugin business configuration

Each plugin writes only `config/plugins/{pluginId}.properties`, using UTF-8 Java-properties syntax:

```properties
example.timeout-ms=15000
example.output-format=json
```

The host rejects attempts to override default host keys, `plugins.*.enabled`, or credential-like keys from these files. Keys should not collide across plugin files. A plugin child Spring context reads values through `Environment`, `@Value`, or `@ConfigurationProperties`; a third-party plugin should not read files directly or depend on app-shell configuration classes.

The plugin's `GuiConfigContribution` is the source of truth for its complete configuration section, including fields, groups, layout, actions, presets, and interactions. The application shell merges it with core configuration into one toolkit-neutral desktop document and owns the unified save flow; Swing and Compose only render it and do not maintain separate plugin pages. After save, the host refreshes plugin property sources and reports whether the result is immediate, requires a backend restart, or requires a process restart. After uncertain manual edits, a full restart is safest.

## Plugin credentials

Passwords, cookies, tokens, API keys, secrets, and webhook keys belong in `config/credentials/{pluginId}.properties`. The host owns encryption, permissions, migration, and owner-scoped injection. A plugin reads only its already-decrypted values from its child-context `Environment`.

Do not place credentials in `config.yaml` or `config/plugins/*.properties`, and do not let plugins read, parse, or decrypt credential files. A credential backup is useful only with the protected credential master key; encrypted values cannot be recovered in a different environment without the original key.

## Confirming the current contract

1. Start with the GUI Configuration page; it combines host fields with contributions from currently installed plugins.
2. Host defaults are defined by the current `DefaultConfigTemplate` output.
3. Plugin fields are defined by that plugin's `GuiConfigContribution`, `@ConfigurationProperties`, or settings service.
4. Do not copy old `mail.*`, `push.*`, `notification.*`, or `download.novel-*` examples into `config.yaml`; those settings are now plugin-owned.
