# Configuration Reference

`config.yaml` is the main runtime configuration file for PixivDownloader, located in the `config/` directory. Generated automatically on first launch; new options from later versions are automatically appended.

> [!NOTE]
> You can edit visually via the GUI "Configuration" tab, or edit `config.yaml` directly. Most changes require a **service restart** to take effect — see individual option descriptions.

---

## Full Configuration Reference

### Basic Service

```yaml
server.port: 6999
```
HTTP service port. **Requires restart** after change.

```yaml
download.root-folder: pixiv-download
```
Download file storage root directory. Supports relative paths (relative to working directory) or absolute paths. **Requires restart** after change.

```yaml
download.user-flat-folder: false
```
Use flat directory structure. When `true`, all artwork is stored directly under the root without subdirectories. `false` creates a separate subdirectory per artwork. **Hot-reload supported**.

```yaml
download.max-concurrent: 10
```
Maximum concurrent artwork downloads. **Hot-reload supported**.

```yaml
download.novel-max-concurrent: 10
```
Maximum concurrent novel downloads. **Hot-reload supported**.

---

### Proxy Configuration

All of the backend's outbound traffic goes through this HTTP proxy: accessing Pixiv and downloading works, online updates (checking/downloading new versions), downloading the bundled FFmpeg, and online TTS (the Edge neural voices used by "Listen"). The first-run setup wizard (web `setup.html`, desktop GUI onboarding, CLI `--setup`) walks you through configuring the proxy.

```yaml
proxy.enabled: true
```
Enable HTTP proxy. **Hot-reload supported**.

```yaml
proxy.host: 127.0.0.1
```
Proxy server address. **Hot-reload supported**.

```yaml
proxy.port: 7890
```
Proxy server port. **Hot-reload supported**.

> [!TIP]
> The FFmpeg download button in the GUI also reuses this proxy configuration for fetching FFmpeg release packages from GitHub.

---

### Multi-mode Configuration

Takes effect only in Multi Mode; Solo mode is unaffected.

#### Quota

```yaml
multi-mode.quota.enabled: true
```
Enable quotas. When `true`, each guest has a download quota limit. **Hot-reload supported**.

```yaml
multi-mode.quota.max-artworks: 50
```
Maximum artworks each guest can download within one reset cycle. **Hot-reload supported**.

```yaml
multi-mode.quota.reset-period-hours: 24
```
Quota reset cycle in hours. **Hot-reload supported**.

```yaml
multi-mode.quota.archive-expire-minutes: 60
```
Validity period (minutes) for download archive links generated after quota exceeded. Guests can save archive links to resume downloads within the validity period. **Hot-reload supported**.

```yaml
multi-mode.quota.limit-image: 0
```
Image count threshold per artwork. When an artwork's total image count exceeds this value, it counts as multiple quota units. `0` means no limit. **Hot-reload supported**.

```yaml
multi-mode.quota.max-proxy-requests: 200
```
Maximum Pixiv proxy requests a guest can make within one reset cycle. Controls the frequency of Pixiv access via the backend proxy API. **Hot-reload supported**.

```yaml
multi-mode.quota.archive-max-concurrent: 10
```
Maximum concurrent archive packaging tasks. **Hot-reload supported**.

#### Post-Download Processing

```yaml
multi-mode.post-download-mode: pack-and-delete
```
Post-download processing mode in multi-user mode:
- `pack-and-delete`: Pack then delete original files
- `never-delete`: Never delete
- `timed-delete`: Timed deletion (works with `delete-after-hours`) **Hot-reload supported**.

```yaml
multi-mode.delete-after-hours: 72
```
Hours before auto-deletion of downloaded artwork when `post-download-mode` is `timed-delete`. **Hot-reload supported**.

#### Rate Limiting

```yaml
multi-mode.request-limit-minute: 300
```
Maximum API requests per guest per minute. **Hot-reload supported**.

```yaml
multi-mode.static-resource-request-limit-minute: 1200
```
Maximum static resource requests per guest per minute. TCP source IP-based limiting, up to 50000 unique IPs. Applies only to unauthenticated multi-mode guests (invited-guest static resource limiting is under `guest-invite.*` below). **Hot-reload supported**.

```yaml
multi-mode.limit-page: 3
```
Results per page limit in search mode. **Hot-reload supported**.

#### Invited-guest rate limiting (`guest-invite.*`)

> [!NOTE]
> The limits below apply to **visitors holding an invite session**. Guest invites are independent of the solo/multi run mode (they work in both), so these limits apply in both modes and are **counted per invite code** (the same code shares one quota across browsers). Admins / the solo owner are never limited.

```yaml
guest-invite.request-limit-minute: 300
```
Maximum API requests per minute per invite code for invited guests. `0` = unlimited. **Hot-reload supported**.

