package top.sywyar.pixivdownload.download.media;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 扫描夹具只由测试释放；取消通知不能替代实际退出。 */
final class ScanGate implements AutoCloseable {
    final CountDownLatch entered = new CountDownLatch(1);
    final LinkedBlockingQueue<Boolean> interruptions = new LinkedBlockingQueue<>();
    final CompletableFuture<Void> exited = new CompletableFuture<>();
    private final CountDownLatch release = new CountDownLatch(1);

    void awaitRelease() {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        entered.countDown();
        try {
            while (true) {
                try {
                    if (!release.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        throw new AssertionError("测试未释放扫描退出闸门");
                    }
                    break;
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                    interruptions.add(true);
                }
            }
        } catch (RuntimeException | Error failure) {
            exited.completeExceptionally(failure);
            throw failure;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        exited.complete(null);
    }

    @Override
    public void close() {
        release.countDown();
    }
}
