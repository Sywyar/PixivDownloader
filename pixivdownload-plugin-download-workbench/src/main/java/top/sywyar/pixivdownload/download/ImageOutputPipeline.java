package top.sywyar.pixivdownload.download;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 子任务寿命严格包含在作品任务内；退出前排空，不能把 Future 取消当成线程已经退出。 */
final class ImageOutputPipeline implements AutoCloseable {
    // ponytail: 每件作品最多两张在途图片；需要更高单作品吞吐时先校准累计内存与磁盘预算。
    static final int MAX_IN_FLIGHT = 2;
    private final ThreadPoolExecutor executor;
    private final ExecutorCompletionService<Void> completion;
    private int pending;
    private final BooleanSupplier cancelled;

    ImageOutputPipeline(boolean enabled, BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        executor = enabled ? new ThreadPoolExecutor(MAX_IN_FLIGHT, MAX_IN_FLIGHT,
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_IN_FLIGHT), task -> {
                    Thread thread = new Thread(task, "image-output");
                    thread.setDaemon(true);
                    return thread;
                }) : null;
        completion = enabled ? new ExecutorCompletionService<>(executor, new ArrayBlockingQueue<>(MAX_IN_FLIGHT)) : null;
    }

    void awaitCapacity() {
        checkCancelled();
        if (pending >= MAX_IN_FLIGHT) awaitOne();
    }

    void submit(Runnable task) {
        awaitCapacity();
        if (executor == null) task.run();
        else {
            completion.submit(task, null);
            pending++;
        }
    }

    void finish() {
        while (pending > 0) awaitOne();
        checkCancelled();
    }

    private void awaitOne() {
        for (;;) {
            checkCancelled();
            try {
                var result = completion.poll(100, TimeUnit.MILLISECONDS);
                if (result == null) continue;
                pending--;
                result.get();
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof Error error) throw error;
                if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(failure.getCause());
            }
        }
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    @Override
    public void close() {
        if (executor == null) return;
        executor.shutdownNow();
        boolean interrupted = Thread.interrupted();
        try {
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException cancellation) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
