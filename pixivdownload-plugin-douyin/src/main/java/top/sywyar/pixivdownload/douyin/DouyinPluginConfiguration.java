package top.sywyar.pixivdownload.douyin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.client.RestTemplate;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.douyin.client.DefaultDouyinShortLinkResolver;
import top.sywyar.pixivdownload.core.download.queue.QueueOperations;
import top.sywyar.pixivdownload.douyin.client.DefaultDouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinRedirectClient;
import top.sywyar.pixivdownload.douyin.client.DouyinRestTemplateFactory;
import top.sywyar.pixivdownload.douyin.client.DouyinShortLinkResolver;
import top.sywyar.pixivdownload.douyin.client.RestTemplateDouyinRedirectClient;
import top.sywyar.pixivdownload.douyin.controller.DouyinController;
import top.sywyar.pixivdownload.douyin.controller.DouyinGalleryController;
import top.sywyar.pixivdownload.douyin.controller.DouyinHistoryMediaController;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryMapper;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryRepository;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.download.DouyinMediaDownloader;
import top.sywyar.pixivdownload.douyin.download.DouyinDownloadService;
import top.sywyar.pixivdownload.douyin.download.DouyinQueueOperations;
import top.sywyar.pixivdownload.douyin.download.work.DouyinWorkDownloadExecutor;
import top.sywyar.pixivdownload.douyin.gallery.DouyinGalleryDataProvider;
import top.sywyar.pixivdownload.douyin.gallery.frontend.DouyinGalleryFrontendProvider;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.douyin.schedule.credential.DouyinScheduledCredentialPolicy;
import top.sywyar.pixivdownload.douyin.schedule.guard.DouyinRiskExecutionGuard;
import top.sywyar.pixivdownload.douyin.schedule.network.DouyinScheduledSourceRouteResolver;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceExecutor;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceSupport;
import top.sywyar.pixivdownload.douyin.schedule.work.DouyinScheduledWorkExecutor;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.setup.SetupService;

import java.nio.file.Path;

@Configuration
public class DouyinPluginConfiguration {

