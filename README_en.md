# PixivDownload

[中文](./README.md) | English

Local Pixiv batch image download tool, consisting of a **Spring Boot backend** + **Tampermonkey userscript**.

**Features:** Single artwork download / User homepage batch download / N-Tab bookmark batch download / Animated image auto-conversion to WebP / Download history management / Image classification tool / Multi-mode rate limiting

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

---

## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
  - [1. Run the Backend](#1-run-the-backend)
  - [2. Initial Setup](#2-initial-setup)
  - [3. Install Userscripts](#3-install-userscripts)
  - [4. Configure Proxy](#4-configure-proxy)
  - [5. Install ffmpeg (Optional, Ugoira Only)](#5-install-ffmpeg-optional-ugoira-only)
- [Usage](#usage)
  - [Single Artwork Download](#single-artwork-download)
  - [User Homepage Batch Download](#user-homepage-batch-download)
  - [N-Tab Bookmark Batch Download](#n-tab-bookmark-batch-download)
  - [Web Batch Download Page](#web-batch-download-page)
  - [Download Monitor Page](#download-monitor-page)
- [Tools](#tools)
  - [Image Classifier](#image-classifier)
  - [R18 Backfill Tool](#r18-backfill-tool)
  - [Folder Path Checker](#folder-path-checker)
  - [Legacy Data Migration Tool](#legacy-data-migration-tool)
- [Configuration](#configuration)
- [Disclaimer](#disclaimer)

---

## Requirements

| Dependency | Description |
|------------|-------------|
| Java 17+ | Required to run the backend |
| Proxy Software | Clash, v2rayN, etc., for accessing Pixiv (default `127.0.0.1:7890`) |
| Tampermonkey | Browser extension for installing userscripts |
| ffmpeg (Optional) | Only required for animated images (Ugoira); must be in system PATH |

---

## Installation

### 1. Run the Backend

Download the latest `PixivDownload-vX.X.X.jar` from [Releases](../../releases) and run anywhere:

```bash
java -jar PixivDownload-vX.X.X.jar
```

The backend listens on `http://localhost:6999` by default, and downloads are saved to `pixiv-download/` in the working directory.

On first startup, the browser will automatically open the setup wizard. **Please complete [Initial Setup](#2-initial-setup) before using other features.**

### 2. Initial Setup

After first startup, the browser will automatically open `http://localhost:6999/setup.html`. Follow these steps:

**① Set Admin Account**

Enter username and password (minimum 6 characters) for future login.

**② Choose Usage Mode**

| Mode | Use Case | Description |
|------|----------|-------------|
| Solo Mode | Personal use | All pages require login. Cookies, settings, and download queue are stored server-side, sharing state across devices. |
| Multi Mode | Shared server | No login required. Each visitor's configuration is stored independently in their browser's `localStorage`. |

**③ Click "Complete Setup"**

After configuration is written to `pixiv-download/setup_config.json`, this page will no longer appear. To reinitialize, delete that file and restart.

> **Solo Mode Login:** Visiting any page will automatically redirect to `/login.html`. After entering credentials, check "Remember me" to stay logged in for 30 days; otherwise the session expires after 2 hours of browser closure.

### 3. Install Userscripts

Ensure [Tampermonkey](https://www.tampermonkey.net/) extension is installed in your browser. Download the scripts from [Releases](../../releases) and **drag them into the Tampermonkey management panel** to install:

| Script File | Use Case |
|-------------|----------|
| `Pixiv作品图片下载器(Java后端版).user.js` | One-click download on single artwork pages |
| `Pixiv User 批量下载器 (N-Tab UI 风格版).user.js` | Batch download all works from a user homepage |
| `Pixiv N-Tab 批量下载器 (修复版).user.js` | Import N-Tab bookmark JSON for batch favorites download |

**Changing Server Address:** All three scripts connect to `http://localhost:6999` by default, sharing the same server address setting.

- **User Batch / N-Tab Scripts:** The server address input box at the bottom of the floating panel saves automatically on blur.
- **Single Artwork Script:** Click the Tampermonkey icon next to the browser address bar → Select "⚙️ Set Server Address" from the menu.

> **First-launch notice / Extra step when using an external server**
>
> All three scripts display a **one-time popup on first run** covering the following:
>
> Tampermonkey's `GM_xmlhttpRequest` is restricted by the `@connect` whitelist. Scripts only allow connections to `localhost` by default. **If the backend is deployed on another machine** (e.g. LAN IP `192.168.1.100` or a domain), you must manually update the `@connect` declaration in each script:
>
> 1. Open the Tampermonkey dashboard → Find the script → Click Edit
> 2. Replace `// @connect      YOUR_SERVER_HOST` in the script header with the actual address, e.g.:
>    ```
>    // @connect      192.168.1.100
>    ```
> 3. Save the script (Ctrl+S) — repeat for all three scripts
>
> Alternatively, you can skip the userscripts entirely and download artworks directly via the web interface by visiting `http://<server-address>/login.html` in your browser.

### 4. Configure Proxy

The backend accesses Pixiv CDN through an HTTP proxy. On startup, `config.yaml` is automatically generated in the working directory. Edit the proxy configuration and **restart the service** to apply:

```yaml
proxy.enabled: true
proxy.host: 127.0.0.1
proxy.port: 7890   # Change to your proxy software's actual listening port
```

Ensure your proxy software (Clash, v2rayN, etc.) has a correctly configured node that can access Pixiv, and the local HTTP proxy port matches this configuration.

### 5. Install ffmpeg (Optional, Ugoira Only)

Not installing ffmpeg does not affect normal image downloads; it is only required for animated images (illustType=2).

**Windows Installation Steps:**

1. Go to [ffmpeg official website](https://ffmpeg.org/download.html) to download the Windows pre-built version (recommended gyan.dev build)
2. Extract to any directory, e.g., `C:\ffmpeg`
3. Add `C:\ffmpeg\bin` to system environment variable `PATH`
4. Open a **new** command prompt window and verify with:
   ```bash
   ffmpeg -version
   ```

---

## Usage

### Single Artwork Download

1. Open any Pixiv artwork page (`https://www.pixiv.net/artworks/{id}`)
2. After the page loads, the script will display a download button next to the image
3. Click the button, and the script automatically extracts the image URL and sends it to the backend to start downloading
4. Animated images (Ugoira) are automatically detected and synthesized with ffmpeg into WebP with delays

### User Homepage Batch Download

1. Open any Pixiv user homepage (`https://www.pixiv.net/users/{userId}`)
2. A floating control panel will appear in the corner of the page with the following options:

| Option | Description |
|--------|-------------|
| Start / Pause | Control batch task |
| Skip Already Downloaded | Automatically skip artworks already in the database, resume from breakpoints |
| R18 Only | Download only R18 artworks |
| Download Interval | Wait time between artworks in seconds (default 2, recommended not too short) |
| Concurrency | Number of simultaneous download tasks (default 1, max 5) |

3. Normal artworks are saved to `pixiv-download/{username}/{artworkId}/`, R18 artworks are saved to `pixiv-download/{username}/R18/{artworkId}/`
4. Download progress is obtained in real-time via SSE, with automatic polling fallback on disconnect; the same artwork will not be submitted twice

### [N-Tab](https://github.com/scoful/N-Tab/) Bookmark Batch Download

1. Send one or more tabs into one or more groups of tags
2. Click "Other Functions - Export" in the navigation bar
3. Click "Export" and copy the exported links into the input box
4. After the floating control panel appears, click "Start"
5. Supports skip downloaded, R18 filtering, etc. — same operations as [User Homepage Batch Download](#user-homepage-batch-download)

### Web Batch Download Page

Visit `http://localhost:6999/pixiv-batch.html` solo mode requires login first

Enter Pixiv user ID and Cookie to directly trigger batch downloads from the web page, no userscript required.

**How to Get Cookie (Cookie-Editor recommended):**

1. Install the [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm) browser extension
2. Log into Pixiv in your browser, open any Pixiv page
3. Click the Cookie-Editor icon → **Export** in the bottom right corner → Select **Netscape** format and copy
4. Above the page Cookie input box, switch format to **Netscape**, paste and save

> In solo mode, cookies are stored server-side (`batch_state.json`), shared across devices; in multi mode, they are stored in browser `localStorage`, independent for each visitor.

### Download Monitor Page

Visit `http://localhost:6999/monitor.html`

- Real-time display of current download task progress
- Paginated browsing of all download history, click artworks to preview images
- Display statistics (total artworks, total images, moved count)

---

## Tools

### Image Classifier

`ImageClassifier` is a standalone Java Swing desktop application, **not accessed via browser**, and must be run separately:

```bash
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.imageclassifier.ImageClassifier
```

On first run, `image_classifier.properties` configuration file will be generated in the current directory.

**Basic Workflow:**

1. Enter the parent directory path in the top path field, or click "Browse" to select a directory, then press Enter or click "Open"
2. The program automatically loads all subfolders and displays thumbnails of the current folder (10 per group, click `<` / `>` to paginate)
3. Enter the target classification number in the right-side number box (classification name displays in real-time next to it), click "Classify Entire Folder" to execute the move
4. After moving completes, automatically enters the next folder; click "Skip" to skip the current folder

**Configuration Settings (Menu Bar "Settings"):**

| Tab | Content |
|-----|---------|
| Basic Settings | Configure backend server address (for reporting move paths) |
| Target Folders | Add/Edit/Delete classification directories, format: path + descriptive name |

> When the backend service is online, classification operations automatically report new paths to the backend; when offline, only local file moving occurs.

### R18 Backfill Tool

`R18Backfill` is a command-line tool for batch backfilling NULL `R18` field records in the database. It queries each artwork's restriction level through the Pixiv AJAX interface (no login required) and writes to the database.

**How to Run:**

```bash
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.tools.R18Backfill [options]
```

**Available Options:**

| Option | Default | Description |
|--------|---------|-------------|
| `--db <path>` | `pixiv-download/pixiv_download.db` | Database file path |
| `--proxy <host:port>` | `127.0.0.1:7890` | HTTP proxy address |
| `--no-proxy` | — | Don't use proxy |
| `--delay <ms>` | `800` | Delay between requests (milliseconds) |
| `--dry-run` | — | Print results only, don't write to database |

**Examples:**

```bash
# Run with default settings
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.tools.R18Backfill

# Dry run (don't write to database)
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.tools.R18Backfill --dry-run

# Specify database path, no proxy
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.tools.R18Backfill --db D:/data/pixiv_download.db --no-proxy
```

> When encountering HTTP 429 (rate limit triggered), the tool will automatically stop; already processed results have been written to the database. Rerunning will continue from unbackfilled records.

---

### Folder Path Checker

`FolderChecker` is a Java Swing desktop tool for checking whether recorded artwork folder paths in the database are still accessible. When folders are manually moved causing database paths to become invalid, this tool can be used to batch discover and fix them.

**How to Run:**

```bash
java -cp PixivDownload-vX.X.X.jar top.sywyar.pixivdownload.tools.FolderChecker
```

**Usage Steps:**

1. In the top **Database** input field, fill in the database path, or click **Browse...** to select the `pixiv_download.db` file
2. Click **Check Folders**, the tool scans all artwork records and lists entries with inaccessible paths (status bar shows error count)
3. Click a row in the list to select it; the **New Path** input field at the bottom will automatically fill with the current path
4. Modify to the correct path (or click **Browse...** to select a directory), click **Update DB** to write to database
5. The list refreshes automatically after update

> Already moved artworks check the `move_folder` field; unmoved artworks check the `folder` field. The **Copy ID** button on each row copies the artwork ID to clipboard.

---

### Legacy Data Migration Tool

If you previously used the legacy version (based on JSON files: `download_history.json`, `statistics.json`), you can migrate data to the SQLite database with the following command:

**Real-time Progress Mode (recommended for large data):**
```bash
curl -N http://localhost:6999/api/migration/json-to-sqlite/stream
```

**Normal Mode (returns JSON result):**
```bash
curl -X POST http://localhost:6999/api/migration/json-to-sqlite
```

Both endpoints are idempotent and can be called multiple times without producing duplicate data. Original JSON files will not be deleted after migration; you can manually back them up and delete them.

---

## Configuration

On first startup, `config.yaml` is automatically generated. Edit directly and restart the service to apply:

```yaml
server.port: 6999                              # Service listening port

download.root-folder: pixiv-download           # Download root folder (relative or absolute path)
download.user-flat-folder: false               # User mode folder structure: false=organize by username, true=flat structure same as N-Tab

# ---- Proxy Configuration ----

proxy.enabled: true                            # Enable HTTP proxy
proxy.host: 127.0.0.1                          # Proxy server address
proxy.port: 7890                               # Proxy server port

# ---- Multi Mode Configuration (only effective in multi mode) ----

multi-mode.quota.enabled: true                 # Enable download quota limit
multi-mode.quota.max-artworks: 50              # Max artworks per user per period
multi-mode.quota.reset-period-hours: 24        # Quota reset period (hours)
multi-mode.quota.archive-expire-minutes: 60    # Archive download link expiration (minutes)
multi-mode.quota.limit-image: 0                # Max images per artwork (0=unlimited); if exceeded, counts as ceil(images/limit-image) artworks toward quota

# Post-download processing mode (choose one):
#   pack-and-delete  Package and delete source files (default)
#   never-delete     Package but keep source files; re-downloading same artwork returns completed directly
#   timed-delete     Package and keep source files; auto-delete after delete-after-hours
multi-mode.post-download-mode: pack-and-delete

multi-mode.delete-after-hours: 72              # timed-delete mode: hours to wait before auto-deletion

multi-mode.request-limit-minute: 300           # Max requests per user per minute (0 = unlimited)
```

---

## Disclaimer

- This project is for personal learning and research only; do not use it for any commercial purposes.
- Content downloaded using this tool is copyrighted by the original creators; please respect creators' rights and do not redistribute or use commercially.
- This tool accesses Pixiv through user-provided cookies; users bear their own account risks.
- This project has no affiliation with Pixiv; all consequences of using this tool are the user's responsibility.
- Please set a reasonable download interval to avoid excessive load on Pixiv servers.

---

## Additional Notes

Honestly, I don't really recommend the multi mode of this tool, because all requests go through the server's network IP. Even with different cookies, a large number of requests could lead to IP bans. I'm considering adding a login mechanism to multi mode, but that goes against the project's original intention of simplicity. For now, I'll just continue refining this project.
