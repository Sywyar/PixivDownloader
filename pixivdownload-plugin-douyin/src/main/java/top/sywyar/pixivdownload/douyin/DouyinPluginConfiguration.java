package top.sywyar.pixivdownload.douyin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.core.download.InteractiveDownloadExecutionLane;
import top.sywyar.pixivdownload.douyin.client.redirect.DefaultDouyinShortLinkResolver;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.douyin.client.DefaultDouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.redirect.DouyinRedirectClient;
import top.sywyar.pixivdownload.douyin.client.redirect.DouyinShortLinkResolver;
import top.sywyar.pixivdownload.douyin.client.redirect.OutboundHttpDouyinRedirectClient;
import top.sywyar.pixivdownload.douyin.controller.DouyinController;
import top.sywyar.pixivdownload.douyin.controller.DouyinGalleryController;
import top.sywyar.pixivdownload.douyin.controller.DouyinHistoryMediaController;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryMapper;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryRepository;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinStoredPathCodec;
import top.sywyar.pixivdownload.douyin.download.DouyinMediaDownloader;
import top.sywyar.pixivdownload.douyin.download.DouyinDownloadService;
import top.sywyar.pixivdownload.douyin.download.DouyinQueueOperations;
import top.sywyar.pixivdownload.douyin.download.work.DouyinWorkDownloadExecutor;
import top.sywyar.pixivdownload.douyin.gallery.DouyinGalleryDataProvider;
import top.sywyar.pixivdownload.douyin.http.DouyinHttpClientConfiguration;
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

import java.nio.file.Path;

@Configuration
@Import(DouyinHttpClientConfiguration.class)
public class DouyinPluginConfiguration {

    @Bean
    public DouyinPlugin douyinPlugin() {
        return new DouyinPlugin();
    }

    @Bean
    public DouyinHistoryMapper douyinHistoryMapper(PluginDataSource dataSource) {
        return new DouyinHistoryMapper(dataSource);
    }

    @Bean
    public DouyinHistoryRepository douyinHistoryRepository(DouyinHistoryMapper mapper,
                                                           DouyinPluginSettingsService settingsService) {
        return new DouyinHistoryRepository(mapper, new DouyinStoredPathCodec(settingsService));
    }

    @Bean
    public DouyinHistoryService douyinHistoryService(DouyinHistoryRepository repository) {
        DouyinHistoryService service = new DouyinHistoryService(repository);
        service.backfillRelations();
        return service;
    }

    @Bean
    public DouyinGalleryDataProvider douyinGalleryDataProvider(DouyinHistoryService historyService) {
        return new DouyinGalleryDataProvider(historyService);
    }

    @Bean
    public DouyinGalleryController douyinGalleryController(DouyinHistoryService historyService,
                                                           DouyinGalleryDataProvider dataProvider) {
        return new DouyinGalleryController(historyService, dataProvider);
    }

    @Bean
    public DouyinUrlParser douyinUrlParser() {
        return new DouyinUrlParser();
    }

    @Bean
    public DouyinRedirectClient douyinRedirectClient(
            @Qualifier("douyinRedirectHttpClient") OutboundHttpClient httpClient) {
        return new OutboundHttpDouyinRedirectClient(httpClient);
    }

    @Bean
    public DouyinRedirectClient douyinDirectRedirectClient(
            @Qualifier("douyinDirectRedirectHttpClient") OutboundHttpClient httpClient) {
        return new OutboundHttpDouyinRedirectClient(httpClient);
    }

    @Bean
    public DouyinRedirectClient douyinProxyRedirectClient(
            @Qualifier("douyinProxyRedirectHttpClient") OutboundHttpClient httpClient) {
        return new OutboundHttpDouyinRedirectClient(httpClient);
    }

    @Bean
    public DouyinRedirectClient douyinCustomProxyRedirectClient(
            @Qualifier("douyinCustomProxyRedirectHttpClient") OutboundHttpClient httpClient) {
        return new OutboundHttpDouyinRedirectClient(httpClient);
    }

