package top.sywyar.pixivdownload.douyin.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.core.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.core.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryRepository;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinSourceRelation;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadPhase;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadRequest;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadSnapshot;
import top.sywyar.pixivdownload.douyin.model.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.model.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceRequest;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DouyinDownloadService 抖音下载服务")
class DouyinDownloadServiceTest {

    private static final String VALID_COOKIE =
            "ttwid=tt; passport_csrf_token=csrf; sessionid=sid; sid_tt=sid; sid_guard=guard";
    private static final byte[] DOWNLOADED_VIDEO_BYTES = {
            0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o'
    };
    private static final byte[] EXISTING_VIDEO_BYTES = {0, 0, 0, 0x18, 'f', 't', 'y', 'p'};
    private static final byte[] EXISTING_IMAGE_BYTES = {
            (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0, 0, 0
    };

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("同步解析期间 quiesce 必须等调用线程退出")
    void waitsForSynchronousResolveBeforeDraining() throws Exception {
        FakeClient client = new FakeClient();
        CountDownLatch resolveEntered = new CountDownLatch(1);
        CountDownLatch releaseResolve = new CountDownLatch(1);
        client.blockResolve(resolveEntered, releaseResolve);
        DouyinDownloadService service = service(client, Runnable::run);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                service.start(new DouyinDownloadRequest(
                        "https://www.douyin.com/video/10001", "", VALID_COOKIE), "owner-a");
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "douyin-resolve-drain-test");
        caller.setDaemon(true);
        caller.start();
        assertThat(resolveEntered.await(2, TimeUnit.SECONDS)).isTrue();

        QueueGenerationDrain drain = service.prepareQuiesceDownloads();
        service.cancelQuiescedDownloads();
        assertThat(drain.activeCount()).isEqualTo(1);
        assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20))).isFalse();

        releaseResolve.countDown();
        caller.join(2_000);
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(QueueNotAcceptingException.class);
        assertThat(drain.isDrained()).isTrue();
        assertThat(service.active("owner-a", false)).isEmpty();
    }

    @Test
    @DisplayName("父执行器拒绝 Douyin 提交时应回滚状态并归还 permit")
    void releasesPermitAndStatusWhenExecutorRejects() {
        FakeClient client = new FakeClient();
        RejectedExecutionException rejected = new RejectedExecutionException("full");
        DouyinDownloadService service = service(client, task -> { throw rejected; });

        assertThatThrownBy(() -> service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/10001", "", VALID_COOKIE), "owner-a"))
                .isSameAs(rejected);

        QueueGenerationDrain drain = service.prepareQuiesceDownloads();
        service.cancelQuiescedDownloads();
        assertThat(drain.isDrained()).isTrue();
        assertThat(service.active("owner-a", false)).isEmpty();
    }

    @Test
    @DisplayName("已入父队列但尚未运行的 Douyin 任务可无残留取消")
    void cancelsQueuedTaskWithoutRunningPluginDelegate() throws Exception {
        FakeClient client = new FakeClient();
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);

        var response = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/10001", "", VALID_COOKIE), "owner-a");
        QueueGenerationDrain drain = service.prepareQuiesceDownloads();
        service.cancelQuiescedDownloads();
        executor.runAll();

        assertThat(drain.isDrained()).isTrue();
        assertThat(service.status(response.id(), "owner-a", false)).isEmpty();
    }

    @Test
    @DisplayName("公开视频元数据解析后写入插件私有下载目录")
    void downloadsPublicWorkWithMockClient() throws Exception {
        FakeClient client = new FakeClient();
        DouyinDownloadService service = service(client, Runnable::run);

        var response = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");
        DouyinDownloadSnapshot status = service.status(response.id(), "owner-a", false).orElseThrow();

        assertThat(response.workId()).isEqualTo("7351234567890123456");
        assertThat(response.id()).isNotEqualTo("d7351234567890123456");
        assertThat(status.phase()).isEqualTo(DouyinDownloadPhase.COMPLETED);
        assertThat(status.completed()).isTrue();
        assertThat(status.fileName()).isEqualTo("7351234567890123456.mp4");
        assertThat(Files.readAllBytes(client.downloader.lastTarget)).containsExactly(DOWNLOADED_VIDEO_BYTES);
        assertThat(client.downloader.lastDirectory.normalize().startsWith(tempDir.resolve("owner-a").normalize())).isTrue();
        assertThat(client.downloader.lastCredential).isEqualTo(VALID_COOKIE);
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
        assertThat(service.status(response.id(), "owner-a", false).orElseThrow().workId())
                .isEqualTo("7351234567890123456");
    }

    @Test
    @DisplayName("同一 owner 的普通链接和短链解析到同一 aweme_id 时共享状态")
    void normalAndShortUrlShareSameRunningStatus() throws Exception {
        FakeClient client = new FakeClient();
        client.mapSingle("ShortSame", "10001");
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);

        var first = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");
        var second = service.start(new DouyinDownloadRequest("https://v.douyin.com/ShortSame/", "", VALID_COOKIE),
                "owner-a");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.workId()).isEqualTo("10001");
        executor.runAll();
        assertThat(client.downloader.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 owner 的运行中作品合并多个发现来源且只下载一次")
    void runningWorkMergesAllDiscoverySources() throws Exception {
        FakeClient client = new FakeClient();
        CapturingExecutor executor = new CapturingExecutor();
        RecordingHistoryService history = recordingHistoryService();
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                executor,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);
        String workId = "10001";

        var first = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/" + workId, "", VALID_COOKIE,
                null, null, null, null, null, null, null,
                List.of(new DouyinSourceRequest(
                        "douyin.search", "猫", "猫", "https://www.douyin.com/search/猫", 3))),
                "owner-a");
        var second = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/" + workId, "", VALID_COOKIE,
                null, null, null, null, null, null, null,
                List.of(new DouyinSourceRequest(
                        "douyin.user", "author-1", "Author", "https://www.douyin.com/user/author-1", 8))),
                "owner-a");

        assertThat(second.id()).isEqualTo(first.id());
        executor.runAll();

        assertThat(client.downloader.calls).isEqualTo(1);
        assertThat(history.relations)
                .extracting(DouyinSourceRelation::sourceType, DouyinSourceRelation::sourceId,
                        DouyinSourceRelation::sourceOrder)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("douyin.search", "猫", 3),
                        org.assertj.core.groups.Tuple.tuple("douyin.user", "author-1", 8));
    }

    @Test
    @DisplayName("来源分页序号只补充未声明顺序的关系")
    void generatedSourceOrderDoesNotOverwriteExplicitRelationOrder() throws Exception {
        FakeClient client = new FakeClient();
        String workId = "10001";
        client.seriesPageListing = new DouyinListing(
                List.of(FakeClient.work(workId)), 1, 1, 20, true,
                "合集", "12345", "作者", "", false);
        RecordingHistoryService history = recordingHistoryService();
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/mix/12345", "", VALID_COOKIE,
                null, null, null, null, null, null, null,
                List.of(
                        new DouyinSourceRequest("douyin.collection", "12345", "合集", null, null),
                        new DouyinSourceRequest("douyin.search", "猫", "猫", null, 8))),
                "owner-a");

        assertThat(history.relations)
                .extracting(DouyinSourceRelation::sourceType, DouyinSourceRelation::sourceOrder)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("douyin.collection", 0),
                        org.assertj.core.groups.Tuple.tuple("douyin.search", 8));
    }

    @Test
    @DisplayName("历史首次写入期间吸收的新来源会在成功终态前补写")
    void sourceAbsorbedDuringHistoryWriteIsFinalizedBeforeCompletion() throws Exception {
        FakeClient client = new FakeClient();
        RecordingHistoryService history = recordingHistoryService();
        CountDownLatch recordEntered = new CountDownLatch(1);
        CountDownLatch releaseRecord = new CountDownLatch(1);
        history.blockRecord(recordEntered, releaseRecord);
        AtomicReference<Thread> worker = new AtomicReference<>();
        TaskExecutor executor = task -> {
            Thread thread = new Thread(task, "douyin-history-finalize-test");
            thread.setDaemon(true);
            worker.set(thread);
            thread.start();
        };
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                executor,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);
        String workId = "10001";

        var first = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/" + workId, "", VALID_COOKIE,
                null, null, null, null, null, null, null,
                List.of(new DouyinSourceRequest("douyin.search", "猫", null, null, 3))), "owner-a");
        assertThat(recordEntered.await(2, TimeUnit.SECONDS)).isTrue();

        var second = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/" + workId, "", VALID_COOKIE,
                null, null, null, null, null, null, null,
                List.of(new DouyinSourceRequest("douyin.user", "author-1", null, null, 8))), "owner-a");
        releaseRecord.countDown();
        worker.get().join(2_000);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(worker.get().isAlive()).isFalse();
        assertThat(service.status(first.id(), "owner-a", false).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.COMPLETED);
        assertThat(history.relations)
                .extracting(DouyinSourceRelation::sourceType, DouyinSourceRelation::sourceId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("douyin.search", "猫"),
                        org.assertj.core.groups.Tuple.tuple("douyin.user", "author-1"));
    }

    @Test
    @DisplayName("不同 owner 同时提交同一 aweme_id 时状态与下载相互隔离")
    void twoOwnersUseIndependentDownloads() throws Exception {
        FakeClient client = new FakeClient();
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);

        var first = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");
        var second = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-b");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(service.status(first.id(), "owner-b", false)).isEmpty();
        assertThat(service.status(second.id(), "owner-a", false)).isEmpty();
        executor.runAll();
        assertThat(client.downloader.calls).isEqualTo(2);
        assertThat(service.status(first.id(), "owner-a", false).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.COMPLETED);
    }

    @Test
    @DisplayName("一个 owner 取消同作品任务不影响另一 owner 的下载")
    void ownerCancelDoesNotAffectAnotherOwner() throws Exception {
        FakeClient client = new FakeClient();
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);
        DouyinQueueOperations queue = new DouyinQueueOperations(service);

        var first = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");
        var second = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-b");

        queue.cancel(10001L, "owner-b", false);

        assertThat(service.active("owner-b", false)).isEmpty();
        assertThat(service.active("owner-a", false)).extracting(DouyinDownloadSnapshot::id).containsExactly(first.id());
        executor.runAll();
        assertThat(client.downloader.calls).isEqualTo(1);
        assertThat(service.status(first.id(), "owner-a", false).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.COMPLETED);
        assertThat(service.status(second.id(), "owner-b", false).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.CANCELLED);
    }

    @Test
    @DisplayName("owner 只能取消自身任务而管理员可取消任意任务")
    void initiatorOrAdminCancelCancelsUnderlyingDownload() throws Exception {
        FakeClient initiatorClient = new FakeClient();
        CapturingExecutor initiatorExecutor = new CapturingExecutor();
        DouyinDownloadService initiatorService = service(initiatorClient, initiatorExecutor);
        DouyinQueueOperations initiatorQueue = new DouyinQueueOperations(initiatorService);
        var initiatorResponse = initiatorService.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/10001", "", VALID_COOKIE), "owner-a");
        var otherOwnerResponse = initiatorService.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-b");

        initiatorQueue.cancel(10001L, "owner-a", false);
        initiatorExecutor.runAll();

        assertThat(initiatorClient.downloader.calls).isEqualTo(1);
        assertThat(initiatorService.status(initiatorResponse.id(), "owner-a", false).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.CANCELLED);
        assertThat(initiatorService.status(otherOwnerResponse.id(), "owner-b", false).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.COMPLETED);

        FakeClient adminClient = new FakeClient();
        CapturingExecutor adminExecutor = new CapturingExecutor();
        DouyinDownloadService adminService = service(adminClient, adminExecutor);
        DouyinQueueOperations adminQueue = new DouyinQueueOperations(adminService);
        var adminResponse = adminService.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/10002", "", VALID_COOKIE), "owner-a");

        adminQueue.cancel(10002L, null, true);
        adminExecutor.runAll();

        assertThat(adminClient.downloader.calls).isZero();
        assertThat(adminService.status(adminResponse.id(), null, true).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.CANCELLED);
    }

    @Test
    @DisplayName("同一 owner 的任务终态后从运行索引移除，再次提交创建新状态")
    void terminalJobIsRemovedFromRunningIndex() throws Exception {
        FakeClient client = new FakeClient();
        DouyinDownloadService service = service(client, Runnable::run);

        var first = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");
        var second = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");

        assertThat(service.status(first.id(), "owner-a", false).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.COMPLETED);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.workId()).isEqualTo("10001");
        assertThat(client.downloader.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("active 只向普通 owner 返回自身任务，管理员返回全部任务")
    void activeFiltersByOwnerUnlessAdmin() throws Exception {
        FakeClient client = new FakeClient();
        CapturingExecutor executor = new CapturingExecutor();
        DouyinDownloadService service = service(client, executor);

        var shared = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-a");
        var sameWorkOtherOwner = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/10001", "", VALID_COOKIE),
                "owner-b");
        var other = service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10002", "", VALID_COOKIE),
                "owner-c");

        assertThat(service.active("owner-a", false)).extracting(DouyinDownloadSnapshot::id)
                .containsExactly(shared.id());
        assertThat(service.active("owner-b", false)).extracting(DouyinDownloadSnapshot::id)
                .containsExactly(sameWorkOtherOwner.id());
        assertThat(service.active("owner-c", false)).extracting(DouyinDownloadSnapshot::id)
                .containsExactly(other.id());
        assertThat(service.active("owner-x", false)).isEmpty();
        assertThat(service.active(null, true)).extracting(DouyinDownloadSnapshot::id)
                .containsExactlyInAnyOrder(shared.id(), sameWorkOtherOwner.id(), other.id());
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
        DouyinDownloadSnapshot snapshot = service.status(response.id(), "owner-sensitive", false).orElseThrow();

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
    @DisplayName("合集下载遍历 20 加 20 加 5 个作品并跨页按作品 ID 去重")
    void collectionTraversesAllLogicalPagesAndDeduplicatesWorkIds() throws Exception {
        FakeClient client = new FakeClient();
        client.mapCollection("MixPaged", "mix-paged");
        client.seriesPages = List.of(
                works(1, 20, false),
                works(21, 20, false),
                concat(List.of(FakeClient.work("40")), works(41, 5, false)));
        DouyinDownloadService service = service(client, Runnable::run);

        service.start(new DouyinDownloadRequest("https://v.douyin.com/MixPaged/", "", VALID_COOKIE), "owner-a");

        assertThat(client.seriesListCalls).isEqualTo(3);
        assertThat(client.downloader.calls).isEqualTo(45);
    }

    @Test
    @DisplayName("合集下载越过只有重复作品的中间页继续读取新作品")
    void collectionContinuesPastDuplicateOnlyPageWhenCursorAdvances() throws Exception {
        FakeClient client = new FakeClient();
        client.mapCollection("MixStalled", "mix-stalled");
        client.seriesPages = List.of(
                List.of(FakeClient.work("1")),
                List.of(FakeClient.work("1")),
                List.of(FakeClient.work("2")));
        DouyinDownloadService service = service(client, Runnable::run);

        service.start(new DouyinDownloadRequest("https://v.douyin.com/MixStalled/", "", VALID_COOKIE), "owner-a");

        assertThat(client.seriesListCalls).isEqualTo(3);
        assertThat(client.downloader.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("合集超过 100 件仍继续按游标下载全部作品与媒体")
    void collectionBeyondOneHundredDownloadsAllWorksAndMedia() throws Exception {
        FakeClient client = new FakeClient();
        client.mapCollection("MixLarge", "mix-large");
        client.seriesPages = List.of(
                works(1, 20, true), works(21, 20, true), works(41, 20, true),
                works(61, 20, true), works(81, 20, true), works(101, 20, true));
        DouyinDownloadService service = service(client, Runnable::run);

        service.start(new DouyinDownloadRequest("https://v.douyin.com/MixLarge/", "", VALID_COOKIE), "owner-a");

        assertThat(client.seriesListCalls).isEqualTo(6);
        assertThat(client.downloader.calls).isEqualTo(120);
        assertThat(client.downloader.downloadedFiles).isEqualTo(240);
    }

    @Test
    @DisplayName("媒体下载失败时返回明确错误 key")
    void mediaFailureProducesStatusFailure() throws Exception {
        FakeClient client = new FakeClient();
        client.downloader.failure = DouyinClientErrorCode.NETWORK_ERROR;
        DouyinDownloadService service = service(client, Runnable::run);

        var response = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");
        DouyinDownloadSnapshot status = service.status(response.id(), "owner-a", false).orElseThrow();

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
        assertThat(service.status(first.id(), "owner-a", false).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.CANCELLED);

        assertThat(queue.clearForOwner("owner-b")).isEqualTo(1);
        assertThat(service.status(second.id(), "owner-b", false)).isEmpty();

        service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10003", "third", VALID_COOKIE), "owner-a");
        assertThat(queue.clearAll()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("用户、合集与搜索入口通过可 mock client 表达且不伪造空搜索")
    void acquisitionAdaptersDelegateToClient() throws Exception {
        FakeClient client = new FakeClient();
        DouyinDownloadService service = service(client, Runnable::run);

        assertThat(service.listUserWorks("u1", -10, 0, VALID_COOKIE).items()).hasSize(1);
        assertThat(service.listSeriesWorks("s1", 0, 500, VALID_COOKIE).pageSize()).isEqualTo(100);
        assertThat(service.searchPublic("word", 0, 1, VALID_COOKIE).ownerName()).isEqualTo("search:word");
    }

    @Test
    @DisplayName("合集作品游标页薄透传 Cookie 并限制页大小")
    void delegatesBoundedSeriesCursorPageToClient() throws Exception {
        FakeClient client = new FakeClient();
        client.seriesPageListing = new DouyinListing(List.of(FakeClient.work("s-page")),
                201, 1, 100, false, "合集", "series-1", "作者", "opaque-next", true);
        DouyinDownloadService service = service(client, Runnable::run);

        DouyinListing listing = service.listSeriesWorksPage("series-1", "opaque-current", 500, VALID_COOKIE);

        assertThat(listing.items()).extracting("id").containsExactly("s-page");
        assertThat(client.lastSeriesPageCursor).isEqualTo("opaque-current");
        assertThat(client.lastSeriesPageSize).isEqualTo(100);
        assertThat(client.lastSeriesPageCookie).isEqualTo(VALID_COOKIE);
    }

    @Test
    @DisplayName("账号全部作品越过游标前进的空页继续收集作品 ID")
    void allAccountWorkIdsContinuePastAdvancingEmptyPage() throws Exception {
        FakeClient client = new FakeClient();
        client.accountPages = List.of(
                new DouyinListing(List.of(), 2, 1, 50, false,
                        null, "account", "账号", "account-next", true),
                new DouyinListing(List.of(FakeClient.work("account-work")), 2, 2, 50, true,
                        null, "account", "账号", "", false));
        DouyinDownloadService service = service(client, Runnable::run);

        assertThat(service.listAllAccountWorkIds(DouyinAccountSource.OWN_WORKS, VALID_COOKIE))
                .containsExactly("account-work");
        assertThat(client.accountResolveCalls).isEqualTo(1);
        assertThat(client.accountPageCalls).isEqualTo(2);
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
    @DisplayName("封面设置开启时只把封面作为主媒体之外的可选附件")
    void optionalCoverIsDownloadedAsAttachment() throws Exception {
        FakeClient client = new FakeClient();
        client.thumbnailUrl = "https://p3.douyinpic.com/cover.webp";
        RecordingHistoryService history = recordingHistoryService();
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT, "", 0, true),
                history);

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");

        assertThat(client.downloader.downloadedFiles).isEqualTo(2);
        assertThat(history.work.media()).extracting(DouyinMedia::type)
                .containsExactly(DouyinMediaType.VIDEO, DouyinMediaType.COVER);
        assertThat(history.work.media().get(1).extension()).isEqualTo("webp");
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
    @DisplayName("已下载作品由新来源重复发现时只补关系而不重复下载文件")
    void existingDownloadAddsRelationWithoutDownloadingAgain() throws Exception {
        FakeClient client = new FakeClient();
        RecordingHistoryService history = recordingHistoryService();
        String workId = "7351234567890123456";
        Path folder = tempDir.resolve("existing").resolve(workId);
        Files.createDirectories(folder);
        Path file = folder.resolve(workId + ".mp4");
        Files.write(file, EXISTING_VIDEO_BYTES);
        history.existingRecord = new DouyinWorkRecord(
                workId, "Existing", folder.toString(), 1, "mp4", 1000L, false,
                DouyinWorkKind.VIDEO.name(), null, "https://www.douyin.com/video/" + workId,
                null, "author", "Author", null, null, null, null, null, null, null);
        history.existingFiles = List.of(new DouyinWorkFileRecord(
                workId, 0, workId, DouyinMediaType.VIDEO.name(), file.getFileName().toString(),
                "mp4", 8L, "video/mp4", 1000L));
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);

        var response = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/" + workId, "", VALID_COOKIE,
                null, null, "douyin.search", "猫", "猫", null, 4), "owner-a");

        assertThat(service.status(response.id(), "owner-a", false).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.COMPLETED);
        assertThat(client.downloader.calls).isZero();
        assertThat(history.relations).singleElement().satisfies(relation -> {
            assertThat(relation.sourceType()).isEqualTo("douyin.search");
            assertThat(relation.sourceId()).isEqualTo("猫");
            assertThat(relation.sourceOrder()).isEqualTo(4);
        });
    }

    @Test
    @DisplayName("关闭封面后不会复用仍包含旧封面的历史媒体组")
    void historyWithOldCoverIsNotReusedAfterCoverDisabled() throws Exception {
        FakeClient client = new FakeClient();
        RecordingHistoryService history = recordingHistoryService();
        String workId = "7351234567890123456";
        Path folder = tempDir.resolve("existing-cover").resolve(workId);
        Files.createDirectories(folder);
        Path video = folder.resolve(workId + ".mp4");
        Path cover = folder.resolve(workId + "-cover.webp");
        Files.write(video, EXISTING_VIDEO_BYTES);
        Files.write(cover, EXISTING_IMAGE_BYTES);
        history.existingRecord = new DouyinWorkRecord(
                workId, "Existing", folder.toString(), 2, "mp4,webp", 1000L, false,
                DouyinWorkKind.VIDEO.name(), null, "https://www.douyin.com/video/" + workId,
                null, "author", "Author", null, null, null, null, null, null, null);
        history.existingFiles = List.of(
                new DouyinWorkFileRecord(workId, 0, workId, DouyinMediaType.VIDEO.name(),
                        video.getFileName().toString(), "mp4", 8L, "video/mp4", 1000L),
                new DouyinWorkFileRecord(workId, 1, workId + "-cover", DouyinMediaType.COVER.name(),
                        cover.getFileName().toString(), "webp", 8L, "image/webp", 1000L));
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT, "", 0, false),
                history);

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/" + workId, "", VALID_COOKIE), "owner-a");

        assertThat(client.downloader.calls).isEqualTo(1);
        assertThat(client.downloader.downloadedFiles).isEqualTo(1);
        assertThat(history.calls).isEqualTo(1);
        assertThat(history.work.media()).extracting(DouyinMedia::type)
                .containsExactly(DouyinMediaType.VIDEO);
    }

    @Test
    @DisplayName("旧历史缺少实况视频时重新下载完整媒体组")
    void incompleteLivePhotoHistoryIsNotReused() throws Exception {
        FakeClient client = new FakeClient();
        client.livePhoto = true;
        RecordingHistoryService history = recordingHistoryService();
        String workId = "7351234567890123456";
        Path folder = tempDir.resolve("existing-live").resolve(workId);
        Files.createDirectories(folder);
        Path image = folder.resolve(workId + "-image.jpg");
        Files.write(image, EXISTING_IMAGE_BYTES);
        history.existingRecord = new DouyinWorkRecord(
                workId, "Existing", folder.toString(), 1, "jpg", 1000L, false,
                DouyinWorkKind.LIVE_PHOTO.name(), null, "https://www.douyin.com/video/" + workId,
                null, "author", "Author", null, null, null, null, null, null, null);
        history.existingFiles = List.of(new DouyinWorkFileRecord(
                workId, 0, workId + "-image", DouyinMediaType.IMAGE.name(),
                image.getFileName().toString(), "jpg", 8L, "image/jpeg", 1000L));
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/" + workId, "", VALID_COOKIE), "owner-a");

        assertThat(client.downloader.calls).isEqualTo(1);
        assertThat(client.downloader.downloadedFiles).isEqualTo(2);
        assertThat(history.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("空的历史媒体文件不会被当作完整下载复用")
    void emptyHistoryFileIsNotReused() throws Exception {
        FakeClient client = new FakeClient();
        RecordingHistoryService history = recordingHistoryService();
        String workId = "7351234567890123456";
        Path folder = tempDir.resolve("empty-existing").resolve(workId);
        Files.createDirectories(folder);
        Path file = folder.resolve(workId + ".mp4");
        Files.createFile(file);
        history.existingRecord = new DouyinWorkRecord(
                workId, "Existing", folder.toString(), 1, "mp4", 1000L, false,
                DouyinWorkKind.VIDEO.name(), null, "https://www.douyin.com/video/" + workId,
                null, "author", "Author", null, null, null, null, null, null, null);
        history.existingFiles = List.of(new DouyinWorkFileRecord(
                workId, 0, workId, DouyinMediaType.VIDEO.name(), file.getFileName().toString(),
                "mp4", 0L, "video/mp4", 1000L));
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/" + workId, "", VALID_COOKIE), "owner-a");

        assertThat(client.downloader.calls).isEqualTo(1);
        assertThat(history.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("旧历史中的验证页载荷不会被当作媒体复用")
    void legacyVerificationPayloadIsNotReused() throws Exception {
        FakeClient client = new FakeClient();
        RecordingHistoryService history = recordingHistoryService();
        String workId = "7351234567890123456";
        Path folder = tempDir.resolve("verification-existing").resolve(workId);
        Files.createDirectories(folder);
        Path file = folder.resolve(workId + ".mp4");
        byte[] verificationPage = "<html>captcha verify</html>".getBytes(StandardCharsets.UTF_8);
        Files.write(file, verificationPage);
        history.existingRecord = new DouyinWorkRecord(
                workId, "Existing", folder.toString(), 1, "mp4", 1000L, false,
                DouyinWorkKind.VIDEO.name(), null, "https://www.douyin.com/video/" + workId,
                null, "author", "Author", null, null, null, null, null, null, null);
        history.existingFiles = List.of(new DouyinWorkFileRecord(
                workId, 0, workId, DouyinMediaType.VIDEO.name(), file.getFileName().toString(),
                "mp4", (long) verificationPage.length, "video/mp4", 1000L));
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                client, client, client,
                client.downloader, client.downloader, client.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(tempDir, DouyinProxyMode.INHERIT),
                history);

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/" + workId, "", VALID_COOKIE), "owner-a");

        assertThat(client.downloader.calls).isEqualTo(1);
        assertThat(history.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("下载历史或来源关系写入失败时任务不能标为完整成功")
    void historyRecordFailureFailsCompletedDownload() throws Exception {
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
        DouyinDownloadSnapshot status = service.status(response.id(), "owner-a", false).orElseThrow();

        assertThat(status.phase()).isEqualTo(DouyinDownloadPhase.FAILED);
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

    private static List<DouyinWork> works(int first, int count, boolean twoMedia) {
        List<DouyinWork> works = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            String id = Integer.toString(first + offset);
            works.add(twoMedia ? FakeClient.workWithTwoMedia(id) : FakeClient.work(id));
        }
        return List.copyOf(works);
    }

    private static List<DouyinWork> concat(List<DouyinWork> first, List<DouyinWork> second) {
        List<DouyinWork> combined = new ArrayList<>(first);
        combined.addAll(second);
        return List.copyOf(combined);
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
        private List<List<DouyinWork>> seriesPages;
        private int seriesListCalls;
        private String lastSeriesId;
        private DouyinListing seriesPageListing;
        private String lastSeriesPageCursor;
        private int lastSeriesPageSize;
        private String lastSeriesPageCookie;
        private List<DouyinListing> accountPages;
        private int accountResolveCalls;
        private int accountPageCalls;
        private CountDownLatch resolveEntered;
        private CountDownLatch releaseResolve;
        private String thumbnailUrl = "";
        private boolean livePhoto;

        void blockResolve(CountDownLatch entered, CountDownLatch release) {
            this.resolveEntered = entered;
            this.releaseResolve = release;
        }

        void mapSingle(String token, String stableId) {
            singleStableIds.put(token, stableId);
        }

        void mapCollection(String token, String stableId) {
            collectionStableIds.put(token, stableId);
        }

        @Override
        public DouyinCanonicalDownload resolveDownload(String input, String cookie) throws DouyinClientException {
            if (resolveEntered != null) {
                resolveEntered.countDown();
                try {
                    releaseResolve.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR, "interrupted");
                }
            }
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
            DouyinWork work = resolvedWork(stableWorkId(input, parsed));
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
            return resolvedWork(stableWorkId(input, resolveInput(input, cookie)));
        }

        @Override
        public DouyinListing listUserWorks(String userId, int offset, int limit, String cookie) {
            return new DouyinListing(List.of(work("u-" + userId)), 1, 1, limit, true, null, userId, "user:" + userId);
        }

        @Override
        public DouyinListing listSeriesWorks(String seriesId, int page, int pageSize, String cookie) {
            lastSeriesId = seriesId;
            seriesListCalls++;
            if (seriesPages != null) {
                List<DouyinWork> items = page > 0 && page <= seriesPages.size()
                        ? seriesPages.get(page - 1)
                        : List.of();
                boolean lastPage = page >= seriesPages.size();
                int total = lastPage ? seriesPages.stream().mapToInt(List::size).sum() : 0;
                return new DouyinListing(items, total, page, pageSize, lastPage,
                        "series:" + seriesId, seriesId, "owner");
            }
            return new DouyinListing(seriesWorks, seriesWorks.size(), page, pageSize, true,
                    "series:" + seriesId, seriesId, "owner");
        }

        @Override
        public DouyinListing listSeriesWorksPage(String seriesId, String cursor, int limit, String cookie)
                throws DouyinClientException {
            lastSeriesPageCursor = cursor;
            lastSeriesPageSize = limit;
            lastSeriesPageCookie = cookie;
            return seriesPageListing != null
                    ? seriesPageListing
                    : DouyinClient.super.listSeriesWorksPage(seriesId, cursor, limit, cookie);
        }

        @Override
        public DouyinListing searchPublic(String word, int page, int pageSize, String cookie) {
            return new DouyinListing(List.of(work("q-" + word)), 1, page, pageSize, true,
                    null, null, "search:" + word);
        }

        @Override
        public DouyinAccount resolveAccount(String cookie) {
            accountResolveCalls++;
            return new DouyinAccount("account", "sec-account", "账号", "account");
        }

        @Override
        public DouyinListing listAccountWorksPage(DouyinAccountSource source,
                                                  String cursor,
                                                  int limit,
                                                  String cookie) throws DouyinClientException {
            if (accountPages == null || accountPageCalls >= accountPages.size()) {
                return DouyinClient.super.listAccountWorksPage(source, cursor, limit, cookie);
            }
            return accountPages.get(accountPageCalls++);
        }

        private String stableWorkId(String input, DouyinParsedInput parsed) {
            String mapped = mapped(singleStableIds, input);
            if (mapped != null) {
                return mapped;
            }
            return parsed.kind().singleWork() ? parsed.id() : "7351234567890123456";
        }

        private DouyinWork resolvedWork(String id) {
            DouyinWork base = livePhoto ? workWithTwoMedia(id) : work(id);
            if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
                return base;
            }
            return new DouyinWork(base.id(), base.title(), base.description(), base.itemTitle(), base.caption(),
                    base.authorId(), base.authorName(), base.pageUrl(), thumbnailUrl, base.mediaUrl(),
                    base.media(), base.kind(), base.publishTimeEpochSeconds(), base.collectionId(),
                    base.collectionTitle());
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

        private static DouyinWork workWithTwoMedia(String id) {
            return new DouyinWork(id, "Title " + id, "author", "Author", "https://www.douyin.com/video/" + id,
                    "", null,
                    List.of(
                            new DouyinMedia(id + "-image", DouyinMediaType.IMAGE,
                                    URI.create("https://media.example/" + id + ".jpg"), id + "-image", "jpg", null, null),
                            new DouyinMedia(id + "-video", DouyinMediaType.LIVE_PHOTO_VIDEO,
                                    URI.create("https://media.example/" + id + ".mp4"), id + "-video", "mp4", null, null)),
                    DouyinWorkKind.LIVE_PHOTO,
                    null, null, null);
        }
    }

    private static final class FakeMediaDownloader extends DouyinMediaDownloader {
        private Path lastDirectory;
        private Path lastTarget;
        private int calls;
        private int downloadedFiles;
        private String lastCredential;
        private DouyinClientErrorCode failure;

        private FakeMediaDownloader() {
            super(null, host -> true);
        }

        @Override
        public List<DouyinDownloadedFile> download(List<DouyinMedia> media,
                                                   Path directory,
                                                   BooleanSupplier cancellationRequested,
                                                   String credential)
                throws java.io.IOException, DouyinClientException {
            if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
                throw new DouyinClientException(DouyinClientErrorCode.CANCELLED, "cancelled");
            }
            calls++;
            lastCredential = credential;
            if (failure != null) {
                throw new DouyinClientException(failure, failure.name());
            }
            lastDirectory = directory;
            Files.createDirectories(directory);
            List<DouyinMedia> candidates = media == null || media.isEmpty()
                    ? List.of(new DouyinMedia("fallback", DouyinMediaType.VIDEO,
                    URI.create("https://media.example/fallback.mp4"), "fallback", "mp4", null, null))
                    : media;
            List<DouyinDownloadedFile> downloaded = new ArrayList<>();
            for (DouyinMedia candidate : candidates) {
                lastTarget = directory.resolve(candidate.fileNameStem() + "." + candidate.extension());
                Files.write(lastTarget, DOWNLOADED_VIDEO_BYTES);
                String contentType = switch (candidate.type()) {
                    case IMAGE, COVER -> "image/" + candidate.extension();
                    default -> "video/mp4";
                };
                downloaded.add(new DouyinDownloadedFile(lastTarget, 11, contentType));
            }
            downloadedFiles += downloaded.size();
            return List.copyOf(downloaded);
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
        private CountDownLatch recordEntered;
        private CountDownLatch releaseRecord;
        private DouyinWorkRecord existingRecord;
        private List<DouyinWorkFileRecord> existingFiles = List.of();
        private final List<DouyinSourceRelation> relations = new ArrayList<>();

        private RecordingHistoryService(DouyinHistoryRepository repository) {
            super(repository);
        }

        private void blockRecord(CountDownLatch entered, CountDownLatch release) {
            this.recordEntered = entered;
            this.releaseRecord = release;
        }

        @Override
        public boolean recordCompleted(DouyinWork work,
                                       Path folder,
                                       List<DouyinDownloadedFile> files,
                                       String sourceUrl,
                                       String collectionId,
                                       String collectionTitle,
                                       Integer collectionOrder,
                                       DouyinSourceRelation relation) {
            return captureCompleted(work, folder, files, sourceUrl, collectionId,
                    relation == null ? List.of() : List.of(relation));
        }

        @Override
        public boolean recordCompleted(DouyinWork work,
                                       Path folder,
                                       List<DouyinDownloadedFile> files,
                                       String sourceUrl,
                                       String collectionId,
                                       String collectionTitle,
                                       Integer collectionOrder,
                                       List<DouyinSourceRelation> sourceRelations) {
            return captureCompleted(work, folder, files, sourceUrl, collectionId, sourceRelations);
        }

        private boolean captureCompleted(DouyinWork work,
                                         Path folder,
                                         List<DouyinDownloadedFile> files,
                                         String sourceUrl,
                                         String collectionId,
                                         List<DouyinSourceRelation> sourceRelations) {
            this.calls++;
            if (throwOnRecord) {
                throw new IllegalStateException("history unavailable");
            }
            if (recordEntered != null) {
                recordEntered.countDown();
                try {
                    releaseRecord.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("history recording interrupted", interrupted);
                }
            }
            this.work = work;
            this.folder = folder;
            this.files = List.copyOf(files);
            this.sourceUrl = sourceUrl;
            this.collectionId = collectionId;
            addRelations(sourceRelations);
            return true;
        }

        @Override
        public Optional<DouyinWorkRecord> findById(String workId) {
            return Optional.ofNullable(existingRecord);
        }

        @Override
        public List<DouyinWorkFileRecord> findFilesByWorkId(String workId) {
            return existingFiles;
        }

        @Override
        public boolean recordRelation(DouyinSourceRelation relation) {
            addRelations(List.of(relation));
            return true;
        }

        @Override
        public boolean recordRelations(String workId, List<DouyinSourceRelation> sourceRelations) {
            addRelations(sourceRelations);
            return true;
        }

        private void addRelations(List<DouyinSourceRelation> sourceRelations) {
            for (DouyinSourceRelation relation : sourceRelations) {
                relations.removeIf(existing -> existing.workId().equals(relation.workId())
                        && existing.sourceType().equals(relation.sourceType())
                        && existing.sourceId().equals(relation.sourceId()));
                relations.add(relation);
            }
        }
    }
}
