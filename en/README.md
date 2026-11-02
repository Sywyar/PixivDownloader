# PixivDownloader documentation

PixivDownloader saves Pixiv illustrations, manga, animations and novels to your computer or server and organizes them in a local gallery. Use the desktop interface, browser download page or Tampermonkey scripts. For restricted works, supply your own Pixiv cookie.

## First use

1. [Install and start](/en/installation): choose the Windows installer, a Java distribution or Docker deployment.
2. [Complete setup](/en/first-setup): create this app's administrator account and configure the backend proxy.
3. [Download your first work](/en/first-download): save a cookie, add a work, and check its files and gallery record.

Compose is the default desktop interface; distributions also include Swing. Supported interface languages are Simplified Chinese, Traditional Chinese, English, Japanese and Korean. SQLite is the default database. To use PostgreSQL, install its database plugin, then check and migrate data through settings.

## Find your task

| Task | Guide |
| --- | --- |
| Download your bookmarks, followed users' new works or collections | [Quick Fetch](/en/quick-access) |
| Download a list of work links or IDs | [URL batch download](/en/batch-download) |
| Get works by an artist | [Artist batch download](/en/user-download) |
| Search Pixiv and select works to download | [Search](/en/search) |
| Save novels, compile EPUBs, read, translate or listen | [Novels](/en/novel) |
| Browse, filter, export or delete local works | [Gallery](/en/gallery) |
| Follow artists, search results or series on a schedule | [Scheduled tasks](/en/scheduled-tasks) |
| Use download helpers on Pixiv pages | [Userscripts](/en/userscripts) |
| Add new PixivBatchDownloader downloads to the gallery | [Import support](/en/pixiv-batch-downloader-import) |
| Change configuration, proxy, database or media formats | [Configuration reference](/en/configuration) |
| Install, enable, disable or repair plugins | [Plugin management](/en/plugin-management) |
| Understand network requests, sent data and disable controls | [Network access and third-party services](/en/network-access) |
| Back up, restore or move work directories | [Storage](/en/storage) |
| Use launch arguments, filename templates, maintenance tools or guest invitations | [Advanced usage](/en/usage-guide) |
| Diagnose startup, connection or download failures | [FAQ](/en/faq) |

## Develop a plugin or contribute code

- [Third-party plugin SDK](/en/plugin-development): choose an SDK, create a project, use extension contracts, sign packages and submit them.
- [Main repository development](/en/development): prepare the environment, build, test and submit a pull request.
- [SDK downloads](https://github.com/Sywyar/PixivDownloader-Plugin-SDK/releases) and [versioned API reference](https://sywyar.github.io/PixivDownloader-Plugin-SDK/).

## Releases and help

Get the app from [GitHub Releases](https://github.com/Sywyar/PixivDownloader/releases) and read release changes in the [CHANGELOG](https://github.com/Sywyar/PixivDownloader/blob/master/CHANGELOG.md). If troubleshooting does not resolve an issue, report it through [Issues](https://github.com/Sywyar/PixivDownloader/issues), including the version, steps, actual result and logs with credentials removed.

The default Web address is `http://localhost:6999/`. Available pages depend on enabled plugins. If a recovery page appears, check required plugins and database availability first.

The project is maintained by [Sywyar](https://github.com/Sywyar). Software licensing is described in [AGPL-3.0](https://github.com/Sywyar/PixivDownloader/blob/master/LICENSE) and the accompanying [Plugin Linking Exception](https://github.com/Sywyar/PixivDownloader/blob/master/PLUGIN-LINKING-EXCEPTION.txt). Downloaded content remains subject to its rights holders' permissions. The project is not affiliated with Pixiv. Follow content licenses and service rules, limit request frequency, and protect your cookies.