    @Bean
    public DouyinShortLinkResolver douyinShortLinkResolver(DouyinUrlParser parser,
                                                           @Qualifier("douyinRedirectClient")
                                                           DouyinRedirectClient redirectClient) {
        return new DefaultDouyinShortLinkResolver(parser, redirectClient);
    }

    @Bean
    public DouyinShortLinkResolver douyinDirectShortLinkResolver(DouyinUrlParser parser,
                                                                 @Qualifier("douyinDirectRedirectClient")
                                                                 DouyinRedirectClient redirectClient) {
        return new DefaultDouyinShortLinkResolver(parser, redirectClient);
    }

    @Bean
    public DouyinShortLinkResolver douyinProxyShortLinkResolver(DouyinUrlParser parser,
                                                                @Qualifier("douyinProxyRedirectClient")
                                                                DouyinRedirectClient redirectClient) {
        return new DefaultDouyinShortLinkResolver(parser, redirectClient);
    }

    @Bean
    public DouyinShortLinkResolver douyinCustomProxyShortLinkResolver(DouyinUrlParser parser,
                                                                      @Qualifier("douyinCustomProxyRedirectClient")
                                                                      DouyinRedirectClient redirectClient) {
        return new DefaultDouyinShortLinkResolver(parser, redirectClient);
    }

    @Bean
    public DouyinClient douyinClient(DouyinUrlParser parser,
                                     @Qualifier("douyinHttpClient") OutboundHttpClient httpClient,
                                     @Qualifier("douyinShortLinkResolver")
                                     DouyinShortLinkResolver shortLinkResolver) {
        return new DefaultDouyinClient(parser, httpClient, shortLinkResolver);
    }

    @Bean
    public DouyinClient douyinDirectClient(DouyinUrlParser parser,
                                           @Qualifier("douyinDirectHttpClient") OutboundHttpClient httpClient,
                                           @Qualifier("douyinDirectShortLinkResolver")
                                           DouyinShortLinkResolver shortLinkResolver) {
        return new DefaultDouyinClient(parser, httpClient, shortLinkResolver);
    }

    @Bean
    public DouyinClient douyinProxyClient(DouyinUrlParser parser,
                                          @Qualifier("douyinProxyHttpClient") OutboundHttpClient httpClient,
                                          @Qualifier("douyinProxyShortLinkResolver")
                                          DouyinShortLinkResolver shortLinkResolver) {
        return new DefaultDouyinClient(parser, httpClient, shortLinkResolver);
    }

    @Bean
    public DouyinClient douyinCustomProxyClient(DouyinUrlParser parser,
                                                @Qualifier("douyinCustomProxyHttpClient") OutboundHttpClient httpClient,
                                                @Qualifier("douyinCustomProxyShortLinkResolver")
                                                DouyinShortLinkResolver shortLinkResolver) {
        return new DefaultDouyinClient(parser, httpClient, shortLinkResolver);
    }

    @Bean
    public DouyinMediaDownloader douyinMediaDownloader(
            @Qualifier("douyinHttpClient") OutboundHttpClient httpClient) {
        return new DouyinMediaDownloader(httpClient);
    }

    @Bean
    public DouyinMediaDownloader douyinDirectMediaDownloader(
            @Qualifier("douyinDirectHttpClient") OutboundHttpClient httpClient) {
        return new DouyinMediaDownloader(httpClient);
    }

    @Bean
    public DouyinMediaDownloader douyinProxyMediaDownloader(
            @Qualifier("douyinProxyHttpClient") OutboundHttpClient httpClient) {
        return new DouyinMediaDownloader(httpClient);
    }

    @Bean
    public DouyinMediaDownloader douyinCustomProxyMediaDownloader(
            @Qualifier("douyinCustomProxyHttpClient") OutboundHttpClient httpClient) {
        return new DouyinMediaDownloader(httpClient);
    }

    @Bean
    public DouyinPluginSettingsService douyinPluginSettingsService(DownloadSettings downloadSettings,
                                                                   RuntimePathProvider runtimePathProvider) {
        Path inherited = Path.of(downloadSettings.getRootFolder()).resolve("douyin").normalize();
        return new DouyinPluginSettingsService(runtimePathProvider, inherited);
    }

