package top.sywyar.pixivdownload.novel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import top.sywyar.pixivdownload.config.DebugSettings;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.core.archive.ArchiveExportService;
import top.sywyar.pixivdownload.core.collection.CollectionDownloadRootResolver;
import top.sywyar.pixivdownload.core.collection.WorkCollectionMembership;
import top.sywyar.pixivdownload.core.db.pathprefix.StoredPathCodec;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivBookmarkActions;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.novel.controller.NovelDownloadController;
import top.sywyar.pixivdownload.novel.controller.NovelDownloadLegacyForwardController;
import top.sywyar.pixivdownload.novel.controller.NovelGlossaryController;
import top.sywyar.pixivdownload.novel.controller.NovelPixivProxyController;
import top.sywyar.pixivdownload.novel.config.NovelExecutionConfiguration;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.db.series.NovelSeriesCatalogRepository;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.download.NovelDownloadExecutionLane;
import top.sywyar.pixivdownload.novel.download.NovelDownloader;
import top.sywyar.pixivdownload.novel.download.NovelQueueOperations;
import top.sywyar.pixivdownload.novel.metadata.NovelWorkDetailsRepository;
import top.sywyar.pixivdownload.novel.schedule.PixivScheduledNovelWorkExecutor;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.narration.NarrationReferenceVoiceService;
import top.sywyar.pixivdownload.novel.narration.NarrationReferenceVoicePaths;
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
import top.sywyar.pixivdownload.novelgallery.NovelOwnedWorkSearch;
import top.sywyar.pixivdownload.novelgallery.PixivNovelGalleryCapabilityProvider;
import top.sywyar.pixivdownload.novelgallery.controller.NovelGalleryController;
import top.sywyar.pixivdownload.novelgallery.frontend.NovelGalleryFrontendProvider;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.AuthorObservationService;
import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import top.sywyar.pixivdownload.core.work.service.WorkDeletionService;
import top.sywyar.pixivdownload.core.work.service.WorkFileNameCatalog;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataCapture;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.core.work.service.WorkTagCatalog;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceSelector;

