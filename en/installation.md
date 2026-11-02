# Installation Guide

Choose a distribution from [Releases](https://github.com/Sywyar/PixivDownloader/releases). In filenames below, `*` or `x.x.x` stands for the selected version. Replace example JAR names with the actual filename and run commands from the fully extracted directory.

| Environment | Package | Prepare separately |
| --- | --- | --- |
| Windows x64 desktop | `*-win-x64-setup.exe` | FFmpeg when needed |
| Run Java on Windows, Linux or macOS | `*-java.zip` | Java 17+ and FFmpeg when needed |
| Include optional database and import plugins | `*-full-offline.zip` | Java 17+ and FFmpeg when needed |
| Docker service | Build with the repository Dockerfile and a full Java distribution | Docker Engine, Compose and writable data directories |

The standard package contains the default official plugins. Full-offline adds `database-postgresql` and `pixiv-batch-downloader-import`. Neither Java package includes a JRE, FFmpeg or Douyin. Full-offline does not eliminate network access to Pixiv, online AI or TTS services.

## Requirements

| Dependency | Minimum Version | Notes |
|------------|----------------|-------|
| **Java** | 17+ | Required for the Java standard / full-offline package; Windows installer bundles JRE |
| **OS** | Windows / macOS / Linux | Cross-platform |
| **Tampermonkey** | Latest | Required for userscripts |
| **FFmpeg** | Encoders for your selected formats | Used for media conversion; MP4 requires an H.264 encoder |

---

## Method 1: Java Standard / Full-Offline Package (Cross-platform)

### 1. Install Java 17+

- **Windows**: Download from [Adoptium](https://adoptium.net/)
- **macOS**: `brew install openjdk@17`
- **Linux**: `sudo apt install openjdk-17-jdk` (Debian/Ubuntu) or `sudo dnf install java-17-openjdk` (Fedora)

Verify installation:

```bash
java -version
# Should output something like: openjdk version "17.0.x" ...
```

### 2. Download and Extract

Download from [Releases](https://github.com/Sywyar/PixivDownloader/releases):

- `PixivDownload-*-java.zip` — Java standard package, with the same plugin set as the Windows installer (no Douyin)
- `PixivDownload-*-full-offline.zip` — full-offline package, adding the PostgreSQL database and PixivBatchDownloader import plugins (no Douyin)

You must **fully extract** the archive — do not take out only the JAR: both the launcher scripts and the `plugins/` directory are required, because official external plugins are loaded from the working directory's `plugins/` folder at startup.

?> The standalone `PixivDownload-*.jar` is the core-shell JAR without the required download workbench plugin. It is only an internal build input and is not provided as a regular user attachment; running it directly enters recovery/repair mode.

### 3. Launch

Run `run.bat` on Windows; run `sh run.sh` inside the extracted directory on Linux/macOS. You can also start manually:

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar
```

> [!IMPORTANT]
> Always add `-Dfile.encoding=UTF-8` to avoid garbled text on Chinese Windows (`run.bat` / `run.sh` already include it).

### 4. Background Running (Server/Docker)

```bash
# Headless mode (no GUI)
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui

# Run in background with nohup
nohup java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui > app.log 2>&1 &
```

> [!IMPORTANT]
> **Headless / `--no-gui` mode requires setup to be completed first.** There is no GUI wizard on a server, and `setup.html` only accepts local connections. Attempting `--no-gui` before first-time setup aborts startup and prints a CLI hint. Run setup first:
>
> ```bash
> # Interactive: prompts for username, password, run mode (solo|multi), and HTTP proxy (enable, host, port)
> java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup
>
> # One-shot non-interactive (the password is visible in shell history / process list;
> # use this only for automation scripts)
> java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup \
>     --username=admin --password='YourPassword123' --mode=solo \
>     --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
> ```
>
> The proxy is used for backend access whose host or task route selects it, such as Pixiv downloads, online updates, FFmpeg downloads, and some online TTS. Browser requests, SMTP, and explicitly direct routes may bypass it; see [Network Access and Third-Party Services](/en/network-access) for the complete boundary. When the `--proxy-*` flags are omitted it prompts interactively, and you can pass `--proxy-enabled=false` if no proxy is needed. Use `--change-password` to change the password later, or `--reset-password` if it was forgotten. See [first-time setup](/en/first-setup).

---

## Method 2: Windows Installer (Recommended for Windows)

### 1. Download and Run Installer

Download `PixivDownload-x.x.x-win-x64-setup.exe` from [Releases](https://github.com/Sywyar/PixivDownloader/releases).

?> The Windows installer includes the official plugin distribution set, including required `download-workbench`, default `gui-compose`, and fallback `gui-swing`. The Java standard package uses the same default set; full-offline adds two optional plugins. Douyin is a regular third-party plugin and must be installed from a custom repository or local package.

The installer requests UAC when it writes the application directory. The installed application and portable launcher also request administrator privileges by default. When the host is actually elevated, `host-process-full-trust` plugins inherit those privileges and the Plugin Management page keeps a warning visible.

### 2. Installation Process

1. Select installation language (中文/English)
2. Choose installation directory
3. **Optional tasks**: Check "Download and install FFmpeg"
   - FFmpeg is used for Ugoira-to-WebP conversion
   - Not required for regular image downloads
   - Can be downloaded later from the GUI status page

### 3. Maintenance Mode

Run the installer again after installation to enter maintenance mode:
- **Repair** — Reinstall program files
- **Modify** — Change installed components
- **Uninstall** — Complete removal

### 4. Launch

Automatic launch after installation, or launch from Start Menu / desktop shortcut.

> [!NOTE]
> The installer supports language selection and will detect if `PixivDownload.exe` is still running, prompting you to close it before continuing.

---

## Method 3: Docker (Long-Running Server)

The repository's `Dockerfile` builds an image from a signed Java distribution and includes Java 17, FFmpeg and curl. Extract the complete Java standard or full-offline package into `build/dist/default-downloader/`. That directory must directly contain the application JAR, `plugins/`, `plugins-manifest.json` and `SHA256SUMS`. Then run `docker compose build`.

### 1. Requirements

- Docker Engine with Docker Compose (the `docker compose` command).

The container runs as UID/GID 10001. For a new Linux installation, create writable mount directories first:

```bash
sudo install -d -o 10001 -g 10001 config state data pixiv-download log
```

### 2. First-Time Setup (do this first)

There is no desktop GUI inside the container, and `setup.html` only accepts local connections, so first-time initialization must use the CLI `--setup`:

```bash
# Run from the repo root (which contains Dockerfile / docker-compose.yml)
docker compose run --rm app --setup
# Interactive prompts: username, password, run mode (solo|multi), and HTTP proxy (enable, host, port)
# Account is written to state/setup_config.json; the proxy goes into config/config.yaml
```

> [!WARNING]
> **Do not skip this and run `up` directly.** Before setup is complete the container exits with code 78 and keeps restarting (the logs print the hint to run `--setup`).

### 3. Run

```bash
docker compose up -d         # detached
docker compose logs -f app   # view logs
docker compose down          # stop
```

The default port binds only to the host's `127.0.0.1:6999`; open `http://127.0.0.1:6999/` on that host. Remote access can use an existing SSH tunnel or a separately configured reverse proxy. Sign in as the administrator to install, inspect and repair plugins.

### 4. Proxy Configuration (important)

Backend access whose host or task route selects a proxy uses this endpoint, including Pixiv downloads, online updates, FFmpeg downloads, and some online TTS. Browser requests, SMTP, and explicitly direct routes may bypass it; see [Network Access and Third-Party Services](/en/network-access) for the complete boundary. `config.yaml` defaults to `proxy.host: 127.0.0.1`, which inside the container points at the container itself and is unreachable. `docker-compose.yml` already declares `host.docker.internal -> host gateway`.

The recommended approach is to configure the proxy right in step 2's `--setup` (no need to edit files afterwards):

```bash
# Reuse the proxy running on the host (keep the port, e.g. 7890)
docker compose run --rm app --setup --proxy-host=host.docker.internal --proxy-port=7890
# If no proxy is needed (you have another route out), add:
#   --proxy-enabled=false
```

You can also edit the mounted `config/config.yaml` after setup:

```yaml
proxy.host: host.docker.internal   # reuse the proxy running on the host (keep the port, e.g. 7890)
# Or, if no proxy is needed (you have another route out):
# proxy.enabled: false
```

Run `docker compose restart app` to apply.

### 5. Data Persistence

`docker-compose.yml` bind-mounts the following directories to the host so data survives restarts/rebuilds:

| Host path | Purpose |
|-----------|---------|
| `./config/` | Runtime config `config.yaml` |
| `./state/` | Session state, `setup_config.json`, batch download state, GUI onboarding markers |
| `./data/` | SQLite database `pixiv_download.db`, collection icons, thumbnail cache, TTS cache, backfill state, narration reference audio |
| `./pixiv-download/` | Downloaded artwork/novel/series files (incl. `_archives` packaging) |
| `./log/` | Runtime logs (optional) |

The `plugins` named volume stores installed packages. The `config/`, `state/`, `data/`, `pixiv-download/` and `log/` directories are mounted from the host. The instance lock uses `state/instance/`, and SQLite extracts its native library into `state/`; these paths must be writable and permit loading the library. The root filesystem remains read-only and `/tmp` retains `noexec,nosuid,nodev`.

Replacing the image does not overwrite an existing plugin volume. After recreation, check plugin versions and disabled states in plugin management. An incompatible required plugin puts the host in recovery mode; install a trusted package matching the host. Preserve the plugin volume, configuration, databases and downloads. Stop with `docker compose down` without the volume-deleting `-v` option.

### 6. Health Check

Both the image and compose configure a health probe against the public actuator endpoints:

- `GET /actuator/health` — returns `{"status":"UP"}` (no login required, no internal details leaked).
- `GET /actuator/info` — returns the app name and version.

The `STATUS` column of `docker compose ps` shows `healthy`/`unhealthy`.

> [!NOTE]
> Changing the port, proxy, SSL, etc. means editing `config/config.yaml` then `docker compose restart app`. If you change `server.port`, also update the compose port mapping and the health-check address.

---

## Installing Userscripts (Optional)

> [!TIP]
> We recommend using the web-based `pixiv-batch.html` first — no script installation needed for batch downloads.

### Method 1: One-Click Install via Web UI (Recommended)

1. Start the PixivDownloader backend
2. Visit `http://localhost:6999/pixiv-batch.html`
3. Find and expand the Userscripts entry on the download page
4. Click the "⬇ Install" button for the desired script

> [!WARNING]
> When installed via the web UI, the script's update check points to the current backend. If the address changes, reinstall from the new backend and check the target address in the script settings. `@connect` only grants host access.

### Method 2: Install an individual script from source

Individual scripts are available as committed `.user.js` files in the repository. See [Userscripts](/en/userscripts) for installation links and updates. The current release workflow does not upload userscript attachments; install the combined script from the running backend.

### Complete Script List

| Script Name | Function | Availability |
|-------------|----------|--------------|
| Page Scrape Downloader | Scrape artwork from Pixiv page DOM | Web UI / GitHub |
| User Batch Downloader | Batch download from user profile | Web UI / GitHub |
| URL Batch Importer | Batch import artwork URLs | Web UI / GitHub |
| Single Artwork Downloader (Java Backend) | Single artwork via backend | Web UI / GitHub |
| Single Artwork Downloader (Local Download) | Browser local download, no backend needed | Web UI / GitHub |
| Enhancement Toolbox | Downloaded artwork markers, cookie import, etc. | Web UI / GitHub |

### Extra Configuration for non-localhost Deployments

<details>
<summary><strong>Click to expand</strong></summary>

For a backend on another machine, first set its actual protocol, host, and port in the script settings. Install from that backend's download page to configure `@connect` permission. For a manual installation, also check the allowed hosts in the header. Changing `@connect` alone does not change the request target.

Refresh Pixiv, submit one work, and check that the intended backend receives it. See [Userscripts](userscripts.md) for troubleshooting.

</details>

---

## Installing FFmpeg (Optional)

FFmpeg is used for Ugoira-to-WebP conversion. Not needed for regular image downloads.

### Automatic Installation (Recommended)

- **Windows Installer**: Check "Download and install FFmpeg" during installation
- **GUI Tool**: Click "Download FFmpeg" on the GUI "Status" tab after startup

Automatic installation uses the project-maintained `ffmpeg-stable` Release, built from the latest official stable FFmpeg source. It selects assets for Windows x64, Linux x64/arm64, or macOS x64/arm64. Use a system FFmpeg installation on other platforms.

### Manual Installation

1. Download from [FFmpeg website](https://ffmpeg.org/download.html)
2. Add the directory containing `ffmpeg.exe` to system `PATH`
3. Verify: `ffmpeg -version`

---

## Directory Structure

The distribution's `plugins/` directory stores installed plugin packages. Runtime configuration goes in `config/`, state in `state/`, databases, user resources and caches in `data/`, and logs in `log/`. Works default to `pixiv-download/`; collections and plugins can select other locations.

Directories are created as needed. See [storage](/en/storage) for exact paths, database companion files, credentials and backup boundaries. Before upgrading, stop the app normally and back up persistent data. Keeping work files alone does not preserve gallery records.

---

## Verifying Installation

Complete [setup](/en/first-setup), sign in to the download page and add one work. Check that the download succeeds, its file exists and the gallery opens it. A health response of `UP` alone does not validate plugins, cookies or the download path.

Useful addresses:

- `http://localhost:6999/` — Redirects to download page
- `http://localhost:6999/setup.html` — First-time setup wizard (redirects if completed)
- `http://localhost:6999/intro.html` — Product introduction page
- `http://localhost:6999/pixiv-batch.html` — Batch download page (requires `download-workbench`; bundled by the Java standard package, the full-offline package, and the default Windows installer)
- `http://localhost:6999/monitor.html` — Download monitor
- `http://localhost:6999/pixiv-gallery.html` — Artwork gallery (requires the optional `gallery` plugin)

---

## Official plugins and packages

`download-workbench` is the required external plugin. It owns the download page, download APIs, queue, userscript entry, Pixiv artwork proxy, and scheduled-task host. If it is missing, corrupted, incompatible, or fails offline verification, the app enters the recovery path and only exposes login, plugin management, and repair/install entry points.

The official plugin distribution set is `download-workbench`, `database-sqlite`, `gui-compose`, `gui-swing`, `gallery-tools`, `posthog`, `gallery`, `novel`, `notification`, `multi-mode-decision-survey`, `push`, `mail`, `tts`, and `ai`. `gui-compose` is the default desktop UI and `gui-swing` is the automatic fallback. Douyin is no longer distributed by the official repository, signature, or release packages. Missing or disabling an optional plugin only removes its own pages, APIs, navigation, resources, i18n, GUI settings, or capability contributions; an unused optional capability does not trigger recovery. An unavailable selected database provider still does.

- Windows installer: bundles the JRE and official plugin distribution set; FFmpeg is downloaded only when selected during setup, and Douyin is not included.
- Java standard package (`*-java.zip`): the same plugin set; no JRE, FFmpeg, or Douyin.
- Full-offline package (`*-full-offline.zip`): the default set plus `database-postgresql` and `pixiv-batch-downloader-import`; no JRE, FFmpeg or Douyin.
- Missing `gallery-tools` does not affect image Hash writes or historical Hash data.
- Missing `gallery` does not affect the download page, download APIs, userscripts, Pixiv artwork proxy, scheduled-task host, work metadata, download facts, Hash data, or local resource index.
- Missing `novel` withdraws novel downloading, its gallery, body storage, translation state, series compilation, and scheduled novel runner together; the core does not retain a parallel implementation.
