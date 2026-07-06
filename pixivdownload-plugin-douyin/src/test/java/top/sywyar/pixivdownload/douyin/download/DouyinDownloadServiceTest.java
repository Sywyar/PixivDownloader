package top.sywyar.pixivdownload.douyin.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryRepository;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadPhase;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadRequest;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadSnapshot;
import top.sywyar.pixivdownload.douyin.model.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DouyinDownloadService 抖音下载服务")
class DouyinDownloadServiceTest {

    private static final String VALID_COOKIE =
            "ttwid=tt; passport_csrf_token=csrf; sessionid=sid; sid_tt=sid; sid_guard=guard";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("公开视频元数据解析后写入插件私有下载目录")
    void downloadsPublicWorkWithMockClient() throws Exception {
        FakeClient client = new FakeClient();
        DouyinDownloadService service = service(client, Runnable::run);

        var response = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");
        DouyinDownloadSnapshot status = service.status(response.id()).orElseThrow();

        assertThat(response.workId()).isEqualTo("7351234567890123456");
        assertThat(response.id()).isNotEqualTo("d7351234567890123456");
        assertThat(status.phase()).isEqualTo(DouyinDownloadPhase.COMPLETED);
        assertThat(status.completed()).isTrue();
        assertThat(status.fileName()).isEqualTo("7351234567890123456.mp4");
        assertThat(Files.readString(client.downloader.lastTarget, StandardCharsets.UTF_8)).isEqualTo("video-bytes");
        assertThat(client.downloader.lastCookie).isEqualTo(VALID_COOKIE);
        assertThat(client.downloader.lastDirectory.normalize().startsWith(tempDir.resolve("owner-a").normalize())).isTrue();
    }

    @Test
    @DisplayName("短链启动返回稳定 aweme_id 而不是短链 code")
    void shortLinkStartReturnsResolvedAwemeId() throws Exception {
        FakeClient client = new FakeClient();
        client.mapSingle("XUyPmdu7naU", "7351234567890123456");
        DouyinDownloadService service = service(client, Runnable::run);

        var response = service.start(new DouyinDownloadRequest("https://v.douyin.com/XUyPmdu7naU/", "", VALID_COOKIE),
                "owner-a");

        assertThat(response.workId()).isEqualTo("7351234567890123456");
        assertThat(response.workId()).doesNotContain("XUyPmdu7naU");
        assertThat(service.status(response.id()).orElseThrow().workId()).isEqualTo("7351234567890123456");
    }

    @Test
    @DisplayName("普通链接和短链解析到同一 aweme_id 时共享同一状态")
    void normalAndShortUrlShareSameRunningStatus() throws Exception {
        FakeClient client = new FakeClient();
        client.mapSingle("ShortSame", "10001");
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);

