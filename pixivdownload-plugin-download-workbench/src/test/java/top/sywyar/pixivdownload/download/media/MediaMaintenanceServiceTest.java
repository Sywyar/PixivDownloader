package top.sywyar.pixivdownload.download.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.work.model.LocalWorkAsset;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkMetadata;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaMaintenanceServiceTest {
    @TempDir Path directory;

    @Test
    @DisplayName("外部只读作品不参与媒体写入，预览后所有权变化也拒绝处理")
    void externalFilesRemainReadOnly() throws Exception {
        Path source = directory.resolve("42_p0.jpg");
        Files.writeString(source, "original");
        var assets = mock(WorkAssetService.class);
        var metadata = mock(WorkMetadataRepository.class);
        var images = mock(ImageOutputService.class);
        var animations = mock(top.sywyar.pixivdownload.download.UgoiraService.class);
        var file = new WorkAssetFile(0, source, "jpg");
        when(metadata.find(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(mock(WorkMetadata.class)));
        when(assets.findAsset(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(new LocalWorkAsset(WorkType.ARTWORK, 42, directory, 1, List.of(file))));
        when(assets.rawFile(WorkType.ARTWORK, 42L, 0)).thenReturn(Optional.of(file));
        try (var service = new MediaMaintenanceService(assets, metadata, query(), images, animations, new MemoryMediaStore())) {
            when(assets.isReadOnly(WorkType.ARTWORK, 42L)).thenReturn(true);
            assertTrue(service.preview(new MediaMaintenanceService.Request("png", null, false)).files().isEmpty());
            when(assets.isReadOnly(WorkType.ARTWORK, 42L)).thenReturn(false);
            var preview = service.preview(new MediaMaintenanceService.Request("png", null, false));
            when(assets.isReadOnly(WorkType.ARTWORK, 42L)).thenReturn(true);
            service.start(preview.token());
            awaitFinished(service);
            assertEquals(1, service.status().failed());
            verifyNoInteractions(images, animations);
            assertEquals("original", Files.readString(source));
        }
    }

    @Test
    @DisplayName("只预览不处理，开始消费一次凭据，取消后保留输入")
    void explicitStartAndCancellation() throws Exception {
        Path source = directory.resolve("42_p0.jpg");
        Files.writeString(source, "original");
        var assets = mock(WorkAssetService.class);
        var metadata = mock(WorkMetadataRepository.class);
        var images = mock(ImageOutputService.class);
        var file = new WorkAssetFile(0, source, "jpg");
        when(metadata.find(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(mock(WorkMetadata.class)));
        when(assets.findAsset(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(new LocalWorkAsset(WorkType.ARTWORK, 42, directory, 1, List.of(file))));
        when(assets.rawFile(WorkType.ARTWORK, 42L, 0)).thenReturn(Optional.of(file));
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            java.util.function.BooleanSupplier cancelled = invocation.getArgument(4);
            while (!cancelled.getAsBoolean()) Thread.sleep(10);
            throw new java.util.concurrent.CancellationException();
        }).when(images).addMissingFormats(eq(42L), eq(0), eq(source), eq("png"), any());
        try (var service = new MediaMaintenanceService(assets, metadata, query(), images, mock(top.sywyar.pixivdownload.download.UgoiraService.class), new top.sywyar.pixivdownload.download.media.MemoryMediaStore())) {
            var desktop = new DesktopMediaMaintenance(service, mock(MediaCapabilityService.class));
            var rejected = assertThrows(DesktopMediaTool.OperationException.class, () -> desktop.start("unreviewed").valueOrThrow());
            assertEquals("media.error.preview-required", rejected.text().key());
            var preview = desktop.preview(new DesktopMediaTool.Request( "png", null, false)).valueOrThrow();
            assertEquals(1, preview.files().size());
            verifyNoInteractions(images);
            desktop.start(preview.token()).valueOrThrow();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(DesktopMediaTool.OperationException.class, () -> desktop.start(preview.token()).valueOrThrow());
            desktop.cancel();
            awaitFinished(service);
            assertEquals("cancelled", desktop.status().state());
            assertEquals("original", Files.readString(source));
        }
    }

    @Test
    @DisplayName("预览后源文件变化时拒绝转码并记录失败")
    void changedSourceIsRejected() throws Exception {
        Path source = directory.resolve("42_p0.jpg");
        Files.writeString(source, "before");
        var assets = mock(WorkAssetService.class);
        var metadata = mock(WorkMetadataRepository.class);
        var images = mock(ImageOutputService.class);
        var file = new WorkAssetFile(0, source, "jpg");
        when(metadata.find(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(mock(WorkMetadata.class)));
        when(assets.findAsset(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(new LocalWorkAsset(WorkType.ARTWORK, 42, directory, 1, List.of(file))));
        when(assets.rawFile(WorkType.ARTWORK, 42L, 0)).thenReturn(Optional.of(file));
        try (var service = new MediaMaintenanceService(assets, metadata, query(), images, mock(top.sywyar.pixivdownload.download.UgoiraService.class), new top.sywyar.pixivdownload.download.media.MemoryMediaStore())) {
            var preview = service.preview(new MediaMaintenanceService.Request( "png", null, false));
            Files.writeString(source, "new content");
            service.start(preview.token());
            awaitFinished(service);
            assertEquals(1, service.status().failed());
            verifyNoInteractions(images);
        }
    }

    @Test
    @DisplayName("自动分页检测只列出缺失格式或缩略图，已有文件和检测阶段均不发生写入")
    void detectsOnlyMissingFiles() throws Exception {
        var assets = mock(WorkAssetService.class);
        var query = mock(top.sywyar.pixivdownload.core.work.service.WorkQueryService.class);
        var images = mock(ImageOutputService.class);
        var animations = mock(top.sywyar.pixivdownload.download.UgoiraService.class);
        var metadata = mock(WorkMetadataRepository.class);
        for (int id = 1; id <= 4; id++) {
            Path source = directory.resolve(id + ".jpg");
            Files.writeString(source, "source");
            var file = new WorkAssetFile(0, source, "jpg");
            when(assets.findAsset(WorkType.ARTWORK, id)).thenReturn(Optional.of(
                    new LocalWorkAsset(WorkType.ARTWORK, id, directory, 1, List.of(file))));
            if (id != 3) when(assets.existingThumbnail(WorkType.ARTWORK, id, 0)).thenReturn(Optional.of(file));
            if (id != 2) Files.writeString(directory.resolve(id + ".png"), "existing");
        }
        when(query.search(any())).thenAnswer(call -> {
            var request = call.getArgument(0, top.sywyar.pixivdownload.core.work.query.WorkQuery.class);
            assertEquals("artworkId", request.sort());
            assertEquals("asc", request.order());
            assertNull(request.restriction());
            var ids = request.page() == 0 ? List.of(1L, 2L) : List.of(3L, 4L);
            return new top.sywyar.pixivdownload.core.work.model.PagedResult<>(
                    ids.stream().map(id -> new top.sywyar.pixivdownload.core.work.model.WorkSummary(WorkType.ARTWORK, id)).toList(),
                    4, request.page(), 100, 2);
        });
        try (var service = new MediaMaintenanceService(assets, metadata, query, images, animations, new top.sywyar.pixivdownload.download.media.MemoryMediaStore())) {
            var result = service.preview(new MediaMaintenanceService.Request("png", "webp", true));
            assertEquals(List.of(2L, 3L), result.files().stream().map(MediaMaintenanceService.Item::artworkId).toList());
            assertEquals(List.of("png"), result.files().get(0).missingFormats());
            assertFalse(result.files().get(0).missingThumbnail());
            assertTrue(result.files().get(1).missingThumbnail());
            assertEquals(4, result.scanned());
            verifyNoInteractions(images, animations);
            verify(assets, never()).thumbnail(any(), anyLong(), anyInt());
            assertFalse(Files.exists(directory.resolve("2.png")));
            var empty = service.preview(new MediaMaintenanceService.Request("original", "webp", false));
            assertTrue(empty.files().isEmpty());
            assertTrue(empty.token().isEmpty());
            assertThrows(RuntimeException.class, () -> service.start(result.token()));
        }
    }

    @Test
    @DisplayName("缺少 Ugoira 归档时报告不可处理，不把动图当成静态图片")
    void unavailableAnimationIsReported() throws Exception {
        Path source = directory.resolve("42.webp");
        Files.write(source, new byte[]{82, 73, 70, 70, 0, 0, 0, 0, 87, 69, 66, 80, 86, 80, 56, 88, 0, 0, 0, 0, 2});
        var assets = mock(WorkAssetService.class);
        when(assets.findAsset(WorkType.ARTWORK, 42)).thenReturn(Optional.of(new LocalWorkAsset(
                WorkType.ARTWORK, 42, directory, 1, List.of(new WorkAssetFile(0, source, "webp")))));
        var images = mock(ImageOutputService.class);
        try (var service = new MediaMaintenanceService(assets, mock(WorkMetadataRepository.class), query(), images,
                mock(top.sywyar.pixivdownload.download.UgoiraService.class), new top.sywyar.pixivdownload.download.media.MemoryMediaStore())) {
            var result = service.preview(new MediaMaintenanceService.Request("png", "gif", false));
            assertTrue(result.files().isEmpty());
            assertEquals(1, result.skipped());
            verifyNoInteractions(images);
            var thumbnailOnly = service.preview(new MediaMaintenanceService.Request("png", "gif", true));
            assertEquals(1, thumbnailOnly.skipped());
            assertEquals(1, thumbnailOnly.files().size());
            assertTrue(thumbnailOnly.files().get(0).missingThumbnail());
            assertTrue(thumbnailOnly.files().get(0).missingFormats().isEmpty());
        }
    }

    @Test
    @DisplayName("检测可取消，取消的检测不留下可启动凭据")
    void detectionCanBeCancelled() throws Exception {
        var query = mock(top.sywyar.pixivdownload.core.work.service.WorkQueryService.class);
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        when(query.search(any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS));
            return new top.sywyar.pixivdownload.core.work.model.PagedResult<>(List.of(), 0, 0, 100, 0);
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var service = new MediaMaintenanceService(mock(WorkAssetService.class), mock(WorkMetadataRepository.class), query,
                mock(ImageOutputService.class), mock(top.sywyar.pixivdownload.download.UgoiraService.class), new top.sywyar.pixivdownload.download.media.MemoryMediaStore())) {
            var future = executor.submit(() -> service.preview(new MediaMaintenanceService.Request("png", "webp", true)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("scanning", service.status().state());
            service.cancel();
            resume.countDown();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
            assertEquals("cancelled", service.status().state());
            assertThrows(RuntimeException.class, () -> service.start(""));
        } finally { resume.countDown(); executor.shutdownNow(); }
    }

    @Test
    @DisplayName("达到本批候选上限时明确返回未扫描完毕")
    void detectionLimitIsVisible() throws Exception {
        Path source = directory.resolve("sample.jpg");
        Files.writeString(source, "source");
        var assets = mock(WorkAssetService.class);
        when(assets.findAsset(eq(WorkType.ARTWORK), anyLong())).thenAnswer(call -> Optional.of(new LocalWorkAsset(
                WorkType.ARTWORK, call.getArgument(1), directory, 1, List.of(new WorkAssetFile(0, source, "jpg")))));
        var query = mock(top.sywyar.pixivdownload.core.work.service.WorkQueryService.class);
        when(query.search(any())).thenAnswer(call -> {
            int page = call.getArgument(0, top.sywyar.pixivdownload.core.work.query.WorkQuery.class).page();
            return new top.sywyar.pixivdownload.core.work.model.PagedResult<>(
                    java.util.stream.LongStream.rangeClosed(page * 100L + 1, Math.min(501, (page + 1) * 100L))
                            .mapToObj(id -> new top.sywyar.pixivdownload.core.work.model.WorkSummary(WorkType.ARTWORK, id)).toList(),
                    501, page, 100, 6);
        });
        try (var service = new MediaMaintenanceService(assets, mock(WorkMetadataRepository.class), query,
                mock(ImageOutputService.class), mock(top.sywyar.pixivdownload.download.UgoiraService.class), new top.sywyar.pixivdownload.download.media.MemoryMediaStore())) {
            var result = service.preview(new MediaMaintenanceService.Request("png", "webp", false));
            assertTrue(result.limited());
            assertEquals(500, result.files().size());
            verify(query, times(5)).search(any());
        }
    }

    private static top.sywyar.pixivdownload.core.work.service.WorkQueryService query() {
        var query = mock(top.sywyar.pixivdownload.core.work.service.WorkQueryService.class);
        when(query.search(any())).thenReturn(new top.sywyar.pixivdownload.core.work.model.PagedResult<>(
                List.of(new top.sywyar.pixivdownload.core.work.model.WorkSummary(WorkType.ARTWORK, 42)), 1, 0, 100, 1));
        return query;
    }

    private static void awaitFinished(MediaMaintenanceService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.status().state().equals("running") && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotEquals("running", service.status().state());
    }
}
