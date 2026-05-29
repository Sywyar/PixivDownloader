# PixivDownloader Wiki

PixivDownloader is a **local batch download tool for Pixiv artwork**, built on Spring Boot 3.5.7 / Java 17, supporting three interaction modes: desktop GUI (Swing +
FlatLaf), web interface, and Tampermonkey userscripts.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)

> [!NOTE]
> Throughout this documentation, "artwork" includes illustrations, manga, animations (Ugoira), and novels.

---

## Core Features

| Feature                               | Description                                                                                          |
|---------------------------------------|------------------------------------------------------------------------------------------------------|
| 🎨 **Batch Import Single Artworks**   | Paste a list of artwork URLs or bare numeric IDs for batch download, with `artwork:` / `novel:` section headers to switch the parsed kind; compatible with OneTab/N-Tab export formats |
| 👤 **User Mode**                      | Download all artwork from a Pixiv user by entering their user ID or pasting an artist profile URL    |
| 🔍 **Search Mode**                    | Built-in search proxy with keyword search and thumbnail preview before adding to queue               |
| 📚 **Series Download**                | Paste series links or links from within a series to batch download entire manga/novel series         |
| ⏰ **Scheduled Tasks**                | Admins can have the backend auto-discover and download new works on a fixed interval or cron schedule (artist/search/series, illustrations & novels) |
| 📖 **Novel Download**                 | Support TXT/HTML/EPUB novel downloads with series compilation (multi-level EPUB TOC)                 |
| 🎬 **Animation Conversion**           | Automatic Ugoira to WebP conversion via ffmpeg                                                      |
| 🖼️ **Artwork Gallery**                | Powerful local gallery with search, filtering, sorting, and collection management                    |
| 🧩 **Userscripts**                    | 6 dedicated scripts + All-in-One bundle for direct operation on Pixiv pages                         |
| 🌐 **Multi-language / Dark Mode**     | Chinese & English, dark mode supported on all web pages and GUI                                      |
| 👥 **Multi-user Mode**                | Multi-user shared server with quota and rate limiting                                                |
| 🔗 **Guest Invite**                   | Share galleries via invite codes with rating/tag/author allowlist control                            |
| 📦 **Windows Installer**              | EXE installer with maintenance mode (repair/modify/uninstall)                                        |
| 🔄 **Online Update**                  | Check and download new versions from within the GUI                                                  |

---

## Quick Start

### Step 1: Download

Download the latest version from [Releases](https://github.com/Sywyar/PixivDownloader/releases):

| Type                                  | Description                                                    |
|---------------------------------------|----------------------------------------------------------------|
| `PixivDownload-vX.X.X.jar`            | Universal JAR, requires **Java 17+**                           |
| `PixivDownload-*-win-x64-setup.exe`   | Windows installer with maintenance mode and optional FFmpeg    |

### Step 2: Launch

```bash
# JAR launch
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar

# Windows EXE launch
PixivDownload.exe

# Optional parameters
--no-gui    # Disable GUI, CLI-only mode (suitable for server/Docker)
--intro     # Open product intro page on startup
--help, -h  # Print command-line help and exit
```

> [!NOTE]
> Starting with v1.10.0, startup arguments are validated strictly: unknown flags are rejected and the help table is printed before the process exits. Arguments shaped as `--key=value`
> are still forwarded to Spring Boot as property overrides.

### Step 3: First-time Setup

The setup entry point depends on how you launch the app:

- **Desktop GUI**: complete admin credentials and run mode directly in the GUI "Home" wizard.
- **Desktop host with `--no-gui`**: the local browser auto-opens `setup.html` (local-only).
- **Headless server / Docker**: run the [CLI command](/en/usage-guide#cli-admin-commands-v1100) `--setup` in the terminal; `--no-gui` start-up
  is blocked until setup is complete.

```bash
# Server / Docker: interactive first-time setup
java -Dfile.encoding=UTF-8 -jar PixivDownload-vX.X.X.jar --setup
```

All three paths then walk you through configuring the HTTP proxy (enable, host, port) after the account and mode — the proxy is used for all of the backend's outbound access (Pixiv, online updates, FFmpeg download,
online TTS).

Either way, fill in admin credentials and choose a usage mode:

| Mode               | Use Case                                                                |
|--------------------|-------------------------------------------------------------------------|
| **Solo Mode**      | Personal use, multi-device state sharing, login required                |
| **Multi Mode**     | Shared server, guests don't need login, quota & rate limit management   |

### Step 4: Start Downloading

Visit `http://localhost:6999/pixiv-batch.html` to begin batch downloading.

---

## Table of Contents

- [Installation](/en/installation) — Detailed installation steps and system requirements
- [Usage Guide](/en/usage-guide) — Complete feature documentation
- [Configuration](/en/configuration) — Full `config.yaml` reference
- [Development Guide](/en/development) — Building, packaging, and contributing
- [FAQ](/en/faq) — Common issues and solutions

---

## Project Information

| Item               | Details                                                                           |
|--------------------|-----------------------------------------------------------------------------------|
| **Author**         | [Sywyar](https://github.com/Sywyar)                                               |
| **License**        | [GNU AGPL v3](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE)      |
| **Language**       | 中文 / English                                                                     |
| **Java Version**   | 17                                                                                |
| **Framework**      | Spring Boot 3.5.7                                                                 |
| **Database**       | SQLite (WAL mode)                                                                 |
| **Default Port**   | 6999                                                                              |
| **Latest Version** | v1.9.0                                                                            |

## Disclaimer

- This project is for personal learning and research only. Do not use for commercial purposes.
- Copyright of downloaded content belongs to the original creators. Please respect their rights.
- This tool accesses Pixiv using cookies provided by the user. Users bear their own account risks.
- This project is not affiliated with Pixiv in any way.
- Please set reasonable download intervals to avoid excessive load on Pixiv servers.
