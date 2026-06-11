# PixivDownloader Wiki

PixivDownloader is a **local batch download tool for Pixiv artwork**, built on Spring Boot 3.5.7 / Java 17, supporting three interaction modes: desktop GUI (Swing + FlatLaf), web interface, and Tampermonkey userscripts.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/Sywyar/PixivDownloader)](https://github.com/Sywyar/PixivDownloader/releases)

?> Throughout this documentation, "artwork" includes illustrations, manga, animations (Ugoira), and novels.

---

## Core Features

| Feature | Description |
|----|------|
| ⚡ **Quick Fetch** | One-click pull your Pixiv bookmarks, follows, and collections |
| 🎨 **Batch Import** | Paste artwork URLs / ID lists, supports mixing illustrations and novels |
| 👤 **User Download** | Enter an artist ID or link to download all their works |
| 🔍 **Search Download** | Built-in search proxy — keyword search, preview, then batch download |
| 📚 **Series Download** | One-click download an entire manga / novel series, auto-follow updates |
| ⏰ **Scheduled Tasks** | Background auto-discovery and download of new works on a schedule, fully hands-off |
| 📖 **Novel Download** | TXT / HTML / EPUB formats, series compilation support |
| 🎬 **Animation Conversion** | Ugoira auto-converted to WebP via ffmpeg |
| 🖼️ **Artwork Gallery** | Local gallery with search, filter, sorting, and collection management |
| 🧩 **Userscripts** | Operate directly on Pixiv pages — 6 dedicated scripts + All-in-One bundle |
| 🌐 **Multi-language / Dark Mode** | Chinese & English, dark mode on all pages |
| 👥 **Multi Mode** | Multi-user shared server with quota and rate limiting |
| 🔗 **Guest Invite** | Share galleries via invite codes with content rating / tag / author allowlist control |

---

## New to PixivDownloader?

First time? Read in this order:

1. **[📥 Installation](/en/installation)** — Download, install, system requirements
2. **[⚙️ First-Time Setup](/en/first-setup)** — Set admin account, run mode, proxy
3. **[⬇️ First Download](/en/first-download)** — Complete beginner download tutorial

---

## Find by Need

| I want to… | See |
|------------|-----|
| Download my Pixiv bookmarks | [Quick Fetch](/en/quick-access) |
| Paste links for batch download | [URL Batch Download](/en/batch-download) |
| Download all works by an artist | [Artist Batch Download](/en/user-download) |
| Search by keyword and download | [Search Download](/en/search) |
| Download novels / compile EPUB | [Novel Download](/en/novel) |
| Organize and browse downloaded works | [Artwork Gallery](/en/gallery) |
| Auto-download new works on a schedule | [Scheduled Tasks](/en/scheduled-tasks) |
| Download directly on Pixiv pages | [Userscripts](/en/userscripts) |
| See all configuration options | [Configuration Reference](/en/configuration) |
| Understand where files go / backup & relocation | [Storage Principles](/en/storage) |
| Solve common issues | [FAQ](/en/faq) |

---

## Project Information

| Item | Details |
|------|------|
| **Author** | [Sywyar](https://github.com/Sywyar) |
| **License** | [GNU AGPL v3](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) |
| **Language** | 中文 / English |
| **Java Version** | 17 |
| **Framework** | Spring Boot 3.5.7 |
| **Database** | SQLite (WAL mode) |
| **Default Port** | 6999 |

## Disclaimer

This project is for personal learning and research only. Do not use for commercial purposes. Copyright of downloaded content belongs to the original creators — please respect their rights. This tool accesses Pixiv using cookies provided by the user; users bear their own account risks. This project is not affiliated with Pixiv in any way. Please set reasonable download intervals to avoid excessive load on Pixiv's servers.
