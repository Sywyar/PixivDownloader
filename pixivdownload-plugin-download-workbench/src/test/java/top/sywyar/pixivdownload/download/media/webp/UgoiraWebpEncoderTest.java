package top.sywyar.pixivdownload.download.media.webp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import top.sywyar.pixivdownload.download.media.MediaOutputSettings;
import top.sywyar.pixivdownload.download.media.UgoiraEncoderSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class UgoiraWebpEncoderTest {
    @TempDir Path directory;

    @Test
    @DisplayName("合并前累计预留包含分片和成品，单帧占位不挤占成品预算")
    void reservesMergeCopyAndAllowsSingleFramePlaceholder() throws Exception {
        Runner runner = (tool, args, cwd, output, maximum, timeout, cancelled, phases, lines) -> {
            phases.accept(FfmpegRunner.Phase.RUNNING);
            byte[] animation = WebpAnimationMuxerTest.animation(0, 1, 2);
            assertTrue(maximum >= animation.length);
            Files.write(output, animation);
            return "";
        };
        long sourceSize = WebpAnimationMuxerTest.animation(0, 1, 2).length;
        long finalSize = 44 + (sourceSize - 44) / 2;
        var reservations = new ArrayList<Long>();
        UgoiraWebpEncoder.encode(runner, frames().subList(0, 1), List.of(100), directory, directory,
                directory.resolve("out.webp"), new MediaOutputSettings(), new UgoiraEncoderSettings(),
                finalSize, Duration.ofSeconds(10), () -> false, event -> {}, reservations::add);
        assertEquals(finalSize, Files.size(directory.resolve("out.webp")));
        assertEquals(sourceSize + finalSize, reservations.get(reservations.size() - 1));
        Files.delete(directory.resolve("out.webp"));
        assertThrows(IOException.class, () -> UgoiraWebpEncoder.encode(
                runner, frames().subList(0, 1), List.of(100), directory, directory,
                directory.resolve("out.webp"), new MediaOutputSettings(), new UgoiraEncoderSettings(),
                finalSize, Duration.ofSeconds(10), () -> false, event -> {}, bytes -> {
                    if (bytes > sourceSize) throw new java.io.UncheckedIOException(new IOException("temporary budget"));
                }));
        assertFalse(Files.exists(directory.resolve("out.webp")));
        assertIntermediatesRemoved();
    }

    @Test
    @DisplayName("连续帧有界并行、进度只由父线程发布，单片失败不会发布成品")
    void parallelFramesAndParentProgress() throws Exception {
        CountDownLatch both = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        Thread caller = Thread.currentThread();
        List<Long> progress = new ArrayList<>();
        Runner runner = (tool, args, cwd, output, maximum, timeout, cancelled, phases, lines) -> {
            calls.incrementAndGet();
            assertEquals("1", args.get(args.indexOf("-threads") + 1));
            assertEquals("100", args.get(args.indexOf("-quality") + 1));
            phases.accept(FfmpegRunner.Phase.RUNNING);
            both.countDown();
            try { assertTrue(both.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new CancellationException(); }
            Path concat = cwd.resolve(args.get(args.indexOf("-i") + 1));
            var inputs = Files.readAllLines(concat).stream().filter(line -> line.startsWith("file ")).toList();
            assertEquals(2, inputs.size());
            boolean first = inputs.get(0).contains("f0.png");
            assertEquals("file '" + (first ? "f1.png" : "f3.png") + "'", inputs.get(1));
            lines.accept("out_time_us=34000");
            Files.write(output, WebpAnimationMuxerTest.animation(0, first ? 1 : 3, first ? 2 : 4));
            return "";
        };
        var settings = new UgoiraEncoderSettings();
        settings.setLosslessEffort(100);
        var media = new MediaOutputSettings();
        media.setWebpLossless(true);
        UgoiraWebpEncoder.encode(runner, frames(), List.of(34, 83, 1, 170), directory, directory,
                directory.resolve("out.webp"), media, settings, 4096, Duration.ofSeconds(10), () -> false, event -> {
                    assertSame(caller, Thread.currentThread());
                    if (!progress.isEmpty()) assertTrue(event.outTimeMs() >= progress.get(progress.size() - 1));
                    progress.add(event.outTimeMs());
                }, bytes -> {});
        assertEquals(2, calls.get());
        assertTrue(Files.size(directory.resolve("out.webp")) > 0);
        assertIntermediatesRemoved();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "failure", "timeout", "bytes"})
    @DisplayName("取消、失败、超时和累计超限均等待全部子线程退出后再清理")
    void drainsWorkersBeforeReturning(String cause) throws Exception {
        CountDownLatch both = new CountDownLatch(2);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger entered = new AtomicInteger();
        Runner runner = (tool, args, cwd, output, maximum, timeout, cancelled, phases, lines) -> {
            int slot = entered.incrementAndGet();
            active.incrementAndGet();
            try {
                phases.accept(FfmpegRunner.Phase.RUNNING);
                if (cause.equals("bytes")) Files.write(output, new byte[3000]);
                both.countDown();
                try {
                    assertTrue(both.await(5, TimeUnit.SECONDS));
                    if (cause.equals("failure") && slot == 1) throw new IOException("broken encoder");
                    while (!cancelled.getAsBoolean()) Thread.sleep(10);
                } catch (InterruptedException interrupted) {
                    throw new CancellationException();
                }
                return "";
            } finally {
                cleaning.countDown();
                boolean interrupted = Thread.interrupted();
                boolean done = false;
                while (!done) {
                    try { done = release.await(5, TimeUnit.SECONDS); assertTrue(done); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                active.decrementAndGet();
                if (interrupted) Thread.currentThread().interrupt();
            }
        };
        CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> {
            try {
                UgoiraWebpEncoder.encode(runner, frames(), List.of(34, 83, 1, 170), directory, directory,
                        directory.resolve("out.webp"), new MediaOutputSettings(), new UgoiraEncoderSettings(),
                        4096, Duration.ofMillis(cause.equals("timeout") ? 100 : 10000), cancel::get, event -> {}, bytes -> {});
                return null;
            } catch (Throwable error) { return error; }
        });
        try {
            assertTrue(both.await(5, TimeUnit.SECONDS));
            if (cause.equals("cancel")) cancel.set(true);
            assertTrue(cleaning.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            assertTrue(active.get() > 0);
        } finally {
            release.countDown();
        }
        Class<? extends Throwable> expected = cause.equals("cancel") ? CancellationException.class : IOException.class;
        assertInstanceOf(expected, result.get(5, TimeUnit.SECONDS));
        assertEquals(0, active.get());
        assertFalse(Files.exists(directory.resolve("out.webp")));
        assertIntermediatesRemoved();
    }

    @Test
    @DisplayName("已有分片完成后，纯排队等待不消耗作品的实际处理时间预算")
    void waitingForNextPermitDoesNotConsumeProcessingBudget() throws Exception {
        AtomicInteger entered = new AtomicInteger();
        CountDownLatch firstRan = new CountDownLatch(1);
        Runner runner = (tool, args, cwd, output, maximum, timeout, cancelled, phases, lines) -> {
            phases.accept(FfmpegRunner.Phase.WAITING);
            if (entered.getAndIncrement() > 0) {
                try {
                    assertTrue(firstRan.await(5, TimeUnit.SECONDS));
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
                    while (System.nanoTime() < until) {
                        assertFalse(cancelled.getAsBoolean());
                        Thread.sleep(10);
                    }
                } catch (InterruptedException interrupted) { throw new CancellationException(); }
            }
            phases.accept(FfmpegRunner.Phase.RUNNING);
            Files.write(output, WebpAnimationMuxerTest.animation(0, 1, 2));
            firstRan.countDown();
            return "";
        };
        UgoiraWebpEncoder.encode(runner, frames(), List.of(34, 83, 1, 170), directory, directory,
                directory.resolve("out.webp"), new MediaOutputSettings(), new UgoiraEncoderSettings(),
                4096, Duration.ofSeconds(1), () -> false, event -> {}, bytes -> {});
        assertEquals(2, entered.get());
        assertIntermediatesRemoved();
    }

    private List<Path> frames() throws IOException {
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(17, 13,
                java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", directory.resolve("f0.png").toFile());
        return List.of(directory.resolve("f0.png"), directory.resolve("f1.png"),
                directory.resolve("f2.png"), directory.resolve("f3.png"));
    }

    private void assertIntermediatesRemoved() {
        for (int index = 0; index < 2; index++) {
            assertFalse(Files.exists(directory.resolve("webp-" + index + ".txt")));
            assertFalse(Files.exists(directory.resolve("webp-" + index + ".part")));
        }
    }

    @FunctionalInterface
    interface Runner extends FfmpegRunner {
        @Override
        default String run(Tool tool, List<String> args, Path cwd, Path output, long maximum,
                           Duration timeout, BooleanSupplier cancelled, Consumer<Phase> phases) throws IOException {
            return run(tool, args, cwd, output, maximum, timeout, cancelled, phases, null);
        }
        @Override
        String run(Tool tool, List<String> args, Path cwd, Path output, long maximum, Duration timeout,
                   BooleanSupplier cancelled, Consumer<Phase> phases, Consumer<String> lines) throws IOException;
    }
}
