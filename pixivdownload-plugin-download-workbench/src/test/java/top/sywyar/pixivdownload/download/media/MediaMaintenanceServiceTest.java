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
            java.util.function.BooleanSupplier cancelled = invocation.getArgument(2);
            while (!cancelled.getAsBoolean()) Thread.sleep(10);
            throw new java.util.concurrent.CancellationException();
        }).when(images).addMissingFormats(eq(source), eq("png"), any());
        try (var service = new MediaMaintenanceService(assets, metadata, images, mock(top.sywyar.pixivdownload.download.UgoiraService.class))) {
            assertThrows(RuntimeException.class, () -> service.start("unreviewed"));
            var preview = service.preview(new MediaMaintenanceService.Request(List.of(42L), "png", null, false));
            assertEquals(1, preview.files().size());
            verifyNoInteractions(images);
            service.start(preview.token());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(RuntimeException.class, () -> service.start(preview.token()));
            service.cancel();
            awaitFinished(service);
            assertEquals("cancelled", service.status().state());
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
        try (var service = new MediaMaintenanceService(assets, metadata, images, mock(top.sywyar.pixivdownload.download.UgoiraService.class))) {
            var preview = service.preview(new MediaMaintenanceService.Request(List.of(42L), "png", null, false));
            Files.writeString(source, "new content");
            service.start(preview.token());
            awaitFinished(service);
            assertEquals(1, service.status().failed());
            verifyNoInteractions(images);
        }
    }

    private static void awaitFinished(MediaMaintenanceService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.status().state().equals("running") && System.nanoTime() < deadline) Thread.sleep(10);
        assertNotEquals("running", service.status().state());
    }
}
