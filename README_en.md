# PixivDownload

[中文](./README.md) | English

Local Pixiv batch image downloader, consisting of a **Spring Boot backend** + **desktop GUI** + **Tampermonkey userscripts**.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

## Table of Contents

- [Overview](#1-overview)
- [Installation](#2-installation)
- [Usage](#3-usage)
- [Development Guide](#4-development-guide)
- [Disclaimer](#disclaimer)
- [Additional Notes](#additional-notes)
- [Friend Links](#friend-links)
- [Development Plan](#development-plan)

## 1. Overview

PixivDownload is a local Pixiv batch image downloader that supports multiple download workflows and convenient management features.

### Features

- `pixiv-batch.html` [one-stop download page](#4-web-batch-download), supporting bulk artwork import, user batch download, and search batch download; with the page scraping userscript, you usually do not need other scripts
- Page batch download — scrape all artworks from Pixiv pages such as search results, following feed, rankings, and more
- `monitor.html` [one-stop management page](#5-download-monitor), with multi-dimensional filtering/sorting and download history filtering by author
- Single artwork download — one-click download on artwork pages
- User homepage batch download — download all works from a specified user
- Bulk artwork import — paste artwork link lists in `url | title` format; compatible with export formats from OneTab, N-Tab, and similar tab manager extensions
- Keyword search download — search Pixiv artworks from the web UI and add them to the queue
- Animated image auto-conversion to WebP — automatically merge Ugoira frames into delayed WebP
- Download history management — records downloaded artworks and author metadata, supports resume, and detects author renames automatically
- GUI tools page — integrates image classification, database directory validation, and database backfill; tools that require exclusive SQLite access automatically stop and restore the backend
- Multi-user mode rate limiting — provides quota and rate limiting for shared deployments

### Screenshots

<details>
<summary><strong>Expand to view screenshots</strong></summary>

#### Screenshot of monitor.html

![](./image/1.png)

#### Screenshot of the pixiv-batch.html userscript installation page

![](./image/2.png)

#### Screenshot of the pixiv-batch.html bulk artwork import page (the userscript offers the same capability, but the web page is more convenient)

![](./image/3.png)

#### Screenshot of the pixiv-batch.html User page parsing and queueing flow (the userscript offers the same capability, but the web page is more convenient)

![](./image/4.png)

#### Screenshot of the pixiv-batch.html Search page parsing and queueing flow

![](./image/5.png)

#### Screenshot of `Pixiv 页面批量下载器.user.js`, which supports scraping across Pixiv pages

![](./image/6.png)

#### Screenshot of single-artwork downloading (Java backend mode and Local download mode provide the same result)

![](./image/7.png)

</details>

## 2. Installation

### 1. Download and Run

Download the latest version from [Releases](../../releases):

| Type                                                | Description                                                                                                                                                                            |
|-----------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PixivDownload-vX.X.X.jar`                          | Universal JAR, requires Java 17+                                                                                                                                                       |
| `PixivDownload-*-win-x64-online-portable.zip`       | Windows online portable build with the smallest size; if FFmpeg is already installed on the system, this version is usually enough                                                     |
| `PixivDownload-*-win-x64-portable.zip`              | Windows offline portable build with bundled slim JRE and FFmpeg, suitable for offline use or systems without FFmpeg                                                                    |
| `PixivDownload-*-win-x64-<culture>-with-ffmpeg.msi` | Windows installer, currently available in `zh-CN` / `en-US`; bundles FFmpeg and works out of the box, but is larger                                                                    |
| `PixivDownload-*-win-x64-<culture>-no-ffmpeg.msi`   | Windows installer, currently available in `zh-CN` / `en-US`; does not bundle FFmpeg, so it is smaller and suitable when FFmpeg is already installed or Ugoira conversion is not needed |

> Windows MSI packages are now split into fixed variants, so choose the package you want before downloading:
> - `zh-CN` / `en-US`: installer UI language
> - `with-ffmpeg`: FFmpeg is bundled for an out-of-the-box setup
> - `no-ffmpeg`: FFmpeg is not bundled; suitable when FFmpeg is already installed or when you only download regular images
> - The MSI installer no longer offers language switching during setup and no longer provides an FFmpeg component selection page

### 2. Install Userscripts (Optional)

You can complete all operations from the web interface without userscripts. If you still want to install them:

**Method 1: One-click install from the web management page (recommended)**

After logging in, open `http://localhost:6999/pixiv-batch.html`, expand the userscript card at the top of the page, and click the install button for the target script. When deployed on a non-localhost server, `@connect` is automatically replaced with the actual address.

**Method 2: Download manually from Releases**

Download the scripts from [Releases](../../releases), then drag them into the Tampermonkey dashboard to install:

<details>
<summary><strong>This extra step is required when installing userscripts from downloaded release files (expand)</strong></summary>

Tampermonkey's `GM_xmlhttpRequest` is restricted by the `@connect` whitelist. By default, the scripts only allow connecting to `localhost`. If the backend is deployed on another machine, you need to manually update each script's `@connect` declaration:

1. Open the Tampermonkey dashboard, find the target script, and click Edit
2. Replace `// @connect      YOUR_SERVER_HOST` in the script header with the actual address
3. Save the script with `Ctrl+S`

</details>

| Script File                              | Recommended Use                                                                                                                  |
|------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| `Pixiv All-in-One.user.js`               | Recommended. Combines page batch download, user batch download, URL bulk import, and single-artwork download (Java backend mode) |
| `Pixiv 单作品图片下载器(Java后端版).user.js`        | Single artwork page download                                                                                                     |
| `Pixiv 单作品图片下载器(Local download).user.js` | Single artwork page download (browser local download, no Java backend required)                                                  |
| `Pixiv User 批量下载器.user.js`               | Batch download from a user homepage                                                                                              |
| `Pixiv URL 批量导入作品下载器.user.js`            | Bulk artwork import, compatible with export formats from OneTab, N-Tab, and similar tab manager extensions                       |
| `Pixiv 页面批量下载器.user.js`                  | Page DOM scraping across Pixiv                                                                                                   |

> **If you only want one script, install `Pixiv All-in-One.user.js` first.** `Local download` remains separate and is not included in the all-in-one bundle.
> **If the all-in-one script has issues, try the matching standalone script first, then include reproduction steps and environment details when opening an issue.**
> **The web interface is recommended first**: `http://localhost:6999/pixiv-batch.html` supports Bulk Artwork Import, User mode, and Search mode without requiring any userscript for batch downloading.

## 3. Usage

```bash
# Start from JAR
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar

# Start from Windows EXE
PixivDownload.exe

# Optional arguments
--no-gui    # Disable the GUI and run in CLI-only mode (recommended only for server/Docker deployments)
--intro     # Open the product introduction page on startup
```

> The desktop GUI starts by default. Use `--no-gui` only for server or Docker deployments. The GUI provides `Status`, `Config`, `Tools`, and `About` tabs for managing the backend and local tools.

> On first startup, the browser automatically opens the setup wizard. Other features remain unavailable until setup is completed.

> The online portable build does not bundle FFmpeg by default, but it will first try to reuse FFmpeg from the system `PATH`, the application directory, or the user directory. If FFmpeg is already installed, the online portable build is enough. Only click `Download FFmpeg` on the GUI `Status` tab when FFmpeg is unavailable and you need Ugoira-to-WebP conversion. Regular image downloads are unaffected.

### 1. Initial Setup

After first startup, the browser automatically opens `http://localhost:6999/setup.html`. Enter a username and password (at least 6 characters) and choose a usage mode:

| Mode       | Use Case                                                                                       |
|------------|------------------------------------------------------------------------------------------------|
| Solo Mode  | Personal use, shared state across devices, requires login                                      |
| Multi Mode | Shared server, each visitor's config is stored independently in the browser, no login required |

The configuration is written to `state/setup_config.json`, and the setup wizard will not appear again. Delete this file and restart to initialize the app again.

### 2. Configure Proxy

The backend accesses the Pixiv CDN through an HTTP proxy. After startup, the config file is generated at `config/config.yaml`. Edit the proxy configuration and **restart the service** for it to take effect:

```yaml
proxy.enabled: true
proxy.host: 127.0.0.1
proxy.port: 7890   # Change this to your actual proxy port
```

> The GUI FFmpeg download button reuses this proxy configuration first, which is useful when the online portable build needs to fetch FFmpeg from GitHub.

### 3. Optional: Get Cookies (required for Search mode, R18-related works, and auto-bookmarking)

1. Install the [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm) extension
2. Log in to Pixiv, click the extension icon, then click **Export** in the lower-right corner and choose **Netscape**
3. Switch the format selector above the cookie input area on the page to **Netscape**, then paste and save

### 4. Web Batch Download

Visit `http://localhost:6999/pixiv-batch.html` (login is required first in Solo Mode):

| Mode                   | Description                                                                                                                           |
|------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| 🎨 Bulk Artwork Import | Paste artwork link lists for batch downloading; compatible with export formats from OneTab, N-Tab, and similar tab manager extensions |
| 👤 User Mode           | Enter a user ID to batch download all works from that user                                                                            |
| 🔍 Search Mode         | Search by keyword, preview thumbnails, and then add results to the queue (requires Cookies)                                           |

Bulk artwork import format:

- One item per line, in `url | title` format
- Example: `https://www.pixiv.net/artworks/12345678 | Sample Title`
- `title` can be left empty; the real title is fetched automatically before download
- Compatible with export formats from OneTab, N-Tab, and similar tab manager extensions; exported lists can also be imported again directly

### 5. Download Monitor

Visit `http://localhost:6999/monitor.html` to view real-time download progress and history.

The author column in the history list supports:

- fuzzy search by author name or author ID
- fuzzy search by tag, plus AI-generated filtering
- checkbox-based author filtering
- click an author name to switch filtering quickly
- sorting by author ID

### 6. GUI Tools Page

Tool-related operations are now launched from the GUI. After starting the app, open the `Tools` tab in the desktop GUI to use the following tools directly:

| Tool                               | Description                                                                                                                                                                                                                                                             |
|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Image Classification Tool          | Opens a standalone image classification window for organizing downloaded images; launching it does not proactively stop the backend                                                                                                                                     |
| Database Directory Validation Tool | Checks whether directory paths stored in the database are still accessible; the backend is automatically stopped before launch and restored after the window closes                                                                                                     |
| Database Backfill Tool             | Uses `ArtworksBackFill` to fill missing `author_id`, `R18`, `is_ai`, `description`, and `tags` / `artwork_tags` data in the `artworks` table in one run; supports database path, proxy, request delay, row limit, dry-run mode, and direct opening of the HTML log page |

> The Database Directory Validation Tool and Database Backfill Tool require exclusive SQLite access. The GUI handles backend stop/resume automatically, so you no longer need to stop the service manually and run commands yourself.

> If your database from older versions contains rows missing `author_id`, `R18`, `is_ai`, `description`, or tag data, just launch the Database Backfill Tool from the GUI.

### 7. Product Intro Page

Visit `http://localhost:6999/intro.html` (publicly accessible, no login required) to view the project introduction.

### 8. Downloaded Images Gallery

Visit `http://localhost:6999/pixiv-gallery.html` to browse a gallery of locally downloaded artworks. A `GALLERY` entry is also available at the top of `monitor.html` and `pixiv-batch.html`, so you can jump there directly from the download monitor or batch page.

The gallery page supports:

- searching by artwork title / artist
- sorting by date, artwork ID, image count, status, artist ID, and tag count, with ascending / descending toggle
- combined filtering by R-18, AI, image format, and collections
- paginated browsing of downloaded artworks, with thumbnail badges for R-18, AI, and multi-page works
- opening the artwork details page at `pixiv-artwork.html?id=<artworkId>` to view the title, description, tags, artist info, related works, and other works by the same artist
- "Expand All" and lightbox preview for multi-page works
- adding artworks to collections or removing them from collections; collections support create, rename, delete, quick create, and custom icons (PNG / JPG / WEBP, max 1 MB)

## 4. Development Guide

### 1. Fork and branch

1. Fork this repository to your own account, then clone your fork.
2. Add the upstream remote so you can keep your branch in sync:

```bash
git remote add upstream <upstream-repo-url>
git fetch upstream
```

3. Create a feature branch from the latest upstream default branch:

```bash
git checkout -b feat/your-change upstream/<default-branch>
```

### 2. Local prerequisites

- JDK 17 (`pom.xml` currently targets Java 17, and Windows packaging also uses `jlink` and `jpackage`)
- Maven 3.9+, or the bundled `mvnw` / `mvnw.cmd`
- PowerShell for Windows packaging; before building MSI packages you also need to install WiX Toolset and make sure `heat.exe`, `candle.exe`, and `light.exe` are available. The current `scripts/package-local.ps1` script calls these classic WiX command-line tools directly, so install a WiX Toolset distribution that includes them.
- Official WiX Toolset overview/docs: https://docs.firegiant.com/wix/
- WiX Toolset v3 releases (installer and binary packages that include the tools above): https://github.com/wixtoolset/wix3/releases
- `curl.exe` if you build the offline portable package or a `with-ffmpeg` MSI, because the packaging script downloads or reuses the FFmpeg payload under `build/ffmpeg`

### 3. Daily development and local verification

Use the Maven lifecycle for local builds:

```powershell
# Windows PowerShell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\mvnw.cmd package -DskipTests
```

```bash
# macOS / Linux
JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8' ./mvnw package -DskipTests

# Run
java -Dfile.encoding=UTF-8 -jar target/PixivDownload-*.jar
```

This matters for userscript changes. The userscript install card in `pixiv-batch.html` loads its built-in script list from `/api/scripts`. Script assembly now has two sources:

- root-level standalone `*.user.js` files
- the bundled `build/generated-userscripts/Pixiv All-in-One.user.js` generated by `scripts/build-userscript-bundle.ps1`

`pom.xml` copies both sources into `target/classes/static/userscripts` during the `generate-resources` phase. If you edit any root-level userscript or the bundle generator, you must run at least one Maven lifecycle phase again (`generate-resources` at minimum, `package` recommended). Do not rely on IDE-only runs or manual file replacement, or the install page may still serve stale scripts.

Before opening a PR, at least run:

```powershell
# Windows PowerShell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
.\mvnw.cmd test
```

```bash
# macOS / Linux
JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8' ./mvnw test
```

If the change touches `*.user.js`, static resource assembly, or the script install flow, also run `package` once and verify the userscript list and install links on `http://localhost:6999/pixiv-batch.html`.

### 4. Build Windows portable packages / EXE / MSI

Use [`scripts/package-local.ps1`](./scripts/package-local.ps1) for local packaging. The script first generates `Pixiv All-in-One.user.js`, then runs Maven `package`, builds a trimmed runtime with `jlink`, creates an app-image with `jpackage` (including `PixivDownload.exe`), and then produces the online portable package, offline portable package, and MSI variants based on the flags you pass.

```powershell
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'

# Build portable output only (includes PixivDownload.exe), skip MSI
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -SkipMsi

# Build the full Windows artifact set
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local

# Run tests as part of packaging
powershell -ExecutionPolicy Bypass -File .\scripts\package-local.ps1 -Version 0.0.1-local -RunTests
```

Useful options:

- `-SkipOfflinePortable`: skip the offline portable package
- `-SkipMsi`: skip MSI generation and keep only portable outputs
- `-MsiCultures zh-CN,en-US`: choose MSI UI languages
- `-MsiVariants with-ffmpeg,no-ffmpeg`: choose MSI variants
- `-RedownloadFfmpeg`: force a fresh FFmpeg download

Artifacts are written to `build/out/` by default. MSI generation requires a working WiX Toolset installation (`heat.exe`, `candle.exe`, `light.exe`). Offline portable packages and `with-ffmpeg` MSI variants also require the FFmpeg payload, which the script prepares automatically.

### 5. Commit and open a PR

1. Sync with upstream before submission, then rebase or merge as needed.
2. Verify your change locally, including tests, packaging commands, and the relevant pages or flows.
3. Do not commit build output such as `target/` or `build/` unless a maintainer explicitly asks for it.
4. Push your branch to your fork with a clear commit history.
5. Open a PR against the upstream default branch and include the motivation, main changes, and verification steps. If you changed UI or packaging behavior, add screenshots or key command output.

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

## Friend Links

**[PixivBatchDownloader](https://github.com/xuejianxianzun/PixivBatchDownloader)**
If you prefer simplicity and don't want to rely on a backend program, give this script a try.

Features:
- Many filtering options
- Useful auxiliary features like ad removal, quick bookmark, image viewer mode, etc. `(can also serve as a Pixiv helper plugin?)`
- Download doesn't depend on third-party tools `(the biggest difference from this project! Easy installation!)`
- Supports multiple languages

## Development Plan
