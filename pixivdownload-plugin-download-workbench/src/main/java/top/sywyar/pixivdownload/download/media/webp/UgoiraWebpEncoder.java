package top.sywyar.pixivdownload.download.media.webp;

import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;
import top.sywyar.pixivdownload.download.media.MediaOutputSettings;
import top.sywyar.pixivdownload.download.media.UgoiraEncoderSettings;
import top.sywyar.pixivdownload.download.media.UgoiraEncoding;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** 连续帧分片共享宿主进程额度；父任务负责预算、进度和所有子任务的退出。 */
public final class UgoiraWebpEncoder {
    private UgoiraWebpEncoder() {}

    public record Progress(boolean waiting, long outTimeMs) {}

    public static void encode(FfmpegRunner runner, List<Path> frames, List<Integer> delays,
                              Path directory, Path workingDirectory, Path output,
                              MediaOutputSettings media, UgoiraEncoderSettings settings,
                              long maximumBytes, Duration timeout, BooleanSupplier cancelled,
                              Consumer<Progress> progress, LongConsumer temporaryBytes) throws IOException {
        if (frames.isEmpty() || frames.size() != delays.size() || maximumBytes <= 0
                || timeout.isZero() || timeout.isNegative()
                || delays.stream().anyMatch(delay -> delay == null || delay <= 0 || delay > 0xffffff)) {
            throw new IllegalArgumentException("Invalid WebP encoding input");
        }
        int count = Math.min(settings.getParallelism(), Math.max(1, frames.size() / 2));
        long outputLimit = Math.min(maximumBytes, WebpAnimationMuxer.MAX_FILE_BYTES);
        // 单帧的中间容器包含占位帧，成品预算只限制保留的真实帧。
        long partsLimit = frames.size() == 1 ? Math.min(2 * outputLimit, WebpAnimationMuxer.MAX_FILE_BYTES)
                : outputLimit + 44L * (count - 1);
        String canvasScale;
        try (var input = ImageIO.createImageInputStream(frames.get(0).toFile())) {
            if (input == null) throw new IOException("Missing animation frame");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid animation frame");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                canvasScale = "scale=" + reader.getWidth(0) + ":" + reader.getHeight(0);
            } finally { reader.dispose(); }
        }
        List<WebpAnimationMuxer.Part> parts = new ArrayList<>();
        List<Path> lists = new ArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean();
        ProcessingClock clock = new ProcessingClock();
        AtomicInteger completed = new AtomicInteger();
        AtomicLongArray elapsed = new AtomicLongArray(count);
        Runnable check = () -> {
            if (stopped.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (clock.elapsedNanos() >= timeout.toNanos()) {
                throw new UncheckedIOException(new IOException("Media processing timed out"));
            }
        };
        var executor = new ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(count), task -> {
                    Thread thread = new Thread(task, "ugoira-webp");
                    thread.setDaemon(true);
                    return thread;
                });
        var completion = new ExecutorCompletionService<Void>(executor, new ArrayBlockingQueue<>(count));
        try {
            if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException();
            check.run();
            for (int index = 0; index < count; index++) {
                int from = frames.size() * index / count;
                int to = frames.size() * (index + 1) / count;
                Path list = directory.resolve("webp-" + index + ".txt");
                Path part = directory.resolve("webp-" + index + ".part");
                lists.add(list);
                parts.add(new WebpAnimationMuxer.Part(part, delays.subList(from, to)));
                StringBuilder concat = new StringBuilder();
                for (int frame = from; frame < to; frame++) {
                    concat.append("file '").append(frames.get(frame).getFileName()).append("'\n")
                            .append("option framerate 1000\n")
                            .append("duration ").append(delays.get(frame) / 1000.0).append('\n');
                }
                // 单帧也生成动画容器；合并时移除这一占位帧并恢复真实末帧时长。
                if (to - from == 1) concat.append("file '").append(frames.get(from).getFileName())
                        .append("'\noption framerate 1000\n");
                Files.writeString(list, concat, StandardCharsets.UTF_8);
            }
            Progress lastProgress = new Progress(true, 0);
            progress.accept(lastProgress);
            for (int index = 0; index < count; index++) {
                int slot = index;
                var part = parts.get(index);
                long duration = part.delays().stream().mapToLong(Integer::longValue).sum();
                var arguments = new ArrayList<>(List.of("-y", "-nostdin", "-nostats",
                        "-stats_period", "0.5", "-progress", "pipe:1",
                        "-threads", "1", "-filter_threads", "1",
                        "-f", "concat", "-safe", "0", "-i",
                        workingDirectory.relativize(lists.get(index).toAbsolutePath()).toString()));
                arguments.addAll(UgoiraEncoding.arguments("webp", media, settings.getLosslessEffort()));
                // 各分片沿用首帧画布，再应用作品缩放，避免分片起点改变输出尺寸。
                int filter = arguments.indexOf("-vf");
                if (filter < 0) arguments.addAll(List.of("-vf", canvasScale));
                else arguments.set(filter + 1, canvasScale + "," + arguments.get(filter + 1));
                arguments.addAll(List.of("-fps_mode", "passthrough", "-threads", "1",
                        workingDirectory.relativize(part.path().toAbsolutePath()).toString()));
                completion.submit(() -> {
                    AtomicBoolean active = new AtomicBoolean();
                    try {
                        check.run();
                        runner.run(FfmpegRunner.Tool.FFMPEG, arguments, workingDirectory, part.path(),
                                frames.size() == 1 ? partsLimit : outputLimit, timeout, () -> { check.run(); return false; },
                                phase -> {
                                    if (phase == FfmpegRunner.Phase.RUNNING && active.compareAndSet(false, true)) {
                                        clock.start();
                                    }
                                },
                                line -> {
                                    if (!line.startsWith("out_time_us=") && !line.startsWith("out_time_ms=")) return;
                                    try {
                                        long millis = Long.parseLong(line.substring(line.indexOf('=') + 1)) / 1000;
                                        elapsed.set(slot, Math.max(elapsed.get(slot), Math.min(duration, Math.max(0, millis))));
                                    } catch (NumberFormatException ignored) { }
                                });
                        check.run();
                        elapsed.set(slot, duration);
                        return null;
                    } finally {
                        completed.incrementAndGet();
                        if (active.get()) clock.stop();
                    }
                });
            }
            for (int remaining = count; remaining > 0;) {
                if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException();
                check.run();
                // 各分片仅多出一个 44 字节容器头，不能各自占用整份输出预算。
                long bytes = 0;
                for (var part : parts) if (Files.exists(part.path())) bytes += Files.size(part.path());
                if (bytes > partsLimit) throw new IOException("Media output byte limit exceeded");
                temporaryBytes.accept(bytes);
                var finished = completion.poll(100, TimeUnit.MILLISECONDS);
                long total = 0;
                for (int slot = 0; slot < count; slot++) total += elapsed.get(slot);
                Progress current = new Progress(clock.waiting() && completed.get() < count, total);
                if (clock.started() && !current.equals(lastProgress)) {
                    progress.accept(current);
                    lastProgress = current;
                }
                if (finished != null) {
                    finished.get();
                    remaining--;
                }
            }
            clock.start();
            try {
                long bytes = 0;
                for (var part : parts) bytes += Files.size(part.path());
                if (bytes > partsLimit) throw new IOException("Media output byte limit exceeded");
                // 合并期间分片仍存在；先预留整份副本，不能等写完才检查累计空间。
                temporaryBytes.accept(bytes + Math.min(bytes, outputLimit));
                WebpAnimationMuxer.merge(parts, output, outputLimit, () -> {
                    if (cancelled != null && cancelled.getAsBoolean()) throw new CancellationException();
                    check.run();
                });
            } finally { clock.stop(); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof UncheckedIOException io) throw io.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException(cause);
        } catch (UncheckedIOException io) {
            throw io.getCause();
        } finally {
            stopped.set(true);
            executor.shutdownNow();
            boolean interrupted = Thread.interrupted();
            try {
                // Future 取消不代表 runner 已退出，清理文件和释放父作品前必须排空。
                while (!executor.isTerminated()) {
                    try { executor.awaitTermination(100, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
                for (Path list : lists) Files.deleteIfExists(list);
                for (var part : parts) Files.deleteIfExists(part.path());
            }
        }
    }

    /** 并行运行按墙钟计时；全部分片排队时暂停，不能把其它作品的编码算入本作品。 */
    private static final class ProcessingClock {
        private int running;
        private long since;
        private long elapsed;

        synchronized void start() {
            if (running++ == 0) since = System.nanoTime();
        }

        synchronized void stop() {
            if (--running == 0) elapsed += System.nanoTime() - since;
        }

        synchronized long elapsedNanos() {
            return elapsed + (running == 0 ? 0 : System.nanoTime() - since);
        }

        synchronized boolean waiting() { return running == 0; }
        synchronized boolean started() { return since != 0; }
    }
}
