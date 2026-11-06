package top.sywyar.pixivdownload.translation.machine;

import java.util.concurrent.TimeUnit;

/** 同一引擎的手动与自动请求共享准入、发送间隔和限流冷却。 */
public final class TranslationRequestGate implements AutoCloseable {
    private final int concurrency;
    private final long intervalNanos;
    private int active;
    private long nextStart = System.nanoTime();
    private long cooldownUntil = nextStart;
    private boolean closed;

    public TranslationRequestGate(int concurrency, int intervalMs) {
        this.concurrency = concurrency;
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMs);
    }

    public synchronized boolean acquire(int cost) throws InterruptedException {
        while (true) {
            if (closed) throw new InterruptedException("Translation client closed");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            long now = System.nanoTime();
            long cooldown = cooldownUntil - now;
            // 长期配额冷却直接返回，避免所有任务占着工作线程等待。
            if (cooldown > TimeUnit.MINUTES.toNanos(1)) return false;
            long waitNanos = Math.max(nextStart - now, cooldown);
            if (active < concurrency && waitNanos <= 0) {
                active++;
                nextStart = System.nanoTime() + intervalNanos * cost;
                return true;
            }
            if (active >= concurrency) wait();
            else TimeUnit.NANOSECONDS.timedWait(this, waitNanos);
        }
    }

    public synchronized void release() { active--; notifyAll(); }

    public synchronized void coolDown(long millis) {
        long candidate = System.nanoTime() + Math.min(TimeUnit.MILLISECONDS.toNanos(millis), Long.MAX_VALUE / 4);
        if (candidate - cooldownUntil > 0) cooldownUntil = candidate;
        notifyAll();
    }

    @Override public synchronized void close() { closed = true; notifyAll(); }
}
