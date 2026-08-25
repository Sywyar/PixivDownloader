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
@DisplayName("Douyin 下载服务合集与队列")
class DouyinDownloadServiceCollectionTest extends DouyinDownloadServiceTestSupport {

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

        queue.cancel("10001", "owner-a", false);
        executor.runAll();
        assertThat(service.status(first.id(), "owner-a", false).orElseThrow().phase())
                .isEqualTo(DouyinDownloadPhase.CANCELLED);

        assertThat(queue.clearForOwner("owner-b")).isEqualTo(1);
        assertThat(service.status(second.id(), "owner-b", false)).isEmpty();

        service.start(new DouyinDownloadRequest("https://www.douyin.com/video/10003", "third", VALID_COOKIE), "owner-a");
        assertThat(queue.clearAll()).isGreaterThanOrEqualTo(1);
    }
}
