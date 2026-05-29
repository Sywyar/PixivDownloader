# Frequently Asked Questions (FAQ)

## Installation & Startup

### Q: "Java is not recognized" error on startup

**A**: You need to install Java 17+. Download from [Adoptium](https://adoptium.net/), then restart your terminal or manually configure `JAVA_HOME` and `PATH`.

### Q: Garbled Chinese text after startup

**A**: Always add `-Dfile.encoding=UTF-8` to the launch command:

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar
```

### Q: Port 6999 is already in use

**A**: Two solutions:
1. Close the program occupying port 6999
2. Change `server.port` in `config.yaml` to a different port (requires restart)

### Q: Windows installer fails

**A**:
1. Ensure `PixivDownload.exe` is closed before installing
2. Check for leftover MSI from previous versions (installer handles migration automatically)
3. Run the installer as Administrator

### Q: How to uninstall?

**A**:
- **Installer version**: Run the installer again and select "Uninstall"; or via Windows "Settings → Apps"
- **JAR version**: Simply delete the program directory

### Q: How to reset all settings?

**A**: Stop the service, delete `state/setup_config.json`, then run `--setup` again (or use the GUI "Home" wizard). Note: this does not delete downloaded files or the database.

### Q: My server / Docker container always exits at startup, asking me to run setup first?

**A**: Starting with v1.10.0, headless / `--no-gui` startup is blocked when first-time setup is not complete — otherwise you would end up with a service that no remote client can configure. Initialize via CLI first:

```bash
# Interactive: prompts for username, password, run mode
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup

# Automation: one-shot (password is visible in shell history / process list)
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
    --username=admin --password='YourPassword123' --mode=solo
```

Then start the service normally with `--no-gui`. See [Usage Guide → CLI Admin Commands](en-Usage-Guide#cli-admin-commands-v1100).

### Q: The admin forgot the password. What now?

**A**: Stop the running PixivDownloader, then reset the password via CLI (the previous password is not required):

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --reset-password
```

The wizard asks for the new password twice (≥ 6 characters). After a successful reset, all existing login sessions are invalidated; log in again with the new password. If you still know the current password and just want to rotate it, use `--change-password` (which verifies the current password).

### Q: Startup says "unrecognized startup argument"?

**A**: From v1.10.0 the launcher validates arguments strictly: typos (`--no-guii`), value flags missing the `=` (`--username admin`), and plain positional tokens are rejected. Run `--help` to see the full option list. Arguments shaped as `--key=value` are still forwarded to Spring Boot as property overrides.

---

## Proxy & Network

### Q: Downloads still fail after configuring proxy

**A**:
1. Confirm proxy settings have been saved (after saving in the GUI or configuring via CLI `--setup`, proxy settings **support hot reload** and take effect immediately, no restart needed; if you hand-edit `config.yaml` directly, restart the service or click Save in the GUI to trigger a hot reload)
2. Verify the proxy works: visit `https://i.pximg.net/` through the proxy in your browser
3. Check if the firewall allows the proxy port
4. Note: the proxy is used for all outbound access (Pixiv, online updates, FFmpeg download, online TTS), so these issues affect those features too

### Q: All users rate-limited when deployed behind reverse proxy

**A**: Backend API and static resource rate limiting are based on TCP source IP (`request.getRemoteAddr()`). Behind a reverse proxy, all requests share the same source IP.

**Solution**: Implement rate limiting at the reverse proxy layer using `X-Forwarded-For` / `X-Real-IP`, and disable backend limits:

```yaml
multi-mode.request-limit-minute: 0
multi-mode.static-resource-request-limit-minute: 0
```

### Q: Cannot access from other devices on LAN

**A**:
1. Allow port 6999 through firewall
2. Ensure `config.yaml` doesn't restrict binding address
3. Visit `http://<server IP>:6999/` in browser

---

## Cookie Related

### Q: Search mode returns no results

**A**: Search mode requires Pixiv cookies. Please verify:
1. Netscape-format cookies have been correctly pasted in `pixiv-batch.html`
2. Cookies haven't expired (re-login to Pixiv and re-fetch)
3. Proxy is correctly configured

### Q: Cookie format error message

**A**: Ensure:
1. Export in **Netscape** format from Cookie-Editor extension
2. Switch to the **Netscape** format tab on the page before pasting
3. Do not manually modify cookie content

### Q: How to clear saved cookies?

**A**:
- Solo mode: Click the clear cookie button on `pixiv-batch.html`, or log out
- Logging out also clears server-side saved cookies

---

## Download Related

### Q: Ugoira animations won't play after download

**A**: Ugoira requires ffmpeg for WebP conversion. Install ffmpeg and ensure it's available in PATH:

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
2. **Handshake 403**: when the version string is stale, the backend automatically fetches the latest Edge version, corrects clock skew, and retries — this usually self-heals; if it persists, check your machine's system clock
3. **Switch to the browser engine**: in the playback bar's "Settings", switch to the browser engine — fully offline, no network (provided your OS has voices for that language installed)
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

**A**: In multi-mode, configure `multi-mode.quota.limit-image` in `config.yaml`. When an artwork's image count exceeds this value, it counts as multiple quota units:

```yaml
multi-mode.quota.limit-image: 10  # Artworks with >10 images consume proportional quota
```

---

## GUI Related

### Q: GUI shows "Starting..." indefinitely

**A**: After 10+ seconds, the waited time is displayed; it may be updating the database. If unresponsive for long:
1. Check `log/latest.log` log file
2. Try deleting the database (backup first) and restart
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

**A**: Default location: `data/pixiv_download.db`. Uses SQLite WAL mode; can be opened with any SQLite tool.

### Q: What if the database is corrupted?

**A**:
1. Stop the program
2. Back up `pixiv_download.db`, `pixiv_download.db-wal`, `pixiv_download.db-shm`
3. Try repair with SQLite: `sqlite3 pixiv_download.db "PRAGMA integrity_check;"`
4. If irreparable, delete the database files and restart (downloaded files unaffected), but gallery data will be lost

### Q: I lost / migrated the database. Will already-downloaded works be redownloaded?

**A**: No. When redownloading an artwork, if the database has no record for it but `{download-root}/{artworkId}/` already contains image files named with the **default** filename template (`{artwork_id}_p{page}.{ext}`, e.g. `123456_p0.jpg`), a database record will be reconstructed from the actual page count and extensions found on disk, and the redownload will be skipped. The recovered work then shows up in the gallery exactly like a normally-downloaded one.

Caveat: only files matching the **default** filename template are recognized. Files saved under a custom template are not, and will be redownloaded. Metadata (title, author, tags, …) cannot be recovered offline either — the gallery will display the work with missing fields; use the "Database backfill tool" to fill them in later.

### Q: How to back up data?

**A**: Back up the following:
- `data/` — database files
- `pixiv-download/` — downloaded files
- `state/` — runtime state
- `config/` — configuration files
- `collection_icons/` — collection icons

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
3. For non-localhost deployments, modify the `@connect` declaration in the script header

---

## Security

### Q: Will using this tool get my Pixiv account banned?

**A**: This project accesses Pixiv using user-provided cookies. Users bear their own account risks. Recommendations:
- Set reasonable download frequency and concurrency
- Avoid excessive requests in a short time
- Comply with Pixiv terms of service

### Q: Can guests see admin data in Multi mode?

**A**: In Multi mode:
- Guests can only see their own download status
- Admin and Solo mode retain the global view
- In guest invite mode, guests can only browse artwork within the allowlist
- Collections, history, statistics, etc. are filtered by access permissions

### Q: How to enable HTTPS?

**A**: Configure SSL certificates in `config.yaml`:

```yaml
server.ssl.enabled: true
server.ssl.certificate: /path/to/cert.pem
server.ssl.certificate-private-key: /path/to/key.pem
```

PEM format is recommended. PEM takes priority when both PEM and JKS are configured.

---

## Other

### Q: How to migrate to a new computer?

**A**: Copy these to the new computer:
1. `data/` — database files
2. `pixiv-download/` — downloaded files
3. `state/` — runtime state
4. `config/` — configuration files
5. `collection_icons/` — collection icons (if any)

### Q: How to upgrade?

**A**:
- **GUI mode**: Auto-check on startup, or manually check on "Status" page
- **JAR version**: Download and overwrite the new JAR
- **Windows installer**: Run new installer and select "Repair" or overwrite-install
- Database auto-migrates, no manual action needed

### Q: How to provide feedback or report issues?

**A**: Submit an issue on [GitHub Issues](https://github.com/Sywyar/PixivDownloader/issues). Please include:
- OS and version
- PixivDownloader version (check GUI "About" page)
- Issue description and reproduction steps
- Relevant logs (`log/latest.log`)
- Screenshots (if applicable)