```yaml
guest-invite.static-resource-request-limit-minute: 1200
```
Maximum static resource requests per minute per invite code for invited guests. `0` = unlimited. **Hot-reload supported**.

```yaml
guest-invite.tts-request-limit-minute: 30
```
Maximum online TTS (speech synthesis) requests per minute per invite code for invited guests. `0` = unlimited; admins and the browser built-in voice engine are not limited. **Hot-reload supported**.

> [!WARNING]
> **Rate Limiting Notes for Multi-mode Behind Reverse Proxy/CDN**
>
> The above rate limits are based on TCP source IP (`request.getRemoteAddr()`). When deployed behind nginx, Caddy, Cloudflare, etc.:
> - All users share the same rate limit counters
> - One user hitting the limit can block all visitors
>
> It is recommended to implement rate limiting at the reverse proxy layer based on `X-Forwarded-For` / `X-Real-IP`, and raise or disable (`0`) backend limits to avoid double-limiting.

---

### Login Security

```yaml
setup.login-rate-limit-minute: 10
```
Maximum login attempts per IP per minute on the login endpoint. `0` means no limit. Effective in both Solo and Multi modes. **Hot-reload supported**.

---

### Maintenance

```yaml
maintenance.enabled: true
```
Enable periodic maintenance tasks. Setting to `false` disables both scheduling and the manual trigger endpoint. **Hot-reload supported**.

```yaml
maintenance.monday.enabled: true
maintenance.monday.time: "10:00"
```
Monday maintenance switch and start time (HH:mm 24-hour format, local time zone). Monday is enabled by default; other weekdays default to disabled. **Hot-reload supported**.

```yaml
maintenance.tuesday.enabled: false
maintenance.tuesday.time: "10:00"
maintenance.wednesday.enabled: false
maintenance.wednesday.time: "10:00"
maintenance.thursday.enabled: false
maintenance.thursday.time: "10:00"
maintenance.friday.enabled: false
maintenance.friday.time: "10:00"
maintenance.saturday.enabled: false
maintenance.saturday.time: "10:00"
maintenance.sunday.enabled: false
maintenance.sunday.time: "10:00"
```
Per-weekday maintenance switch and start time. Multiple weekdays can be enabled with different start times. During maintenance all requests are intercepted: page requests redirect to a maintenance page, API requests return 503. **Hot-reload supported**.

> ⚠️ Time values must be quoted in `config.yaml` (e.g. `"23:50"`), otherwise YAML parses them as sexagesimal integers, causing errors. Editing via the GUI handles quoting automatically.

---

### SSL/HTTPS

```yaml
ssl.domain: localhost
```
SSL certificate domain, used for constructing external URLs. **Requires restart**.

```yaml
ssl.type: pem
```
Certificate type: `pem` (recommended) or `jks`. PEM takes priority when both are configured. **Requires restart**.

```yaml
server.ssl.enabled: false
```
Enable SSL. Certificate required when set to `true`. **Requires restart**.

#### PEM Certificate (Recommended)

```yaml
server.ssl.certificate:
server.ssl.certificate-private-key:
```
Absolute paths to PEM-format certificate and private key files. **Requires restart**.

#### JKS Certificate

```yaml
server.ssl.key-store-type: JKS
server.ssl.key-store:
server.ssl.key-store-password:
```
JKS format keystore path and password. **Requires restart**.

#### HTTP Redirect

```yaml
ssl.http-redirect: false
```
Redirect HTTP traffic to HTTPS when SSL is enabled. **Requires restart**.

```yaml
ssl.http-redirect-port: 80
```
HTTP redirect listening port. **Requires restart**.

---

### Language

```yaml
app.language:
```
Application language setting. Auto-detects system language when empty. Available values:
- Empty: Auto-detect
- `zh-CN`: Chinese
- `en-US`: English

**Hot-reload supported**.

---

### Online Updates

```yaml
update.enabled: true
```
Enable online update check. **Hot-reload supported**.

```yaml
update.manifest-url: https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json
```
Update manifest URL for checking new versions. **Hot-reload supported**.

```yaml
update.auto-check: true
```
Auto-check for updates after startup. **Hot-reload supported**.

```yaml
update.nightly-manifest-url: https://github.com/Sywyar/PixivDownloader/releases/download/nightly/update.json
```
Nightly build update manifest URL. **Hot-reload supported**.

```yaml
update.check-nightly: false
```
Check for nightly build updates. Default `false` for stable releases, `true` for nightly builds; auto-detected from the current version if left unset. **Hot-reload supported**.

### Scheduled tasks (admin)

```yaml
schedule.enabled: true
```
Whether to enable scheduled-task dispatch. When off, the scheduler check is skipped and no scheduled task runs (created tasks are kept, not deleted). **Hot-reload supported**.

