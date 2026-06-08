# Installation Guide

## Requirements

| Dependency | Minimum Version | Notes |
|------------|----------------|-------|
| **Java** | 17+ | Required for JAR; Windows installer bundles JRE |
| **OS** | Windows / macOS / Linux | Cross-platform |
| **Tampermonkey** | Latest | Required for userscripts |
| **ffmpeg** | Any | Required for Ugoira-to-WebP conversion (optional) |

---

## Method 1: JAR (Cross-platform)

### 1. Install Java 17+

- **Windows**: Download from [Adoptium](https://adoptium.net/)
- **macOS**: `brew install openjdk@17`
- **Linux**: `sudo apt install openjdk-17-jdk` (Debian/Ubuntu) or `sudo dnf install java-17-openjdk` (Fedora)

Verify installation:

```bash
java -version
# Should output something like: openjdk version "17.0.x" ...
```

### 2. Download JAR

Download `PixivDownload-vX.X.X.jar` from [Releases](https://github.com/Sywyar/PixivDownloader/releases).

### 3. Launch

```bash
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar
```

> [!IMPORTANT]
> Always add `-Dfile.encoding=UTF-8` to avoid garbled text on Chinese Windows.

### 4. Background Running (Server/Docker)

```bash
# Headless mode (no GUI)
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui

# Run in background with nohup
nohup java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --no-gui > app.log 2>&1 &
```

> [!IMPORTANT]
> **Headless / `--no-gui` mode requires setup to be completed first.** There is no GUI wizard on a server, and `setup.html` only accepts local connections. Starting with v1.10.0, attempting `--no-gui` before first-time setup aborts startup and prints a CLI hint. Run setup first:
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
> The proxy is used for all of the backend's outbound access (reaching Pixiv and downloading works, online updates, downloading the bundled FFmpeg, online TTS); when the `--proxy-*` flags are omitted it prompts interactively, and you can pass `--proxy-enabled=false` if no proxy is needed. Use `--change-password` to change the password later, or `--reset-password` if it was forgotten. See [Usage Guide → Launch Parameters](en-Usage-Guide).

---

## Method 2: Windows Installer (Recommended for Windows)

### 1. Download and Run Installer

Download `PixivDownload-x.x.x-win-x64-setup.exe` from [Releases](https://github.com/Sywyar/PixivDownloader/releases).

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

The repository root ships a `Dockerfile` (multi-stage build; the runtime image bundles the ffmpeg required for Ugoira) and a `docker-compose.yml`.

### 1. Requirements

- Docker 20.10+ with Docker Compose (the `docker compose` command).

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

Then open `http://<host-ip>:6999/` in a browser. Login, monitor, and gallery pages are all reachable remotely via session auth; the setup wizard and desktop GUI are not available inside the container (see below for editing config).

### 4. Proxy Configuration (important)

All of the app's outbound access goes through this proxy: reaching Pixiv and downloading works, online updates, downloading the bundled FFmpeg, and online TTS. `config.yaml` defaults to `proxy.host: 127.0.0.1`, which inside the container points at the container itself and is unreachable. `docker-compose.yml` already declares `host.docker.internal -> host gateway`.

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
3. Expand the "🧩 Userscripts" card at the top
4. Click the "⬇ Install" button for the desired script

> [!WARNING]
> When installed via the web UI, the script's update check points to the current backend. If the backend address changes, you'll need to reinstall or manually edit the `@connect` declaration.

### Method 2: Download from Release

Download script files from [Releases](https://github.com/Sywyar/PixivDownloader/releases) and drag them into Tampermonkey's dashboard.

Scripts available in Release:

| Script File | Description |
|-------------|-------------|
| `Pixiv All-in-One.user.js` | Bundle (recommended): Page Scrape, User Batch, URL Batch, Single Artwork (Java backend), Toolbox |
| `Pixiv Single Artwork Downloader (Local Download).user.js` | Browser-side local download, no Java backend needed |

### Complete Script List

| Script Name | Function | Availability |
|-------------|----------|--------------|
| Page Scrape Downloader | Scrape artwork from Pixiv page DOM | Web UI / GitHub |
| User Batch Downloader | Batch download from user profile | Web UI / GitHub |
| URL Batch Importer | Batch import artwork URLs | Web UI / GitHub |
| Single Artwork Downloader (Java Backend) | Single artwork via backend | Web UI / GitHub |
| Single Artwork Downloader (Local Download) | Browser local download, no backend needed | Release / Web UI / GitHub |
| Enhancement Toolbox | Downloaded artwork markers, cookie import, etc. | Web UI / GitHub |

### Extra Configuration for non-localhost Deployments

<details>
<summary><strong>Click to expand</strong></summary>

Tampermonkey's `@connect` allowlist only permits `localhost` by default. If the backend is deployed on another machine:

1. Open Tampermonkey Dashboard → find the script → click Edit
2. Replace `// @connect      YOUR_SERVER_HOST` in the script header with the actual address
3. Save the script (Ctrl+S)

When installed via the web UI (`pixiv-batch.html`), `@connect` will be automatically set to the current backend address.

</details>

---

## Installing FFmpeg (Optional)

FFmpeg is used for Ugoira-to-WebP conversion. Not needed for regular image downloads.

### Automatic Installation (Recommended)

- **Windows Installer**: Check "Download and install FFmpeg" during installation
- **GUI Tool**: Click "Download FFmpeg" on the GUI "Status" tab after startup

### Manual Installation

1. Download from [FFmpeg website](https://ffmpeg.org/download.html)
2. Add the directory containing `ffmpeg.exe` to system `PATH`
3. Verify: `ffmpeg -version`

---

## Directory Structure

After the first launch, the following files will be generated in the working directory:

```
Working Directory/
├── config/                      # Runtime configuration directory
│   ├── config.yaml              # Main configuration file
│   └── image_classifier.properties  # Image classifier settings
├── state/                       # Runtime state directory
│   ├── setup_config.json        # Account/setup configuration
│   ├── batch_state.json         # Batch download state
│   └── gui/                     # GUI onboarding and proxy step state markers
├── data/                        # Application data directory
│   ├── pixiv_download.db        # SQLite database (+ -wal / -shm companions)
│   ├── collection_icons/        # Custom collection icons
│   ├── gallery_thumbs/          # Gallery binary thumbnail cache
│   ├── tts/                     # TTS version cache
│   ├── backfill/                # Data backfill tool state
│   └── narration-voice/         # Multi-character narration reference audio
├── pixiv-download/              # Downloaded files (default root)
│   ├── artwork-{id}/            # Artwork directories
│   ├── novel-{id}/              # Novel directories
│   ├── novel-series-{id}/       # Novel series
│   └── ...
└── log/                         # Runtime logs
```

---

## Verifying Installation

After startup, visit in your browser:

- `http://localhost:6999/` — Redirects to download page
- `http://localhost:6999/setup.html` — First-time setup wizard (redirects if completed)
- `http://localhost:6999/intro.html` — Product introduction page
- `http://localhost:6999/pixiv-batch.html` — Batch download page
- `http://localhost:6999/monitor.html` — Download monitor
- `http://localhost:6999/pixiv-gallery.html` — Artwork gallery
