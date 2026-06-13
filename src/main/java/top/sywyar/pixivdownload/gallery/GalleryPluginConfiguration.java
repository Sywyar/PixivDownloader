package top.sywyar.pixivdownload.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

/**
 * gallery 插件的 Bean 装配收敛点：业务 Bean（含 {@code @RestController}）均经
 * {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean} 显式提供。
 * 画廊的 SQL 仓库已收编进核心数据层（{@code core.metadata.GalleryRepository}，根包扫描的
 * 核心 Bean），不再由本配置提供。
 */
@Configuration
public class GalleryPluginConfiguration {

    @Bean
    public GalleryPlugin galleryPlugin() {
        return new GalleryPlugin();
    }

    @Bean
    public GalleryService galleryService(WorkQueryService workQueryService,
                                         WorkMetadataRepository workMetadataRepository,
                                         WorkAssetService workAssetService,
                                         WorkDeletionService workDeletionService,
                                         AppMessages messages) {
        return new GalleryService(
                workQueryService, workMetadataRepository, workAssetService, workDeletionService, messages);
    }

    @Bean
    public GalleryBatchService galleryBatchService(GalleryService galleryService,
                                                   WorkMetadataRepository workMetadataRepository,
                                                   WorkAssetService workAssetService,
                                                   CollectionService collectionService,
                                                   UserQuotaService userQuotaService,
                                                   MultiModeConfig multiModeConfig,
                                                   ObjectMapper objectMapper) {
        return new GalleryBatchService(galleryService, workMetadataRepository, workAssetService,
                collectionService, userQuotaService, multiModeConfig, objectMapper);
    }

    @Bean
    public GalleryController galleryController(GalleryService galleryService,
                                               GalleryBatchService galleryBatchService,
                                               GuestAccessGuard guestAccessGuard) {
        return new GalleryController(galleryService, galleryBatchService, guestAccessGuard);
    }
}
