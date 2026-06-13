package top.sywyar.pixivdownload.novel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.controller.NovelGalleryController;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelGalleryRepository;
import top.sywyar.pixivdownload.plugin.api.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.WorkQueryService;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

import javax.sql.DataSource;

/**
 * novel 插件的 Bean 装配收敛点：小说画廊侧业务 Bean（含 {@code @RestController}）均经
 * {@code @PluginManagedBean} 排除出根包扫描，由这里以 {@code @Bean} 显式提供。
 * 收敛范围仅小说画廊侧四个类；novel-core（下载 / 正文 / 翻译 / TTS / AI 听书）不强拆，
 * 其 Bean 仍走根包扫描、在 controller 装配处按参数注入。
 * {@link NovelGalleryRepository} 仍被核心（CoreWorkQueryService / NovelWorkSearch）
 * 注入使用，待其收编进核心数据层后再迁出本配置。
 */
@Configuration
public class NovelPluginConfiguration {

    @Bean
    public NovelPlugin novelPlugin() {
        return new NovelPlugin();
    }

    @Bean
    public NovelGalleryRepository novelGalleryRepository(DataSource dataSource) {
        return new NovelGalleryRepository(dataSource);
    }

    @Bean
    public NovelGalleryService novelGalleryService(WorkQueryService workQueryService,
                                                   WorkMetadataRepository workMetadataRepository,
                                                   WorkAssetService workAssetService,
                                                   WorkDeletionService workDeletionService,
                                                   AppMessages messages) {
        return new NovelGalleryService(
                workQueryService, workMetadataRepository, workAssetService, workDeletionService, messages);
    }

    @Bean
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
    public NovelGalleryController novelGalleryController(NovelGalleryService novelGalleryService,
                                                         NovelBatchService novelBatchService,
                                                         NovelMergeService novelMergeService,
                                                         NovelSeriesService novelSeriesService,
                                                         NovelTranslationService novelTranslationService,
                                                         NovelDatabase novelDatabase,
                                                         NovelGalleryRepository novelGalleryRepository,
                                                         WorkAssetService workAssetService,
                                                         GuestAccessGuard guestAccessGuard,
                                                         AppMessages messages) {
        return new NovelGalleryController(novelGalleryService, novelBatchService, novelMergeService,
                novelSeriesService, novelTranslationService, novelDatabase, novelGalleryRepository,
                workAssetService, guestAccessGuard, messages);
    }
}
