package top.sywyar.pixivdownload.ffmpeg;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegCommandResolver;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegProcessGate;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Component
public final class FfmpegRunnerAdapter implements FfmpegRunner {
    private static final int MAX_CAPTURE_BYTES = 65_536;
    private final FfmpegCommandResolver resolver;
    private final FfmpegProcessGate gate;

    public FfmpegRunnerAdapter(FfmpegCommandResolver resolver, FfmpegProcessGate gate) {
        this.resolver = resolver;
        this.gate = gate;
    }

    @Override
    public String run(Tool tool, List<String> arguments, Path workingDirectory, Path output,
                      long maximumOutputBytes, Duration timeout, BooleanSupplier cancelled,
                      Consumer<Phase> progress) throws IOException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || output != null && maximumOutputBytes <= 0) throw new IllegalArgumentException("Invalid media budget");
        String command = resolver.resolve().command();
        Path executable = Path.of(command);
        if (tool == Tool.FFPROBE) {
            command = executable.getParent() == null ? FfmpegLocator.probeExecutableName()
                    : executable.toAbsolutePath().resolveSibling(FfmpegLocator.probeExecutableName()).toString();
        } else if (executable.getParent() != null) {
            command = executable.toAbsolutePath().toString();
        }
        List<String> argv = new ArrayList<>();
        argv.add(command);
        argv.addAll(arguments);
        checkCancelled(cancelled);
        if (progress != null) progress.accept(Phase.WAITING);
        FfmpegProcessGate.Permit permit = gate.acquire(cancelled);
        Process process = null;
        var descendants = new LinkedHashMap<Long, ProcessHandle>();
        try {
            checkCancelled(cancelled);
            ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
            if (workingDirectory != null) builder.directory(workingDirectory.toFile());
            process = builder.start();
            if (progress != null) progress.accept(Phase.RUNNING);
            process.getOutputStream().close();
            Process running = process;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (var stream = running.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = stream.read(buffer)) >= 0) {
                        synchronized (captured) {
                            // 保留尾部诊断，持续排空管道，防止日志使子进程阻塞或占用无界内存。
                            if (captured.size() + count > MAX_CAPTURE_BYTES) captured.reset();
                            captured.write(buffer, 0, count);
                        }
                    }
                } catch (IOException ignored) {
                    // 进程终止会关闭管道，退出状态由等待线程判定。
                }
            }, "ffmpeg-output");
            reader.setDaemon(true);
            reader.start();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                running.descendants().forEach(child -> descendants.put(child.pid(), child));
                checkCancelled(cancelled);
                if (output != null && Files.exists(output) && Files.size(output) > maximumOutputBytes) {
                    throw new IOException("Media output byte limit exceeded");
                }
                if (System.nanoTime() >= deadline) throw new IOException("Media processing timed out");
                if (running.waitFor(100, TimeUnit.MILLISECONDS)) break;
            }
            reader.join(2000);
            String diagnostic;
            synchronized (captured) {
                diagnostic = captured.toString(StandardCharsets.UTF_8);
            }
            if (running.exitValue() != 0) throw new IOException("Media process failed (" + running.exitValue() + "): " + diagnostic);
            if (output != null && (!Files.isRegularFile(output) || Files.size(output) == 0
                    || Files.size(output) > maximumOutputBytes)) throw new IOException("Invalid media output");
            return diagnostic;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Media processing cancelled");
        } finally {
            if (process == null) {
                permit.close();
            } else {
                process.descendants().forEach(child -> descendants.put(child.pid(), child));
                descendants.put(process.pid(), process.toHandle());
                List<ProcessHandle> handles = new ArrayList<>(descendants.values());
                for (ProcessHandle handle : handles) {
                    if (handle.isAlive()) handle.destroyForcibly();
                }
                // 即使等待方被中断，也只能在所有已观察到的进程退出后归还共享额度。
                CompletableFuture.allOf(handles.stream().map(ProcessHandle::onExit)
                        .toArray(CompletableFuture[]::new)).thenRun(permit::close);
                boolean interrupted = Thread.interrupted();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                try {
                    while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                        try { Thread.sleep(25); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("Media processing cancelled");
        }
    }
}
