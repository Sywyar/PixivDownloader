package top.sywyar.pixivdownload.novel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.common.PixivCoverDownloader;
import top.sywyar.pixivdownload.config.DebugConfig;
import top.sywyar.pixivdownload.core.ai.AiService;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.narration.NarrationEngineRegistry;
import top.sywyar.pixivdownload.core.narration.NarrationTtsConfig;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxProxyClient;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkService;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessGuard;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.controller.NovelDownloadController;
import top.sywyar.pixivdownload.novel.controller.NovelDownloadLegacyForwardController;
import top.sywyar.pixivdownload.novel.controller.NovelGlossaryController;
import top.sywyar.pixivdownload.novel.controller.NovelPixivProxyController;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.download.NovelDownloader;
import top.sywyar.pixivdownload.novel.download.NovelQueueOperations;
import top.sywyar.pixivdownload.novel.download.ScheduledNovelDownloadDelegate;
import top.sywyar.pixivdownload.novel.schedule.PixivScheduledNovelWorkExecutor;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.narration.NarrationReferenceVoiceService;
import top.sywyar.pixivdownload.novel.narration.NarrationReferenceVoiceStore;
import top.sywyar.pixivdownload.novel.narration.NovelNarrationCastService;
import top.sywyar.pixivdownload.novel.narration.NovelNarrationScriptService;
import top.sywyar.pixivdownload.novel.narration.audio.NarrationAudioService;
import top.sywyar.pixivdownload.novel.narration.analysis.NarrationScriptService;
import top.sywyar.pixivdownload.novel.narration.controller.NarrationController;
import top.sywyar.pixivdownload.novel.narration.controller.NarrationReferenceVoiceController;
import top.sywyar.pixivdownload.novel.narration.controller.NarrationTtsController;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.novel.translation.NovelGlossaryService;
import top.sywyar.pixivdownload.novel.translation.NovelTranslationService;
import top.sywyar.pixivdownload.novelgallery.NovelBatchService;
import top.sywyar.pixivdownload.novelgallery.NovelGalleryService;
import top.sywyar.pixivdownload.novelgallery.PixivNovelGalleryDataProvider;
import top.sywyar.pixivdownload.novelgallery.PixivNovelGalleryCapabilityProvider;
import top.sywyar.pixivdownload.novelgallery.controller.NovelGalleryController;
import top.sywyar.pixivdownload.novelgallery.frontend.NovelGalleryFrontendProvider;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

