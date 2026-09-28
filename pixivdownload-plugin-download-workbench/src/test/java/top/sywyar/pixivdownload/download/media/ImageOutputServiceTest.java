package top.sywyar.pixivdownload.download.media;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    @DisplayName("默认原始格式不调用 FFmpeg 且不修改原文件")
    void originalOnlyDoesNotTranscode() throws Exception {
        Path source = jpeg();
        byte[] original = Files.readAllBytes(source);
        FfmpegRunner runner = mock(FfmpegRunner.class);
        var service = new ImageOutputService(runner, new ObjectMapper());
        assertEquals(List.of("jpg"), service.process(directory.resolve("42_p0"), "jpg", settings(null), () -> false));
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
        var service = new ImageOutputService(runner, new ObjectMapper());
        Path stem = directory.resolve("42_p0");
        assertEquals(List.of("png"), service.process(stem, "jpg", settings("png"), () -> false));
        assertEquals(1, calls.get());
        assertFalse(Files.exists(source));
        assertEquals(17, ImageIO.read(directory.resolve("42_p0.png").toFile()).getWidth());
        assertEquals(new ArtworkMediaManifest("jpg", List.of("png")), ArtworkMediaManifest.read(stem).orElseThrow());
    }

    @Test
    @DisplayName("原图格式复用不套用转码尺寸限制")
    void matchingFormatReusesSourceWithoutConversionBudget() throws Exception {
        Path source = directory.resolve("42_p0.png");
        ImageIO.write(new BufferedImage(25_001, 1, BufferedImage.TYPE_INT_RGB), "png", source.toFile());
        byte[] original = Files.readAllBytes(source);
        FfmpegRunner runner = mock(FfmpegRunner.class);
        var service = new ImageOutputService(runner, new ObjectMapper());
        assertEquals(List.of("png"), service.process(directory.resolve("42_p0"), "png", settings("png"), () -> false));
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
        var service = new ImageOutputService(runner, new ObjectMapper());
        Path stem = directory.resolve("42_p0");
        assertThrows(IOException.class, () -> service.process(stem, "jpg", settings("png,webp"), () -> false));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertTrue(ArtworkMediaManifest.read(stem).isEmpty());
        assertThrows(CancellationException.class, () -> service.process(stem, "jpg", settings("png"), () -> true));
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
        var service = new ImageOutputService(runner, new ObjectMapper());
        service.process(directory.resolve("42_p0"), "jpg", settings("png,webp"), () -> false, events::add);
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
        var service = new ImageOutputService(runner, new ObjectMapper());
        var first = settings("webp");
        first.setQuality(73);
        first.setWebpLossless(true);
        first.setMaximumEdge(1280);
        var second = settings("webp");
        second.setQuality(45);
        for (var selected : List.of(first, second)) {
            jpeg();
            service.process(directory.resolve("42_p0"), "jpg", selected, () -> false);
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

    @Test
    @DisplayName("历史补转保留原有文件且不把再生成的原格式当成原图")
    void historicalCopyRetainsOriginalIdentity() throws Exception {
        Path source = jpeg();
        byte[] original = Files.readAllBytes(source);
        Path stem = directory.resolve("42_p0");
        new ArtworkMediaManifest("png", List.of("jpg"), false).write(stem);
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled, progress) -> {
            ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "png", output.toFile());
            return "";
        };
        new ImageOutputService(runner, new ObjectMapper()).addMissingFormats(source, "png", () -> false);
        assertArrayEquals(original, Files.readAllBytes(source));
        assertEquals(new ArtworkMediaManifest("png", List.of("jpg", "png"), false), ArtworkMediaManifest.read(stem).orElseThrow());
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
        var service = new ImageOutputService(runner, new ObjectMapper());
        assertEquals(List.of("jpg", "webp"), service.process(stem, "png", settings("jpg,webp"), () -> false));
        BufferedImage jpg = ImageIO.read(directory.resolve("transparent.jpg").toFile());
        assertEquals(0xFFFFFF, jpg.getRGB(0, 0) & 0xFFFFFF);
        assertTrue(Files.size(directory.resolve("transparent.webp")) > 0);
        assertNotNull(ImageIO.read(directory.resolve("transparent_thumb.jpg").toFile()));
        assertFalse(Files.exists(directory.resolve("transparent.png")));
    }
}
