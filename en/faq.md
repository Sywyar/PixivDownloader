# Frequently Asked Questions (FAQ)

Find the matching symptom below. Before changing account, database, or directory state, confirm the working directory and keep a backup. Logs and task results provide stronger evidence than elapsed waiting time. Replace `APP.jar` in commands with the distribution's main JAR filename and run them from its directory.

## Installation & Startup

### Q: "Java is not recognized" error on startup

**A**: You need to install Java 17+. Download from [Adoptium](https://adoptium.net/), then restart your terminal or manually configure `JAVA_HOME` and `PATH`.

### Q: Garbled Chinese text after startup

**A**: Always add `-Dfile.encoding=UTF-8` to the launch command:

```bash
java -Dfile.encoding=UTF-8 -jar APP.jar
```

### Q: Port 6999 is already in use

**A**: Two solutions:
1. Check whether the port belongs to an existing PixivDownloader instance; do not run two instances against the same data directory. Identify any other process before stopping it
2. Change `server.port` in `config/config.yaml` to a different port (requires restart)

### Q: Windows installer fails

**A**:
1. Ensure `PixivDownload.exe` is closed before installing
2. Check for leftover MSI from previous versions (installer handles migration automatically)
3. Run the installer as Administrator

### Q: How to uninstall?

**A**:
- **Installer version**: Run the installer again and select "Uninstall"; or via Windows "Settings → Apps"
- **JAR version**: Back up the actual artwork directories, configuration, state, and database as described in [Storage](storage.md), then remove program files. The working directory may also contain your library; do not treat the entire directory as disposable.

### Q: How to reinitialize the admin account?

**A**: Use `--reset-password` below if you only forgot the password. For reinitialization, stop the service, back up and move aside `state/setup_config.json`, then use `--setup` or the GUI wizard. This recreates initialization state; it does not reset all configuration or migrate or rebuild the database. See [First-Time Setup](first-setup.md).

### Q: My server / Docker container always exits at startup, asking me to run setup first?

**A**: A headless / `--no-gui` launch exits with code `78` if initialization is incomplete. Initialize interactively, then start the service:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

Then start the service normally with `--no-gui`. See [First-Time Setup](first-setup.md).

### Q: The admin forgot the password. What now?

**A**: Stop the running PixivDownloader, then reset the password via CLI (the previous password is not required):

```bash
java -Dfile.encoding=UTF-8 -jar APP.jar --reset-password
```

The wizard asks for the new password twice (at least 8 characters; 12 or more recommended). After a successful reset, all existing login sessions are invalidated; log in again with the new password. If you still know the current password and just want to rotate it, use `--change-password` (which verifies the current password).

### Q: Startup says "unrecognized startup argument"?

**A**: The launcher validates arguments strictly: typos (`--no-guii`), value flags missing the `=` (`--username admin`), and plain positional tokens are rejected. Run `--help` to see the full option list. Arguments shaped as `--key=value` are still forwarded to Spring Boot as property overrides.

---

## Proxy & Network

### Q: Downloads still fail after configuring proxy

**A**:
1. Confirm proxy settings have been saved (after saving in the GUI or configuring via CLI `--setup`, proxy settings **support hot reload** and take effect immediately, no restart needed; if you hand-edit `config/config.yaml` directly, restart the service or click Save in the GUI to trigger a hot reload)
2. Test an actual Pixiv work URL in the browser, then inspect the failed host and error in backend logs; a CDN root response alone does not prove downloads work
3. Check if the firewall allows the proxy port
4. Distinguish the backend global proxy, task proxy, plugin settings, and browser requests. See [Network Access and Third-Party Services](network-access.md) for their separate rules

### Q: All users rate-limited when deployed behind reverse proxy

**A**: Without a trusted-proxy configuration, the backend uses only the TCP source address. Requests from one reverse-proxy node therefore share the same rate-limit identity.

**Solution**: Add the actual proxy egress address or container subnet to `server.trusted-proxy-cidrs`, and make the proxy send either a complete `Forwarded` header or `X-Forwarded-For` + `X-Forwarded-Proto` + `X-Forwarded-Host` on every request:

```yaml
server.trusted-proxy-cidrs: 172.18.0.0/16
```

The backend then rate-limits by the normalized client address. Never trust public or client networks; requests receive HTTP 400 when forwarding metadata is missing or comes from an untrusted peer. See the full boundary in the [configuration reference](/en/configuration#https-and-reverse-proxies).

### Q: Cannot access from other devices on LAN

**A**:
1. Allow port 6999 through firewall
2. Ensure `config/config.yaml` doesn't restrict binding address
3. Visit `http://<server IP>:6999/` in browser

---

## Cookie Related

### Q: Search mode returns no results

**A**: Search mode requires Pixiv cookies. Please verify:
1. The selected cookie format (Netscape or Header) matches the pasted content, and the saved cookie provides a recognized login session
2. Cookies haven't expired (re-login to Pixiv and re-fetch)
3. Proxy is correctly configured

### Q: Cookie format error message

**A**: Ensure:
1. Match the exported format to **Netscape** or **Header** on the page
2. Authentication needs the complete `PHPSESSID`; ordinary page scripts may not be able to read it
3. Retrieve and save it again using [First Download](first-download.md), and keep credentials out of logs and issues

### Q: How to clear saved cookies?

**A**:
Explicitly clear the cookie on the download page. Scheduled tasks hold separate credential bindings, which must be removed in those tasks. Logging out does not replace checking that each saved credential has been cleared.

---

## Download Related

### Q: Ugoira animations won't play after download

**A**: Ugoira conversion requires FFmpeg and the encoder for the selected output format. Install ffmpeg and ensure it's available in PATH:

```bash
ffmpeg -version
```

Windows installer users can click "Download FFmpeg" on the GUI "Status" page.

### Q: EPUB covers for downloaded novels don't show

**A**: Cover download may require proxy. Ensure:
1. Proxy is correctly configured
2. Cover URL host ends with `.pximg.net` (SSRF security restriction)
3. Re-download the novel

### Q: The "Online engine" for Listen has no sound / synthesis fails

**A**: The online engine (Edge neural voices) requires network. Check in order:
1. **Network/proxy**: online synthesis reaches Microsoft via the backend proxy — make sure the proxy works (same `proxy.*` config as downloads)
2. **Handshake 403**: when the version string is stale, the backend automatically fetches the latest Edge version, corrects clock skew, and retries; if it persists, check your machine's system clock
3. **Switch to the browser engine**: in the playback bar's "Settings", switch to the browser engine — using voices from the browser / system; network requirements depend on the selected speech service
4. **Invited guests rate-limited**: visitors using an invite link are bound by `guest-invite.tts-request-limit-minute` (guest invites work in both solo and multi modes); too-frequent requests return 429 — wait and retry, or have the admin raise the value. Admins themselves are not limited

### Q: Some artworks fail during batch download

**A**: Common causes:
1. Cookies expired — re-fetch and save cookies
2. Pixiv server rate limiting — reduce download concurrency and frequency
3. Unstable proxy — check proxy service
4. Artwork deleted or set to private — skip missed items

### Q: Custom filename template not working

**A**: Check template syntax:
- Use curly braces for variables: `{artwork_id}`
- Don't use unsupported variable names
- Template results cannot contain illegal filename characters (auto-sanitized)
- After template changes, only new downloads use the new template; existing file names won't change

### Q: Ugoira downloads consume excessive quota

**A**: In multi-mode, configure `multi-mode.quota.limit-image` in `config/config.yaml`. When an artwork's image count exceeds this value, it counts as multiple quota units:

```yaml
multi-mode.quota.limit-image: 10  # Artworks with >10 images consume proportional quota
```

---

## GUI Related

### Q: GUI shows "Starting..." indefinitely

**A**: After 10+ seconds, the waited time is displayed; it may be updating the database. If unresponsive for long:
1. Check `log/latest.log` log file
2. Check for migration, port, or plugin errors. Preserve the error and data; do not delete the database to troubleshoot a slow startup
3. Verify Java version is 17+

### Q: GUI online update fails

**A**:
1. Check network connection
2. Check proxy configuration (updates reuse proxy settings)
3. Manually download the installer from [Releases](https://github.com/Sywyar/PixivDownloader/releases) and overwrite-install

### Q: GUI error dialog shows garbled text

**A**: GUI error dialogs now show a unified message; detailed error info is logged. Click "Open Log File" in the dialog to view details.

---

## Database Related

### Q: Where is the database file?

**A**: The default SQLite path is `data/pixiv_download.db`, possibly with WAL / SHM companion files. With PostgreSQL, the main data resides in the configured server database. Check the selected provider and actual location before operating on it.

### Q: What if the database is corrupted?

**A**: Stop the application and preserve the original database and companion files before diagnosing a backup copy. SQLite's `PRAGMA integrity_check;` **checks integrity; it does not repair the database**. It needs the SQLite CLI and should target the copy. Restore a verified backup if corruption is confirmed. Do not delete the original and expect artwork folders to reconstruct the gallery. PostgreSQL requires its own database backup and recovery procedures, not SQLite commands.

### Q: I lost / migrated the database. Will already-downloaded works be redownloaded?

**A**: Skipping redownloads is not guaranteed. Work identity, page count, paths, tags, and collections depend on database records; files in a folder alone do not prove a complete work. Preserve the files and restore the matching database backup first. Use path-prefix migration for directory changes and the checked migration workflow for SQLite / PostgreSQL switches. Backfill fills missing fields in existing records; it does not fully rebuild a library from arbitrary artwork folders.

### Q: How to back up data?

**A**: Stop the application and back up `config/`, `state/`, `data/`, plugins, and all actual artwork directories, including collection-specific paths. Collection icons are under `data/collection_icons/`. PostgreSQL also needs a server database backup; preserve external read-only import sources separately. See [Storage](storage.md) for the full scope and restore checks.

---

## Userscripts

### Q: Scripts don't work after installation

**A**:
1. Confirm Tampermonkey is enabled
2. Check if the script is enabled for the target page (`@match` rules)
3. Check Tampermonkey dashboard to see if the script shows "Enabled"
4. Refresh the Pixiv page

### Q: All-in-One and standalone scripts both enabled, showing duplicate panels

**A**: This is inherent to Tampermonkey — All-in-One and standalone scripts are separate scripts with independent storage. Recommendations:
- Enable only the **All-in-One bundle** (recommended)
- Or enable only the standalone scripts you need, disable All-in-One

### Q: Script shows "Backend Unavailable" or "Not Logged In"

**A**:
1. Confirm backend service is running
2. In Solo mode, confirm you're logged in
3. For remote backends, check both the configured backend address and `@connect` host permission; see [Userscripts](userscripts.md)

---

## Security

### Q: Will using this tool get my Pixiv account banned?

**A**: This project accesses Pixiv using user-provided cookies. Users bear their own account risks. Recommendations:
- Set reasonable download frequency and concurrency
- Avoid excessive requests in a short time
- Comply with Pixiv terms of service

### Q: What can visitors and invited guests see?

**A**: An unauthenticated user without an invite session in `multi` mode is a **visitor**, whose download state is identity-scoped. Admins have the management view. **Invited guests** use invite sessions in either `solo` or `multi` and can only access allowed pages and works. Collections, history, and statistics also enforce their own access rules. These two identities are different.

### Q: How to enable HTTPS?

**A**: Configure SSL certificates in `config/config.yaml`:

```yaml
server.ssl.enabled: true
server.ssl.certificate: /path/to/cert.pem
server.ssl.certificate-private-key: /path/to/key.pem
```

Replace the sample paths with your actual certificate and private-key paths. PEM takes priority when both PEM and JKS are configured. Restart and verify HTTPS; reverse-proxy deployments also need the [Configuration](configuration.md) settings.

---

## Other

### Q: How to migrate to a new computer?

**A**: Follow [Storage](storage.md) to stop, back up, copy, and restore. If paths change, migrate the recorded path prefixes too. Check login, gallery, novel text, collections, and a new download before retiring the old copy.

### Q: How to upgrade?

**A**:
- **GUI mode**: Auto-check on startup, or manually check on "Status" page
- **Java distribution**: Extract the complete new bundle and update the program and matching plugins according to its release notes, preserving configuration, state, and data. Do not replace only the main JAR
- **Windows installer**: Run new installer and select "Repair" or overwrite-install
- Startup can upgrade the schema within the current database. Switching between SQLite and PostgreSQL requires a separate checked migration; changing configuration does not move existing data. Back up before upgrading and verify login and existing works afterwards

### Q: How to provide feedback or report issues?

**A**: Submit an issue on [GitHub Issues](https://github.com/Sywyar/PixivDownloader/issues). Please include:
- OS and version
- PixivDownloader version (check GUI "About" page)
- Issue description and reproduction steps
- Relevant logs (`log/latest.log`), with cookies, tokens, passwords, and personal addresses removed
- Screenshots (if applicable)
