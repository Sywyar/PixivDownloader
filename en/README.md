# PixivDownloader documentation

PixivDownloader downloads Pixiv illustrations, manga, animations and novels to your computer or server and manages them in a local gallery. You can use the desktop interface, browser download page or Tampermonkey scripts; downloading restricted content requires your own Pixiv cookie.

## First use

1. [Install and start](/en/installation): choose the Windows installer, a Java distribution or Docker deployment, and extract the complete package.
2. [First-time setup](/en/first-setup): create an administrator account for this app and configure the backend proxy.
3. [Your first download](/en/first-download): save a cookie, download one work, and check the result in the local directory and gallery.

Compose is the default desktop interface, and distributions also include Swing. Simplified Chinese, Traditional Chinese, English, Japanese and Korean are supported. SQLite is the default database; to use PostgreSQL, install its database plugin first, then check and migrate data in settings.

## Find your task

| Task | Guide |
| --- | --- |
| Download your bookmarks, works, new works by followed artists or collections | [Quick Fetch](/en/quick-access) |
| Paste a list of work links or IDs to download | [URL batch download](/en/batch-download) |
| Download works by a specific artist | [Artist batch download](/en/user-download) |
| Search Pixiv by keyword and download | [Search download](/en/search) |
| Save novels, compile EPUBs, read, translate or listen | [Novels and narration](/en/novel) |
| Discover and download new works automatically on a schedule | [Scheduled tasks](/en/scheduled-tasks) |
| Use download helpers directly on Pixiv pages | [Userscripts](/en/userscripts) |
| Register PixivBatchDownloader downloads in the gallery | [Import external downloads](/en/pixiv-batch-downloader-import) |
| Browse, filter, export or delete registered works | [Artwork gallery](/en/gallery) |
| Change configuration, proxy, database or media formats | [Configuration reference](/en/configuration) |
| Back up, restore or relocate work and data directories | [Storage and backup](/en/storage) |
| Look up launch arguments, filename templates, maintenance tools or guest invitations | [CLI and advanced reference](/en/usage-guide) |
| Check external request destinations, sent data and disable controls | [Network access and third-party services](/en/network-access) |
| Diagnose startup, connection, cookie or download problems | [FAQ](/en/faq) |
| Install, update, enable, disable or repair plugins | [Plugin management](/en/plugin-management) |
| Edit page layouts, styles and custom components | [Page editor](/en/page-editor) |
| Configure online or offline CAPTCHA for login and other forms | [Operation CAPTCHA](/en/captcha) |

## Development and contributions

- [Third-party plugin SDK](/en/plugin-development): create a project from a template, use extension contracts, sign packages and submit them.
- [Main repository development](/en/development): prepare the environment, build, test and submit a PR.
- [SDK downloads](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) · [Versioned API reference](https://sywyar.github.io/PixivDownloader-Plugin-SDK/)

## Releases and help

Download distributions from [GitHub Releases](https://github.com/Sywyar/PixivDownloader/releases) and read the [CHANGELOG](https://github.com/Sywyar/PixivDownloader/blob/master/CHANGELOG.md). If troubleshooting does not resolve the problem, report the version, reproduction steps, actual result and sanitized logs in [Issues](https://github.com/Sywyar/PixivDownloader/issues). Do not include cookies, passwords or tokens.

The default Web address is `http://localhost:6999/`. Available pages depend on enabled plugins; if a recovery page appears, check required plugins and the selected database first.

This project is maintained by [Sywyar](https://github.com/Sywyar) under [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) and the accompanying [Plugin Linking Exception](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt). Copyright in downloaded works belongs to their original authors. The project is not affiliated with Pixiv; follow its terms of service and keep request frequency reasonable.