/**
 * novel 插件的 Bean 装配收敛点。插件 descriptor 始终注册；下载、Pixiv 小说代理、
 * 下载队列适配器、计划任务小说执行器、翻译/合订/朗读编排和小说画廊展示 Bean
 * 均随 {@code plugins.novel.enabled} 装配或缺席。
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
public class NovelPluginConfiguration {

    @Bean
    public NovelPlugin novelPlugin() {
        return new NovelPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public MapperFactoryBean<NovelMapper> novelMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<NovelMapper> factory = new MapperFactoryBean<>(NovelMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelDatabase novelDatabase(NovelMapper novelMapper,
                                       PixivDatabase pixivDatabase,
                                       PathPrefixCodec pathPrefixCodec,
                                       DatabaseInitializer databaseInitializer,
                                       NovelMetadataRepository novelMetadataRepository) {
        return new NovelDatabase(novelMapper, pixivDatabase, pathPrefixCodec,
                databaseInitializer, novelMetadataRepository);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelSeriesService novelSeriesService(NovelDatabase novelDatabase,
                                                 DownloadConfig downloadConfig,
                                                 PixivCoverDownloader coverDownloader,
                                                 AppMessages messages) {
        return new NovelSeriesService(novelDatabase, downloadConfig, coverDownloader, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelMergeService novelMergeService(DownloadConfig downloadConfig,
                                               NovelDatabase novelDatabase,
                                               AuthorService authorService,
                                               AppMessages messages) {
        return new NovelMergeService(downloadConfig, novelDatabase, authorService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelGlossaryService novelGlossaryService(NovelMapper novelMapper,
                                                     NovelDatabase novelDatabase) {
        return new NovelGlossaryService(novelMapper, novelDatabase);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelTranslationService novelTranslationService(AiService aiService,
                                                           NovelDatabase novelDatabase,
                                                           NovelGlossaryService glossaryService,
                                                           AppMessages messages) {
        return new NovelTranslationService(aiService, novelDatabase, glossaryService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelAutoTranslateService novelAutoTranslateService(
            NovelTranslationService translationService,
            NovelGlossaryService glossaryService,
            NovelMergeService mergeService,
            AiService aiService,
            @Qualifier("novelTranslateTaskExecutor") TaskExecutor executor,
            ScheduleCapabilityRegistry scheduleCapabilityRegistry) {
        return new NovelAutoTranslateService(
                translationService, glossaryService, mergeService, aiService, executor,
                scheduleCapabilityRegistry);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelDownloadService novelDownloadService(
            DownloadConfig downloadConfig,
            PixivDatabase pixivDatabase,
            NovelDatabase novelDatabase,
            NovelSeriesService novelSeriesService,
            AuthorService authorService,
            CollectionService collectionService,
            PixivBookmarkService pixivBookmarkService,
            @Nullable UserQuotaService userQuotaService,
            @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            @Qualifier("novelDownloadTaskExecutor") TaskExecutor downloadTaskExecutor,
            AppMessages messages,
            NovelAutoTranslateService novelAutoTranslateService,
            WorkMetaCaptureService workMetaCaptureService) {
        return new NovelDownloadService(downloadConfig, pixivDatabase, novelDatabase, novelSeriesService,
                authorService, collectionService, pixivBookmarkService, userQuotaService, downloadRestTemplate,
                taskScheduler, downloadTaskExecutor, messages, novelAutoTranslateService, workMetaCaptureService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public QueueOperations novelQueueOperations(NovelDownloadService novelDownloadService) {
        return new NovelQueueOperations(novelDownloadService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public ScheduledNovelDownloadDelegate scheduledNovelDownloadDelegate(
            NovelDownloader novelDownloader,
            NovelMergeService novelMergeService,
            NovelAutoTranslateService novelAutoTranslateService) {
        return new ScheduledNovelDownloadDelegate(
                novelDownloader, novelMergeService, novelAutoTranslateService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public PixivScheduledNovelWorkExecutor pixivScheduledNovelWorkExecutor(
            ObjectMapper objectMapper,
            PixivAjaxProxyClient pixivAjaxProxyClient,
            NovelMetadataRepository novelMetadataRepository,
            WorkMetaCaptureService workMetaCaptureService,
            NovelDownloader novelDownloader,
            NovelMergeService novelMergeService,
            NovelAutoTranslateService novelAutoTranslateService,
            DownloadConfig downloadConfig) {
        return new PixivScheduledNovelWorkExecutor(
                objectMapper, pixivAjaxProxyClient, novelMetadataRepository,
                workMetaCaptureService, novelDownloader, novelMergeService,
                novelAutoTranslateService, downloadConfig);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationScriptService narrationScriptService(AiService aiService) {
        return new NarrationScriptService(aiService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationReferenceVoiceStore narrationReferenceVoiceStore() {
        return new NarrationReferenceVoiceStore();
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationAudioService narrationAudioService(NarrationEngineRegistry registry,
                                                       NarrationTtsConfig config,
                                                       AppMessages messages) {
        return new NarrationAudioService(registry, config, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationReferenceVoiceService narrationReferenceVoiceService(
            NovelMapper novelMapper,
            NarrationAudioService narrationAudioService,
            NarrationReferenceVoiceStore fileStore) {
        return new NarrationReferenceVoiceService(novelMapper, narrationAudioService, fileStore);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelNarrationCastService novelNarrationCastService(
            NovelMapper novelMapper,
            NovelDatabase novelDatabase,
            NarrationScriptService narrationScriptService,
            NarrationReferenceVoiceStore referenceVoiceStore,
            PlatformTransactionManager transactionManager) {
        return new NovelNarrationCastService(novelMapper, novelDatabase,
                narrationScriptService, referenceVoiceStore, transactionManager);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelNarrationScriptService novelNarrationScriptService(
            NovelNarrationCastService castService,
            NovelDatabase novelDatabase,
            NovelMapper novelMapper,
            NarrationAudioService narrationAudioService,
            NarrationReferenceVoiceService referenceVoiceService,
            ObjectMapper objectMapper) {
        return new NovelNarrationScriptService(castService, novelDatabase, novelMapper,
                narrationAudioService, referenceVoiceService, objectMapper);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationController narrationController(NovelNarrationScriptService scriptService,
                                                   NovelNarrationCastService castService,
                                                   NarrationReferenceVoiceService referenceVoiceService,
                                                   NarrationAudioService narrationAudioService,
                                                   NovelDatabase novelDatabase,
                                                   AppMessages messages,
                                                   DebugConfig debugConfig,
                                                   AiService aiService) {
        return new NarrationController(scriptService, castService, referenceVoiceService, narrationAudioService,
                novelDatabase, messages, debugConfig, aiService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationTtsController narrationTtsController(NarrationAudioService narrationAudioService,
                                                         NovelNarrationScriptService narrationScriptService,
                                                         AppMessages messages) {
        return new NarrationTtsController(narrationAudioService, narrationScriptService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationReferenceVoiceController narrationReferenceVoiceController(
            NovelNarrationCastService castService,
            NarrationReferenceVoiceService referenceVoiceService,
            AppMessages messages) {
        return new NarrationReferenceVoiceController(castService, referenceVoiceService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelDownloadController novelDownloadController(NovelDownloadService novelDownloadService,
                                                          NovelAutoTranslateService novelAutoTranslateService,
                                                          NovelDatabase novelDatabase,
                                                          NovelGalleryRepository novelGalleryRepository,
                                                          NovelMergeService novelMergeService,
                                                          NovelTranslationService novelTranslationService,
                                                          SetupService setupService,
                                                          UserQuotaService userQuotaService,
                                                          MultiModeConfig multiModeConfig,
                                                          AppMessages messages) {
        return new NovelDownloadController(novelDownloadService, novelAutoTranslateService, novelDatabase,
                novelGalleryRepository, novelMergeService, novelTranslationService, setupService,
                userQuotaService, multiModeConfig, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelPixivProxyController novelPixivProxyController(ObjectMapper objectMapper,
                                                              PixivAjaxProxyClient pixivAjaxProxyClient,
                                                              PixivProxyAccessGuard pixivProxyAccessGuard,
                                                              GuestAccessGuard guestAccessGuard,
                                                              AppMessages messages) {
        return new NovelPixivProxyController(objectMapper, pixivAjaxProxyClient, pixivProxyAccessGuard,
                guestAccessGuard, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelDownloadLegacyForwardController novelDownloadLegacyForwardController() {
        return new NovelDownloadLegacyForwardController();
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelGlossaryController novelGlossaryController(NovelGlossaryService glossaryService) {
        return new NovelGlossaryController(glossaryService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelGalleryService novelGalleryService(WorkQueryService workQueryService,
                                                   WorkMetadataRepository workMetadataRepository,
                                                   WorkDeletionService workDeletionService) {
        return new NovelGalleryService(workQueryService, workMetadataRepository, workDeletionService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public PixivNovelGalleryDataProvider pixivNovelGalleryDataProvider(WorkQueryService workQueryService,
                                                                       WorkMetadataRepository workMetadataRepository) {
        return new PixivNovelGalleryDataProvider(workQueryService, workMetadataRepository);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public PixivNovelGalleryCapabilityProvider pixivNovelGalleryCapabilityProvider(
            PixivNovelGalleryDataProvider legacyProvider,
            WorkMetadataRepository workMetadataRepository,
            NovelDatabase novelDatabase) {
        return new PixivNovelGalleryCapabilityProvider(legacyProvider, workMetadataRepository, novelDatabase);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelGalleryFrontendProvider novelGalleryFrontendProvider() {
        return new NovelGalleryFrontendProvider();
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
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
    @ConditionalOnPluginEnabled("novel")
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
