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
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
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
    private final top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry runtimeTasks =
            new top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry();

    @Test
    @DisplayName("共享媒体工具失效只尝试一页并交付失败终态，修复后可再次处理")
    void stopsSharedFailureAndReportsCompletion() throws Exception {
        var assets = mock(WorkAssetService.class);
        var metadata = mock(WorkMetadataRepository.class);
        var images = mock(ImageOutputService.class);
        var first = new WorkAssetFile(0, Files.writeString(directory.resolve("42_p0.jpg"), "first"), "jpg");
        var second = new WorkAssetFile(1, Files.writeString(directory.resolve("42_p1.jpg"), "second"), "jpg");
        when(assets.findAsset(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.ARTWORK, 42, directory, 2, List.of(first, second))));
        when(assets.rawFile(WorkType.ARTWORK, 42L, 0)).thenReturn(Optional.of(first));
        when(assets.rawFile(WorkType.ARTWORK, 42L, 1)).thenReturn(Optional.of(second));
        when(metadata.find(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(mock(WorkMetadata.class)));
        doThrow(new top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner.UnavailableException(
                "missing-ffmpeg", new java.io.IOException("not found")))
                .when(images).addMissingFormats(anyLong(), anyInt(), any(), anyString(), any());
        try (var service = new MediaMaintenanceService(assets, metadata, query(), images,
                mock(top.sywyar.pixivdownload.download.UgoiraService.class), new MemoryMediaStore(), mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"))) {
            var desktop = new DesktopMediaMaintenance(service, mock(MediaCapabilityService.class));
            var request = new DesktopMediaTool.Request("png", null, false);
            var preview = desktop.preview(request).valueOrThrow();
            var result = new java.util.concurrent.CompletableFuture<DesktopMediaTool.Status>();
            desktop.start(preview.token(), result::complete).valueOrThrow();
            var terminal = result.get(5, TimeUnit.SECONDS);
            assertEquals("failed", terminal.state());
            assertEquals(2, terminal.total());
            assertEquals(1, terminal.completed());
            assertEquals(1, terminal.failed());
            verify(images, times(1)).addMissingFormats(anyLong(), anyInt(), any(), anyString(), any());
            reset(images);
            var retry = new java.util.concurrent.CompletableFuture<DesktopMediaTool.Status>();
            desktop.start(desktop.preview(request).valueOrThrow().token(), retry::complete).valueOrThrow();
            assertEquals("completed", retry.get(5, TimeUnit.SECONDS).state());
            verify(images, times(2)).addMissingFormats(anyLong(), anyInt(), any(), anyString(), any());
        }
    }

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
        try (var service = new MediaMaintenanceService(assets, metadata, query(), images, animations, new MemoryMediaStore(), mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"))) {
            when(assets.isReadOnly(WorkType.ARTWORK, 42L)).thenReturn(true);
            assertTrue(service.preview(new MediaMaintenanceService.Request("png", null, false)).get(5, TimeUnit.SECONDS).files().isEmpty());
            when(assets.isReadOnly(WorkType.ARTWORK, 42L)).thenReturn(false);
            var preview = service.preview(new MediaMaintenanceService.Request("png", null, false)).get(5, TimeUnit.SECONDS);
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
        try (var service = new MediaMaintenanceService(assets, metadata, query(), images, mock(top.sywyar.pixivdownload.download.UgoiraService.class), new top.sywyar.pixivdownload.download.media.MemoryMediaStore(), mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"))) {
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
        try (var service = new MediaMaintenanceService(assets, metadata, query(), images, mock(top.sywyar.pixivdownload.download.UgoiraService.class), new top.sywyar.pixivdownload.download.media.MemoryMediaStore(), mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"))) {
            var preview = service.preview(new MediaMaintenanceService.Request( "png", null, false)).get(5, TimeUnit.SECONDS);
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
        try (var service = new MediaMaintenanceService(assets, metadata, query, images, animations, new top.sywyar.pixivdownload.download.media.MemoryMediaStore(), mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"))) {
            var result = service.preview(new MediaMaintenanceService.Request("png", "webp", true)).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(2L, 3L), result.files().stream().map(MediaMaintenanceService.Item::artworkId).toList());
            assertEquals(List.of("png"), result.files().get(0).missingFormats());
            assertFalse(result.files().get(0).missingThumbnail());
            assertTrue(result.files().get(1).missingThumbnail());
            assertEquals(4, result.scanned());
            verifyNoInteractions(images, animations);
            verify(assets, never()).thumbnail(any(), anyLong(), anyInt());
            assertFalse(Files.exists(directory.resolve("2.png")));
            var empty = service.preview(new MediaMaintenanceService.Request("original", "webp", false)).get(5, TimeUnit.SECONDS);
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
                mock(top.sywyar.pixivdownload.download.UgoiraService.class), new top.sywyar.pixivdownload.download.media.MemoryMediaStore(), mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"))) {
            var result = service.preview(new MediaMaintenanceService.Request("png", "gif", false)).get(5, TimeUnit.SECONDS);
            assertTrue(result.files().isEmpty());
            assertEquals(1, result.skipped());
            verifyNoInteractions(images);
            var thumbnailOnly = service.preview(new MediaMaintenanceService.Request("png", "gif", true)).get(5, TimeUnit.SECONDS);
            assertEquals(1, thumbnailOnly.skipped());
            assertEquals(1, thumbnailOnly.files().size());
            assertTrue(thumbnailOnly.files().get(0).missingThumbnail());
            assertTrue(thumbnailOnly.files().get(0).missingFormats().isEmpty());
        }
    }

    @Test
    @DisplayName("预览立即返回后台任务，忙碌请求快速拒绝，取消中断查询且不留下凭据")
    void detectionCanBeCancelled() throws Exception {
        var query = mock(top.sywyar.pixivdownload.core.work.service.WorkQueryService.class);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(query.search(any())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stop) { interrupted.countDown(); throw stop; }
            throw new AssertionError("Query should be interrupted");
        });
        try (var service = new MediaMaintenanceService(mock(WorkAssetService.class), mock(WorkMetadataRepository.class), query,
                mock(ImageOutputService.class), mock(top.sywyar.pixivdownload.download.UgoiraService.class), new MemoryMediaStore(), mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"))) {
            var future = service.preview(new MediaMaintenanceService.Request("png", "webp", true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(future.isDone());
            assertEquals("scanning", service.status().state());
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
                    assertThrows(RuntimeException.class, () -> service.start("")));
            service.cancel();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.CancellationException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertEquals("cancelled", service.status().state());
            assertThrows(RuntimeException.class, () -> service.start(""));
        }
    }

    @Test
    @DisplayName("关闭服务等待扫描实际退出，关闭后拒绝新预览")
    void closeDrainsScan() throws Exception {
        var query = mock(top.sywyar.pixivdownload.core.work.service.WorkQueryService.class);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(query.search(any())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stop) {
                interrupted.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return new top.sywyar.pixivdownload.core.work.model.PagedResult<>(List.of(), 0, 0, 100, 0);
        });
        var service = new MediaMaintenanceService(mock(WorkAssetService.class), mock(WorkMetadataRepository.class), query,
                mock(ImageOutputService.class), mock(top.sywyar.pixivdownload.download.UgoiraService.class), new MemoryMediaStore(), mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"));
        var closer = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = service.preview(new MediaMaintenanceService.Request("png", null, false));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closing = closer.submit(service::close);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(closing.isDone());
            release.countDown();
            closing.get(5, TimeUnit.SECONDS);
            assertThrows(java.util.concurrent.CancellationException.class, result::join);
            assertThrows(RuntimeException.class, () -> service.preview(new MediaMaintenanceService.Request("png", null, false)));
        } finally { release.countDown(); service.close(); closer.shutdownNow(); }
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
                mock(ImageOutputService.class), mock(top.sywyar.pixivdownload.download.UgoiraService.class), new top.sywyar.pixivdownload.download.media.MemoryMediaStore(), mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"))) {
            var result = service.preview(new MediaMaintenanceService.Request("png", "webp", false)).get(5, TimeUnit.SECONDS);
            assertTrue(result.limited());
            assertEquals(500, result.files().size());
            verify(query, times(5)).search(any());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("插件关闭或排空中断正在处理的文件，并且只交付一次取消终态")
    void closeDeliversTerminalStatus(boolean quiesce) throws Exception {
        var assets = mock(WorkAssetService.class);
        var metadata = mock(WorkMetadataRepository.class);
        var images = mock(ImageOutputService.class);
        var file = new WorkAssetFile(0, Files.writeString(directory.resolve("42_p0.jpg"), "source"), "jpg");
        when(assets.findAsset(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.ARTWORK, 42, directory, 1, List.of(file))));
        when(assets.rawFile(WorkType.ARTWORK, 42L, 0)).thenReturn(Optional.of(file));
        when(metadata.find(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(mock(WorkMetadata.class)));
        var entered = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            new CountDownLatch(1).await();
            throw new AssertionError("Processing must be interrupted");
        }).when(images).addMissingFormats(anyLong(), anyInt(), any(), anyString(), any());
        var results = new java.util.concurrent.LinkedBlockingQueue<MediaMaintenanceService.Status>();
        try (var service = new MediaMaintenanceService(assets, metadata, query(), images,
                mock(top.sywyar.pixivdownload.download.UgoiraService.class), new MemoryMediaStore(),
                mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"))) {
            var preview = service.preview(new MediaMaintenanceService.Request("png", null, false)).get(5, TimeUnit.SECONDS);
            service.start(preview.token(), results::add);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (quiesce) {
                assertEquals(1, runtimeTasks.activeTaskCount("sample"));
                var drain = runtimeTasks.prepareQuiesce("sample");
                runtimeTasks.cancelQuiescedTasks("sample", drain);
                assertTrue(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
            }
            service.close();
            assertEquals(0, runtimeTasks.activeTaskCount("sample"));
            var terminal = results.poll(5, TimeUnit.SECONDS);
            assertNotNull(terminal);
            assertEquals("cancelled", terminal.state());
            assertEquals(0, terminal.completed());
            assertEquals(0, terminal.failed());
            assertTrue(results.isEmpty());
        }
        assertTrue(results.isEmpty());
    }

    private static top.sywyar.pixivdownload.core.work.service.WorkQueryService query() {
        var query = mock(top.sywyar.pixivdownload.core.work.service.WorkQueryService.class);
        when(query.search(any())).thenReturn(new top.sywyar.pixivdownload.core.work.model.PagedResult<>(
                List.of(new top.sywyar.pixivdownload.core.work.model.WorkSummary(WorkType.ARTWORK, 42)), 1, 0, 100, 1));
        return query;
    }

    @Test
    @DisplayName("真实 owner 排空中断扫描但等待查询实际退出，拒绝提交后不会卡在扫描状态")
    void runtimeQuiesceWaitsForScanExit() throws Exception {
        var tasks = new top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry();
        var query = mock(WorkQueryService.class);
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(query.search(any())).thenAnswer(call -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException cancelled) {
                interrupted.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return new top.sywyar.pixivdownload.core.work.model.PagedResult<>(List.of(), 0, 0, 100, 0);
        });
        try (var service = new MediaMaintenanceService(mock(WorkAssetService.class), mock(WorkMetadataRepository.class), query,
                mock(ImageOutputService.class), mock(top.sywyar.pixivdownload.download.UgoiraService.class), new MemoryMediaStore(),
                mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), tasks.registrarForPlugin("independent"))) {
            var preview = service.preview(new MediaMaintenanceService.Request("png", null, false));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, tasks.activeTaskCount("independent"));
            var drain = tasks.prepareQuiesce("independent");
            tasks.cancelQuiescedTasks("independent", drain);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(drain.isDrained());
            release.countDown();
            assertTrue(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
            assertThrows(java.util.concurrent.CancellationException.class, preview::join);
            assertThrows(top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRejectedException.class,
                    () -> service.preview(new MediaMaintenanceService.Request("png", null, false)));
            assertEquals("idle", service.status().state());
            assertEquals(0, tasks.activeTaskCount("independent"));
            verify(query, times(1)).search(any());
        } finally { release.countDown(); }
    }

    private static void awaitFinished(MediaMaintenanceService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.status().state().equals("running") && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotEquals("running", service.status().state());
    }

    @Test
    @DisplayName("扫描的进程级错误保留原身份，不降格为普通操作失败")
    void previewPreservesFatalFailure() {
        var query = mock(WorkQueryService.class);
        var fatal = new OutOfMemoryError("simulated fatal failure");
        when(query.search(any())).thenThrow(fatal);
        try (var service = new MediaMaintenanceService(mock(WorkAssetService.class), mock(WorkMetadataRepository.class), query,
                mock(ImageOutputService.class), mock(top.sywyar.pixivdownload.download.UgoiraService.class), new MemoryMediaStore(),
                mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), runtimeTasks.registrarForPlugin("sample"))) {
            var desktop = new DesktopMediaMaintenance(service, mock(MediaCapabilityService.class));
            assertSame(fatal, assertThrows(OutOfMemoryError.class,
                    () -> desktop.preview(new DesktopMediaTool.Request("png", null, false))));
        }
        assertEquals(0, runtimeTasks.activeTaskCount("sample"));
    }

    @Test
    @DisplayName("注册后排队前撤回仍完成取消，关闭不会留下等待中的预览")
    void cancellationBeforeSubmissionReleasesPreview() {
        var scoped = runtimeTasks.registrarForPlugin("independent");
        var registrar = mock(top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar.class,
                org.mockito.AdditionalAnswers.delegatesTo(scoped));
        var drain = new java.util.concurrent.atomic.AtomicReference<top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskDrain>();
        doAnswer(call -> {
            var task = scoped.registerOneShot(call.getArgument(0));
            drain.set(runtimeTasks.prepareQuiesce("independent"));
            runtimeTasks.cancelQuiescedTasks("independent", drain.get());
            return task;
        }).when(registrar).registerOneShot(any());
        var query = mock(WorkQueryService.class);
        try (var service = new MediaMaintenanceService(mock(WorkAssetService.class), mock(WorkMetadataRepository.class), query,
                mock(ImageOutputService.class), mock(top.sywyar.pixivdownload.download.UgoiraService.class), new MemoryMediaStore(),
                mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), registrar)) {
            var preview = service.preview(new MediaMaintenanceService.Request("png", null, false));
            assertTrue(drain.get().awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
            assertThrows(java.util.concurrent.CancellationException.class, () -> preview.get(1, TimeUnit.SECONDS));
            service.close();
            verifyNoInteractions(query);
        }
    }

    @Test
    @DisplayName("执行在排队前撤回时无需关闭服务就交付一次取消，且不处理任何文件")
    void cancellationBeforeRunDeliversCompletion() throws Exception {
        var scoped = runtimeTasks.registrarForPlugin("independent");
        var registrar = mock(top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar.class,
                org.mockito.AdditionalAnswers.delegatesTo(scoped));
        var quiesce = new java.util.concurrent.atomic.AtomicBoolean();
        var drain = new java.util.concurrent.atomic.AtomicReference<top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskDrain>();
        doAnswer(call -> {
            var task = scoped.registerOneShot(call.getArgument(0));
            if (quiesce.get()) {
                drain.set(runtimeTasks.prepareQuiesce("independent"));
                runtimeTasks.cancelQuiescedTasks("independent", drain.get());
            }
            return task;
        }).when(registrar).registerOneShot(any());
        var assets = mock(WorkAssetService.class);
        var images = mock(ImageOutputService.class);
        var file = new WorkAssetFile(0, Files.writeString(directory.resolve("42_p0.jpg"), "source"), "jpg");
        when(assets.findAsset(WorkType.ARTWORK, 42L)).thenReturn(Optional.of(
                new LocalWorkAsset(WorkType.ARTWORK, 42, directory, 1, List.of(file))));
        var completions = new java.util.concurrent.LinkedBlockingQueue<MediaMaintenanceService.Status>();
        try (var service = new MediaMaintenanceService(assets, mock(WorkMetadataRepository.class), query(), images,
                mock(top.sywyar.pixivdownload.download.UgoiraService.class), new MemoryMediaStore(),
                mock(top.sywyar.pixivdownload.i18n.MessageResolver.class), registrar)) {
            var preview = service.preview(new MediaMaintenanceService.Request("png", null, false)).get(5, TimeUnit.SECONDS);
            assertEquals(1, preview.files().size());
            quiesce.set(true);
            service.start(preview.token(), completions::add);
            var completion = completions.poll(1, TimeUnit.SECONDS);
            assertNotNull(completion);
            assertEquals("cancelled", completion.state());
            assertEquals(0, completion.completed());
            assertTrue(drain.get().awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
            service.close();
            assertTrue(completions.isEmpty());
            verifyNoInteractions(images);
        }
    }
}
