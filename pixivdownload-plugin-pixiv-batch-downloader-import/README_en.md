# PixivBatchDownloader import support

The official `pixiv-batch-downloader-import` plugin and its companion userscript automatically add new PixivBatchDownloader downloads to the local gallery: illustrations, manga, animations and novels. Configure the download directory once; subsequent downloads need no manual import. Original files are referenced in place and retained when gallery records are deleted.

[Installation, configuration and troubleshooting](https://sywyar.github.io/PixivDownloader/#/en/pixiv-batch-downloader-import).

## Public SDK

`WorkFileImporter.importFiles(WorkFileImportRequest)` validates local files and commits metadata with exact file references. Trusted callers must authorize the source root. Optional work owners publish `WorkFileImportHandler`; unavailable owners fail without registering work. Calls use exact publication proxies with withdrawal and drain.

`WorkAssetService.isReadOnly` identifies external references. `WebRouteContribution.trustedWriteOrigins` declares HTTPS origins for exact ADMIN / LOCAL POST routes without ambient cookies; it does not bypass route authentication. The local collector additionally requires solo mode, loopback trust and a short-lived single-use token.

`UserscriptContribution(id, classpathResource, i18nNamespace)` supports plugin-owned script translations. `DownloadObserver` and `DownloadAdmissionPolicy` provide synchronous best-effort events and fail-closed admission through `DownloadLifecycle`, currently connected to artwork execution and local import. They do not guarantee replay, crash terminal events or global ordering, and do not replace private methods or provide WebView.
