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
| `database.maximum-pool-size` | `28` | SQLite connection-pool limit |

`download.root-folder` contains downloaded works only. Configuration, databases, plugin state, and caches are stored elsewhere. Private settings for novel, Douyin, and other download types belong to their plugins and are exposed after installation.

Pixiv artwork images, novel covers, and embedded images have a fixed safety limit of 100 MiB each. These responses are limited to 1 GiB in total within one ordinary artwork or novel download task. The service checks both `Content-Length` and the actual decoded response stream; an over-limit transfer is stopped and its partial file is removed. The final extension is selected from an image allowlist using the URL path, response `Content-Type`, and verified file signature; query parameters never become part of the filename. These limits cannot be raised through configuration.

Ugoira processing limits the ZIP download to 100 MiB, 500 entries, 32 MiB per expanded entry, 200 MiB of expanded data in total, a 100:1 compression ratio per entry, 500 frames, and 25,000,000 pixels per frame. Ugoira conversion runs one ffmpeg process at a time; each process may run for 10 minutes and produce at most 100 MiB. Exceeding a limit terminates processing and removes the ZIP, extracted frames, and partial output.

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
| `app.config-menu-expand-all` | `false` | Expand all configuration groups initially |

Available themes are contributed by installed theme plugins; the setting is not a host hard-coded list of concrete implementations.

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

A required plugin cannot be disabled. Whether a change is immediate depends on the plugin's `pixiv.lifecycle-policy` and the requested lifecycle operation; see [plugin management](/zh-cn/plugin-management).

## Plugin business configuration

Each plugin writes only `config/plugins/{pluginId}.properties`, using UTF-8 Java-properties syntax:

```properties
example.timeout-ms=15000
example.output-format=json
```

The host rejects attempts to override default host keys, `plugins.*.enabled`, or credential-like keys from these files. Keys should not collide across plugin files. A plugin child Spring context reads values through `Environment`, `@Value`, or `@ConfigurationProperties`; a third-party plugin should not read files directly or depend on app-shell configuration classes.

The plugin's GUI configuration contribution is the source of truth for fields and persistence. After save, the host refreshes plugin property sources and reports whether the result is immediate, requires a backend restart, or requires a process restart. After uncertain manual edits, a full restart is safest.

## Plugin credentials

Passwords, cookies, tokens, API keys, secrets, and webhook keys belong in `config/credentials/{pluginId}.properties`. The host owns encryption, permissions, migration, and owner-scoped injection. A plugin reads only its already-decrypted values from its child-context `Environment`.

Do not place credentials in `config.yaml` or `config/plugins/*.properties`, and do not let plugins read, parse, or decrypt credential files. A credential backup is useful only with the protected credential master key; encrypted values cannot be recovered in a different environment without the original key.

## Confirming the current contract

1. Start with the GUI Configuration page; it combines host fields with contributions from currently installed plugins.
2. Host defaults are defined by the current `DefaultConfigTemplate` output.
3. Plugin fields are defined by that plugin's `GuiConfigContribution`, `@ConfigurationProperties`, or settings service.
4. Do not copy old `mail.*`, `push.*`, `notification.*`, or `download.novel-*` examples into `config.yaml`; those settings are now plugin-owned.
