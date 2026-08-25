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
@DisplayName("Douyin 下载服务历史与文件复用")
class DouyinDownloadServiceHistoryTest extends DouyinDownloadServiceTestSupport {

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
}
