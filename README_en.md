# PixivDownloader

[中文](./README.md) | [繁體中文](./README_zh-Hant.md) | [日本語](./README_ja.md) | [한국어](./README_ko.md) | English

> [!NOTE]
> In this document, "works" includes illustrations, manga, ugoira, and novels.

### Local batch downloader for Pixiv works, supporting novels, manga, and other work types

- Batch download works from work links
- Batch download works by user ID
- Batch download works through the built-in search proxy
- Batch download an entire series by entering a series link or a work link from that series
- Use Tampermonkey userscripts to scrape illustrations, manga, ugoira, and novels from Pixiv pages, or download directly
  from single-work pages
- Powerful artwork and novel galleries

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![GitHub Repo stars](https://img.shields.io/github/stars/Sywyar/PixivDownloader)](https://github.com/Sywyar/PixivDownloader/stargazers)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/Sywyar/PixivDownloader)](../../releases)

## Features

> [!WARNING]
> **Items marked with `*` are not yet available in the stable release (nightly build only)**

- One-stop download web page: Quick Fetch, Bulk Import Single Works, User Mode, Search Mode, Series Mode
- Quick Fetch: with the saved Cookie, one-click load your own bookmarks (illust/novel, incl. private), your own works (incl. private), following list, and collections; drill in and add to the download queue
- Page batch download userscript — scrape works from search results, following feed, rankings, and more
- Experience-enhancement toolbox (downloaded-work marking, one-click Cookie import)
- Powerful artwork and novel galleries with search-scope selection, filtering, sorting, and collections
- Full-text "body" search in the novel gallery (backed by a local full-text index; combinable with age-rating / tag /
  author filters)
- Statistics dashboard: overview cards, downloads-by-month line chart, top authors by downloads, popular-tag
  cloud; authors/tags are clickable and jump to a filtered gallery view
- Suspected-duplicate detection: identifies substantially duplicate downloaded images via perceptual hashing (
  dHash), with adjustable threshold, cross-artwork/all scope switching, and manual scan backfill
- `*` Plugin management page: a card list showing every plugin with status, source, version, and dependencies; load, start, quiet, stop, unload, remove, restart, and reload actions for external plugins (Not yet launched)
- `*` Plugin marketplace page: browse, search, page through, and install plugins from trusted repositories; enter a public HTTPS `repository.json`, review the publisher, network hosts, and complete public-key fingerprints, then save a third-party repository. Before installation, the host resolves the version again and verifies size, SHA-256, signature, and the package descriptor
- Scheduled tasks: automatically discover and download new works in the background on a fixed interval or cron schedule, supporting three source types
- Email / push notifications: events needing manual attention are delivered via email and push channels; each notification type individually toggleable
- Novel download and series compilation (TXT/HTML/EPUB with multi-level TOC and embedded images)
- Novel AI translation (requires an LLM configured): translate a novel or a whole series into a chosen language and store it locally, with a content-language switch between the original and translations
- Novel AI multi-voice narration (beta): an LLM attributes sentences to speakers, each character synthesized with a fixed voice and played back with follow-along highlighting; analysis is cached for replay

- Animated image (Ugoira) auto-conversion to WebP
- Custom file naming templates (11 variables)
- Downloaded-state verification: stale DB records auto-pruned; missing records reconstructed from disk to skip re-download
- Quota and rate limiting for multi-user scenarios
- Guest invite system (age-rating / tag / author whitelist)
- Multiple languages / dark mode
- Desktop GUI (Swing + FlatLaf) with online update

## Screenshots

> [!NOTE]
> Some screenshot devices have HDR enabled, so the color effect may differ.

### [Light Mode Screenshots](./en-US/md/light-screenshot.md)

### [Dark Mode Screenshots](./en-US/md/dark-screenshot.md)

## Quick Start

### Download

Download the latest version from [Releases](../../releases):

| Type                                | Description                                                                                          |
|-------------------------------------|------------------------------------------------------------------------------------------------------|
| `PixivDownload-*-win-x64-setup.exe` | Windows installer with bundled JRE; repair/change/uninstall and optional FFmpeg installation; includes the official plugin distribution set, without Douyin |
| `PixivDownload-*-java.zip`          | Java standard package (cross-platform), requires Java 17; same plugin set as the Windows installer, without Douyin |
| `PixivDownload-*-full-offline.zip`  | Full-offline package (cross-platform), requires Java 17; same plugin set as the Java standard package, without Douyin |

The Java standard package and the full-offline package must be **fully extracted** before use — do not take out only
the JAR: the launcher scripts and the `plugins/` directory are both required, because external official plugins are
loaded from the working directory's `plugins/` folder at startup.

### Run

```bash
# Windows installer
PixivDownload.exe

# Java standard / full-offline package (Windows)
run.bat

# Java standard / full-offline package (Linux/macOS, requires Java 17)
sh run.sh

# Optional arguments
--no-gui    # Disable the GUI and run in CLI-only mode (server/Docker)
--intro     # Open the product introduction page on startup
```

After first startup, follow the wizard to complete setup, then visit `http://localhost:6999/pixiv-batch.html` to start
downloading.

### Route web Pixiv through the backend-configured proxy (no system proxy needed)

The backend reaches Pixiv through the proxy in your config (default `127.0.0.1:7890`) and does not rely on a system
proxy. If you also want to open `pixiv.net` directly in the browser (e.g. with the userscripts) without turning on
Clash's system proxy, use the built-in proxy auto-config (PAC):

Set your OS/browser "Automatic proxy configuration script (PAC) URL" to `http://localhost:6999/proxy.pac` (match your
configured port; with HTTPS enabled it becomes `https://<domain>:<port>/proxy.pac`). Only Pixiv-related domains then go
through the same backend-configured proxy while everything else stays direct. The endpoint is local-only, and proxy
changes (including hot reload) are reflected automatically — no more toggling the system proxy back and forth.

For the exact settings entry points per browser/OS (Firefox `about:preferences#general`, Windows
`ms-settings:network-proxy`, etc.), see [Configuration · Route web Pixiv through the same
proxy](https://sywyar.github.io/PixivDownloader/#/en/configuration).

---

## Online Documentation

For detailed installation steps, usage guides, configuration reference, and development guides, see the
[online documentation](https://sywyar.github.io/PixivDownloader/#/en/). Traditional Chinese readers can switch to the
[繁體中文 documentation](https://sywyar.github.io/PixivDownloader/#/zh-hant/). Quick jump to each section:

**Quick Start**

- [📥 Installation & Startup](https://sywyar.github.io/PixivDownloader/#/en/installation)
- [⚙️ First-Time Setup](https://sywyar.github.io/PixivDownloader/#/en/first-setup)
- [⬇️ First Download](https://sywyar.github.io/PixivDownloader/#/en/first-download)

**Feature Guide**

- [⚡ Quick Fetch](https://sywyar.github.io/PixivDownloader/#/en/quick-access)
- [📋 URL Batch Download](https://sywyar.github.io/PixivDownloader/#/en/batch-download)
- [👤 Artist Batch Download](https://sywyar.github.io/PixivDownloader/#/en/user-download)
- [🔍 Search Download](https://sywyar.github.io/PixivDownloader/#/en/search)
- [📖 Novel Download](https://sywyar.github.io/PixivDownloader/#/en/novel)
- [🖼️ Artwork Gallery](https://sywyar.github.io/PixivDownloader/#/en/gallery)
- [⏰ Scheduled Tasks](https://sywyar.github.io/PixivDownloader/#/en/scheduled-tasks)
- [🧩 Userscripts](https://sywyar.github.io/PixivDownloader/#/en/userscripts)

**Reference**

- [⚙️ Configuration](https://sywyar.github.io/PixivDownloader/#/en/configuration)
- [🔌 Plugin Management](https://sywyar.github.io/PixivDownloader/#/en/plugin-management)
- [🧩 Third-party Plugin SDK](https://sywyar.github.io/PixivDownloader/#/en/plugin-development)
- [📦 Plugin SDK downloads and release history](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) (an empty list means no SDK has been published)
- [💾 Storage Principles](https://sywyar.github.io/PixivDownloader/#/en/storage)
- [❓ FAQ](https://sywyar.github.io/PixivDownloader/#/en/faq)
- [🛠️ Development](https://sywyar.github.io/PixivDownloader/#/en/development)

---

## Disclaimer

- This project is for personal learning and research only; do not use it for any commercial purposes.
- Content downloaded using this tool is copyrighted by the original creators; please respect creators' rights and do not
  redistribute or use commercially.
- This tool accesses Pixiv through user-provided cookies or, with user permission, by extracting cookies via
  Tampermonkey userscripts; users bear their own account risks.
- This project has no affiliation with Pixiv; all consequences of using this tool are the user's responsibility.
- Please set a reasonable download interval to avoid excessive load on Pixiv servers.

---

## Additional Notes

Honestly, I don't really recommend the multi mode of this tool, because all requests go through the server's network IP.
Even with different cookies, a large number of requests could lead to IP bans. I'm considering adding a login mechanism
to multi mode, but that goes against the project's original intention of simplicity. For now, I'll just continue
refining this project.

## Friend Links

**[PixivBatchDownloader](https://github.com/xuejianxianzun/PixivBatchDownloader)**
If you prefer simplicity and don't want to rely on a backend program, give this script a try.

Features:

- Many filtering options
- Useful auxiliary features like ad removal, quick bookmark, image viewer mode, etc.
  `(can also serve as a Pixiv helper plugin?)`
- Download doesn't depend on third-party tools `(the biggest difference from this project! Easy installation!)`
- Supports multiple languages

## Development Plan