    @Bean
    public DouyinPlugin douyinPlugin() {
        return new DouyinPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public MapperFactoryBean<DouyinHistoryMapper> douyinHistoryMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<DouyinHistoryMapper> factory = new MapperFactoryBean<>(DouyinHistoryMapper.class);
        factory.setSqlSessionFactory(sqlSessionFactory);
        return factory;
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinHistoryRepository douyinHistoryRepository(DouyinHistoryMapper mapper,
                                                           PathPrefixCodec pathPrefixCodec) {
        return new DouyinHistoryRepository(mapper, pathPrefixCodec);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinHistoryService douyinHistoryService(DouyinHistoryRepository repository) {
        DouyinHistoryService service = new DouyinHistoryService(repository);
        service.backfillRelations();
        return service;
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinGalleryDataProvider douyinGalleryDataProvider(DouyinHistoryService historyService) {
        return new DouyinGalleryDataProvider(historyService);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinGalleryFrontendProvider douyinGalleryFrontendProvider() {
        return new DouyinGalleryFrontendProvider();
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinGalleryController douyinGalleryController(DouyinHistoryService historyService,
                                                           DouyinGalleryDataProvider dataProvider) {
        return new DouyinGalleryController(historyService, dataProvider);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinUrlParser douyinUrlParser() {
        return new DouyinUrlParser();
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinRedirectClient douyinRedirectClient(ProxyConfig proxyConfig) {
        return new RestTemplateDouyinRedirectClient(proxyConfig);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinRedirectClient douyinDirectRedirectClient() {
        return new RestTemplateDouyinRedirectClient((ProxyConfig) null);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinRedirectClient douyinProxyRedirectClient(ProxyConfig proxyConfig) {
        return new RestTemplateDouyinRedirectClient(proxyConfig, true);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinRedirectClient douyinCustomProxyRedirectClient(DouyinPluginSettingsService settingsService) {
        return new RestTemplateDouyinRedirectClient(settingsService);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinShortLinkResolver douyinShortLinkResolver(DouyinUrlParser parser,
                                                           @Qualifier("douyinRedirectClient")
                                                           DouyinRedirectClient redirectClient) {
        return new DefaultDouyinShortLinkResolver(parser, redirectClient);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinShortLinkResolver douyinDirectShortLinkResolver(DouyinUrlParser parser,
                                                                 @Qualifier("douyinDirectRedirectClient")
                                                                 DouyinRedirectClient redirectClient) {
        return new DefaultDouyinShortLinkResolver(parser, redirectClient);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinShortLinkResolver douyinProxyShortLinkResolver(DouyinUrlParser parser,
                                                                @Qualifier("douyinProxyRedirectClient")
                                                                DouyinRedirectClient redirectClient) {
        return new DefaultDouyinShortLinkResolver(parser, redirectClient);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinShortLinkResolver douyinCustomProxyShortLinkResolver(DouyinUrlParser parser,
                                                                      @Qualifier("douyinCustomProxyRedirectClient")
                                                                      DouyinRedirectClient redirectClient) {
        return new DefaultDouyinShortLinkResolver(parser, redirectClient);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public RestTemplate douyinRestTemplate(ProxyConfig proxyConfig) {
        return DouyinRestTemplateFactory.inheritedDownloadTemplate(proxyConfig);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public RestTemplate douyinDirectRestTemplate() {
        return DouyinRestTemplateFactory.directDownloadTemplate();
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public RestTemplate douyinProxyRestTemplate(ProxyConfig proxyConfig) {
        return DouyinRestTemplateFactory.forcedProxyDownloadTemplate(proxyConfig);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public RestTemplate douyinCustomProxyRestTemplate(DouyinPluginSettingsService settingsService) {
        return DouyinRestTemplateFactory.customProxyDownloadTemplate(settingsService);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinClient douyinClient(DouyinUrlParser parser,
                                     @Qualifier("douyinRestTemplate") RestTemplate downloadRestTemplate,
                                     @Qualifier("douyinShortLinkResolver")
                                     DouyinShortLinkResolver shortLinkResolver) {
        return new DefaultDouyinClient(parser, downloadRestTemplate, shortLinkResolver);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinClient douyinDirectClient(DouyinUrlParser parser,
                                           @Qualifier("douyinDirectRestTemplate") RestTemplate restTemplate,
                                           @Qualifier("douyinDirectShortLinkResolver")
                                           DouyinShortLinkResolver shortLinkResolver) {
        return new DefaultDouyinClient(parser, restTemplate, shortLinkResolver);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinClient douyinProxyClient(DouyinUrlParser parser,
                                          @Qualifier("douyinProxyRestTemplate") RestTemplate restTemplate,
                                          @Qualifier("douyinProxyShortLinkResolver")
                                          DouyinShortLinkResolver shortLinkResolver) {
        return new DefaultDouyinClient(parser, restTemplate, shortLinkResolver);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinClient douyinCustomProxyClient(DouyinUrlParser parser,
                                                @Qualifier("douyinCustomProxyRestTemplate") RestTemplate restTemplate,
                                                @Qualifier("douyinCustomProxyShortLinkResolver")
                                                DouyinShortLinkResolver shortLinkResolver) {
        return new DefaultDouyinClient(parser, restTemplate, shortLinkResolver);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinMediaDownloader douyinMediaDownloader(
            @Qualifier("douyinRestTemplate") RestTemplate downloadRestTemplate) {
        return new DouyinMediaDownloader(downloadRestTemplate);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinMediaDownloader douyinDirectMediaDownloader(
            @Qualifier("douyinDirectRestTemplate") RestTemplate restTemplate) {
        return new DouyinMediaDownloader(restTemplate);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinMediaDownloader douyinProxyMediaDownloader(
            @Qualifier("douyinProxyRestTemplate") RestTemplate restTemplate) {
        return new DouyinMediaDownloader(restTemplate);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinMediaDownloader douyinCustomProxyMediaDownloader(
            @Qualifier("douyinCustomProxyRestTemplate") RestTemplate restTemplate) {
        return new DouyinMediaDownloader(restTemplate);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinPluginSettingsService douyinPluginSettingsService(DownloadConfig downloadConfig) {
        Path inherited = Path.of(downloadConfig.getRootFolder()).resolve("douyin").normalize();
        return new DouyinPluginSettingsService(inherited);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinDownloadService douyinDownloadService(DouyinUrlParser parser,
                                                       @Qualifier("douyinClient") DouyinClient client,
                                                       @Qualifier("douyinProxyClient") DouyinClient proxyClient,
                                                       @Qualifier("douyinCustomProxyClient") DouyinClient customProxyClient,
                                                       @Qualifier("douyinDirectClient") DouyinClient directClient,
                                                       @Qualifier("douyinMediaDownloader")
                                                       DouyinMediaDownloader mediaDownloader,
                                                       @Qualifier("douyinProxyMediaDownloader")
                                                       DouyinMediaDownloader proxyMediaDownloader,
                                                       @Qualifier("douyinCustomProxyMediaDownloader")
                                                       DouyinMediaDownloader customProxyMediaDownloader,
                                                       @Qualifier("douyinDirectMediaDownloader")
                                                       DouyinMediaDownloader directMediaDownloader,
                                                       @Qualifier("downloadTaskExecutor") TaskExecutor executor,
                                                       DouyinPluginSettingsService settingsService,
                                                       DouyinHistoryService historyService) {
        return new DouyinDownloadService(parser,
                client, proxyClient, customProxyClient, directClient,
                mediaDownloader, proxyMediaDownloader, customProxyMediaDownloader, directMediaDownloader,
                executor, settingsService, historyService);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduleCodec douyinScheduleCodec(ObjectMapper objectMapper) {
        return new DouyinScheduleCodec(objectMapper);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledSourceSupport douyinScheduledSourceSupport(
            @Qualifier("douyinClient") DouyinClient client,
            DouyinScheduleCodec codec,
            DouyinPluginSettingsService settingsService,
            ProxyConfig proxyConfig) {
        return new DouyinScheduledSourceSupport(
                client, codec,
                new DouyinScheduledSourceRouteResolver(settingsService, proxyConfig));
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledSourceExecutor douyinUserScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.USER, support);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledSourceExecutor douyinSearchScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.SEARCH, support);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledSourceExecutor douyinCollectionScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.COLLECTION, support);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledSourceExecutor douyinMusicScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.MUSIC, support);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledSourceExecutor douyinAccountOwnScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.ACCOUNT_OWN_WORKS, support);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledSourceExecutor douyinAccountLikedScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.ACCOUNT_LIKED_WORKS, support);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledSourceExecutor douyinAccountFavoriteScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS, support);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledSourceExecutor douyinAccountFavoriteCollectionScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION, support);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledCredentialPolicy douyinScheduledCredentialPolicy(
            @Qualifier("douyinClient") DouyinClient client) {
        return new DouyinScheduledCredentialPolicy(client);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinRiskExecutionGuard douyinRiskExecutionGuard() {
        return new DouyinRiskExecutionGuard();
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinWorkDownloadExecutor douyinWorkDownloadExecutor(
            DouyinHistoryService historyService) {
        return new DouyinWorkDownloadExecutor(historyService);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinScheduledWorkExecutor douyinScheduledWorkExecutor(
            @Qualifier("douyinClient") DouyinClient client,
            @Qualifier("douyinMediaDownloader") DouyinMediaDownloader mediaDownloader,
            DouyinWorkDownloadExecutor workDownloadExecutor,
            DouyinPluginSettingsService settingsService,
            DouyinScheduleCodec codec,
            DownloadConfig downloadConfig) {
        return new DouyinScheduledWorkExecutor(
                client, mediaDownloader, workDownloadExecutor, settingsService,
                codec, downloadConfig);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public QueueOperations douyinQueueOperations(DouyinDownloadService downloadService) {
        return new DouyinQueueOperations(downloadService);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinController douyinController(DouyinDownloadService downloadService,
                                             SetupService setupService,
                                             MultiModeConfig multiModeConfig) {
        return new DouyinController(downloadService, setupService, multiModeConfig);
    }

    @Bean
    @ConditionalOnPluginEnabled("douyin")
    public DouyinHistoryMediaController douyinHistoryMediaController(DouyinHistoryService historyService) {
        return new DouyinHistoryMediaController(historyService);
    }
}