    @Bean
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
                                                       InteractiveDownloadExecutionLane executionLane,
                                                       DouyinPluginSettingsService settingsService,
                                                       DouyinHistoryService historyService) {
        return new DouyinDownloadService(parser,
                client, proxyClient, customProxyClient, directClient,
                mediaDownloader, proxyMediaDownloader, customProxyMediaDownloader, directMediaDownloader,
                executionLane, settingsService, historyService);
    }

    @Bean
    public DouyinScheduleCodec douyinScheduleCodec(ObjectMapper objectMapper) {
        return new DouyinScheduleCodec(objectMapper);
    }

    @Bean
    public DouyinScheduledSourceSupport douyinScheduledSourceSupport(
            @Qualifier("douyinClient") DouyinClient client,
            DouyinScheduleCodec codec,
            DouyinPluginSettingsService settingsService,
            OutboundProxySettings proxySettings) {
        return new DouyinScheduledSourceSupport(
                client, codec,
                new DouyinScheduledSourceRouteResolver(settingsService, proxySettings));
    }

    @Bean
    public DouyinScheduledSourceExecutor douyinUserScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.USER, support);
    }

    @Bean
    public DouyinScheduledSourceExecutor douyinSearchScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.SEARCH, support);
    }

    @Bean
    public DouyinScheduledSourceExecutor douyinCollectionScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.COLLECTION, support);
    }

    @Bean
    public DouyinScheduledSourceExecutor douyinMusicScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.MUSIC, support);
    }

    @Bean
    public DouyinScheduledSourceExecutor douyinAccountOwnScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.ACCOUNT_OWN_WORKS, support);
    }

    @Bean
    public DouyinScheduledSourceExecutor douyinAccountLikedScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.ACCOUNT_LIKED_WORKS, support);
    }

    @Bean
    public DouyinScheduledSourceExecutor douyinAccountFavoriteScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS, support);
    }

    @Bean
    public DouyinScheduledSourceExecutor douyinAccountFavoriteFolderScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.ACCOUNT_FAVORITE_FOLDER, support);
    }

    @Bean
    public DouyinScheduledSourceExecutor douyinAccountFavoriteCollectionScheduledSourceExecutor(
            DouyinScheduledSourceSupport support) {
        return new DouyinScheduledSourceExecutor(
                DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION, support);
    }

    @Bean
    public DouyinScheduledCredentialPolicy douyinScheduledCredentialPolicy(
            @Qualifier("douyinClient") DouyinClient client) {
        return new DouyinScheduledCredentialPolicy(client);
    }

    @Bean
    public DouyinRiskExecutionGuard douyinRiskExecutionGuard() {
        return new DouyinRiskExecutionGuard();
    }

    @Bean
    public DouyinWorkDownloadExecutor douyinWorkDownloadExecutor(
            DouyinHistoryService historyService) {
        return new DouyinWorkDownloadExecutor(historyService);
    }

    @Bean
    public DouyinScheduledWorkExecutor douyinScheduledWorkExecutor(
            @Qualifier("douyinClient") DouyinClient client,
            @Qualifier("douyinMediaDownloader") DouyinMediaDownloader mediaDownloader,
            DouyinWorkDownloadExecutor workDownloadExecutor,
            DouyinPluginSettingsService settingsService,
            DouyinScheduleCodec codec,
            DownloadSettings downloadSettings) {
        return new DouyinScheduledWorkExecutor(
                client, mediaDownloader, workDownloadExecutor, settingsService,
                codec, downloadSettings);
    }

    @Bean
    public QueueOperations douyinQueueOperations(DouyinDownloadService downloadService) {
        return new DouyinQueueOperations(downloadService);
    }

    @Bean
    public DouyinController douyinController(DouyinDownloadService downloadService,
                                             RequestOwnerIdentityResolver ownerIdentityResolver,
                                             MultiModeSettings multiModeSettings) {
        return new DouyinController(downloadService, ownerIdentityResolver, multiModeSettings);
    }

    @Bean
    public DouyinHistoryMediaController douyinHistoryMediaController(DouyinHistoryService historyService) {
        return new DouyinHistoryMediaController(historyService);
    }
}
