package top.sywyar.pixivdownload.duplicate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.download.ArtworkFileLocator;
import top.sywyar.pixivdownload.i18n.AppMessages;

/**
 * duplicate 插件的 Bean 装配收敛点：业务 Bean（含 {@code @RestController} 与
 * {@code MaintenanceTask}）均经 {@code @PluginManagedBean} 排除出根包扫描，由这里以
 * {@code @Bean} 显式提供。{@link ImageHashMapper} 是 MyBatis {@code @Mapper}，由 MyBatis
 * 自动扫描注册、不在收敛范围内。
 */
@Configuration
public class DuplicatePluginConfiguration {

    @Bean
    public DuplicatePlugin duplicatePlugin() {
        return new DuplicatePlugin();
    }

    @Bean
    public DuplicateService duplicateService(ImageHashMapper imageHashMapper, AppMessages messages) {
        return new DuplicateService(imageHashMapper, messages);
    }

    @Bean
    public ImageHashService imageHashService(ImageHashMapper imageHashMapper,
                                             ArtworkFileLocator artworkFileLocator,
                                             DuplicateService duplicateService,
                                             AppMessages messages,
                                             PlatformTransactionManager transactionManager) {
        return new ImageHashService(
                imageHashMapper, artworkFileLocator, duplicateService, messages, transactionManager);
    }

    @Bean
    public DuplicateScanService duplicateScanService(ImageHashMapper imageHashMapper,
                                                     ImageHashService imageHashService,
                                                     DuplicateService duplicateService,
                                                     PixivDatabase pixivDatabase,
                                                     AppMessages messages,
                                                     @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        return new DuplicateScanService(
                imageHashMapper, imageHashService, duplicateService, pixivDatabase, messages, taskExecutor);
    }

    @Bean
    public DuplicateController duplicateController(DuplicateService duplicateService,
                                                   DuplicateScanService duplicateScanService) {
        return new DuplicateController(duplicateService, duplicateScanService);
    }

    @Bean
    public DuplicateHashBackfillTask duplicateHashBackfillTask(ImageHashMapper imageHashMapper,
                                                               ImageHashService imageHashService,
                                                               DuplicateService duplicateService,
                                                               PixivDatabase pixivDatabase,
                                                               AppMessages messages) {
        return new DuplicateHashBackfillTask(
                imageHashMapper, imageHashService, duplicateService, pixivDatabase, messages);
    }
}
