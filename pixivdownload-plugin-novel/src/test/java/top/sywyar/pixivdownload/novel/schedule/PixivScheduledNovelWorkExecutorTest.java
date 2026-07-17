package top.sywyar.pixivdownload.novel.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.metadata.novel.NovelMetadataRepository;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxProxyClient;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.download.NovelDownloader;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunStatistics;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Pixiv 小说计划作品执行器")
class PixivScheduledNovelWorkExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PixivAjaxProxyClient pixivAjaxProxyClient;
    @Mock
    private NovelMetadataRepository novelMetadataRepository;
    @Mock
    private WorkMetaCaptureService workMetaCaptureService;
    @Mock
    private NovelDownloader novelDownloader;
    @Mock
    private NovelMergeService novelMergeService;
    @Mock
    private NovelAutoTranslateService novelAutoTranslateService;

    @AfterEach
    void clearRouteOverride() {
        OutboundProxyOverride.clear();
    }

    @Test
    @DisplayName("严格拒绝非 ID-only、非 canonical 或身份不一致的作品载荷")
    void rejectsUnsupportedWorkPayloads() {
        List<ScheduledWork> invalid = List.of(
                work("123", "{\"workId\":123}"),
                work("123", "{\"workId\":\"123\",\"title\":\"x\"}"),
                work("123", "{\"workId\":\"0123\"}"),
                work("0123", "{\"workId\":\"0123\"}"),
                work("123", "{\"workId\":\"124\"}"),
                new ScheduledWork(
                        new ScheduledWorkKey("novel", "123"),
                        "other.schema",
                        1,
                        "{\"workId\":\"123\"}",
                        ScheduledWorkPresentation.empty(),
                        List.of()));
        ScheduledWorkContext unusedContext = org.mockito.Mockito.mock(ScheduledWorkContext.class);

        for (ScheduledWork candidate : invalid) {
            ScheduledExecutionException failure = catchThrowableOfType(
                    () -> executor().execute(candidate, unusedContext),
                    ScheduledExecutionException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.PAYLOAD_UNSUPPORTED);
        }
        verifyNoInteractions(pixivAjaxProxyClient, novelDownloader);
    }

    @Test
    @DisplayName("本地去重按 redownloadDeleted 在全部记录与活动记录之间切换且不读取凭证")
    void localDeduplicationHonorsRedownloadDeleted() throws Exception {
        char[] firstSecret = "first-cookie".toCharArray();
        ContextFixture normal = context(definition(false, false), ScheduledNetworkRoute.direct(), firstSecret);
        when(novelMetadataRepository.hasNovel(123L)).thenReturn(true);

        ScheduledWorkResult normalResult = executor().execute(work("123"), normal.context());

        assertThat(normalResult.outcome()).isEqualTo(ScheduledWorkResult.Outcome.ALREADY_COMPLETED);
        verify(novelMetadataRepository).hasNovel(123L);
        verify(novelMetadataRepository, never()).hasActiveNovel(123L);
        verify(normal.credential(), never()).copySecret();

        reset(novelMetadataRepository);
        char[] redownloadSecret = "second-cookie".toCharArray();
        ContextFixture redownload = context(definition(true, false), ScheduledNetworkRoute.direct(), redownloadSecret);
        when(novelMetadataRepository.hasActiveNovel(123L)).thenReturn(true);

        ScheduledWorkResult redownloadResult = executor().execute(work("123"), redownload.context());

        assertThat(redownloadResult.outcome()).isEqualTo(ScheduledWorkResult.Outcome.ALREADY_COMPLETED);
        verify(novelMetadataRepository).hasActiveNovel(123L);
        verify(novelMetadataRepository, never()).hasNovel(123L);
        verify(redownload.credential(), never()).copySecret();
        verifyNoInteractions(pixivAjaxProxyClient, novelDownloader);
    }

    @Test
    @DisplayName("珍藏集 mixed 定义可执行其中的小说作品")
    void collectionMixedDefinitionExecutesNovelWork() throws Exception {
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull()))
                .thenReturn(minimalNovelResponse());
        when(novelDownloader.downloadBlocking(any(), isNull())).thenReturn(true);
        ScheduledTaskDefinition collectionTask = task(
                "collection",
                """
                        {"kind":"mixed","source":{"collectionId":"42"},"filters":{},
                         "download":{"redownloadDeleted":false}}
                        """);

        ScheduledWorkResult result = executor().execute(
                work("123"),
                context(collectionTask, ScheduledNetworkRoute.direct(), null).context());

        assertThat(result.outcome()).isEqualTo(ScheduledWorkResult.Outcome.COMPLETED);
        verify(novelDownloader).downloadBlocking(any(), isNull());
    }

    @Test
    @DisplayName("代理路由覆盖详情系列和阻塞下载并完整映射请求后捕获 sidecar")
    void mapsCompleteRequestInsideProxyRouteAndCapturesSidecar() throws Exception {
        char[] secret = "PHPSESSID=test".toCharArray();
        ContextFixture fixture = context(
                completeDefinition(),
                ScheduledNetworkRoute.proxy("proxy.local", 7890, null),
                secret);
        AtomicInteger fetches = new AtomicInteger();
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), eq("PHPSESSID=test")))
                .thenAnswer(invocation -> {
                    assertThat(OutboundProxyOverride.isActive()).isTrue();
                    assertThat(OutboundProxyOverride.current()).isNotNull();
                    assertThat(OutboundProxyOverride.current().getHostName()).isEqualTo("proxy.local");
                    assertThat(OutboundProxyOverride.current().getPort()).isEqualTo(7890);
                    URI uri = invocation.getArgument(0);
                    if (fetches.getAndIncrement() == 0) {
                        assertThat(uri.toString()).isEqualTo("https://www.pixiv.net/ajax/novel/123?lang=zh");
                        return completeNovelResponse();
                    }
                    assertThat(uri.toString()).isEqualTo("https://www.pixiv.net/ajax/novel/series/42?lang=zh");
                    return completeSeriesResponse();
                });
        when(novelDownloader.downloadBlocking(any(), isNull())).thenAnswer(invocation -> {
            assertThat(OutboundProxyOverride.isActive()).isTrue();
            assertThat(OutboundProxyOverride.current().getHostName()).isEqualTo("proxy.local");
            return true;
        });

        ScheduledWorkResult result = executor().execute(work("123"), fixture.context());

        assertThat(result.outcome()).isEqualTo(ScheduledWorkResult.Outcome.COMPLETED);
        assertThat(result.resultCode()).isEqualTo("work.completed");
        assertThat(result.attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "title", "标题",
                "xRestrict", "1",
                "ai", "true",
                "autoTranslateSubmitted", "true"));
        assertThat(fetches).hasValue(2);
        assertThat(OutboundProxyOverride.isActive()).isFalse();
        assertThat(OutboundProxyOverride.current()).isNull();
        assertThat(secret).containsOnly('\0');

        ArgumentCaptor<NovelDownloadRequest> requestCaptor = ArgumentCaptor.forClass(NovelDownloadRequest.class);
        verify(novelDownloader).downloadBlocking(requestCaptor.capture(), isNull());
        NovelDownloadRequest request = requestCaptor.getValue();
        assertThat(request.getNovelId()).isEqualTo(123L);
        assertThat(request.getTitle()).isEqualTo("标题");
        assertThat(request.getCookie()).isEqualTo("PHPSESSID=test");
        assertThat(request.getContent()).isEqualTo("正文[newpage]第二页");
        NovelDownloadRequest.Other other = request.getOther();
        assertThat(other.getAuthorId()).isEqualTo(9L);
        assertThat(other.getAuthorName()).isEqualTo("作者");
        assertThat(other.getXRestrict()).isEqualTo(1);
        assertThat(other.isAi()).isTrue();
        assertThat(other.isOriginal()).isTrue();
        assertThat(other.getLanguage()).isEqualTo("ja");
        assertThat(other.getWordCount()).isEqualTo(1234);
        assertThat(other.getTextLength()).isEqualTo(5678);
        assertThat(other.getReadingTimeSeconds()).isEqualTo(90);
        assertThat(other.getPageCount()).isEqualTo(2);
        assertThat(other.getDescription()).isEqualTo("<a href=\"/users/9\">简介</a>");
        assertThat(other.getTags()).singleElement().satisfies(tag -> {
            assertThat(tag.getName()).isEqualTo("Orig");
            assertThat(tag.getTranslatedName()).isEqualTo("Translated");
        });
        assertThat(other.getSeriesId()).isEqualTo(42L);
        assertThat(other.getSeriesOrder()).isEqualTo(7L);
        assertThat(other.getSeriesTitle()).isEqualTo("系列标题");
        assertThat(other.getUploadTimestamp()).isEqualTo(1_700_000_000_000L);
        assertThat(other.getCoverUrl()).isEqualTo("https://i.pximg.net/c/600x600/novel-cover-master/x.jpg");
        assertThat(other.getEmbeddedImages())
                .containsExactly(Map.entry("img", "https://i.pximg.net/img-original/img.jpg"));
        assertThat(other.getFileNameTemplate()).isEqualTo("{title}");
        assertThat(other.isBookmark()).isTrue();
        assertThat(other.getCollectionId()).isEqualTo(88L);
        assertThat(other.getFormat()).isEqualTo("epub");
        assertThat(other.isAutoTranslate()).isTrue();
        assertThat(other.getAutoTranslateLanguage()).isEqualTo("english");
        assertThat(other.getAutoTranslateSegmentSize()).isEqualTo(35);
        assertThat(other.isAutoTranslateMerge()).isTrue();
        assertThat(other.getAutoTranslateMergeFormat()).isEqualTo("html");
        assertThat(other.getSeriesDescription()).isEqualTo("系列简介");
        assertThat(other.getSeriesCoverUrl()).isEqualTo("https://i.pximg.net/series-cover.jpg");
        assertThat(other.getSeriesTags()).singleElement().satisfies(tag -> {
            assertThat(tag.getName()).isEqualTo("SeriesTag");
            assertThat(tag.getTranslatedName()).isEqualTo("Series translated");
        });

        ArgumentCaptor<JsonNode> sidecarBody = ArgumentCaptor.forClass(JsonNode.class);
        verify(workMetaCaptureService).captureNovel(eq(123L), sidecarBody.capture(), eq("schedule"));
        assertThat(sidecarBody.getValue()).isEqualTo(objectMapper.readTree(completeNovelResponse()).path("body"));
    }

    @Test
    @DisplayName("显式直连覆盖全局路由语义并允许匿名凭证且结束后清理")
    void directRouteAllowsAnonymousCredentialAndCleansUp() throws Exception {
        ContextFixture fixture = context(definition(false, false), ScheduledNetworkRoute.direct(), null);
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenAnswer(invocation -> {
            assertThat(OutboundProxyOverride.isActive()).isTrue();
            assertThat(OutboundProxyOverride.current()).isNull();
            return minimalNovelResponse();
        });
        when(novelDownloader.downloadBlocking(any(), isNull())).thenAnswer(invocation -> {
            assertThat(OutboundProxyOverride.isActive()).isTrue();
            assertThat(OutboundProxyOverride.current()).isNull();
            NovelDownloadRequest request = invocation.getArgument(0);
            assertThat(request.getCookie()).isNull();
            assertThat(request.getOther().isAutoTranslate()).isFalse();
            assertThat(request.getOther().getAutoTranslateLanguage()).isNull();
            assertThat(request.getOther().getAutoTranslateSegmentSize()).isNull();
            assertThat(request.getOther().isAutoTranslateMerge()).isFalse();
            assertThat(request.getOther().getAutoTranslateMergeFormat()).isNull();
            return true;
        });

        ScheduledWorkResult result = executor().execute(work("123"), fixture.context());

        assertThat(result.outcome()).isEqualTo(ScheduledWorkResult.Outcome.COMPLETED);
        assertThat(OutboundProxyOverride.isActive()).isFalse();
        verify(fixture.credential(), never()).copySecret();
    }

    @Test
    @DisplayName("筛选在系列抓取和下载前执行且命中时返回跳过")
    void filtersBeforeSeriesAndDownload() throws Exception {
        ContextFixture fixture = context(
                """
                        {"kind":"novel","source":{},"filters":{"content":"safe"},"download":{}}
                        """,
                ScheduledNetworkRoute.direct(),
                null);
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenReturn(completeNovelResponse());

        ScheduledWorkResult result = executor().execute(work("123"), fixture.context());

        assertThat(result.outcome()).isEqualTo(ScheduledWorkResult.Outcome.SKIPPED);
        assertThat(result.resultCode()).isEqualTo("pixiv.novel.filtered");
        assertThat(result.attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "title", "标题",
                "xRestrict", "1",
                "ai", "true"));
        verify(pixivAjaxProxyClient, times(1)).proxyGetUri(any(URI.class), isNull());
        verifyNoInteractions(novelDownloader, workMetaCaptureService);
    }

    @Test
    @DisplayName("同一任务的系列富信息含空结果只抓一次且轮末清理缓存")
    void cachesSeriesMetadataWithinRunAndClearsAtFinish() throws Exception {
        AtomicInteger seriesFetches = new AtomicInteger();
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if (uri.getPath().contains("/series/")) {
                seriesFetches.incrementAndGet();
                return "{\"error\":true,\"message\":\"temporary restriction\"}";
            }
            return completeNovelResponse();
        });
        when(novelDownloader.downloadBlocking(any(), isNull())).thenReturn(true);
        ScheduledTaskDefinition sharedTask = task(definition(false, false));
        ContextFixture first = context(sharedTask, ScheduledNetworkRoute.direct(), null);
        ContextFixture second = context(sharedTask, ScheduledNetworkRoute.direct(), null);
        PixivScheduledNovelWorkExecutor executor = executor();

        executor.execute(work("123"), first.context());
        executor.execute(work("124"), second.context());

        assertThat(seriesFetches).hasValue(1);

        ScheduledWorkRunContext finish = runContext(2L, sharedTask);
        executor.finishRun(finish);
        ContextFixture nextRun = context(definition(false, false), ScheduledNetworkRoute.direct(), null);
        executor.execute(work("125"), nextRun.context());

        assertThat(seriesFetches).hasValue(2);
    }

    @Test
    @DisplayName("异常结束未调用轮末时新任务定义身份会丢弃上一轮系列缓存")
    void freshDefinitionIdentityReplacesAbandonedRunCache() throws Exception {
        AtomicInteger seriesFetches = new AtomicInteger();
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if (uri.getPath().contains("/series/")) {
                seriesFetches.incrementAndGet();
                return completeSeriesResponse();
            }
            return completeNovelResponse();
        });
        when(novelDownloader.downloadBlocking(any(), isNull())).thenReturn(true);
        PixivScheduledNovelWorkExecutor executor = executor();

        executor.execute(
                work("123"),
                context(task(definition(false, false)), ScheduledNetworkRoute.direct(), null).context());
        executor.execute(
                work("124"),
                context(task(definition(false, false)), ScheduledNetworkRoute.direct(), null).context());

        assertThat(seriesFetches).hasValue(2);
    }

    @Test
    @DisplayName("异常终止清理后同一任务定义也会重新抓取系列信息")
    void abortRunClearsSuccessfulWorkSeriesCache() throws Exception {
        AtomicInteger seriesFetches = new AtomicInteger();
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if (uri.getPath().contains("/series/")) {
                seriesFetches.incrementAndGet();
                return completeSeriesResponse();
            }
            return completeNovelResponse();
        });
        when(novelDownloader.downloadBlocking(any(), isNull())).thenReturn(true);
        ScheduledTaskDefinition task = task(definition(false, false));
        PixivScheduledNovelWorkExecutor executor = executor();

        executor.execute(
                work("123"), context(task, ScheduledNetworkRoute.direct(), null).context());
        executor.abortRun(task);
        executor.execute(
                work("124"), context(task, ScheduledNetworkRoute.direct(), null).context());

        assertThat(seriesFetches).hasValue(2);
    }

    @Test
    @DisplayName("HTTP 不存在与其余客户端服务端错误保持既有失败分类")
    void classifiesNotFoundCredentialAndRetryableFailures() {
        ContextFixture fixture = context(definition(false, false), ScheduledNetworkRoute.direct(), null);

        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenThrow(
                HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        assertFailureCategory(fixture.context(), ScheduledFailure.Category.NOT_FOUND);

        reset(pixivAjaxProxyClient);
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenThrow(
                HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN, "", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        assertFailureCategory(fixture.context(), ScheduledFailure.Category.NOT_FOUND);

        reset(pixivAjaxProxyClient);
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenThrow(
                HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        assertFailureCategory(fixture.context(), ScheduledFailure.Category.RETRYABLE_NETWORK);

        reset(pixivAjaxProxyClient);
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenThrow(
                HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8));
        assertFailureCategory(fixture.context(), ScheduledFailure.Category.RETRYABLE_NETWORK);

        reset(pixivAjaxProxyClient);
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenThrow(
                HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8));
        assertFailureCategory(fixture.context(), ScheduledFailure.Category.RETRYABLE_NETWORK);

        reset(pixivAjaxProxyClient);
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenThrow(
                HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "", HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8));
        assertFailureCategory(fixture.context(), ScheduledFailure.Category.RETRYABLE_NETWORK);

        reset(pixivAjaxProxyClient);
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull()))
                .thenThrow(new ResourceAccessException("timeout"));
        assertFailureCategory(fixture.context(), ScheduledFailure.Category.RETRYABLE_NETWORK);
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("阻塞下载返回 false 时进入可重试失败且不捕获 sidecar")
    void falseDownloadResultIsRetryableWithoutSidecar() {
        ContextFixture fixture = context(definition(false, false), ScheduledNetworkRoute.direct(), null);
        when(pixivAjaxProxyClient.proxyGetUri(any(URI.class), isNull())).thenReturn(minimalNovelResponse());
        when(novelDownloader.downloadBlocking(any(), isNull())).thenReturn(false);

        assertFailureCategory(fixture.context(), ScheduledFailure.Category.RETRYABLE_NETWORK);

        verifyNoInteractions(workMetaCaptureService);
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("轮末仅在有新完成作品且开启系列合订时 best-effort 合订一次")
    void finishRunMergesOnceBestEffort() throws Exception {
        ScheduledWorkRunContext completed = runContext(1L);
        when(novelMergeService.merge(42L, NovelDownloadService.NovelFormat.HTML))
                .thenThrow(new IOException("write failed"));

        assertThatCode(() -> executor().finishRun(completed)).doesNotThrowAnyException();

        verify(novelMergeService).merge(42L, NovelDownloadService.NovelFormat.HTML);

        ScheduledWorkRunContext noNewWork = runContext(0L);
        executor().finishRun(noNewWork);
        verify(novelMergeService, times(1)).merge(eq(42L), eq(NovelDownloadService.NovelFormat.HTML));
    }

    @Test
    @DisplayName("轮末不会因非系列来源夹带 seriesId 而误触发合订")
    void finishRunDoesNotMergeForNonSeriesSource() throws Exception {
        ScheduledTaskDefinition searchTask = task("search", definition(false, true));

        executor().finishRun(runContext(1L, searchTask));

        verifyNoInteractions(novelMergeService);
    }

    @Test
    @DisplayName("状态只投影翻译阶段耗时和系列等待数")
    void exposesOnlySafeTranslationStatus() {
        when(novelAutoTranslateService.getStatus(123L)).thenReturn(
                new NovelAutoTranslateService.StatusView(
                        "WAITING_SERIES", 12L, 3, "en", false, false, "sensitive detail"));

        Map<String, String> status = executor().status(new ScheduledWorkKey("novel", "123"));

        assertThat(status).containsExactlyInAnyOrderEntriesOf(Map.of(
                "phase", "WAITING_SERIES",
                "elapsedSeconds", "12",
                "seriesPending", "3"));
        assertThat(status).hasSize(3);

        when(novelAutoTranslateService.getStatus(124L)).thenReturn(null);
        assertThat(executor().status(new ScheduledWorkKey("novel", "124"))).isEmpty();
        assertThat(executor().status(new ScheduledWorkKey("novel", "0124"))).isEmpty();
    }

    private void assertFailureCategory(
            ScheduledWorkContext context,
            ScheduledFailure.Category expectedCategory) {
        ScheduledExecutionException failure = catchThrowableOfType(
                () -> executor().execute(work("123"), context),
                ScheduledExecutionException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.category()).isEqualTo(expectedCategory);
    }

    private PixivScheduledNovelWorkExecutor executor() {
        return new PixivScheduledNovelWorkExecutor(
                objectMapper,
                pixivAjaxProxyClient,
                novelMetadataRepository,
                workMetaCaptureService,
                novelDownloader,
                novelMergeService,
                novelAutoTranslateService,
                mock(DownloadSettings.class));
    }

    private ContextFixture context(String definitionJson, ScheduledNetworkRoute route, char[] secret) {
        return context(task(definitionJson), route, secret);
    }

    private ContextFixture context(
            ScheduledTaskDefinition task,
            ScheduledNetworkRoute route,
            char[] secret) {
        ScheduledCredentialHandle credential = org.mockito.Mockito.mock(ScheduledCredentialHandle.class);
        if (secret != null) {
            lenient().when(credential.isPresent()).thenReturn(true);
            lenient().when(credential.copySecret()).thenReturn(secret);
        }
        ScheduledWorkContext context = org.mockito.Mockito.mock(ScheduledWorkContext.class);
        when(context.task()).thenReturn(task);
        lenient().when(context.route()).thenReturn(route);
        lenient().when(context.credential()).thenReturn(credential);
        lenient().when(context.cancellation()).thenReturn((ScheduledCancellation) () -> false);
        return new ContextFixture(context, credential);
    }

    private ScheduledWorkRunContext runContext(long completedWorkCount) {
        return runContext(completedWorkCount, completeDefinition());
    }

    private ScheduledWorkRunContext runContext(long completedWorkCount, String definitionJson) {
        return runContext(completedWorkCount, task(definitionJson));
    }

    private ScheduledWorkRunContext runContext(
            long completedWorkCount,
            ScheduledTaskDefinition task) {
        ScheduledWorkRunContext context = org.mockito.Mockito.mock(ScheduledWorkRunContext.class);
        when(context.workType()).thenReturn("novel");
        when(context.statistics()).thenReturn(new ScheduledWorkRunStatistics(
                completedWorkCount, completedWorkCount, 0L, 0L, 0L));
        lenient().when(context.task()).thenReturn(task);
        lenient().when(context.cancellation()).thenReturn(() -> false);
        return context;
    }

    private ScheduledTaskDefinition task(String definitionJson) {
        return task("series", definitionJson);
    }

    private ScheduledTaskDefinition task(String sourceType, String definitionJson) {
        return new ScheduledTaskDefinition(
                7L,
                sourceType,
                PixivScheduledNovelDefinition.SCHEMA,
                PixivScheduledNovelDefinition.VERSION,
                definitionJson,
                ScheduledTaskPresentation.empty());
    }

    private static ScheduledWork work(String id) {
        return work(id, "{\"workId\":\"" + id + "\"}");
    }

    private static ScheduledWork work(String id, String payload) {
        return new ScheduledWork(
                new ScheduledWorkKey("novel", id),
                PixivScheduledNovelWorkExecutor.WORK_PAYLOAD_SCHEMA,
                PixivScheduledNovelWorkExecutor.WORK_PAYLOAD_VERSION,
                payload,
                ScheduledWorkPresentation.empty(),
                List.of());
    }

    private static String definition(boolean redownloadDeleted, boolean merge) {
        return """
                {"kind":"novel","source":{"seriesId":"42"},"filters":{},
                 "download":{"redownloadDeleted":%s,"novelMerge":%s,"novelMergeFormat":"html"}}
                """.formatted(redownloadDeleted, merge);
    }

    private static String completeDefinition() {
        return """
                {
                  "kind":"novel",
                  "source":{"seriesId":"42"},
                  "filters":{
                    "content":"r18",
                    "aiFilter":"only",
                    "tagsExact":[" orig "],
                    "tagsFuzzy":["slat"],
                    "wordsMin":"1000",
                    "wordsMax":2000,
                    "bookmarksMin":10,
                    "bookmarksMax":"100"
                  },
                  "download":{
                    "fileNameTemplate":"{title}",
                    "bookmark":true,
                    "collectionId":"88",
                    "redownloadDeleted":false,
                    "novelFormat":"epub",
                    "novelMerge":true,
                    "novelMergeFormat":"html",
                    "novelAutoTranslate":true,
                    "novelTranslateLanguage":"english",
                    "novelTranslateSegmentSize":"35"
                  }
                }
                """;
    }

    private static String minimalNovelResponse() {
        return """
                {"error":false,"body":{"title":"t","content":"c","userId":"9"}}
                """;
    }

    private static String completeNovelResponse() {
        return """
                {
                  "error":false,
                  "body":{
                    "title":"标题",
                    "xRestrict":1,
                    "aiType":2,
                    "bookmarkCount":99,
                    "userId":"9",
                    "userName":"作者",
                    "description":"<a href=\\\"/users/9\\\">简介</a>",
                    "tags":{"tags":[{"tag":"Orig","translation":{"en":"Translated"}}]},
                    "seriesNavData":{"seriesId":"42","order":7,"title":"系列标题"},
                    "content":"正文[newpage]第二页",
                    "wordCount":1234,
                    "characterCount":5678,
                    "readingTime":"90 sec",
                    "isOriginal":true,
                    "language":"ja",
                    "imageUrls":{"original":"https://i.pximg.net/c/600x600/novel-cover-master/x.jpg"},
                    "uploadTimestamp":1700000000,
                    "textEmbeddedImages":{
                      "img":{"urls":{"original":"https://i.pximg.net/img-original/img.jpg"}},
                      "bad":{"urls":{"original":"https://example.com/not-allowed.jpg"}}
                    }
                  }
                }
                """;
    }

    private static String completeSeriesResponse() {
        return """
                {
                  "error":false,
                  "body":{
                    "caption":"系列简介",
                    "cover":{"urls":{"original":"https://i.pximg.net/series-cover.jpg"}},
                    "tags":{"tags":[
                      {"tag":"SeriesTag","translation":{"en":"Series translated"}}
                    ]}
                  }
                }
                """;
    }

    private record ContextFixture(
            ScheduledWorkContext context,
            ScheduledCredentialHandle credential) {
    }
}
