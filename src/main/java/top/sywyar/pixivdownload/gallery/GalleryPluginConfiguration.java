package top.sywyar.pixivdownload.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.WorkQueryService;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

import javax.sql.DataSource;

/**
 * gallery 插件的 Bean 装配收敛点：业务 Bean（含 {@code @RestController}）均经
 * {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean} 显式提供。
 * {@link GalleryRepository} 仍被核心（CoreWorkQueryService 与作者 / 收藏夹 / 系列 / 下载
 * controller）注入使用，待其收编进核心数据层后再迁出本配置。
 */
@Configuration
public class GalleryPluginConfiguration {

    @Bean
    public GalleryPlugin galleryPlugin() {
        return new GalleryPlugin();
    }

    @Bean
    public GalleryRepository galleryRepository(DataSource dataSource) {
        return new GalleryRepository(dataSource);
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
