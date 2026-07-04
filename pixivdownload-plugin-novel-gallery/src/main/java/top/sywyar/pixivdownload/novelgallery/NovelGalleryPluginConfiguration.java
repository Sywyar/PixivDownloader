package top.sywyar.pixivdownload.novelgallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository;
import top.sywyar.pixivdownload.novel.NovelSeriesService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novelgallery.controller.NovelGalleryController;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

/**
 * novel-gallery 插件的 Bean 装配收敛点。业务 Bean 经 {@link ConditionalOnPluginEnabled}
 * 随 {@code plugins.novel-gallery.enabled} 装配 / 缺席；核心小说数据、下载、正文、翻译与合订服务
 * 仍由宿主小说核心提供。
 */
@Configuration
public class NovelGalleryPluginConfiguration {

    @Bean
    public NovelGalleryPlugin novelGalleryPlugin() {
        return new NovelGalleryPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled("novel-gallery")
    public NovelGalleryService novelGalleryService(WorkQueryService workQueryService,
                                                   WorkMetadataRepository workMetadataRepository,
                                                   WorkDeletionService workDeletionService) {
        return new NovelGalleryService(workQueryService, workMetadataRepository, workDeletionService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel-gallery")
    public NovelBatchService novelBatchService(NovelGalleryService novelGalleryService,
                                               WorkMetadataRepository workMetadataRepository,
                                               WorkAssetService workAssetService,
                                               CollectionService collectionService,
                                               UserQuotaService userQuotaService,
                                               MultiModeConfig multiModeConfig,
                                               ObjectMapper objectMapper) {
        return new NovelBatchService(novelGalleryService, workMetadataRepository, workAssetService,
                collectionService, userQuotaService, multiModeConfig, objectMapper);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel-gallery")
    public NovelGalleryController novelGalleryController(NovelGalleryService novelGalleryService,
                                                         NovelBatchService novelBatchService,
                                                         NovelSeriesService novelSeriesService,
                                                         NovelDatabase novelDatabase,
                                                         NovelGalleryRepository novelGalleryRepository,
                                                         WorkAssetService workAssetService,
                                                         GuestAccessGuard guestAccessGuard) {
        return new NovelGalleryController(novelGalleryService, novelBatchService, novelSeriesService,
                novelDatabase, novelGalleryRepository, workAssetService, guestAccessGuard);
    }
}
