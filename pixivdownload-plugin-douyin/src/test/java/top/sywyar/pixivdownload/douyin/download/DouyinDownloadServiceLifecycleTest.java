package top.sywyar.pixivdownload.douyin.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.download.InteractiveDownloadExecutionLane;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryRepository;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinSourceRelation;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.download.DouyinDownloadPhase;
import top.sywyar.pixivdownload.douyin.model.download.DouyinDownloadRequest;
import top.sywyar.pixivdownload.douyin.model.download.DouyinDownloadSnapshot;
import top.sywyar.pixivdownload.douyin.model.listing.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.input.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.model.input.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWork;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.model.favorite.DouyinFavoriteFolderListing;
import top.sywyar.pixivdownload.douyin.model.favorite.DouyinFavoriteFolderSummary;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceRequest;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;

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
@DisplayName("Douyin 下载服务生命周期与所有权")
class DouyinDownloadServiceLifecycleTest extends DouyinDownloadServiceTestSupport {

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
                List.of(
                        new DouyinSourceRequest(
                                DouyinSourceTypes.SEARCH, "猫", "猫",
                                "https://www.douyin.com/search/猫", 3),
                        new DouyinSourceRequest(
                                DouyinSourceTypes.ACCOUNT_FAVORITE_FOLDER,
                                "folder-1", "收藏夹", null, 1))),
                "owner-a");
        var second = service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/" + workId, "", VALID_COOKIE,
                null, null, null, null, null, null, null,
                List.of(new DouyinSourceRequest(
                        DouyinSourceTypes.USER, "author-1", "Author",
                        "https://www.douyin.com/user/author-1", 8))),
                "owner-a");

        assertThat(second.id()).isEqualTo(first.id());
        executor.runAll();

        assertThat(client.downloader.calls).isEqualTo(1);
        assertThat(history.relations)
                .extracting(DouyinSourceRelation::sourceType, DouyinSourceRelation::sourceId,
                        DouyinSourceRelation::sourceOrder)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(DouyinSourceTypes.SEARCH, "猫", 3),
                        org.assertj.core.groups.Tuple.tuple(
                                DouyinSourceTypes.ACCOUNT_FAVORITE_FOLDER, "folder-1", 1),
                        org.assertj.core.groups.Tuple.tuple(DouyinSourceTypes.USER, "author-1", 8));
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
        InteractiveDownloadExecutionLane executor = task -> {
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

        queue.cancel("10001", "owner-b", false);

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

        initiatorQueue.cancel("10001", "owner-a", false);
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

        adminQueue.cancel("10002", null, true);
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
}
