package top.sywyar.pixivdownload.download.schedule.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCaptureService;
import top.sywyar.pixivdownload.download.ArtworkDownloader;
import top.sywyar.pixivdownload.download.PixivFetchService;
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
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Pixiv 插画计划作品执行器")
class PixivScheduledIllustWorkExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PixivFetchService fetchService;
    @Mock
    private PixivDatabase pixivDatabase;
    @Mock
    private ArtworkDownloader artworkDownloader;
    @Mock
    private WorkMetaCaptureService workMetaCaptureService;
    @Mock
    private ScheduledIllustWorkRunner downloadRunner;

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
                        new ScheduledWorkKey("illust", "123"),
                        "other.schema",
                        1,
                        "{\"workId\":\"123\"}",
                        ScheduledWorkPresentation.empty(),
                        List.of()));
        ContextFixture fixture = context(task(), ScheduledNetworkRoute.direct());
        PixivScheduledIllustWorkExecutor executor = executor();

        for (ScheduledWork candidate : invalid) {
            ScheduledExecutionException failure = catchThrowableOfType(
                    () -> executor.execute(candidate, fixture.context()),
                    ScheduledExecutionException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.PAYLOAD_UNSUPPORTED);
            assertThat(failure.code()).isEqualTo("pixiv.illust.payload-invalid");
        }

        assertThat(fixture.credential().lastCopy()).isNull();
        verifyNoInteractions(fetchService, pixivDatabase, artworkDownloader,
                workMetaCaptureService, downloadRunner);
    }

    @ParameterizedTest(name = "HTTP {0} 归类为作品不存在")
    @ValueSource(ints = {403, 404})
    @DisplayName("HTTP 403 与 404 都归类为作品不存在")
    void classifiesForbiddenAndNotFoundAsMissing(int status) throws Exception {
        ContextFixture fixture = context(task(), ScheduledNetworkRoute.direct());
        when(fetchService.fetchArtworkMetaCapture(eq("123"), eq("PHPSESSID=8_secret")))
                .thenThrow(httpFailure(status));

        ScheduledExecutionException failure = catchThrowableOfType(
                () -> executor().execute(work("123"), fixture.context()),
                ScheduledExecutionException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.NOT_FOUND);
        assertThat(failure.code()).isEqualTo("pixiv.illust.gone");
        assertThat(fixture.credential().lastCopy()).containsOnly('\0');
        assertThat(OutboundProxyOverride.isActive()).isFalse();
        verifyNoInteractions(workMetaCaptureService, downloadRunner);
    }

    @ParameterizedTest(name = "HTTP {0} 归类为 {1}")
    @CsvSource({
            "401, RETRYABLE_NETWORK",
            "500, RETRYABLE_NETWORK"
    })
    @DisplayName("HTTP 401 与服务端错误都保持可重试且不触发凭证熔断")
    void classifiesUnauthorizedAndServerErrors(
            int status,
            ScheduledFailure.Category expectedCategory) throws Exception {
        ContextFixture fixture = context(task(), ScheduledNetworkRoute.direct());
        when(fetchService.fetchArtworkMetaCapture(eq("123"), eq("PHPSESSID=8_secret")))
                .thenThrow(httpFailure(status));

        ScheduledExecutionException failure = catchThrowableOfType(
                () -> executor().execute(work("123"), fixture.context()),
                ScheduledExecutionException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.category()).isEqualTo(expectedCategory);
        assertThat(failure.code()).isEqualTo("pixiv.illust.http-" + status);
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("Pixiv 拒绝响应与未知运行时异常分别归类为凭证失效和可重试网络失败")
    void classifiesPixivRejectionAndUnexpectedFailures() throws Exception {
        ContextFixture rejected = context(task(), ScheduledNetworkRoute.direct());
        when(fetchService.fetchArtworkMetaCapture(eq("123"), eq("PHPSESSID=8_secret")))
                .thenThrow(new PixivFetchService.PixivFetchException("denied"));

        ScheduledExecutionException credentialFailure = catchThrowableOfType(
                () -> executor().execute(work("123"), rejected.context()),
                ScheduledExecutionException.class);

        assertThat(credentialFailure).isNotNull();
        assertThat(credentialFailure.category()).isEqualTo(ScheduledFailure.Category.CREDENTIAL_INVALID);
        assertThat(credentialFailure.code()).isEqualTo("pixiv.illust.access-unavailable");

        reset(fetchService);
        ContextFixture unexpected = context(task(), ScheduledNetworkRoute.direct());
        when(fetchService.fetchArtworkMetaCapture(eq("124"), eq("PHPSESSID=8_secret")))
                .thenThrow(new IllegalStateException("network"));

        ScheduledExecutionException retryableFailure = catchThrowableOfType(
                () -> executor().execute(work("124"), unexpected.context()),
                ScheduledExecutionException.class);

        assertThat(retryableFailure).isNotNull();
        assertThat(retryableFailure.category()).isEqualTo(ScheduledFailure.Category.RETRYABLE_NETWORK);
        assertThat(retryableFailure.code()).isEqualTo("pixiv.illust.fetch-failed");
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("失败轮次立即清理系列缓存使下一轮重新抓取")
    void failedRunClearsSeriesCacheBeforeNextRun() throws Exception {
        stubSeriesArtwork();
        when(downloadRunner.download(any(), any(), eq("PHPSESSID=8_secret")))
                .thenReturn(false, true);
        ScheduledTaskDefinition task = task();
        PixivScheduledIllustWorkExecutor executor = executor();

        ScheduledExecutionException firstFailure = catchThrowableOfType(
                () -> executor.execute(
                        work("123"), context(task, ScheduledNetworkRoute.direct()).context()),
                ScheduledExecutionException.class);
        ScheduledWorkResult nextRun = executor.execute(
                work("124"), context(task, ScheduledNetworkRoute.direct()).context());

        assertThat(firstFailure).isNotNull();
        assertThat(firstFailure.category()).isEqualTo(ScheduledFailure.Category.RETRYABLE_NETWORK);
        assertThat(firstFailure.code()).isEqualTo("pixiv.illust.download-failed");
        assertThat(nextRun.outcome()).isEqualTo(ScheduledWorkResult.Outcome.COMPLETED);
        verify(fetchService, times(2))
                .fetchIllustSeriesMeta(42L, "PHPSESSID=8_secret");
    }

    @Test
    @DisplayName("成功轮内复用系列缓存且 finishRun 后下一轮重新抓取")
    void finishRunClearsSuccessfulRunSeriesCache() throws Exception {
        stubSeriesArtwork();
        when(downloadRunner.download(any(), any(), eq("PHPSESSID=8_secret")))
                .thenReturn(true);
        ScheduledTaskDefinition task = task();
        PixivScheduledIllustWorkExecutor executor = executor();

        executor.execute(work("123"), context(task, ScheduledNetworkRoute.direct()).context());
        executor.execute(work("124"), context(task, ScheduledNetworkRoute.direct()).context());
        verify(fetchService).fetchIllustSeriesMeta(42L, "PHPSESSID=8_secret");

        executor.finishRun(runContext(task));
        executor.execute(work("125"), context(task, ScheduledNetworkRoute.direct()).context());

        verify(fetchService, times(2))
                .fetchIllustSeriesMeta(42L, "PHPSESSID=8_secret");
    }

    @Test
    @DisplayName("异常终止清理后同一任务定义也会重新抓取系列信息")
    void abortRunClearsSuccessfulWorkSeriesCache() throws Exception {
        stubSeriesArtwork();
        when(downloadRunner.download(any(), any(), eq("PHPSESSID=8_secret")))
                .thenReturn(true);
        ScheduledTaskDefinition task = task();
        PixivScheduledIllustWorkExecutor executor = executor();

        executor.execute(work("123"), context(task, ScheduledNetworkRoute.direct()).context());
        executor.abortRun(task);
        executor.execute(work("124"), context(task, ScheduledNetworkRoute.direct()).context());

        verify(fetchService, times(2))
                .fetchIllustSeriesMeta(42L, "PHPSESSID=8_secret");
    }

    @Test
    @DisplayName("代理与凭证作用域覆盖抓取和下载并在执行结束后清理")
    void appliesProxyAndCookieScopeAndAlwaysCleansUp() throws Exception {
        ContextFixture fixture = context(
                task(), ScheduledNetworkRoute.proxy("proxy.local", 7890, null));
        JsonNode body = objectMapper.createObjectNode();
        when(fetchService.fetchArtworkMetaCapture(eq("123"), eq("PHPSESSID=8_secret")))
                .thenAnswer(invocation -> {
                    assertProxyScope();
                    return new PixivFetchService.ArtworkMetaCapture(meta(null), body);
                });
        when(fetchService.resolveArtworkPages(eq("123"), eq("PHPSESSID=8_secret")))
                .thenAnswer(invocation -> {
                    assertProxyScope();
                    return new PixivFetchService.ArtworkPages(
                            List.of("https://i.pximg.net/original.jpg"), body);
                });
        when(downloadRunner.download(any(), any(), eq("PHPSESSID=8_secret")))
                .thenAnswer(invocation -> {
                    assertProxyScope();
                    return true;
                });

        ScheduledWorkResult result = executor().execute(work("123"), fixture.context());

        assertThat(result.outcome()).isEqualTo(ScheduledWorkResult.Outcome.COMPLETED);
        assertThat(result.attributes()).containsEntry("title", "标题");
        assertThat(fixture.credential().lastCopy()).containsOnly('\0');
        assertThat(OutboundProxyOverride.isActive()).isFalse();
        assertThat(OutboundProxyOverride.current()).isNull();
        verify(workMetaCaptureService).captureArtwork(123L, body, body, "schedule");
    }

    private void stubSeriesArtwork() throws Exception {
        JsonNode body = objectMapper.createObjectNode();
        when(fetchService.fetchArtworkMetaCapture(anyString(), eq("PHPSESSID=8_secret")))
                .thenReturn(new PixivFetchService.ArtworkMetaCapture(meta(42L), body));
        when(fetchService.fetchIllustSeriesMeta(42L, "PHPSESSID=8_secret"))
                .thenReturn(new PixivFetchService.IllustSeriesMeta("系列简介", "https://cover"));
        when(fetchService.resolveArtworkPages(anyString(), eq("PHPSESSID=8_secret")))
                .thenReturn(new PixivFetchService.ArtworkPages(
                        List.of("https://i.pximg.net/original.jpg"), body));
    }

    private PixivScheduledIllustWorkExecutor executor() {
        return new PixivScheduledIllustWorkExecutor(
                fetchService,
                pixivDatabase,
                artworkDownloader,
                workMetaCaptureService,
                downloadRunner,
                new PixivSchedulePersistenceCodec(objectMapper),
                objectMapper,
                mock(DownloadSettings.class));
    }

    private ContextFixture context(
            ScheduledTaskDefinition task,
            ScheduledNetworkRoute route) {
        RecordingCredential credential = new RecordingCredential("PHPSESSID=8_secret");
        ScheduledWorkContext context = mock(ScheduledWorkContext.class);
        when(context.task()).thenReturn(task);
        lenient().when(context.route()).thenReturn(route);
        lenient().when(context.credential()).thenReturn(credential);
        lenient().when(context.cancellation()).thenReturn((ScheduledCancellation) () -> false);
        return new ContextFixture(context, credential);
    }

    private ScheduledWorkRunContext runContext(ScheduledTaskDefinition task) {
        ScheduledWorkRunContext context = mock(ScheduledWorkRunContext.class);
        when(context.task()).thenReturn(task);
        return context;
    }

    private ScheduledTaskDefinition task() {
        return new ScheduledTaskDefinition(
                7L,
                "series",
                PixivSchedulePersistenceCodec.DEFINITION_SCHEMA,
                PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                """
                        {"kind":"illust","source":{"seriesId":"42"},
                         "filters":{},"download":{}}
                        """,
                ScheduledTaskPresentation.empty());
    }

    private PixivFetchService.ArtworkMeta meta(Long seriesId) {
        return new PixivFetchService.ArtworkMeta(
                0,
                "标题",
                0,
                false,
                9L,
                "作者",
                seriesId,
                seriesId == null ? null : 1L,
                100,
                1,
                List.of(),
                "简介",
                seriesId == null ? null : "系列标题");
    }

    private static ScheduledWork work(String id) {
        return work(id, "{\"workId\":\"" + id + "\"}");
    }

    private static ScheduledWork work(String id, String payload) {
        return new ScheduledWork(
                new ScheduledWorkKey("illust", id),
                PixivSchedulePersistenceCodec.WORK_PAYLOAD_SCHEMA,
                PixivSchedulePersistenceCodec.WORK_PAYLOAD_VERSION,
                payload,
                ScheduledWorkPresentation.empty(),
                List.of());
    }

    private static HttpClientErrorException httpFailure(int status) {
        return HttpClientErrorException.create(
                HttpStatus.valueOf(status),
                "",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);
    }

    private static void assertProxyScope() {
        assertThat(OutboundProxyOverride.isActive()).isTrue();
        assertThat(OutboundProxyOverride.current()).isNotNull();
        assertThat(OutboundProxyOverride.current().getHostName()).isEqualTo("proxy.local");
        assertThat(OutboundProxyOverride.current().getPort()).isEqualTo(7890);
    }

    private record ContextFixture(
            ScheduledWorkContext context,
            RecordingCredential credential) {
    }

    private static final class RecordingCredential implements ScheduledCredentialHandle {
        private final String secret;
        private char[] lastCopy;

        private RecordingCredential(String secret) {
            this.secret = secret;
        }

        @Override
        public boolean isPresent() {
            return true;
        }

        @Override
        public String reference() {
            return "credential-reference";
        }

        @Override
        public String accountKey() {
            return "8";
        }

        @Override
        public char[] copySecret() {
            lastCopy = secret.toCharArray();
            return lastCopy;
        }

        @Override
        public void close() {
        }

        private char[] lastCopy() {
            return lastCopy;
        }
    }
}
