package top.sywyar.pixivdownload.douyin.schedule.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinSourceRelation;
import top.sywyar.pixivdownload.douyin.download.DouyinDownloadedFile;
import top.sywyar.pixivdownload.douyin.download.DouyinMediaDownloader;
import top.sywyar.pixivdownload.douyin.download.work.DouyinWorkDownloadExecutor;
import top.sywyar.pixivdownload.douyin.model.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.model.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("抖音计划作品同步执行")
class DouyinScheduledWorkExecutorTest {

    private static final String COOKIE =
            "ttwid=tt-value; passport_csrf_token=csrf-value; sessionid=session-value";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("同一代理与凭据覆盖解析媒体下载并在返回前写入来源关系")
    void executeCompletesFilesAndHistorySynchronously() throws Exception {
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        ScheduledWorkRelation relation = codec.createRelation(
                DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION,
                "collection-9", "收藏夹", 7);
        ScheduledWork scheduledWork = codec.createWork(
                "work-1", "计划作品", "作者", relation);
        ScheduledNetworkRoute route = ScheduledNetworkRoute.proxy("127.0.0.3", 7892, null);
        char[] copiedSecret = COOKIE.toCharArray();
        AtomicBoolean historyRecorded = new AtomicBoolean();

        DouyinClient client = mock(DouyinClient.class);
        when(client.resolvePublicWork("work-1", COOKIE)).thenAnswer(invocation -> {
            assertProxyScope("127.0.0.3", 7892);
            return work("work-1");
        });
        DouyinMediaDownloader mediaDownloader = mock(DouyinMediaDownloader.class);
        when(mediaDownloader.download(anyList(), any(Path.class), any(BooleanSupplier.class),
                eq(COOKIE))).thenAnswer(invocation -> {
            assertProxyScope("127.0.0.3", 7892);
            Path directory = invocation.getArgument(1);
            Files.createDirectories(directory);
            Path file = directory.resolve("work-1.mp4");
            Files.write(file, mp4Bytes());
            return List.of(new DouyinDownloadedFile(file, Files.size(file), "video/mp4"));
        });
        DouyinHistoryService history = mock(DouyinHistoryService.class);
        when(history.findById("work-1")).thenReturn(Optional.empty());
        when(history.recordCompleted(any(DouyinWork.class), any(Path.class), anyList(),
                eq("work-1"), eq("collection-9"), eq("收藏夹"), eq(7), anyList()))
                .thenAnswer(invocation -> {
                    assertProxyScope("127.0.0.3", 7892);
                    List<DouyinSourceRelation> relations = invocation.getArgument(7);
                    assertThat(relations).singleElement().satisfies(value -> {
                        assertThat(value.workId()).isEqualTo("work-1");
                        assertThat(value.sourceType())
                                .isEqualTo(DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION);
                        assertThat(value.sourceId()).isEqualTo("collection-9");
                        assertThat(value.sourceTitle()).isEqualTo("收藏夹");
                        assertThat(value.sourceUrl()).isNull();
                        assertThat(value.sourceOrder()).isEqualTo(7);
                    });
                    historyRecorded.set(true);
                    return true;
                });
        DouyinScheduledWorkExecutor executor = executor(
                client, mediaDownloader, history, codec, false);

        ScheduledWorkResult result = executor.execute(
                scheduledWork, context(route, copiedSecret, () -> false));

        assertThat(result.outcome()).isEqualTo(ScheduledWorkResult.Outcome.COMPLETED);
        assertThat(result.attributes()).containsEntry("fileCount", "1");
        assertThat(historyRecorded).isTrue();
        assertThat(Files.walk(tempDir).filter(Files::isRegularFile).toList())
                .singleElement().satisfies(file -> assertThat(file).hasFileName("work-1.mp4"));
        assertThat(copiedSecret).containsOnly('\0');
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("账号自建收藏夹只写来源关系而不投影为普通合集")
    void favoriteFolderPersistsRelationWithoutCollectionProjection() throws Exception {
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        ScheduledWorkRelation relation = codec.createRelation(
                DouyinSourceTypes.ACCOUNT_FAVORITE_FOLDER,
                "folder-3", "我创建的收藏夹", 4);
        ScheduledWork scheduledWork = codec.createWork(
                "work-folder", "计划作品", "作者", relation);

        DouyinClient client = mock(DouyinClient.class);
        when(client.resolvePublicWork("work-folder", COOKIE)).thenReturn(work("work-folder"));
        DouyinMediaDownloader mediaDownloader = downloaderWriting("work-folder.mp4");
        DouyinHistoryService history = mock(DouyinHistoryService.class);
        when(history.findById("work-folder")).thenReturn(Optional.empty());
        AtomicBoolean historyRecorded = new AtomicBoolean();
        when(history.recordCompleted(any(DouyinWork.class), any(Path.class), anyList(),
                eq("work-folder"), any(), any(), any(), anyList()))
                .thenAnswer(invocation -> {
                    String collectionId = invocation.getArgument(4);
                    String collectionTitle = invocation.getArgument(5);
                    Integer collectionOrder = invocation.getArgument(6);
                    assertThat(collectionId).isNull();
                    assertThat(collectionTitle).isNull();
                    assertThat(collectionOrder).isNull();
                    List<DouyinSourceRelation> relations = invocation.getArgument(7);
                    assertThat(relations).singleElement().satisfies(value -> {
                        assertThat(value.workId()).isEqualTo("work-folder");
                        assertThat(value.sourceType())
                                .isEqualTo(DouyinSourceTypes.ACCOUNT_FAVORITE_FOLDER);
                        assertThat(value.sourceId()).isEqualTo("folder-3");
                        assertThat(value.sourceTitle()).isEqualTo("我创建的收藏夹");
                        assertThat(value.sourceUrl()).isNull();
                        assertThat(value.sourceOrder()).isEqualTo(4);
                    });
                    historyRecorded.set(true);
                    return true;
                });
        DouyinScheduledWorkExecutor executor = executor(
                client, mediaDownloader, history, codec, false);

        ScheduledWorkResult result = executor.execute(
                scheduledWork,
                context(ScheduledNetworkRoute.direct(), COOKIE.toCharArray(), () -> false));

        assertThat(result.outcome()).isEqualTo(ScheduledWorkResult.Outcome.COMPLETED);
        assertThat(historyRecorded).isTrue();
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("历史写入失败必须返回内部失败而不能误报作品完成")
    void historyFailureDoesNotReturnSuccess() throws Exception {
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        ScheduledWork scheduledWork = codec.createWork(
                "work-2", "计划作品", "作者",
                codec.createRelation(DouyinSourceTypes.SEARCH, "关键词", "关键词", 0));
        DouyinClient client = mock(DouyinClient.class);
        when(client.resolvePublicWork("work-2", COOKIE)).thenReturn(work("work-2"));
        DouyinMediaDownloader mediaDownloader = downloaderWriting("work-2.mp4");
        DouyinHistoryService history = mock(DouyinHistoryService.class);
        when(history.findById("work-2")).thenReturn(Optional.empty());
        when(history.recordCompleted(any(DouyinWork.class), any(Path.class), anyList(),
                anyString(), any(), any(), any(), anyList())).thenReturn(false);
        DouyinScheduledWorkExecutor executor = executor(
                client, mediaDownloader, history, codec, false);

        assertThatThrownBy(() -> executor.execute(scheduledWork,
                context(ScheduledNetworkRoute.direct(), COOKIE.toCharArray(), () -> false)))
                .isInstanceOfSatisfying(ScheduledExecutionException.class, failure -> {
                    assertThat(failure.category()).isEqualTo(ScheduledFailure.Category.INTERNAL);
                    assertThat(failure.code())
                            .isEqualTo("douyin.schedule.work-execution-failed");
                });
        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }

    @Test
    @DisplayName("解析作品身份不一致时在下载与历史之前拒绝载荷")
    void workIdentityMismatchStopsBeforeDownload() throws Exception {
        DouyinScheduleCodec codec = new DouyinScheduleCodec(new ObjectMapper());
        ScheduledWork scheduledWork = codec.createWork(
                "expected", "计划作品", "作者",
                codec.createRelation(DouyinSourceTypes.USER, "user-1", "作者", 0));
        DouyinClient client = mock(DouyinClient.class);
        when(client.resolvePublicWork("expected", COOKIE)).thenReturn(work("different"));
        DouyinMediaDownloader mediaDownloader = mock(DouyinMediaDownloader.class);
        DouyinHistoryService history = mock(DouyinHistoryService.class);
        DouyinScheduledWorkExecutor executor = executor(
                client, mediaDownloader, history, codec, false);

        assertThatThrownBy(() -> executor.execute(scheduledWork,
                context(ScheduledNetworkRoute.direct(), COOKIE.toCharArray(), () -> false)))
                .isInstanceOfSatisfying(ScheduledExecutionException.class, failure ->
                        assertThat(failure.category())
                                .isEqualTo(ScheduledFailure.Category.PAYLOAD_UNSUPPORTED));
        verify(mediaDownloader, never()).download(
                anyList(), any(Path.class), any(BooleanSupplier.class), anyString());
        verify(history, never()).recordCompleted(any(DouyinWork.class), any(Path.class),
                anyList(), anyString(), any(), any(), any(), anyList());
    }

    private DouyinScheduledWorkExecutor executor(
            DouyinClient client,
            DouyinMediaDownloader mediaDownloader,
            DouyinHistoryService history,
            DouyinScheduleCodec codec,
            boolean includeCover) {
        DownloadConfig downloadConfig = new DownloadConfig();
        downloadConfig.setMaxConcurrent(3);
        return new DouyinScheduledWorkExecutor(
                client,
                mediaDownloader,
                new DouyinWorkDownloadExecutor(history),
                DouyinPluginSettingsService.fixed(
                        tempDir, DouyinProxyMode.INHERIT, "", 0, includeCover),
                codec,
                downloadConfig);
    }

    private DouyinMediaDownloader downloaderWriting(String fileName) throws Exception {
        DouyinMediaDownloader downloader = mock(DouyinMediaDownloader.class);
        when(downloader.download(anyList(), any(Path.class), any(BooleanSupplier.class),
                eq(COOKIE))).thenAnswer(invocation -> {
            Path directory = invocation.getArgument(1);
            Files.createDirectories(directory);
            Path file = directory.resolve(fileName);
            Files.write(file, mp4Bytes());
            return List.of(new DouyinDownloadedFile(file, Files.size(file), "video/mp4"));
        });
        return downloader;
    }

    private static ScheduledWorkContext context(
            ScheduledNetworkRoute route,
            char[] copiedSecret,
            BooleanSupplier cancellation) {
        ScheduledCredentialHandle handle = mock(ScheduledCredentialHandle.class);
        when(handle.isPresent()).thenReturn(true);
        when(handle.copySecret()).thenReturn(copiedSecret);
        ScheduledWorkContext context = mock(ScheduledWorkContext.class);
        when(context.route()).thenReturn(route);
        when(context.credential()).thenReturn(handle);
        when(context.cancellation()).thenReturn(cancellation::getAsBoolean);
        return context;
    }

    private static DouyinWork work(String id) {
        URI mediaUri = URI.create("https://v3.douyinvod.com/" + id + ".mp4");
        return new DouyinWork(
                id,
                "Title " + id,
                "Description",
                "Item title",
                "Caption",
                "author-1",
                "Author",
                "https://www.douyin.com/video/" + id,
                null,
                mediaUri,
                List.of(new DouyinMedia(id, DouyinMediaType.VIDEO,
                        mediaUri, id, "mp4", null, "video/mp4")),
                DouyinWorkKind.VIDEO,
                1_710_000_000L,
                null,
                null);
    }

    private static void assertProxyScope(String host, int port) {
        assertThat(OutboundProxyOverride.isActive()).isTrue();
        assertThat(OutboundProxyOverride.current()).isNotNull();
        assertThat(OutboundProxyOverride.current().getHostName()).isEqualTo(host);
        assertThat(OutboundProxyOverride.current().getPort()).isEqualTo(port);
    }

    private static byte[] mp4Bytes() {
        return new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                0, 0, 0, 0, 'i', 's', 'o', 'm', 'm', 'p', '4', '2'};
    }
}
