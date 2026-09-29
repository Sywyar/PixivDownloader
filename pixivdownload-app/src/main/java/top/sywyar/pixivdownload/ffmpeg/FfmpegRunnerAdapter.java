package top.sywyar.pixivdownload.ffmpeg;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegCommandResolver;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegProcessGate;
import top.sywyar.pixivdownload.core.ffmpeg.FfmpegRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
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
        return run(tool, arguments, workingDirectory, output, maximumOutputBytes, timeout, cancelled, progress, null);
    }

    @Override
    public String run(Tool tool, List<String> arguments, Path workingDirectory, Path output,
                      long maximumOutputBytes, Duration timeout, BooleanSupplier cancelled,
                      Consumer<Phase> progress, Consumer<String> outputLine) throws IOException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || output != null && maximumOutputBytes <= 0) throw new IllegalArgumentException("Invalid media budget");
        checkCancelled(cancelled);
        String command = resolver.resolve(tool).command();
        Path executable = Path.of(command);
        if (executable.getParent() != null) {
            command = executable.toAbsolutePath().toString();
        }
        List<String> argv = new ArrayList<>();
        argv.add(command);
        argv.addAll(arguments);
        checkCancelled(cancelled);
        if (progress != null) progress.accept(Phase.WAITING);
        FfmpegProcessGate.Permit permit = gate.acquire(cancelled);
        Process process = null;
        List<Thread> readers = new ArrayList<>();
        var descendants = new LinkedHashMap<Long, ProcessHandle>();
        try {
            checkCancelled(cancelled);
            ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(outputLine == null);
            if (workingDirectory != null) builder.directory(workingDirectory.toFile());
            process = builder.start();
            if (progress != null) progress.accept(Phase.RUNNING);
            process.getOutputStream().close();
            Process running = process;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            var lines = new ArrayBlockingQueue<String>(64);
            var readFailure = new AtomicReference<IOException>();
            readers.add(readOutput(running.getInputStream(), captured, outputLine == null ? null : lines, readFailure));
            if (outputLine != null) readers.add(readOutput(running.getErrorStream(), captured, null, readFailure));
            long deadline = System.nanoTime() + timeout.toNanos();
            long drainDeadline = Long.MAX_VALUE;
            while (true) {
                running.descendants().forEach(child -> descendants.put(child.pid(), child));
                checkCancelled(cancelled);
                for (int i = 0; outputLine != null && i < 64; i++) {
                    String line = lines.poll();
                    if (line == null) break;
                    checkCancelled(cancelled);
                    outputLine.accept(line);
                }
                if (readFailure.get() != null) throw readFailure.get();
                if (output != null && Files.exists(output) && Files.size(output) > maximumOutputBytes) {
                    throw new IOException("Media output byte limit exceeded");
                }
                if (System.nanoTime() >= deadline) throw new IOException("Media processing timed out");
                if (running.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (readers.stream().noneMatch(Thread::isAlive) && lines.isEmpty()) break;
                    if (drainDeadline == Long.MAX_VALUE) drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    if (System.nanoTime() >= drainDeadline) throw new IOException("Media output drain timed out");
                    Thread.sleep(1);
                }
            }
            if (readFailure.get() != null) throw readFailure.get();
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
            readers.forEach(Thread::interrupt);
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
                CompletableFuture<Void> exited = CompletableFuture.allOf(
                        CompletableFuture.allOf(handles.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new)),
                        process.onExit());
                exited.thenRun(permit::close);
                boolean interrupted = Thread.interrupted();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                try {
                    while (!exited.isDone() && System.nanoTime() < deadline) {
                        try { exited.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                        catch (InterruptedException ignored) { interrupted = true; }
                        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) { break; }
                    }
                    for (Thread reader : readers) {
                        while (reader.isAlive() && System.nanoTime() < deadline) {
                            try { reader.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))); }
                            catch (InterruptedException ignored) { interrupted = true; }
                        }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static Thread readOutput(InputStream stream, ByteArrayOutputStream captured,
                                     ArrayBlockingQueue<String> lines, AtomicReference<IOException> failure) {
        Thread reader = new Thread(() -> {
            try (stream) {
                byte[] buffer = new byte[4096];
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    if (lines == null) {
                        synchronized (captured) {
                            // 只保留最后 64 KiB，持续排空诊断管道。
                            if (captured.size() + count > MAX_CAPTURE_BYTES) {
                                byte[] previous = captured.toByteArray();
                                captured.reset();
                                int keep = MAX_CAPTURE_BYTES - count;
                                captured.write(previous, previous.length - keep, keep);
                            }
                            captured.write(buffer, 0, count);
                        }
                    } else {
                        for (int i = 0; i < count; i++) {
                            if (buffer[i] == '\n') {
                                lines.put(line.toString(StandardCharsets.UTF_8).stripTrailing());
                                line.reset();
                            } else {
                                if (line.size() == 4096) throw new IOException("Media progress line byte limit exceeded");
                                line.write(buffer[i]);
                            }
                        }
                    }
                }
                if (lines != null && line.size() > 0) lines.put(line.toString(StandardCharsets.UTF_8));
            } catch (IOException error) {
                failure.compareAndSet(null, error);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "ffmpeg-output");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("Media processing cancelled");
        }
    }
}