/**
 * novel 插件的 Bean 装配收敛点。插件 descriptor 始终注册；下载、Pixiv 小说代理、
 * 下载队列适配器、计划任务小说执行器、翻译/合订/朗读编排和小说画廊展示 Bean
 * 均随 {@code plugins.novel.enabled} 装配或缺席。
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
@Import(NovelExecutionConfiguration.class)
public class NovelPluginConfiguration {

    @Bean
    public NovelPlugin novelPlugin() {
        return new NovelPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public MessageResolver novelPluginMessages(MessageResolver messages) {
        return ResourceBundleMessageResolver.of(
                messages, NovelPlugin.class.getClassLoader(), "i18n.novel.messages");
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
    public NovelSeriesCatalogRepository novelSeriesCatalogRepository(NovelMapper novelMapper) {
        return new NovelSeriesCatalogRepository(novelMapper);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelDatabase novelDatabase(NovelMapper novelMapper,
                                       WorkTagCatalog workTagCatalog,
                                       StoredPathCodec pathPrefixCodec) {
        return new NovelDatabase(novelMapper, workTagCatalog, pathPrefixCodec);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelWorkDetailsRepository novelWorkDetailsRepository(NovelMapper novelMapper) {
        return new NovelWorkDetailsRepository(novelMapper);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelSeriesService novelSeriesService(NovelDatabase novelDatabase,
                                                 DownloadSettings downloadConfig,
                                                 PixivImageDownloader pixivImageDownloader,
                                                 @Qualifier("novelPluginMessages") MessageResolver messages) {
        return new NovelSeriesService(novelDatabase, downloadConfig, pixivImageDownloader, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelMergeService novelMergeService(DownloadSettings downloadConfig,
                                               NovelDatabase novelDatabase,
                                               WorkQueryService workQueryService,
                                               @Qualifier("novelPluginMessages") MessageResolver messages) {
        return new NovelMergeService(downloadConfig, novelDatabase, workQueryService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelGlossaryService novelGlossaryService(NovelMapper novelMapper,
                                                     NovelDatabase novelDatabase) {
        return new NovelGlossaryService(novelMapper, novelDatabase);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelTranslationService novelTranslationService(AiChatClient aiChatClient,
                                                           NovelDatabase novelDatabase,
                                                           NovelGlossaryService glossaryService,
                                                           @Qualifier("novelPluginMessages") MessageResolver messages) {
        return new NovelTranslationService(aiChatClient, novelDatabase, glossaryService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelAutoTranslateService novelAutoTranslateService(
            NovelTranslationService translationService,
            NovelGlossaryService glossaryService,
            NovelMergeService mergeService,
            AiChatClient aiChatClient,
            @Qualifier("novelTranslateTaskExecutor") TaskExecutor executor,
            ScheduleCapabilityRegistry scheduleCapabilityRegistry) {
        return new NovelAutoTranslateService(
                translationService, glossaryService, mergeService, aiChatClient, executor,
                scheduleCapabilityRegistry);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelDownloadExecutionLane novelDownloadExecutionLane(
            @Qualifier("novelDownloadTaskExecutor") ThreadPoolTaskExecutor taskExecutor) {
        return new NovelDownloadExecutionLane(taskExecutor, taskExecutor.getMaxPoolSize());
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelDownloadService novelDownloadService(
            DownloadSettings downloadConfig,
            WorkFileNameCatalog workFileNameCatalog,
            DownloadPathGuard downloadPathGuard,
            NovelDatabase novelDatabase,
            NovelSeriesService novelSeriesService,
            AuthorObservationService authorObservationService,
            WorkCollectionMembership workCollectionMembership,
            CollectionDownloadRootResolver collectionDownloadRootResolver,
            PixivBookmarkActions pixivBookmarkActions,
            @Nullable VisitorDownloadQuotaService visitorDownloadQuotaService,
            PixivImageDownloader pixivImageDownloader,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler,
            NovelDownloadExecutionLane downloadExecutionLane,
            @Qualifier("novelPluginMessages") MessageResolver messages,
            NovelAutoTranslateService novelAutoTranslateService,
            WorkMetadataCapture workMetadataCapture) {
        return new NovelDownloadService(downloadConfig, workFileNameCatalog, downloadPathGuard,
                novelDatabase, novelSeriesService,
                authorObservationService, workCollectionMembership, collectionDownloadRootResolver,
                pixivBookmarkActions, visitorDownloadQuotaService, pixivImageDownloader,
                taskScheduler, downloadExecutionLane, messages, novelAutoTranslateService, workMetadataCapture);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public QueueOperations novelQueueOperations(NovelDownloadService novelDownloadService) {
        return new NovelQueueOperations(novelDownloadService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public PixivScheduledNovelWorkExecutor pixivScheduledNovelWorkExecutor(
            ObjectMapper objectMapper,
            PixivAjaxClient pixivAjaxClient,
            WorkQueryService workQueryService,
            WorkMetadataCapture workMetadataCapture,
            NovelDownloader novelDownloader,
            NovelMergeService novelMergeService,
            NovelAutoTranslateService novelAutoTranslateService,
            NovelDownloadExecutionLane downloadExecutionLane) {
        return new PixivScheduledNovelWorkExecutor(
                objectMapper, pixivAjaxClient, workQueryService,
                workMetadataCapture, novelDownloader, novelMergeService,
                novelAutoTranslateService, downloadExecutionLane);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationScriptService narrationScriptService(AiChatClient aiChatClient) {
        return new NarrationScriptService(aiChatClient);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationReferenceVoicePaths narrationReferenceVoicePaths(RuntimePathProvider runtimePathProvider) {
        return new NarrationReferenceVoicePaths(runtimePathProvider);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationReferenceVoiceStore narrationReferenceVoiceStore(NarrationReferenceVoicePaths paths) {
        return new NarrationReferenceVoiceStore(paths);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationAudioService narrationAudioService(NarrationVoiceSelector voiceSelector,
                                                       @Qualifier("novelPluginMessages") MessageResolver messages) {
        return new NarrationAudioService(voiceSelector, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationReferenceVoiceService narrationReferenceVoiceService(
            NovelMapper novelMapper,
            NarrationAudioService narrationAudioService,
            NarrationReferenceVoiceStore fileStore,
            NarrationReferenceVoicePaths paths) {
        return new NarrationReferenceVoiceService(
                novelMapper, narrationAudioService, fileStore, paths);
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
                                                   @Qualifier("novelPluginMessages") MessageResolver messages,
                                                   DebugSettings debugSettings,
                                                   AiChatClient aiChatClient) {
        return new NarrationController(scriptService, castService, referenceVoiceService, narrationAudioService,
                novelDatabase, messages, debugSettings, aiChatClient);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationTtsController narrationTtsController(NarrationAudioService narrationAudioService,
                                                         NovelNarrationScriptService narrationScriptService,
                                                         @Qualifier("novelPluginMessages") MessageResolver messages) {
        return new NarrationTtsController(narrationAudioService, narrationScriptService, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NarrationReferenceVoiceController narrationReferenceVoiceController(
            NovelNarrationCastService castService,
            NarrationReferenceVoiceService referenceVoiceService,
            @Qualifier("novelPluginMessages") MessageResolver messages,
            NarrationReferenceVoicePaths paths) {
        return new NarrationReferenceVoiceController(
                castService, referenceVoiceService, messages, paths);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelDownloadController novelDownloadController(NovelDownloadService novelDownloadService,
                                                          NovelAutoTranslateService novelAutoTranslateService,
                                                          NovelDatabase novelDatabase,
                                                          NovelGalleryService novelGalleryService,
                                                          WorkVisibilityService workVisibilityService,
                                                          NovelMergeService novelMergeService,
                                                          NovelTranslationService novelTranslationService,
                                                          ApplicationModeProvider applicationModeProvider,
                                                          RequestOwnerIdentityResolver requestOwnerIdentityResolver,
                                                          VisitorDownloadQuotaService visitorDownloadQuotaService,
                                                          MultiModeSettings multiModeSettings,
                                                          @Qualifier("novelPluginMessages") MessageResolver messages) {
        return new NovelDownloadController(novelDownloadService, novelAutoTranslateService, novelDatabase,
                novelGalleryService, novelMergeService, novelTranslationService, applicationModeProvider,
                requestOwnerIdentityResolver, workVisibilityService, visitorDownloadQuotaService,
                multiModeSettings, messages);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelPixivProxyController novelPixivProxyController(ObjectMapper objectMapper,
                                                               PixivAjaxClient pixivAjaxClient,
                                                               PixivProxyAccessPolicy pixivProxyAccessPolicy,
                                                               RequestOwnerIdentityResolver requestOwnerIdentityResolver,
                                                               WorkVisibilityService workVisibilityService,
                                                               @Qualifier("novelPluginMessages") MessageResolver messages) {
        return new NovelPixivProxyController(objectMapper, pixivAjaxClient, pixivProxyAccessPolicy,
                requestOwnerIdentityResolver, workVisibilityService, messages);
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
    public NovelOwnedWorkSearch novelOwnedWorkSearch(WorkQueryService workQueryService,
                                                     NovelDatabase novelDatabase) {
        return new NovelOwnedWorkSearch(workQueryService, novelDatabase);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelGalleryService novelGalleryService(WorkQueryService workQueryService,
                                                   NovelOwnedWorkSearch novelOwnedWorkSearch,
                                                   WorkMetadataRepository workMetadataRepository,
                                                   NovelWorkDetailsRepository novelWorkDetailsRepository,
                                                   NovelSeriesCatalogRepository novelSeriesCatalogRepository,
                                                   WorkDeletionService workDeletionService) {
        return new NovelGalleryService(
                workQueryService, novelOwnedWorkSearch, workMetadataRepository,
                novelWorkDetailsRepository, novelSeriesCatalogRepository, workDeletionService);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public PixivNovelGalleryCapabilityProvider pixivNovelGalleryCapabilityProvider(
            WorkQueryService workQueryService,
            WorkMetadataRepository workMetadataRepository,
            NovelDatabase novelDatabase,
            NovelWorkDetailsRepository novelWorkDetailsRepository) {
        return new PixivNovelGalleryCapabilityProvider(
                workQueryService, workMetadataRepository, novelDatabase, novelWorkDetailsRepository);
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
                                               NovelWorkDetailsRepository novelWorkDetailsRepository,
                                               WorkAssetService workAssetService,
                                               WorkCollectionMembership workCollectionMembership,
                                               ArchiveExportService archiveExportService,
                                               ObjectMapper objectMapper) {
        return new NovelBatchService(novelGalleryService, workMetadataRepository,
                novelWorkDetailsRepository, workAssetService, workCollectionMembership,
                archiveExportService, objectMapper);
    }

    @Bean
    @ConditionalOnPluginEnabled("novel")
    public NovelGalleryController novelGalleryController(NovelGalleryService novelGalleryService,
                                                         NovelBatchService novelBatchService,
                                                         NovelSeriesService novelSeriesService,
                                                         NovelDatabase novelDatabase,
                                                         WorkAssetService workAssetService,
                                                         WorkVisibilityService workVisibilityService) {
        return new NovelGalleryController(novelGalleryService, novelBatchService, novelSeriesService,
                novelDatabase, workAssetService, workVisibilityService);
    }
}
