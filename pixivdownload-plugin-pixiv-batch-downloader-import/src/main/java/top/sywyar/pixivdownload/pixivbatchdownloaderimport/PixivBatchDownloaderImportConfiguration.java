package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.history.HistoryImportService;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.history.BrowserHistoryImport;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;
import java.sql.SQLException;

/** 配置和统计均使用宿主绑定的 owner 路径。 */
@Configuration(proxyBeanMethods = false)
public class PixivBatchDownloaderImportConfiguration {
    @Bean public ImportDiagnostics importDiagnostics(top.sywyar.pixivdownload.i18n.MessageResolver messages,
            top.sywyar.pixivdownload.i18n.LocaleBundlePolicy policy) {
        return new ImportDiagnostics(top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver.of(
                messages, PixivBatchDownloaderImportPlugin.class.getClassLoader(), policy,
                "i18n.web.pixiv-batch-downloader-import"));
    }

    @Bean(destroyMethod = "close")
    public BrowserHistoryImport browserHistoryImport(WorkFileImporter importer, WorkQueryService works,
            PixivBatchDownloaderImportStatistics statistics, PluginRuntimeTaskRegistrar tasks, RuntimePathProvider paths,
            ImportDiagnostics diagnostics) {
        return new BrowserHistoryImport(importer, works, statistics, tasks, paths.dataDirectory().resolve("browser-history"), diagnostics);
    }
    @Bean(destroyMethod = "close")
    public HistoryImportService historyImportService(
            WorkFileImporter importer,
            WorkQueryService works,
            PixivAjaxClient pixiv,
            PluginRuntimeTaskRegistrar tasks,
            PixivBatchDownloaderImportStatistics statistics, RuntimePathProvider paths, ImportDiagnostics diagnostics) {
        return new HistoryImportService(
                importer, works, pixiv, tasks, statistics, paths.dataDirectory().resolve("history-import"), diagnostics);
    }

    @Bean public PixivBatchDownloaderImportStatistics pixivBatchDownloaderImportStatistics(PluginDatabase dataSource) throws SQLException {
        return new PixivBatchDownloaderImportStatistics(dataSource);
    }
    @Bean public PixivBatchDownloaderImportController pixivBatchDownloaderImportController(WorkFileImporter importer,
            NamespaceMessageResolver messages, PixivBatchDownloaderImportDirectory directory, ApplicationModeProvider mode,
            PixivBatchDownloaderImportStatistics statistics, BrowserHistoryImport history, ImportDiagnostics diagnostics) {
        return new PixivBatchDownloaderImportController(importer, messages,
                directory, mode, statistics, history, diagnostics);
    }

    @Bean public PixivBatchDownloaderImportDirectory pixivBatchDownloaderImportDirectory(
            RuntimePathProvider paths) {
        return new PixivBatchDownloaderImportDirectory(paths.configFile("properties"));
    }
}
