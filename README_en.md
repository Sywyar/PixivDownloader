# PixivDownloader

[中文](./README.md) | English

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
[![GitHub commit activity](https://img.shields.io/github/commit-activity/m/Sywyar/PixivDownloader)](https://github.com/Sywyar/PixivDownloader/commits)

## Features

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
- Scheduled tasks: automatically discover and download new artworks in the background on a fixed interval or
  cron schedule, with three source types (artist new works / saved search / series — both illustrations and novels)
- Email notifications and SMTP config: used to receive scheduled-task overuse / auth-expired alerts that need manual
  attention
- Novel download and series compilation (TXT/HTML/EPUB with multi-level TOC and embedded images)
- Animated image (Ugoira) auto-conversion to WebP
- Custom file naming templates (11 variables)
- Downloaded-state verification: stale DB records (file deleted on disk) are auto-pruned; if files for an artwork
  already exist on disk under the default filename template but the DB record is missing, the record is reconstructed
  from disk and the redownload is skipped
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

| Type                                | Description                                                         |
|-------------------------------------|---------------------------------------------------------------------|
| `PixivDownload-vX.X.X.jar`          | Universal JAR, requires Java 17+                                    |
| `PixivDownload-*-win-x64-setup.exe` | Windows installer; repair/change/uninstall, optional FFmpeg install |

### Run

```bash
# Start from JAR
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar

# Start from Windows EXE
PixivDownload.exe

# Optional arguments
--no-gui    # Disable the GUI and run in CLI-only mode (server/Docker)
--intro     # Open the product introduction page on startup
```

After first startup, follow the wizard to complete setup, then visit `http://localhost:6999/pixiv-batch.html` to start
downloading.

> [!TIP]
> **See the [Wiki](https://github.com/Sywyar/PixivDownloader/wiki/en-Home) for detailed installation, usage,
configuration, and development guides.**

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
