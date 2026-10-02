# PixivBatchDownloader import support

The official `pixiv-batch-downloader-import` plugin and its companion userscript automatically add new PixivBatchDownloader downloads to the local gallery: illustrations, manga, animations and novels. Configure the download directory once; subsequent downloads need no manual import. Original files are referenced in place and retained when gallery records are deleted.

[Installation, configuration and troubleshooting](https://sywyar.github.io/PixivDownloader/#/en/pixiv-batch-downloader-import).

If no directory is configured, the first complete success report containing absolute paths brings the desktop settings and a confirmation dialog into focus. You can choose a different download root. Import starts only after **Confirm and save**, without a restart. Cancel leaves settings unchanged and the same report does not repeatedly open the dialog. You can configure the directory later in settings. Relative paths require manual root selection.

The desktop home overview shows **Imported works**, the cumulative number of unique works successfully imported by this plugin. A multi-page manga counts as one work; an illustration and a novel with the same ID count separately. The total survives restarts and gallery deletion. Existing records, failed imports and skipped downloads do not add to it; past imports are not backfilled.

## Public SDK

`DesktopDirectorySuggestionSource` supplies one `DesktopDirectorySuggestion` for an owner-declared, non-sensitive, unconditional `PATH_DIR` field with `HOT_RELOAD` effect. The host validates paths and saves only after desktop confirmation under the current publication. Withdrawal rejects stale confirmation. The plugin reads saved settings through its owner-bound `RuntimePathProvider`; observations alone never authorize directory access.

`WorkFileImporter.importFiles(WorkFileImportRequest)` validates local files and commits metadata with exact file references. Trusted callers must authorize the source root. Optional work owners publish `WorkFileImportHandler`; unavailable owners fail without registering work. Calls use exact publication proxies with withdrawal and drain.

`WorkAssetService.isReadOnly` identifies external references. `WebRouteContribution.trustedWriteOrigins` declares HTTPS origins for exact ADMIN / LOCAL POST routes without ambient cookies; it does not bypass route authentication. The local collector additionally requires solo mode, loopback trust and a short-lived single-use token.

`UserscriptContribution(id, classpathResource, i18nNamespace)` supports plugin-owned script translations. `DownloadObserver` and `DownloadAdmissionPolicy` provide synchronous best-effort events and fail-closed admission through `DownloadLifecycle`, currently connected to artwork execution and local import. They do not guarantee replay, crash terminal events or global ordering, and do not replace private methods or provide WebView.
