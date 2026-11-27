package top.sywyar.pixivdownload.download.media;

import top.sywyar.pixivdownload.core.work.service.DownloadPathGuard;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.asset.ArtworkMediaManifest;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageOutputServiceTest {
    @TempDir Path directory;
    private final MemoryMediaStore mediaStore = new MemoryMediaStore();

    @Test
    @DisplayName("默认原始格式不调用 FFmpeg 且不修改原文件")
    void originalOnlyDoesNotTranscode() throws Exception {
        Path source = jpeg();
        byte[] original = Files.readAllBytes(source);
        FfmpegRunner runner = mock(FfmpegRunner.class);
        var service = new ImageOutputService(runner, JsonMapper.builderWithJackson2Defaults().build(), mediaStore, new TestMediaTransactions());
        assertEquals(List.of("jpg"), service.process(42L, 0, directory.resolve("42_p0"), "jpg", settings(null), () -> false));
        assertArrayEquals(original, Files.readAllBytes(source));
        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("JPG 选择 PNG 后执行转码并在成功后移除原图")
    void convertsRequestedFormatAndRecordsSourceIdentity() throws Exception {
        Path source = jpeg();
        AtomicInteger calls = new AtomicInteger();
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled, progress) -> {
            calls.incrementAndGet();
            assertTrue(args.contains("png"));
            ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "png", output.toFile());
            return "";
        };
        var service = new ImageOutputService(runner, JsonMapper.builderWithJackson2Defaults().build(), mediaStore, new TestMediaTransactions());
        Path stem = directory.resolve("42_p0");
        assertEquals(List.of("png"), service.process(42L, 0, stem, "jpg", settings("png"), () -> false));
        assertEquals(1, calls.get());
        assertFalse(Files.exists(source));
        assertEquals(17, ImageIO.read(directory.resolve("42_p0.png").toFile()).getWidth());
        assertEquals(new ArtworkMediaManifest("jpg", List.of("png")), mediaStore.find(42L, 0).orElseThrow());
    }

    @Test
    @DisplayName("原图格式复用不套用转码尺寸限制")
    void matchingFormatReusesSourceWithoutConversionBudget() throws Exception {
        Path source = directory.resolve("42_p0.png");
        ImageIO.write(new BufferedImage(25_001, 1, BufferedImage.TYPE_INT_RGB), "png", source.toFile());
        byte[] original = Files.readAllBytes(source);
        FfmpegRunner runner = mock(FfmpegRunner.class);
        var service = new ImageOutputService(runner, JsonMapper.builderWithJackson2Defaults().build(), mediaStore, new TestMediaTransactions());
        assertEquals(List.of("png"), service.process(42L, 0, directory.resolve("42_p0"), "png", settings("png"), () -> false));
        assertArrayEquals(original, Files.readAllBytes(source));
        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("任一目标失败或取消时保留原图且不发布成功清单")
    void failureAndCancellationPreserveSource() throws Exception {
        Path source = jpeg();
        byte[] original = Files.readAllBytes(source);
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled, progress) -> {
            Files.writeString(output, "partial");
            throw new IOException("fixture failure");
        };
        var service = new ImageOutputService(runner, JsonMapper.builderWithJackson2Defaults().build(), mediaStore, new TestMediaTransactions());
        Path stem = directory.resolve("42_p0");
        assertThrows(IOException.class, () -> service.process(42L, 0, stem, "jpg", settings("png,webp"), () -> false));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertTrue(mediaStore.find(42L, 0).isEmpty());
        assertThrows(CancellationException.class, () -> service.process(42L, 0, stem, "jpg", settings("png"), () -> true));
        assertArrayEquals(original, Files.readAllBytes(source));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test
    @DisplayName("多格式进度按真实调用报告排队、转换、校验、缩略图与保存")
    void reportsEachOutputAndFinalization() throws Exception {
        Path source = jpeg();
        var events = new java.util.ArrayList<ImageOutputService.Progress>();
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled, progress) -> {
            progress.accept(FfmpegRunner.Phase.WAITING);
            assertTrue(Files.exists(source));
            progress.accept(FfmpegRunner.Phase.RUNNING);
            ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "png", output.toFile());
            return "";
        };
        var service = new ImageOutputService(runner, JsonMapper.builderWithJackson2Defaults().build(), mediaStore, new TestMediaTransactions());
        service.process(42L, 0, directory.resolve("42_p0"), "jpg", settings("png,webp"), () -> false, events::add);
        assertEquals(List.of("verifying", "ffmpeg-waiting", "ffmpeg", "verifying",
                "ffmpeg-waiting", "ffmpeg", "verifying", "thumbnail", "finalizing"),
                events.stream().map(ImageOutputService.Progress::phase).toList());
        assertEquals(List.of(new ImageOutputService.Progress("ffmpeg-waiting", "png", 1, 2),
                        new ImageOutputService.Progress("ffmpeg-waiting", "webp", 2, 2)),
                events.stream().filter(p -> p.phase().equals("ffmpeg-waiting")).toList());
        assertFalse(Files.exists(source));
    }

    private static MediaOutputSettings settings(String formats) {
        var settings = new MediaOutputSettings();
        if (formats != null) settings.setImageFormats(formats);
        return settings;
    }

    @Test
    @DisplayName("同一转码服务逐任务使用独立质量无损和最长边")
    void encodingOptionsDoNotLeakBetweenTasks() throws Exception {
        var invocations = new java.util.ArrayList<List<String>>();
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled, progress) -> {
            invocations.add(List.copyOf(args));
            ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "png", output.toFile());
            return "";
        };
        var service = new ImageOutputService(runner, JsonMapper.builderWithJackson2Defaults().build(), mediaStore, new TestMediaTransactions());
        var first = settings("webp");
        first.setQuality(73);
        first.setWebpLossless(true);
        first.setMaximumEdge(1280);
        var second = settings("webp");
        second.setQuality(45);
        for (var selected : List.of(first, second)) {
            jpeg();
            service.process(42L, 0, directory.resolve("42_p0"), "jpg", selected, () -> false);
        }
        assertEquals(2, invocations.size());
        var one = invocations.get(0);
        var two = invocations.get(1);
        assertEquals("73", one.get(one.indexOf("-quality") + 1));
        assertEquals("1", one.get(one.indexOf("-lossless") + 1));
        assertTrue(one.get(one.indexOf("-vf") + 1).contains("1280"));
        assertEquals("45", two.get(two.indexOf("-quality") + 1));
        assertEquals("0", two.get(two.indexOf("-lossless") + 1));
        assertFalse(two.contains("-vf"));
    }

    private Path jpeg() throws IOException {
        Path source = directory.resolve("42_p0.jpg");
        ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "jpg", source.toFile());
        return source;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"cancel", "save-failure", "user-arrival"})
    void interruptedThumbnailCanBeRetriedWithoutClaimingUserFiles(String failure) throws Exception {
        Path source = jpeg();
        Path stem = directory.resolve("42_p0");
        Path thumbnail = directory.resolve("42_p0_thumb.jpg");
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled, progress) -> {
            ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "png", output.toFile());
            return "";
        };
        var service = new ImageOutputService(runner, JsonMapper.builderWithJackson2Defaults().build(), mediaStore, new TestMediaTransactions());
        var cancel = new java.util.concurrent.atomic.AtomicBoolean();
        byte[] user = {73};
        var error = assertThrows(Exception.class, () -> service.process(0, stem, "jpg", settings("original,webp"), cancel::get, progress -> {
            if (!progress.phase().equals("finalizing")) return;
            if (failure.equals("cancel")) cancel.set(true);
            if (failure.equals("user-arrival")) {
                try { Files.write(thumbnail, user, java.nio.file.StandardOpenOption.CREATE_NEW); }
                catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }
        }, (page, path, value) -> {
            assertEquals("save-failure", failure);
            assertTrue(Files.isRegularFile(thumbnail));
            throw new IOException("controlled media save failure");
        }));
        assertTrue(failure.equals("cancel") ? error instanceof CancellationException : error instanceof IOException);
        assertTrue(mediaStore.find(42L, 0).isEmpty());
        assertTrue(Files.isRegularFile(source));
        if (failure.equals("user-arrival")) assertArrayEquals(user, Files.readAllBytes(thumbnail));
        else assertFalse(Files.exists(thumbnail));
        service.process(42L, 0, stem, "jpg", settings("original,webp"), () -> false);
        assertEquals(!failure.equals("user-arrival"), mediaStore.find(42L, 0).orElseThrow().thumbnailOwned());
        if (failure.equals("user-arrival")) assertArrayEquals(user, Files.readAllBytes(thumbnail));
        else assertNotNull(ImageIO.read(thumbnail.toFile()));
    }

    @Test
    @DisplayName("媒体入库失败保留原文件，即使转码产物已生成也不返回成功")
    void databaseFailurePreservesOriginal() throws Exception {
        Path source = jpeg();
        byte[] original = Files.readAllBytes(source);
        var failedStore = mock(top.sywyar.pixivdownload.core.asset.ArtworkMediaStore.class);
        doThrow(new IOException("database unavailable")).when(failedStore).save(eq(42L), eq(0), any());
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled, progress) -> {
            ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "png", output.toFile());
            return "";
        };
        var service = new ImageOutputService(runner, JsonMapper.builderWithJackson2Defaults().build(), failedStore, new TestMediaTransactions());
        assertThrows(IOException.class, () -> service.process(42L, 0, directory.resolve("42_p0"), "jpg", settings("png"), () -> false));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertTrue(Files.exists(directory.resolve("42_p0.png")));
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".media.properties")));
        }
    }

    @Test
    @DisplayName("历史补转保留原有文件且不把再生成的原格式当成原图")
    void historicalCopyRetainsOriginalIdentity() throws Exception {
        Path source = jpeg();
        byte[] original = Files.readAllBytes(source);
        Path stem = directory.resolve("42_p0");
        mediaStore.save(42L, 0, new ArtworkMediaManifest("png", List.of("jpg"), false));
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled, progress) -> {
            ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "png", output.toFile());
            return "";
        };
        Path target = directory.resolve("42_p0.png");
        Path userThumbnail = Files.writeString(directory.resolve("42_p0_thumb.jpg"), "user thumbnail");
        var paths = mock(DownloadPathGuard.class);
        var store = mock(top.sywyar.pixivdownload.core.asset.ArtworkMediaStore.class, org.mockito.AdditionalAnswers.delegatesTo(mediaStore));
        var service = new ImageOutputService(runner, JsonMapper.builderWithJackson2Defaults().build(), store, new TestMediaTransactions());
        var held = new java.util.concurrent.atomic.AtomicBoolean();
        var closes = new AtomicInteger();
        when(paths.reserveOutputs(42L, java.util.Set.of(target))).thenThrow(
                new top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException("occupied", ""))
                .thenAnswer(invocation -> {
                    assertTrue(held.compareAndSet(false, true));
                    return (java.io.Closeable) () -> {
                        assertEquals(closes.getAndIncrement() > 0, Files.exists(target));
                        held.set(false);
                    };
                });
        doAnswer(invocation -> {
            assertTrue(held.get());
            assertTrue(Files.isRegularFile(target));
            if (closes.get() == 0) throw new IOException("media commit failed");
            mediaStore.save(42, 0, invocation.getArgument(2));
            return null;
        }).when(store).save(eq(42L), eq(0), any());
        var previous = mediaStore.find(42, 0);
        assertThrows(IOException.class, () -> service.addMissingFormats(42, 0, source, "png", () -> false, paths));
        assertFalse(Files.exists(target));
        assertEquals(previous, mediaStore.find(42, 0));
        assertThrows(IOException.class, () -> service.addMissingFormats(42, 0, source, "png", () -> false, paths));
        assertFalse(Files.exists(target));
        assertEquals(previous, mediaStore.find(42, 0));
        service.addMissingFormats(42, 0, source, "png", () -> false, paths);
        assertEquals(2, closes.get());
        assertFalse(held.get());
        assertEquals("user thumbnail", Files.readString(userThumbnail));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertEquals(new ArtworkMediaManifest("png", List.of("jpg", "png"), false), mediaStore.find(42L, 0).orElseThrow());
        assertNotNull(ImageIO.read(directory.resolve("42_p0.png").toFile()));
    }

    @Test
    @DisplayName("真实 FFmpeg 输出透明图白底 JPG 与 WebP 并生成可读缩略图")
    void realFfmpegWritesRequestedFormats() throws Exception {
        Process check;
        try { check = new ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); }
        catch (IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "FFmpeg required");
            return;
        }
        assertTrue(check.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled, progress) -> {
            var command = new java.util.ArrayList<String>();
            command.add(tool == FfmpegRunner.Tool.FFMPEG ? "ffmpeg" : "ffprobe");
            command.addAll(args);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            try {
                if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                    process.destroyForcibly();
                    throw new IOException(text);
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return text;
        };
        Path stem = directory.resolve("transparent");
        ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_ARGB), "png", directory.resolve("transparent.png").toFile());
        var service = new ImageOutputService(runner, JsonMapper.builderWithJackson2Defaults().build(), mediaStore, new TestMediaTransactions());
        assertEquals(List.of("jpg", "webp"), service.process(42L, 0, stem, "png", settings("jpg,webp"), () -> false));
        BufferedImage jpg = ImageIO.read(directory.resolve("transparent.jpg").toFile());
        assertEquals(0xFFFFFF, jpg.getRGB(0, 0) & 0xFFFFFF);
        assertTrue(Files.size(directory.resolve("transparent.webp")) > 0);
        assertNotNull(ImageIO.read(directory.resolve("transparent_thumb.jpg").toFile()));
        assertFalse(Files.exists(directory.resolve("transparent.png")));
        var thumbnail = directory.resolve("native_thumb.jpg");
        var nativeStem = directory.resolve("native");
        Files.copy(directory.resolve("transparent.webp"), directory.resolve("native.webp"));
        service.process(43L, 0, nativeStem, "webp", settings("original"), () -> false);
        assertFalse(mediaStore.find(43L, 0).orElseThrow().thumbnailOwned());
        assertFalse(Files.exists(thumbnail));
        service.process(43L, 0, nativeStem, "webp", settings("original,png"), () -> false);
        assertTrue(mediaStore.find(43L, 0).orElseThrow().thumbnailOwned());
        assertNotNull(ImageIO.read(thumbnail.toFile()));
        Files.delete(directory.resolve("native.png"));
        Files.writeString(thumbnail, "user thumbnail");
        mediaStore.save(43L, 0, new ArtworkMediaManifest("webp", List.of("webp")));
        var paths = mock(DownloadPathGuard.class);
        when(paths.reserveOutputs(eq(43L), any())).thenReturn(() -> {});
        service.addMissingFormats(43L, 0, directory.resolve("native.webp"), "png", () -> false, paths);
        assertFalse(mediaStore.find(43L, 0).orElseThrow().thumbnailOwned());
        assertEquals("user thumbnail", Files.readString(thumbnail));
    }
}
