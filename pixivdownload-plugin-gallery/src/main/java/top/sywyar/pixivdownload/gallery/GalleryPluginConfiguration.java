package top.sywyar.pixivdownload.gallery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.core.archive.ArchiveExportService;
import top.sywyar.pixivdownload.core.collection.ArtworkCollectionMembership;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryRuntimeQuery;
import top.sywyar.pixivdownload.gallery.frontend.PixivGalleryFrontendProvider;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;

/**
 * gallery 插件的 Bean 装配收敛点：业务 Bean（含 {@code @RestController}）均经
 * {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean} 显式提供。
 * 画廊的 SQL 仓库已收编进核心数据层（{@code core.metadata.artwork.GalleryRepository}，根包扫描的
 * 核心 Bean），不再由本配置提供。
 * <p>
 * 插件 descriptor {@link GalleryPlugin} 始终注册（安装态视图、schema owner 稳定身份与 disabledPlugins
 * 都依赖全部 descriptor 在场）；业务 Bean 经 {@link ConditionalOnPluginEnabled} 随
 * {@code plugins.gallery.enabled} 装配 / 缺席——禁用本插件时画廊页面与 API 不在场、其路由因
 * 「未声明即 404」不可达，但核心下载链路与已落库数据不受影响（重新启用即可读既有数据）。
 */
@Configuration
public class GalleryPluginConfiguration {

    @Bean
    public GalleryPlugin galleryPlugin() {
        return new GalleryPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled("gallery")
    public GalleryService galleryService(WorkQueryService workQueryService,
                                         WorkMetadataRepository workMetadataRepository,
                                         WorkDeletionService workDeletionService) {
        return new GalleryService(workQueryService, workMetadataRepository, workDeletionService);
    }

    @Bean
    @ConditionalOnPluginEnabled("gallery")
    public PixivImageGalleryCapabilityProvider pixivImageGalleryCapabilityProvider(
            WorkQueryService workQueryService,
            WorkMetadataRepository workMetadataRepository) {
        return new PixivImageGalleryCapabilityProvider(workQueryService, workMetadataRepository);
    }

    @Bean
    @ConditionalOnPluginEnabled("gallery")
    public PixivGalleryFrontendProvider pixivGalleryFrontendProvider() {
        return new PixivGalleryFrontendProvider();
    }

    @Bean
    @ConditionalOnPluginEnabled("gallery")
    @SuppressWarnings("deprecation")
    public UnifiedGalleryController unifiedGalleryController(
            GalleryRuntimeQuery galleryRuntimeQuery,
            RequestOwnerIdentityResolver ownerIdentityResolver) {
        return new UnifiedGalleryController(galleryRuntimeQuery, ownerIdentityResolver);
    }

    @Bean
    @ConditionalOnPluginEnabled("gallery")
    public GalleryBatchService galleryBatchService(GalleryService galleryService,
                                                   WorkMetadataRepository workMetadataRepository,
                                                   WorkAssetService workAssetService,
                                                   ArtworkCollectionMembership collectionMembership,
                                                   ArchiveExportService archiveExportService,
                                                   ObjectMapper objectMapper) {
        return new GalleryBatchService(galleryService, workMetadataRepository, workAssetService,
                collectionMembership, archiveExportService, objectMapper);
    }

    @Bean
    @ConditionalOnPluginEnabled("gallery")
    public GalleryController galleryController(GalleryService galleryService,
                                               GalleryBatchService galleryBatchService,
                                               WorkVisibilityService workVisibilityService) {
        return new GalleryController(galleryService, galleryBatchService, workVisibilityService);
    }
}
