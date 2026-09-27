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
        var service = new ImageOutputService(runner, new MediaOutputSettings(), new ObjectMapper());
        assertEquals(List.of("jpg"), service.process(directory.resolve("42_p0"), "jpg", null, () -> false));
        assertArrayEquals(original, Files.readAllBytes(source));
        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("JPG 选择 PNG 后执行转码并在成功后移除原图")
    void convertsRequestedFormatAndRecordsSourceIdentity() throws Exception {
        Path source = jpeg();
        AtomicInteger calls = new AtomicInteger();
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled) -> {
            calls.incrementAndGet();
            assertTrue(args.contains("png"));
            ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "png", output.toFile());
            return "";
        };
        var service = new ImageOutputService(runner, new MediaOutputSettings(), new ObjectMapper());
        Path stem = directory.resolve("42_p0");
        assertEquals(List.of("png"), service.process(stem, "jpg", "png", () -> false));
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
        var service = new ImageOutputService(runner, new MediaOutputSettings(), new ObjectMapper());
        assertEquals(List.of("png"), service.process(directory.resolve("42_p0"), "png", "png", () -> false));
        assertArrayEquals(original, Files.readAllBytes(source));
        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("任一目标失败或取消时保留原图且不发布成功清单")
    void failureAndCancellationPreserveSource() throws Exception {
        Path source = jpeg();
        byte[] original = Files.readAllBytes(source);
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled) -> {
            Files.writeString(output, "partial");
            throw new IOException("fixture failure");
        };
        var service = new ImageOutputService(runner, new MediaOutputSettings(), new ObjectMapper());
        Path stem = directory.resolve("42_p0");
        assertThrows(IOException.class, () -> service.process(stem, "jpg", "png,webp", () -> false));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertTrue(ArtworkMediaManifest.read(stem).isEmpty());
        assertThrows(CancellationException.class, () -> service.process(stem, "jpg", "png", () -> true));
        assertArrayEquals(original, Files.readAllBytes(source));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
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
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled) -> {
            ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "png", output.toFile());
            return "";
        };
        new ImageOutputService(runner, new MediaOutputSettings(), new ObjectMapper()).addMissingFormats(source, "png", () -> false);
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
        FfmpegRunner runner = (tool, args, cwd, output, limit, timeout, cancelled) -> {
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
        var service = new ImageOutputService(runner, new MediaOutputSettings(), new ObjectMapper());
        assertEquals(List.of("jpg", "webp"), service.process(stem, "png", "jpg,webp", () -> false));
        BufferedImage jpg = ImageIO.read(directory.resolve("transparent.jpg").toFile());
        assertEquals(0xFFFFFF, jpg.getRGB(0, 0) & 0xFFFFFF);
        assertTrue(Files.size(directory.resolve("transparent.webp")) > 0);
        assertNotNull(ImageIO.read(directory.resolve("transparent_thumb.jpg").toFile()));
        assertFalse(Files.exists(directory.resolve("transparent.png")));
    }
}
