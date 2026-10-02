package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import java.sql.SQLException;

/** 配置和统计均使用宿主绑定的 owner 路径。 */
@Configuration(proxyBeanMethods = false)
public class PixivBatchDownloaderImportConfiguration {
    @Bean public PixivBatchDownloaderImportStatistics pixivBatchDownloaderImportStatistics(PluginDataSource dataSource) throws SQLException {
        return new PixivBatchDownloaderImportStatistics(dataSource);
    }
    @Bean public PixivBatchDownloaderImportController pixivBatchDownloaderImportController(WorkFileImporter importer,
            NamespaceMessageResolver messages, PixivBatchDownloaderImportDirectory directory, ApplicationModeProvider mode,
            PixivBatchDownloaderImportStatistics statistics) {
        return new PixivBatchDownloaderImportController(importer, messages,
                directory, mode, statistics);
    }

    @Bean public PixivBatchDownloaderImportDirectory pixivBatchDownloaderImportDirectory(
            RuntimePathProvider paths) {
        return new PixivBatchDownloaderImportDirectory(paths.configFile("properties"));
    }
}
