package top.sywyar.pixivdownload.ffmpeg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FfmpegRunnerAdapterTest {
    @TempDir Path directory;

    @Test
    @DisplayName("真实 FFmpeg 将 JPG 转为可解码 PNG 并由 ffprobe 核验尺寸")
    void encodesAndProbesRealImage() throws Exception {
        var runner = runner();
        Path source = directory.resolve("original.jpg");
        Path output = directory.resolve("converted.png");
        ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_RGB), "jpg", source.toFile());
        runner.run(FfmpegRunner.Tool.FFMPEG, List.of("-y", "-nostdin", "-v", "error", "-i", source.toString(), output.toString()),
                null, output, 1_000_000, Duration.ofSeconds(30), () -> false);
        assertEquals(17, ImageIO.read(output.toFile()).getWidth());
        assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71}, java.util.Arrays.copyOf(Files.readAllBytes(output), 4));
        String probe = runner.run(FfmpegRunner.Tool.FFPROBE,
                List.of("-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0", output.toString()),
                null, null, 0, Duration.ofSeconds(30), () -> false);
        assertEquals("17,13", probe.trim());
    }

    @Test
    @DisplayName("取消真实媒体进程后释放额度供下一个任务使用")
    void cancellationReturnsBudgetAfterExit() throws Exception {
        var runner = runner();
        AtomicBoolean cancelled = new AtomicBoolean();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> runner.run(FfmpegRunner.Tool.FFMPEG,
                    List.of("-nostdin", "-v", "error", "-re", "-f", "lavfi", "-i", "color=size=16x16:rate=1", "-f", "null", "-"),
                    null, null, 0, Duration.ofSeconds(30), cancelled::get));
            Thread.sleep(500);
            cancelled.set(true);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertTrue(runner.run(FfmpegRunner.Tool.FFMPEG, List.of("-version"), null, null, 0,
                    Duration.ofSeconds(10), () -> false).startsWith("ffmpeg version"));
        } finally { worker.shutdownNow(); }
    }

    @Test
    @DisplayName("真实进程只在取得额度后报告运行，排队取消不启动进程")
    void reportsWaitingUntilBudgetIsGranted() throws Exception {
        var installation = FfmpegLocator.locate();
        assumeTrue(installation.isPresent(), "FFmpeg required");
        var properties = new FfmpegProperties();
        properties.setMaxConcurrent(1);
        var gate = new FfmpegProcessGateAdapter(properties);
        var runner = new FfmpegRunnerAdapter(() -> new ResolvedFfmpegCommand(
                installation.orElseThrow().ffmpegPath().toString(), ResolvedFfmpegCommand.Source.SYSTEM), gate);
        var worker = Executors.newSingleThreadExecutor();
        try {
            for (boolean cancel : List.of(false, true)) {
                var held = gate.acquire(() -> false);
                var waiting = new java.util.concurrent.CountDownLatch(1);
                var phases = new java.util.concurrent.CopyOnWriteArrayList<FfmpegRunner.Phase>();
                var cancelled = new AtomicBoolean();
                var result = worker.submit(() -> runner.run(FfmpegRunner.Tool.FFMPEG, List.of("-version"),
                        null, null, 0, Duration.ofSeconds(10), cancelled::get, phase -> {
                            phases.add(phase);
                            if (phase == FfmpegRunner.Phase.WAITING) waiting.countDown();
                        }));
                try {
                    assertTrue(waiting.await(5, TimeUnit.SECONDS));
                    assertEquals(List.of(FfmpegRunner.Phase.WAITING), phases);
                    assertFalse(result.isDone());
                    if (cancel) {
                        cancelled.set(true);
                        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                                () -> result.get(5, TimeUnit.SECONDS));
                        assertInstanceOf(CancellationException.class, failure.getCause());
                        assertEquals(List.of(FfmpegRunner.Phase.WAITING), phases);
                    } else {
                        held.close();
                        assertTrue(result.get(10, TimeUnit.SECONDS).startsWith("ffmpeg version"));
                        assertEquals(List.of(FfmpegRunner.Phase.WAITING, FfmpegRunner.Phase.RUNNING), phases);
                    }
                } finally { held.close(); }
            }
        } finally { worker.shutdownNow(); }
    }

    @Test
    @DisplayName("探测调用实际执行安装记录里的独立 ffprobe")
    void executesIndependentProbePath() throws Exception {
        var installation = new FfmpegInstallation(directory.resolve("missing/ffmpeg"), Path.of(javaCommand()),
                directory, FfmpegInstallation.Source.CUSTOM);
        var resolver = new FfmpegCommandResolverAdapter(() -> java.util.Optional.of(installation), () -> "unused");
        var runner = new FfmpegRunnerAdapter(resolver, new FfmpegProcessGateAdapter(new FfmpegProperties()));
        assertTrue(runner.run(FfmpegRunner.Tool.FFPROBE, List.of("-version"), directory, null, 0,
                Duration.ofSeconds(10), () -> false).contains("version"));
    }

    @Test
    @DisplayName("进度逐行只交付一次，标准错误保留有界尾部，失败带出诊断")
    void boundedProgressAndDiagnostics() throws Exception {
        var runner = fixtureRunner();
        var lines = new java.util.ArrayList<String>();
        Thread caller = Thread.currentThread();
        String diagnostic = runner.run(FfmpegRunner.Tool.FFMPEG, fixtureArgs("progress"),
                directory, null, 0, Duration.ofSeconds(10), () -> false, null, line -> {
                    assertSame(caller, Thread.currentThread());
                    lines.add(line);
                });
        assertEquals(2001, lines.size());
        assertEquals("out_time_ms=0", lines.get(0));
        assertEquals("progress=end", lines.get(lines.size() - 1));
        assertTrue(diagnostic.endsWith("diagnostic-tail"));
        assertEquals(65_536, diagnostic.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        var failure = assertThrows(java.io.IOException.class, () -> runner.run(FfmpegRunner.Tool.FFMPEG,
                fixtureArgs("failure"), directory, null, 0, Duration.ofSeconds(10), () -> false, null, line -> {}));
        assertTrue(failure.getMessage().contains("encoder-failure"));
        assertThrows(java.io.IOException.class, () -> runner.run(FfmpegRunner.Tool.FFMPEG,
                fixtureArgs("long-line"), directory, null, 0, Duration.ofSeconds(10), () -> false, null, line -> {}));
    }

    @Test
    @DisplayName("媒体超时回收父子进程并归还共享额度")
    void timeoutTerminatesProcessTree() throws Exception {
        var runner = fixtureRunner();
        Path pid = directory.resolve("child.pid");
        assertThrows(java.io.IOException.class, () -> runner.run(FfmpegRunner.Tool.FFMPEG,
                fixtureArgs(pid.toString()), directory, null, 0, Duration.ofSeconds(3), () -> false));
        assertTrue(Files.isRegularFile(pid));
        long child = Long.parseLong(Files.readString(pid));
        assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
        assertTrue(runner.run(FfmpegRunner.Tool.FFMPEG, fixtureArgs("alive"),
                directory, null, 0, Duration.ofSeconds(5), () -> false).endsWith("alive"));
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", FfmpegLocator.isWindows() ? "java.exe" : "java").toString();
    }

    private static List<String> fixtureArgs(String mode) {
        return List.of("-cp", System.getProperty("java.class.path"), OutputFixture.class.getName(), mode);
    }

    private FfmpegRunnerAdapter fixtureRunner() {
        var properties = new FfmpegProperties();
        properties.setMaxConcurrent(1);
        return new FfmpegRunnerAdapter(() -> new ResolvedFfmpegCommand(javaCommand(), ResolvedFfmpegCommand.Source.SYSTEM),
                new FfmpegProcessGateAdapter(properties));
    }

    public static final class OutputFixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "progress" -> {
                    for (int i = 0; i < 2000; i++) System.out.println("out_time_ms=" + i);
                    System.out.print("progress=end");
                    System.err.print("x".repeat(100_000) + "diagnostic-tail");
                }
                case "failure" -> { System.err.print("encoder-failure"); System.exit(7); }
                case "long-line" -> { System.out.print("x".repeat(4097)); System.out.flush(); Thread.sleep(10_000); }
                case "alive" -> System.out.print("alive");
                case "child" -> Thread.sleep(60_000);
                default -> {
                    var command = new java.util.ArrayList<String>();
                    command.add(javaCommand());
                    command.addAll(fixtureArgs("child"));
                    Process child = new ProcessBuilder(command).start();
                    Files.writeString(Path.of(args[0]), Long.toString(child.pid()));
                    Thread.sleep(60_000);
                }
            }
        }
    }

    private FfmpegRunnerAdapter runner() {
        var installation = FfmpegLocator.locate();
        assumeTrue(installation.isPresent() && installation.get().ffprobePath() != null, "FFmpeg and ffprobe required");
        var properties = new FfmpegProperties();
        properties.setMaxConcurrent(1);
        return new FfmpegRunnerAdapter(() -> new ResolvedFfmpegCommand(installation.orElseThrow().ffmpegPath().toString(),
                ResolvedFfmpegCommand.Source.SYSTEM), new FfmpegProcessGateAdapter(properties));
    }

    @Test
    @DisplayName("原生解码器不支持的视频通过 FFmpeg 生成缓存且不改动源文件")
    void videoFallbackProducesThumbnailAndRemovesTemporaryFile() throws Exception {
        var runner = runner();
        Path video = directory.resolve("animation.mp4");
        runner.run(FfmpegRunner.Tool.FFMPEG, List.of("-y", "-v", "error", "-f", "lavfi", "-i",
                        "testsrc=size=16x16:rate=1", "-t", "1", "-c:v", "mpeg4", "-pix_fmt", "yuv420p", video.toString()),
                null, video, 1_000_000, Duration.ofSeconds(30), () -> false);
        byte[] original = Files.readAllBytes(video);
        try (var paths = org.mockito.Mockito.mockStatic(top.sywyar.pixivdownload.config.RuntimeFiles.class)) {
            paths.when(top.sywyar.pixivdownload.config.RuntimeFiles::galleryThumbnailDirectory).thenReturn(directory.resolve("cache"));
            var decoder = new top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaDecoder(runner, new com.fasterxml.jackson.databind.ObjectMapper());
            assertEquals(8, decoder.read(video, 8).getWidth());
            assertArrayEquals(original, Files.readAllBytes(video));
            try (var files = Files.list(directory.resolve("cache"))) { assertEquals(0, files.count()); }
        }
    }

    @Test
    @DisplayName("没有伴随缩略图的静态 WebP 可生成预览并计算哈希")
    void staticWebpFallbackPreservesSource() throws Exception {
        var runner = runner();
        Path png = directory.resolve("source.png");
        Path webp = directory.resolve("source.webp");
        ImageIO.write(new BufferedImage(17, 13, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
        runner.run(FfmpegRunner.Tool.FFMPEG, List.of("-y", "-v", "error", "-i", png.toString(), "-c:v", "libwebp", webp.toString()),
                null, webp, 1_000_000, Duration.ofSeconds(30), () -> false);
        byte[] original = Files.readAllBytes(webp);
        try (var paths = org.mockito.Mockito.mockStatic(top.sywyar.pixivdownload.config.RuntimeFiles.class)) {
            paths.when(top.sywyar.pixivdownload.config.RuntimeFiles::galleryThumbnailDirectory).thenReturn(directory.resolve("cache"));
            var decoder = new top.sywyar.pixivdownload.core.asset.artwork.ArtworkMediaDecoder(runner, new com.fasterxml.jackson.databind.ObjectMapper());
            BufferedImage thumbnail = decoder.read(webp, 8);
            assertEquals(8, thumbnail.getWidth());
            assertTrue(top.sywyar.pixivdownload.core.hash.ImageHasher.dHash(decoder.read(webp, 0)).isPresent());
            assertArrayEquals(original, Files.readAllBytes(webp));
            if (Files.isDirectory(directory.resolve("cache"))) {
                try (var files = Files.list(directory.resolve("cache"))) { assertEquals(0, files.count()); }
            }
        }
    }
}
