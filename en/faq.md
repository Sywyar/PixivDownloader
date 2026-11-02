# Frequently asked questions (FAQ)

Find diagnosis and resolution steps by symptom. Before operations involving databases, accounts or directories, confirm the actual working directory and keep a backup. Replace `APP.jar` in the commands below with the actual main application JAR filename from the distribution, and run commands from the fully extracted directory.

## Installation and startup

### Java is not recognized as an internal or external command

Java 17+ is not installed or is not on the environment path. Install Java 17+ from [Adoptium](https://adoptium.net/) and reopen the terminal, or check `JAVA_HOME` and `PATH`.

### Chinese text is garbled in the console after startup

Add `-Dfile.encoding=UTF-8` when starting manually (the distribution's `run.bat` / `run.sh` already includes it):

```bash
java -Dfile.encoding=UTF-8 -jar APP.jar
```

### Port 6999 is already in use

First check whether a PixivDownloader instance using the same data directory is already running (do not run multiple instances against one data directory). If another program occupies the port, stop it or change `server.port` in `config/config.yaml` and restart.

### Windows installer fails

Close any running `PixivDownload.exe`, then run the installer again as an administrator.

### How to uninstall or reinitialize the administrator account

- **Uninstall**: use the installer or system "Settings → Apps" for the Windows installer edition. For the Java archive edition, back up the work directory, `config/`, `state/` and `data/` as described in [Storage and backup](/en/storage), then delete program files (do not simply delete the entire working directory containing works and databases).
- **Forgotten password or reinitialization**: if you only forgot the password, stop the service and run `--reset-password`. If you really need to recreate initialization state, stop the service, back up, move `state/setup_config.json` aside, then run `--setup` or the desktop wizard (this does not clear business configuration or rebuild the database; see [First-time setup](/en/first-setup)).

### Server / Docker exits immediately with code 78

Headless mode (`--no-gui`) aborts with exit code `78` if initial setup is incomplete. Run interactive setup first, then start the service:

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

### Forgotten administrator password

Stop the running instance, then run the reset command locally (the old password is not required; use at least 8 characters, preferably 12 or more. Resetting invalidates all old sessions. Use `--change-password` if you remember the old password):

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --reset-password
```

### Unrecognized startup option

Startup arguments are validated strictly. Misspelled switches (such as `--no-guii`), arguments missing `=` (such as `--username admin`) and stray positional arguments are rejected. Run `--help` for supported options.

## Proxy and network

### Downloads still fail after configuring a proxy

1. Confirm that the proxy settings were saved (saving in the desktop GUI or using CLI `--setup` supports hot reload; manually editing `config/config.yaml` requires a restart or clicking Save in the GUI to trigger a reload).
2. Verify that the actual Pixiv work page is accessible in your browser, and inspect the failing host and error code in `log/latest.log`.
3. Check that the firewall allows the proxy port. For containers, confirm that you use `host.docker.internal` rather than `127.0.0.1`.
4. Distinguish the global proxy, task-specific proxies, plugin-owned proxies and direct browser requests; see [Network access and third-party services](/en/network-access).

### All users behind a reverse proxy share one rate limit in multi mode

Without trusted proxy configuration, the backend rate-limits by TCP source IP. Set `server.trusted-proxy-cidrs` in `config/config.yaml` to the actual reverse proxy egress or container subnet (such as `172.18.0.0/16`), and have the reverse proxy send complete `Forwarded` or `X-Forwarded-For` + `X-Forwarded-Proto` + `X-Forwarded-Host` headers. See [HTTPS and reverse proxies in Configuration reference](/en/configuration#https-and-reverse-proxies).

### Other devices on the LAN cannot connect

Confirm that the firewall allows `server.port` (default `6999`) and the service is not bound only to `127.0.0.1` (Docker defaults to binding only the host loopback address; expose access through a reverse proxy or change the mapping). From the LAN device, visit `http://<server IP>:6999/`.

## Cookies and downloads

### Search returns no results or reports an invalid cookie format

- Search downloads, Quick access and R-18 works require a cookie containing a valid `PHPSESSID`.
- The selected paste format (**Netscape** or **Header String**) must match the exported content. `document.cookie` read by ordinary frontend scripts does not include the HttpOnly `PHPSESSID`; obtain it again as described in [Your first download](/en/first-download#_2-save-a-pixiv-cookie-if-needed).
- To clear a cookie, explicitly click Clear on the download page. Scheduled tasks hold separate cookie bindings, which must also be removed from the corresponding tasks.

### Ugoira conversion fails or a novel EPUB has no cover

- **Ugoira**: confirm that `ffmpeg -version` works (or click "Download FFmpeg" on the desktop GUI's "Status" page). MP4 also requires an H.264 encoder.
- **Novel covers**: covers come from `.pximg.net`; make sure the backend proxy can reach that image domain, then download the novel again after fixing the proxy.

### The online listening engine is silent or synthesis fails

1. The online engine (Edge neural voices) connects to Microsoft services through the backend proxy. First confirm that `proxy.*` works.
2. On an HTTP 403 handshake response, the backend automatically refreshes the Edge version and corrects clock skew before retrying. If failures continue, correct the local system time.
3. You can switch to "Browser engine" in the playback bar.
4. Invited guests are rate-limited by `guest-invite.tts-request-limit-minute` (default 30 requests per minute); exceeding it returns 429. Administrators are not subject to this limit.

### Some batch downloads fail, filename templates do not apply, or quota is used quickly

- **Partial failure**: check whether the author deleted the work or made it private, and whether the cookie expired. For upstream rate limits, reduce concurrency and increase the interval between works.
- **Filename templates**: enclose variables in braces (such as `{artwork_id}`). Changes apply only to new downloads afterward (see [Filename template variables](/en/usage-guide#filename-template-variables) for supported variables).
- **Multi-page quota in multi mode**: when `multi-mode.quota.limit-image` is greater than `0`, works with more pages than that threshold consume a proportionally larger number of quota units.

## Desktop GUI and database

### GUI remains at Starting, updates fail, or an error dialog appears

- **Slow startup**: after 10 seconds, the waiting time is displayed. Database migrations or plugin loading may be in progress. Check `log/latest.log` and retain the data and logs. **Do not delete the database to troubleshoot slow startup**.
- **Online update failure**: check the backend proxy connection, or download a newer installer directly from [Releases](https://github.com/Sywyar/PixivDownloader/releases) and install over the existing version.
- **Error details**: click "Open log file" in the dialog to view complete English diagnostic logs in `log/latest.log`.

### Database location, corruption and backup recovery

- **Location**: SQLite defaults to `data/pixiv_download.db` (with `-wal` / `-shm` companion files); PostgreSQL resides on the configured database server.
- **Database corruption**: first stop the application and fully back up the original database and companion files. Run `PRAGMA integrity_check;` only on a **backup copy** (it checks but does not repair). If corruption is confirmed, prefer restoring a verified backup. Work identification, page counts, paths and relationships all rely on the database. **After losing the database, work folders alone cannot restore the gallery or guarantee that redownloads will be skipped**.
- **Backup, relocation and upgrades**: see [Storage and backup](/en/storage) for full backup scope and directory relocation. When upgrading a Java distribution, fully extract it and replace the main application and `plugins/` together, retaining `config/`, `state/` and `data/`. Do not replace only the main JAR.

## Userscripts and security

### Userscripts do not appear, show duplicate panels, or cannot connect to the backend

- Confirm that Tampermonkey and the script are enabled, that the current page matches `@match` (for example, User Batch runs only on `/users/`), and refresh the page.
- **Duplicate panels**: do not enable All-in-One and individual download scripts together; keep only one option enabled.
- **Cannot connect to the backend**: confirm that the backend is running and that you are signed in for solo mode. For deployments other than `localhost:6999`, change both the backend address inside the script and its `@connect` permission; see [Userscripts](/en/userscripts).

### Account security, identity isolation and HTTPS

- This tool requests Pixiv using the cookie you provide. Set reasonable concurrency and intervals, and follow the platform's rules.
- **Identity distinction**: users in `multi` mode who are not signed in and have no invitation code are **visitors** (isolated by UUID and `multi-mode.*`). Users with invitation codes are **invited guests** (read-only access to allowlisted content in both `solo` / `multi`, rate-limited by `guest-invite.*`).
- **Enable HTTPS**: set `server.ssl.enabled: true`, `server.ssl.certificate` and `server.ssl.certificate-private-key` in `config/config.yaml` (see [Configuration reference](/en/configuration#https-and-reverse-proxies)).

## Report an issue

When reporting an issue on [GitHub Issues](https://github.com/Sywyar/PixivDownloader/issues), include your operating system, application version, reproduction steps, relevant screenshots and `log/latest.log` with cookies, passwords, tokens and private addresses removed.