```yaml
schedule.tick-interval-ms: 60000
```
Interval (ms) for checking due tasks, default 60000 (60s). Resolved by the startup timer, so changing it **requires a restart** (not hot-reloadable).

```yaml
schedule.max-tasks: 100
```
Maximum number of scheduled tasks per database, to prevent abuse. Default 100. **Hot-reload supported**.

```yaml
schedule.inbox-check-every: 500
```
A cookie-bound task reads the Pixiv message inbox once per this many successfully downloaded works to detect "overuse" warnings (skipped / filtered works are not counted). Default 500. **Hot-reload supported**.

```yaml
schedule.auth-failure-circuit-breaker: 5
```
When per-work fetch failures reach this count consecutively within a single run, the login session is treated as expired and the task is suspended for re-authorization. Default 5. **Hot-reload supported**.

```yaml
schedule.pending-max-attempts: 5
```
Maximum number of automatic retries for an isolated failed work; once reached the work is marked as needing manual action and auto-retry stops. Default 5. **Hot-reload supported**.

```yaml
schedule.overuse-defer-default-minutes: 60
```
Default minutes to defer when resuming an account paused for overuse via "continue later". Minimum 60. Default 60. **Hot-reload supported**.

> For how to use scheduled tasks (creation, source types, cookie authorization, overuse pause and resume, etc.), see the [Usage Guide](en-Usage-Guide#-scheduled-tasks-admin-only).

### Mail / SMTP

> Configure an SMTP mailbox so PixivDownloader can deliver operational notifications (e.g. when scheduled tasks hit a Pixiv overuse warning or the login cookie expires). All fields can be edited directly in the **GUI Settings page → Mail / SMTP**; after configuring you can click "Send test email" to receive a "Mail configuration successful" message and verify the setup. The same panel also exposes a "Send all email templates" button that uses sample data to deliver all four notification templates (configuration success / overuse-paused / auth-expired / circuit-breaker) in sequence, so you can preview every email style at once. **Passwords / authorization codes never appear in logs or email bodies.** All `mail.*` keys are **hot-reloadable**.

```yaml
mail.enabled: false
```
Master switch for outgoing mail; when off, every send is skipped. Off by default.

```yaml
mail.host: smtp.example.com
mail.port: 587
mail.security: starttls
```
SMTP host / port / encryption. `mail.security` accepts `none` / `ssl` / `starttls` (default `starttls`; `ssl` is SMTPS, usually 465; `starttls` usually 587). The GUI provides a "Provider preset" dropdown with one-click defaults for 163 / QQ / Gmail / Outlook / iCloud / NetEase business / Tencent business / Aliyun business / Microsoft 365 / Google Workspace and other mainstream providers.

```yaml
mail.username: you@example.com
mail.password:
```
SMTP username (usually the full email address) and an **authorization code / app-specific password**. **Note**: most Chinese personal mailboxes (163 / 126 / QQ / NetEase business mail) reject the regular login password — generate an "Authorization Code" on the provider's web settings and use it as the SMTP password. Gmail / Outlook.com / iCloud / Yahoo require two-factor auth followed by an "App Password". Microsoft 365 / Google Workspace business tenants currently enforce OAuth, which **this release does not support yet** — ask your admin to enable SMTP AUTH explicitly or use a different mailbox.

```yaml
mail.from:
mail.to: admin@example.com,ops@example.com
```
Sender address (falls back to `mail.username` when empty) and recipient address (multiple addresses comma-separated).

```yaml
mail.socks-proxy:
```
Optional SOCKS proxy in `host:port` form (e.g. `127.0.0.1:1080`). **The existing `proxy.*` HTTP proxy does not carry SMTP**, so this is configured independently. Leave empty for a direct connection.

```yaml
mail.subject-prefix: "[PixivDownloader]"
```
Subject prefix prepended to template titles for client-side filtering.

---

## Hot Reload Reference

The following settings can be changed via GUI and take effect **without restart**:

| Setting | Hot Reload |
|---------|:----------:|
| `proxy.*` | ✅ |
| `multi-mode.quota.*` | ✅ |
| `multi-mode.request-limit-minute` | ✅ |
| `multi-mode.static-resource-request-limit-minute` | ✅ |
| `multi-mode.limit-page` | ✅ |
| `guest-invite.request-limit-minute` | ✅ |
| `guest-invite.static-resource-request-limit-minute` | ✅ |
| `guest-invite.tts-request-limit-minute` | ✅ |
| `multi-mode.post-download-mode` | ✅ |
| `multi-mode.delete-after-hours` | ✅ |
| `download.user-flat-folder` | ✅ |
| `download.max-concurrent` | ✅ |
| `download.novel-max-concurrent` | ✅ |
| `setup.login-rate-limit-minute` | ✅ |
| `maintenance.enabled` | ✅ |
| `maintenance.monday.enabled` | ✅ |
| `maintenance.monday.time` | ✅ |
| `maintenance.tuesday.enabled` | ✅ |
| `maintenance.tuesday.time` | ✅ |
| `maintenance.wednesday.enabled` | ✅ |
| `maintenance.wednesday.time` | ✅ |
| `maintenance.thursday.enabled` | ✅ |
| `maintenance.thursday.time` | ✅ |
| `maintenance.friday.enabled` | ✅ |
| `maintenance.friday.time` | ✅ |
| `maintenance.saturday.enabled` | ✅ |
| `maintenance.saturday.time` | ✅ |
| `maintenance.sunday.enabled` | ✅ |
| `maintenance.sunday.time` | ✅ |
| `server.ssl.*` | ❌ Requires restart |
| `ssl.*` | ❌ Requires restart |
| `app.language` | ✅ |
| `update.*` | ✅ |
| `schedule.enabled` | ✅ |
| `schedule.max-tasks` | ✅ |
| `schedule.inbox-check-every` | ✅ |
| `schedule.auth-failure-circuit-breaker` | ✅ |
| `schedule.pending-max-attempts` | ✅ |
| `schedule.overuse-defer-default-minutes` | ✅ |
| `schedule.tick-interval-ms` | ❌ Requires restart |
| `mail.*` | ✅ |
| `server.port` | ❌ Requires restart |
| `download.root-folder` | ❌ Requires restart |

---

## Configuration Example

```yaml
# ========================================================
# PixivDownloader Runtime Configuration
# Most changes require a service restart to take effect
# ========================================================

server.port: 6999                          # HTTP service port

download.root-folder: pixiv-download        # Download storage directory
download.user-flat-folder: false            # Flat directory structure
download.max-concurrent: 10                 # Max concurrent artwork downloads
download.novel-max-concurrent: 10           # Max concurrent novel downloads

# --- Proxy Configuration ---
proxy.enabled: true
proxy.host: 127.0.0.1
proxy.port: 7890

# --- Multi-mode Configuration ---
multi-mode.quota.enabled: true
multi-mode.quota.max-artworks: 50
multi-mode.quota.reset-period-hours: 24
multi-mode.quota.archive-expire-minutes: 60
multi-mode.quota.limit-image: 0
multi-mode.quota.max-proxy-requests: 200
multi-mode.quota.archive-max-concurrent: 10

multi-mode.post-download-mode: pack-and-delete
multi-mode.delete-after-hours: 72

multi-mode.request-limit-minute: 300
multi-mode.static-resource-request-limit-minute: 1200
multi-mode.limit-page: 3

# --- Invited-guest rate limits (apply to invited guests in both solo and multi mode, counted per invite code) ---
guest-invite.request-limit-minute: 300
guest-invite.static-resource-request-limit-minute: 1200
guest-invite.tts-request-limit-minute: 30

# --- Login Security ---
setup.login-rate-limit-minute: 10

# --- Maintenance ---
maintenance.enabled: true
maintenance.monday.enabled: true
maintenance.monday.time: 10:00
maintenance.tuesday.enabled: false
maintenance.tuesday.time: 10:00
maintenance.wednesday.enabled: false
maintenance.wednesday.time: 10:00
maintenance.thursday.enabled: false
maintenance.thursday.time: 10:00
maintenance.friday.enabled: false
maintenance.friday.time: 10:00
maintenance.saturday.enabled: false
maintenance.saturday.time: 10:00
maintenance.sunday.enabled: false
maintenance.sunday.time: 10:00

# --- SSL/HTTPS ---
ssl.domain: localhost
ssl.type: pem
server.ssl.enabled: false
server.ssl.certificate:
server.ssl.certificate-private-key:
server.ssl.key-store-type: JKS
server.ssl.key-store:
server.ssl.key-store-password:
ssl.http-redirect: false
ssl.http-redirect-port: 80

# --- Language ---
app.language:

# --- Online Updates ---
update.enabled: true
update.manifest-url: https://github.com/Sywyar/PixivDownloader/releases/latest/download/update.json
update.auto-check: true
update.nightly-manifest-url: https://github.com/Sywyar/PixivDownloader/releases/download/nightly/update.json
update.check-nightly: false

# --- Scheduled tasks (admin) ---
schedule.enabled: true
schedule.tick-interval-ms: 60000
schedule.max-tasks: 100
schedule.inbox-check-every: 500
schedule.auth-failure-circuit-breaker: 5
schedule.pending-max-attempts: 5
schedule.overuse-defer-default-minutes: 60

# --- Mail / SMTP (scheduled task notifications) ---
mail.enabled: false
mail.host:
mail.port: 587
mail.security: starttls
mail.username:
mail.password:
mail.from:
mail.to:
mail.socks-proxy:
mail.subject-prefix: "[PixivDownloader]"
```
