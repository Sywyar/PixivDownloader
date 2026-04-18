# PixivDownload

[中文](./README.md) | English

Local Pixiv batch image download tool, consisting of a **Spring Boot backend** + **Tampermonkey userscript**.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

## Table of Contents

- [Overview](#overview)
- [Installation](#installation)
- [Usage](#usage)
- [Development Guide](#development-guide)
- [Disclaimer](#disclaimer)
- [Additional Notes](#additional-notes)
- [Friends Links](#friends-links)
- [开发计划](#开发计划)

## Overview

PixivDownload is a local Pixiv batch image download tool with multiple download methods and convenient management features.

### Features

- Pixiv-batch.html [offers one-stop downloading](#Web-Batch-Download), including bulk artwork import, batch downloading of user-submitted works, and batch downloading of searched works. It works in conjunction with a page-based batch download script, requiring no other scripts.
- Batch page download — scrape all works from the entire Pixiv site, including search pages, followed feeds, leaderboards, etc.
- monitor.html is a [one-stop management page](#Download-Monitor) that allows for multi-dimensional filtering and sorting of works, including author-based search/filter/sort in download history.
- Single artwork download — one-click download on artwork pages
- User homepage batch download — batch download all works from a user
- Bulk artwork import — paste artwork link lists in `url | title` format for batch downloading, compatible with export formats from tab manager extensions such as OneTab and N-Tab
- Keyword search download — search and download Pixiv artworks via web interface
- Animated image auto-conversion to WebP — automatically convert Ugoira to WebP with delays
- Download history management — record downloaded artworks and author metadata, support resumable downloads, and detect author renames automatically
- Image classification tool — standalone desktop tool for organizing downloaded images
- Multi-mode rate limiting — quota and rate limits for multi-user scenarios

### Screenshots

<details>
<summary><strong>Expand to view screenshots</strong></summary>

#### Screenshot of monitor.html page
![](./image/1.png)

#### Screenshot of the pixiv-batch.html plugin installation page
![](./image/2.png)

#### Screenshot of the pixiv-batch.html bulk artwork import page (The script achieves the same effect, but the web version is recommended for its convenience.)
![](./image/3.png)

#### Screenshot of pixiv-batch.html User parsing and queueing page (The script achieves the same effect, but the web version is recommended for its convenience.)
![](./image/4.png)

#### Screenshot of pixiv-batch.html Search parsing and queueing page
![](./image/5.png)

#### This is a screenshot of a Pixiv page batch downloader (user.js), supporting full site scraping of Pixiv.
![](./image/6.png)

#### Screenshot of single-artwork script download (same effect for Java backend and Local download)
![](./image/7.png)

</details>

## Installation

### 1. Download and Run

Download the latest version from [Releases](../../releases):

| Type                                                | Description                                                                                                                                                                         |
|-----------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PixivDownload-vX.X.X.jar`                          | Universal JAR, requires Java 17+                                                                                                                                                    |
| `PixivDownload-*-win-x64-online-portable.zip`       | Windows online portable build with the smallest download size; if FFmpeg is already installed, this is the only package you need                                                    |
| `PixivDownload-*-win-x64-portable.zip`              | Windows offline portable build with bundled JRE and FFmpeg, suitable when FFmpeg is not installed or when you need an offline setup                                                 |
| `PixivDownload-*-win-x64-<culture>-with-ffmpeg.msi` | Windows installer, currently shipped in `zh-CN` and `en-US`; FFmpeg is bundled and installed by default, so it works out of the box at a larger download size                       |
| `PixivDownload-*-win-x64-<culture>-no-ffmpeg.msi`   | Windows installer, currently shipped in `zh-CN` and `en-US`; FFmpeg is not bundled, so the installer is smaller and best when FFmpeg already exists or Ugoira-to-WebP is not needed |

> Windows MSI packages are now split into fixed variants, so choose the package before downloading:
> - `zh-CN` / `en-US`: installer UI language
> - `with-ffmpeg`: FFmpeg is bundled for an out-of-box setup
> - `no-ffmpeg`: FFmpeg is not bundled; best when FFmpeg already exists or when you only need regular image downloads
> - The MSI wizard does not offer runtime language switching and no longer exposes an FFmpeg feature-selection page

```bash
# JAR startup
java -jar PixivDownload-vX.X.X.jar

# Windows exe startup
PixivDownload.exe

# Optional arguments
--no-gui    # Disable GUI, headless mode (for server/Docker)
--intro     # Open product intro page on startup
```

> On first startup, the browser will automatically open the setup wizard. Complete the setup before using other features.

> The online portable build does not bundle FFmpeg by default, but it will reuse FFmpeg from the system PATH, the app directory, or the managed user directory. If FFmpeg is already installed, the online portable build is enough. Only use `Download FFmpeg` on the `Status` tab when you need Ugoira-to-WebP conversion and do not already have FFmpeg available. Regular image downloads work without it.

### 2. Initial Setup

After first startup, the browser will automatically open `http://localhost:6999/setup.html`. Enter username and password (minimum 6 characters) and choose usage mode:

| Mode       | Use Case                                                                                |
|------------|-----------------------------------------------------------------------------------------|
| Solo Mode  | Personal use, shared state across devices, requires login                               |
| Multi Mode | Shared server, each visitor's config stored independently in browser, no login required |

Configuration is written to `state/setup_config.json` and the setup page will not appear again. Delete this file and restart to reinitialize.

### 3. Configure Proxy

The backend accesses Pixiv CDN through an HTTP proxy. On startup, the config file is automatically generated at `config/config.yaml`. Edit the proxy configuration and **restart the service** to apply:

```yaml
proxy.enabled: true
proxy.host: 127.0.0.1
proxy.port: 7890   # Change to your proxy's actual port
```

> The GUI FFmpeg download button reuses this proxy configuration first, which makes the online portable build easier to use behind a proxy when fetching FFmpeg from GitHub.

### 4. Install Userscripts (Optional)

You can perform all operations via the web interface without userscripts. If you need to install them:

<details>
<summary><strong>Step required when installing userscripts from release files (expand)</strong></summary>

Tampermonkey's `GM_xmlhttpRequest` is restricted by the `@connect` whitelist. Scripts only allow connections to `localhost` by default. If the backend is deployed on another machine, you must manually update the `@connect` declaration in each script:

1. Open the Tampermonkey dashboard → Find the script → Click Edit
2. Replace `// @connect      YOUR_SERVER_HOST` in the script header with the actual address
3. Save the script (Ctrl+S)

</details>

**Method 1: One-click installation from web management page (recommended)**

After logging in, open `http://localhost:6999/pixiv-batch.html`, click the「🧩 油猴脚本」card at the top, then click「⬇ 安装」next to the desired script. When deployed on a non-localhost server, `@connect` is automatically replaced with the actual address.

**Method 2: Manual download from Releases**

Download scripts from [Releases](../../releases) and drag them into the Tampermonkey management panel:

| Script File                            | Use Case                                                                                                  |
|----------------------------------------|-----------------------------------------------------------------------------------------------------------|
| `Pixiv 单作品图片下载器(Java后端版).user.js`        | Single artwork page download                                                                              |
| `Pixiv 单作品图片下载器(Local download).user.js` | Single artwork page download (browser local download, no Java backend required)                           |
| `Pixiv User 批量下载器.user.js`             | User homepage batch download                                                                              |
| `Pixiv 批量导入作品下载器.user.js`              | Bulk artwork import (compatible with export formats from tab manager extensions such as OneTab and N-Tab) |
| `Pixiv 页面批量下载器.user.js`                | Page DOM crawling (supports full Pixiv site crawling)                                                     |

> **Recommended: Use the web interface first.** `http://localhost:6999/pixiv-batch.html` supports Bulk Artwork Import, User mode, and Search mode. No userscript installation needed for batch downloading.

## Usage

### Getting Cookie (required for Search mode,restricted works,auto bookmark)

1. Install the [Cookie-Editor](https://chromewebstore.google.com/detail/cookie-editor/hlkenndednhfkekhgcdicdfddnkalmdm) extension
2. After logging into Pixiv, click the extension icon → **Export** in the bottom right corner → Select **Netscape** format and copy
3. Switch the format to **Netscape** above the cookie input on the page, paste and save

### Web Batch Download

Visit `http://localhost:6999/pixiv-batch.html` (solo mode requires login first):

| Mode           | Description                                                                       |
|----------------|-----------------------------------------------------------------------------------|
| 🎨 Bulk Artwork Import | Paste artwork link lists for batch downloading, compatible with export formats from tab manager extensions such as OneTab and N-Tab |
| 👤 User Mode   | Enter user ID to batch download all works from that user                          |
| 🔍 Search Mode | Search by keyword and preview thumbnails before adding to queue (requires Cookie) |

Bulk artwork import format:

- One artwork per line, in `url | title` format
- Example: `https://www.pixiv.net/artworks/12345678 | Sample Title`
- `title` can be left empty; the real title is fetched automatically before download
- Compatible with export formats from tab manager extensions such as OneTab and N-Tab; exported lists can be imported again directly

### Download Monitor

Visit `http://localhost:6999/monitor.html` to view real-time download progress and history.

The history table now supports:

- fuzzy search by author name or author ID
- checkbox-based author filtering
- click-to-toggle filtering from the author cell
- sorting by author ID

### Author Backfill Tool

If you already have an older database with many history rows where `author_id` is still empty, use `AuthorBackfill` to populate them.

Stop the backend service before running it, to avoid concurrent SQLite writes.

```powershell
# Windows
.\mvnw.cmd -q -DskipTests compile dependency:copy-dependencies "-DincludeScope=runtime"
java -cp "target/classes;target/dependency/*" top.sywyar.pixivdownload.tools.AuthorBackfill --dry-run
java -cp "target/classes;target/dependency/*" top.sywyar.pixivdownload.tools.AuthorBackfill --limit 200 --delay 1200
```

```bash
# macOS / Linux
./mvnw -q -DskipTests compile dependency:copy-dependencies -DincludeScope=runtime
java -cp "target/classes:target/dependency/*" top.sywyar.pixivdownload.tools.AuthorBackfill --dry-run
java -cp "target/classes:target/dependency/*" top.sywyar.pixivdownload.tools.AuthorBackfill --limit 200 --delay 1200
```

Common options:

- `--db <path>`: custom database path
- `--proxy <host:port>` / `--no-proxy`: configure or disable the proxy
- `--delay <ms>`: request spacing between Pixiv AJAX calls
- `--limit <n>`: process only the first `n` missing rows
- `--dry-run`: print what would be updated without writing to SQLite

### Product Intro Page

Visit `http://localhost:6999/intro.html` (no login required, publicly accessible) to view the project introduction.

## Development Guide

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

```bash
# Windows
.\mvnw.cmd package -DskipTests

# macOS / Linux
./mvnw package -DskipTests

# Run
java -jar target/PixivDownload-*.jar
```

This matters for userscript changes. The userscript install card in `pixiv-batch.html` loads its built-in script list from `/api/scripts`, and `pom.xml` copies repository-root `*.user.js` files into `target/classes/static/userscripts` during the `generate-resources` phase. If you edit any root-level userscript, you must run at least one Maven lifecycle phase again (`generate-resources` at minimum, `package` recommended). Do not rely on IDE-only runs or manual file replacement, or the install page may still serve stale scripts.

Before opening a PR, at least run:

```bash
.\mvnw.cmd test
```

If the change touches `*.user.js`, static resource assembly, or the script install flow, also run `package` once and verify the userscript list and install links on `http://localhost:6999/pixiv-batch.html`.

### 4. Build Windows portable packages / EXE / MSI

Use [`scripts/package-local.ps1`](./scripts/package-local.ps1) for local packaging. The script runs Maven `package`, builds a trimmed runtime with `jlink`, creates an app-image with `jpackage` (including `PixivDownload.exe`), and then produces the online portable package, offline portable package, and MSI variants based on the flags you pass.

```powershell
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

## Friends Links

**[![](https://raw.githubusercontent.com/xuejianxianzun/PixivBatchDownloader/master/static/icon/logo48.png)PixivBatchDownloader](//github.com/xuejianxianzun/PixivBatchDownloader)**  
If you prefer simplicity and don't want to rely on a backend program, give this script a try.

Features:
- Many filtering options
- Useful auxiliary features like ad removal, quick bookmark, image viewer mode, etc. `(can also serve as a Pixiv helper plugin?)`
- Download doesn't depend on third-party tools `(the biggest difference from this project! Easy installation!)`
- Supports multiple languages

## 开发计划

#### View image page (conceptual diagram)
![](./image/8.png)