        var first = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");
        var second = service.start(new DouyinDownloadRequest("https://v.douyin.com/ShortSame/", "", VALID_COOKIE),
                "owner-b");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.workId()).isEqualTo("10001");
        executor.runAll();
        assertThat(client.downloader.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("两个 owner 同时提交同一 aweme_id 只触发一次媒体下载")
    void twoOwnersShareOneMediaDownload() throws Exception {
        FakeClient client = new FakeClient();
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);

        var first = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");
        var second = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-b");

        assertThat(second.id()).isEqualTo(first.id());
        executor.runAll();
        assertThat(client.downloader.calls).isEqualTo(1);
        assertThat(service.status(first.id()).orElseThrow().phase()).isEqualTo(DouyinDownloadPhase.COMPLETED);
    }

    @Test
    @DisplayName("第二个参与者取消只移除参与关系，不取消底层下载")
    void participantCancelDoesNotCancelUnderlyingDownload() throws Exception {
        FakeClient client = new FakeClient();
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);
        DouyinQueueOperations queue = new DouyinQueueOperations(service);

        var first = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");
        service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-b");

        queue.cancel(10001L, "owner-b", false);

        assertThat(service.active("owner-b", false)).isEmpty();
        assertThat(service.active("owner-a", false)).extracting(DouyinDownloadSnapshot::id).containsExactly(first.id());
        executor.runAll();
        assertThat(client.downloader.calls).isEqualTo(1);
        assertThat(service.status(first.id()).orElseThrow().phase()).isEqualTo(DouyinDownloadPhase.COMPLETED);
    }

    @Test
    @DisplayName("发起者或管理员取消会取消底层下载")
    void initiatorOrAdminCancelCancelsUnderlyingDownload() throws Exception {
        FakeClient initiatorClient = new FakeClient();
        CapturingExecutor initiatorExecutor = new CapturingExecutor();
        DouyinDownloadService initiatorService = service(initiatorClient, initiatorExecutor);
        DouyinQueueOperations initiatorQueue = new DouyinQueueOperations(initiatorService);
        var initiatorResponse = initiatorService.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/10001", "", VALID_COOKIE), "owner-a");
        initiatorService.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-b");

        initiatorQueue.cancel(10001L, "owner-a", false);
        initiatorExecutor.runAll();

        assertThat(initiatorClient.downloader.calls).isZero();
        assertThat(initiatorService.status(initiatorResponse.id()).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.CANCELLED);

        FakeClient adminClient = new FakeClient();
        CapturingExecutor adminExecutor = new CapturingExecutor();
        DouyinDownloadService adminService = service(adminClient, adminExecutor);
        DouyinQueueOperations adminQueue = new DouyinQueueOperations(adminService);
        var adminResponse = adminService.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/10002", "", VALID_COOKIE), "owner-a");

        adminQueue.cancel(10002L, null, true);
        adminExecutor.runAll();

        assertThat(adminClient.downloader.calls).isZero();
        assertThat(adminService.status(adminResponse.id()).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.CANCELLED);
    }

    @Test
    @DisplayName("共享任务终态后从运行索引移除，再次提交创建新状态")
    void terminalJobIsRemovedFromRunningIndex() throws Exception {
        FakeClient client = new FakeClient();
        DouyinDownloadService service = service(client, Runnable::run);

        var first = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");
        var second = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-b");

        assertThat(service.status(first.id()).orElseThrow().phase()).isEqualTo(DouyinDownloadPhase.COMPLETED);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.workId()).isEqualTo("10001");
        assertThat(client.downloader.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("active 只向普通 owner 返回参与任务，管理员返回所有运行任务")
    void activeFiltersByParticipantsUnlessAdmin() throws Exception {
        FakeClient client = new FakeClient();
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);

        var shared = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");
        service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-b");
        var other = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10002", "", VALID_COOKIE),
                "owner-c");

        assertThat(service.active("owner-a", false)).extracting(DouyinDownloadSnapshot::id)
                .containsExactly(shared.id());
        assertThat(service.active("owner-b", false)).extracting(DouyinDownloadSnapshot::id)
                .containsExactly(shared.id());
        assertThat(service.active("owner-c", false)).extracting(DouyinDownloadSnapshot::id)
                .containsExactly(other.id());
        assertThat(service.active("owner-x", false)).isEmpty();
        assertThat(service.active(null, true)).extracting(DouyinDownloadSnapshot::id)
                .containsExactlyInAnyOrder(shared.id(), other.id());
    }

    @Test
    @DisplayName("共享状态快照不暴露归属、凭据、原始输入和真实本地目录")
    void snapshotDoesNotExposeSensitiveFields() throws Exception {
        FakeClient client = new FakeClient();
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);
        String originalInput = "https://www.douyin.com/video/10001?modal_id=temporary";

        var response = service.start(new DouyinDownloadRequest(originalInput, "", VALID_COOKIE),
                "owner-sensitive");
        DouyinDownloadSnapshot snapshot = service.status(response.id()).orElseThrow();

        assertThat(List.of(DouyinDownloadSnapshot.class.getRecordComponents())
                .stream()
                .map(RecordComponent::getName)
                .toList())
                .doesNotContain("ownerUuid", "initiatorOwnerUuid", "participants", "cookie",
                        "input", "originalInput", "downloadDirectory", "localPath", "folder");
        assertThat(snapshot.toString())
                .doesNotContain("owner-sensitive")
                .doesNotContain(VALID_COOKIE)
                .doesNotContain(originalInput)
                .doesNotContain(tempDir.toString());
    }

    @Test
    @DisplayName("合集下载使用稳定合集 ID，历史和命名使用接口返回的 aweme_id")
    void collectionUsesStableCollectionIdAndAwemeHistoryIds() throws Exception {
        FakeClient client = new FakeClient();
        client.mapCollection("MixShort", "mix123");
        client.seriesWorks = List.of(FakeClient.work("9001"));
        RecordingHistoryService history = recordingHistoryService();
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);

        var response = service.start(new DouyinDownloadRequest("https://v.douyin.com/MixShort/", "", VALID_COOKIE),
                "owner-a");

        assertThat(response.workId()).isEqualTo("mix123");
        assertThat(client.lastSeriesId).isEqualTo("mix123");
        assertThat(history.calls).isEqualTo(1);
        assertThat(history.work.id()).isEqualTo("9001");
        assertThat(history.collectionId).isEqualTo("mix123");
        assertThat(history.folder.getFileName().toString()).startsWith("9001-");
        assertThat(history.folder.toString()).doesNotContain("MixShort");
    }

    @Test
    @DisplayName("媒体下载失败时返回明确错误 key")
    void mediaFailureProducesStatusFailure() throws Exception {
        FakeClient client = new FakeClient();
        client.downloader.failure = DouyinClientErrorCode.NETWORK_ERROR;
        DouyinDownloadService service = service(client, Runnable::run);

        var response = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");
        DouyinDownloadSnapshot status = service.status(response.id()).orElseThrow();

        assertThat(status.phase()).isEqualTo(DouyinDownloadPhase.FAILED);
        assertThat(status.errorCode()).isEqualTo("NETWORK_ERROR");
        assertThat(status.messageKey()).isEqualTo("douyin.error.network-error");
    }

    @Test
    @DisplayName("规范化失败在启动阶段返回明确错误")
    void canonicalFailureIsRejectedBeforeQueueing() {
        FakeClient client = new FakeClient();
        client.resolveFailure = DouyinClientErrorCode.UNSUPPORTED_CONTENT;
        DouyinDownloadService service = service(client, Runnable::run);

        assertThatThrownBy(() -> service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a"))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.UNSUPPORTED_CONTENT);
    }

    @Test
    @DisplayName("短链解析失败在启动阶段保留明确错误")
    void shortLinkFailureKeepsExplicitMessageKey() {
        FakeClient client = new FakeClient();
        client.resolveFailure = DouyinClientErrorCode.SHORT_LINK_UNRESOLVED;
        DouyinDownloadService service = service(client, Runnable::run);

        assertThatThrownBy(() -> service.start(new DouyinDownloadRequest(
                "https://v.douyin.com/XUyPmdu7naU/", "", VALID_COOKIE), "owner-a"))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.SHORT_LINK_UNRESOLVED);
    }

    @Test
    @DisplayName("QueueOperations 支持清空全部、按 owner 清空与取消")
    void queueOperationsClearAndCancel() throws Exception {
        FakeClient client = new FakeClient();
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);
        DouyinQueueOperations queue = new DouyinQueueOperations(service);

        var first = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/10001", "first", VALID_COOKIE), "owner-a");
        var second = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/10002", "second", VALID_COOKIE), "owner-b");

        queue.cancel(10001L, "owner-a", false);
        executor.runAll();
        assertThat(service.status(first.id()).orElseThrow().phase()).isEqualTo(DouyinDownloadPhase.CANCELLED);

        assertThat(queue.clearForOwner("owner-b")).isEqualTo(1);
        assertThat(service.status(second.id())).isEmpty();

        service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10003", "third", VALID_COOKIE), "owner-a");
        assertThat(queue.clearAll()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("用户、合集与搜索入口通过可 mock client 表达")
    void acquisitionAdaptersDelegateToClient() throws Exception {
        FakeClient client = new FakeClient();
        DouyinDownloadService service = service(client, Runnable::run);

        assertThat(service.listUserWorks("u1", -10, 0, VALID_COOKIE).items()).hasSize(1);
        assertThat(service.listSeriesWorks("s1", 0, 500, VALID_COOKIE).pageSize()).isEqualTo(100);
        assertThat(service.searchPublic("word", 0, 1, VALID_COOKIE).ownerName()).isEqualTo("search:word");
        assertThat(service.quickPublic(0, 0, VALID_COOKIE).ownerName()).isEqualTo("search:");
    }

    @Test
    @DisplayName("下载按插件设置选择保存目录与代理运行时")
    void usesPluginRuntimeSettingsForDownloads() throws Exception {
        FakeClient inheritClient = new FakeClient();
        FakeClient proxyClient = new FakeClient();
        FakeClient directClient = new FakeClient();
        Path customDirectory = tempDir.resolve("custom-output");
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                inheritClient, proxyClient, directClient,
                inheritClient.downloader, proxyClient.downloader, directClient.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(customDirectory, DouyinProxyMode.DIRECT));

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");

        assertThat(directClient.downloader.lastTarget).isNotNull();
        assertThat(inheritClient.downloader.lastTarget).isNull();
        assertThat(proxyClient.downloader.lastTarget).isNull();
        assertThat(directClient.downloader.lastDirectory.normalize()
                .startsWith(customDirectory.resolve("owner-a").normalize())).isTrue();
    }

    @Test
    @DisplayName("自定义代理模式选择自定义运行时")
    void usesCustomProxyRuntimeForDownloads() throws Exception {
        FakeClient inheritClient = new FakeClient();
        FakeClient proxyClient = new FakeClient();
        FakeClient customClient = new FakeClient();
        FakeClient directClient = new FakeClient();
        Path customDirectory = tempDir.resolve("custom-proxy-output");
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                inheritClient, proxyClient, customClient, directClient,
                inheritClient.downloader, proxyClient.downloader, customClient.downloader, directClient.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(customDirectory, DouyinProxyMode.CUSTOM, "127.0.0.1", 10809),
                null);

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");

        assertThat(customClient.downloader.lastTarget).isNotNull();
        assertThat(inheritClient.downloader.lastTarget).isNull();
        assertThat(proxyClient.downloader.lastTarget).isNull();
        assertThat(directClient.downloader.lastTarget).isNull();
        assertThat(customClient.downloader.lastDirectory.normalize()
                .startsWith(customDirectory.resolve("owner-a").normalize())).isTrue();
    }

    @Test
    @DisplayName("下载启动前校验抖音 Cookie 必需字段")
    void validatesRequiredCookieFieldsBeforeStart() {
        DouyinDownloadService service = service(new FakeClient(), Runnable::run);

        assertThatThrownBy(() -> service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", null), "owner-a"))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.COOKIE_REQUIRED);

        assertThatThrownBy(() -> service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", "ttwid=tt; passport_csrf_token=csrf"), "owner-a"))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.COOKIE_MISSING_FIELDS);
    }

    @Test
    @DisplayName("单作品完整下载成功后写入下载历史")
    void recordsHistoryAfterSuccessfulSingleWorkDownload() throws Exception {
        FakeClient client = new FakeClient();
        RecordingHistoryService history = recordingHistoryService();
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");

        assertThat(history.calls).isEqualTo(1);
        assertThat(history.work.id()).isEqualTo("7351234567890123456");
        assertThat(history.folder.normalize().startsWith(tempDir.resolve("owner-a").normalize())).isTrue();
        assertThat(history.files).hasSize(1);
        assertThat(history.sourceUrl).isEqualTo("https://www.douyin.com/video/7351234567890123456");
    }

    @Test
    @DisplayName("单作品失败或取消时不写入下载历史")
    void doesNotRecordHistoryWhenSingleWorkFails() throws Exception {
        FakeClient client = new FakeClient();
        client.downloader.failure = DouyinClientErrorCode.NETWORK_ERROR;
        RecordingHistoryService history = recordingHistoryService();
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");

        assertThat(history.calls).isZero();
    }

    @Test
    @DisplayName("下载历史写入失败不改变媒体下载成功结果")
    void historyRecordFailureDoesNotFailSuccessfulDownload() throws Exception {
        FakeClient client = new FakeClient();
        RecordingHistoryService history = recordingHistoryService();
        history.throwOnRecord = true;
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);

        var response = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");
        DouyinDownloadSnapshot status = service.status(response.id()).orElseThrow();

        assertThat(status.phase()).isEqualTo(DouyinDownloadPhase.COMPLETED);
        assertThat(status.completed()).isTrue();
        assertThat(history.calls).isEqualTo(1);
        assertThat(Files.exists(client.downloader.lastTarget)).isTrue();
    }

    private DouyinDownloadService service(FakeClient client, TaskExecutor executor) {
        return new DouyinDownloadService(new DouyinUrlParser(), client, client.downloader, executor, tempDir);
    }

    private static RecordingHistoryService recordingHistoryService() {
        DouyinHistoryRepository repository = mock(DouyinHistoryRepository.class);
        when(repository.findMaxTime()).thenReturn(null);
        return new RecordingHistoryService(repository);
    }

    private static final class CapturingExecutor implements TaskExecutor {
        private final java.util.ArrayList<Runnable> tasks = new java.util.ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            List.copyOf(tasks).forEach(Runnable::run);
            tasks.clear();
        }
    }

    private static final class FakeClient implements DouyinClient {
        private final FakeMediaDownloader downloader = new FakeMediaDownloader();
        private final Map<String, String> singleStableIds = new LinkedHashMap<>();
        private final Map<String, String> collectionStableIds = new LinkedHashMap<>();
        private DouyinClientErrorCode resolveFailure;
        private List<DouyinWork> seriesWorks = List.of(work("s-default"));
        private String lastSeriesId;

        void mapSingle(String token, String stableId) {
            singleStableIds.put(token, stableId);
        }

        void mapCollection(String token, String stableId) {
            collectionStableIds.put(token, stableId);
        }

        @Override
        public DouyinCanonicalDownload resolveDownload(String input, String cookie) throws DouyinClientException {
            if (resolveFailure != null) {
                throw new DouyinClientException(resolveFailure, resolveFailure.name());
            }
            DouyinParsedInput parsed = resolveInput(input, cookie);
            String collectionId = mapped(collectionStableIds, input);
            if (collectionId != null || parsed.kind() == DouyinParsedKind.COLLECTION) {
                String stableId = collectionId == null ? parsed.id() : collectionId;
                return new DouyinCanonicalDownload(DouyinCanonicalKind.COLLECTION, stableId,
                        "https://www.douyin.com/mix/" + stableId, null, input);
            }
            DouyinWork work = work(stableWorkId(input, parsed));
            return new DouyinCanonicalDownload(DouyinCanonicalKind.SINGLE_WORK, work.id(),
                    "https://www.douyin.com/video/" + work.id(), work, input);
        }

        @Override
        public DouyinParsedInput resolveInput(String input, String cookie) throws DouyinClientException {
            return new DouyinUrlParser().parse(input)
                    .orElseThrow(() -> new DouyinClientException(DouyinClientErrorCode.INVALID_URL, "invalid"));
        }

        @Override
        public DouyinWork resolvePublicWork(String input, String cookie) throws DouyinClientException {
            if (resolveFailure != null) {
                throw new DouyinClientException(resolveFailure, resolveFailure.name());
            }
            return work(stableWorkId(input, resolveInput(input, cookie)));
        }

        @Override
        public DouyinListing listUserWorks(String userId, int offset, int limit, String cookie) {
            return new DouyinListing(List.of(work("u-" + userId)), 1, 1, limit, true, null, userId, "user:" + userId);
        }

        @Override
        public DouyinListing listSeriesWorks(String seriesId, int page, int pageSize, String cookie) {
            lastSeriesId = seriesId;
            return new DouyinListing(seriesWorks, seriesWorks.size(), page, pageSize, true,
                    "series:" + seriesId, seriesId, "owner");
        }

        @Override
        public DouyinListing searchPublic(String word, int page, int pageSize, String cookie) {
            return new DouyinListing(List.of(work("q-" + word)), 1, page, pageSize, true,
                    null, null, "search:" + word);
        }

        private String stableWorkId(String input, DouyinParsedInput parsed) {
            String mapped = mapped(singleStableIds, input);
            if (mapped != null) {
                return mapped;
            }
            return parsed.kind().singleWork() ? parsed.id() : "7351234567890123456";
        }

        private static String mapped(Map<String, String> mappings, String input) {
            String value = input == null ? "" : input;
            return mappings.entrySet().stream()
                    .filter(entry -> value.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        private static DouyinWork work(String id) {
            return new DouyinWork(id, "Title " + id, "author", "Author", "https://www.douyin.com/video/" + id,
                    "", URI.create("https://media.example/" + id + ".mp4"));
        }
    }

    private static final class FakeMediaDownloader extends DouyinMediaDownloader {
        private Path lastDirectory;
        private Path lastTarget;
        private String lastCookie;
        private int calls;
        private DouyinClientErrorCode failure;

        private FakeMediaDownloader() {
            super(null, host -> true);
        }

        @Override
        public List<DouyinDownloadedFile> download(List<DouyinMedia> media,
                                                   Path directory,
                                                   String cookie,
                                                   BooleanSupplier cancellationRequested)
                throws java.io.IOException, DouyinClientException {
            if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
                throw new DouyinClientException(DouyinClientErrorCode.CANCELLED, "cancelled");
            }
            calls++;
            if (failure != null) {
                throw new DouyinClientException(failure, failure.name());
            }
            lastDirectory = directory;
            lastCookie = cookie;
            Files.createDirectories(directory);
            DouyinMedia first = media == null || media.isEmpty()
                    ? new DouyinMedia("fallback", DouyinMediaType.VIDEO,
                    URI.create("https://media.example/fallback.mp4"), "fallback", "mp4", null, null)
                    : media.get(0);
            lastTarget = directory.resolve(first.fileNameStem() + "." + first.extension());
            Files.writeString(lastTarget, "video-bytes", StandardCharsets.UTF_8);
            return List.of(new DouyinDownloadedFile(lastTarget, 11));
        }
    }

    private static final class RecordingHistoryService extends DouyinHistoryService {
        private int calls;
        private DouyinWork work;
        private Path folder;
        private List<DouyinDownloadedFile> files = List.of();
        private String sourceUrl;
        private String collectionId;
        private boolean throwOnRecord;

        private RecordingHistoryService(DouyinHistoryRepository repository) {
            super(repository);
        }

        @Override
        public boolean recordCompleted(DouyinWork work,
                                       Path folder,
                                       List<DouyinDownloadedFile> files,
                                       String sourceUrl,
                                       String collectionId,
                                       String collectionTitle,
                                       Integer collectionOrder) {
            this.calls++;
            if (throwOnRecord) {
                throw new IllegalStateException("history unavailable");
            }
            this.work = work;
            this.folder = folder;
            this.files = List.copyOf(files);
            this.sourceUrl = sourceUrl;
            this.collectionId = collectionId;
            return true;
        }
    }
}
