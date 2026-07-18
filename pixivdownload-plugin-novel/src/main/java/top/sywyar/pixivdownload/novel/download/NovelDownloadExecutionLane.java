package top.sywyar.pixivdownload.novel.download;

import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * 小说下载统一执行通道。
 *
 * <p>交互式下载与需要同步等待的后台调用都通过同一个父执行器运行，从而共享同一硬并发上限。
 * 已在本通道工作线程内的嵌套同步调用直接执行，避免固定线程池自提交造成死锁。
 */
public final class NovelDownloadExecutionLane {

    private final TaskExecutor taskExecutor;
    private final int capacity;
    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

    public NovelDownloadExecutionLane(TaskExecutor taskExecutor, int capacity) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor, "taskExecutor");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        ProxyScope proxyScope = ProxyScope.capture();
        taskExecutor.execute(() -> runInLane(proxyScope, task));
    }

    public <T> T executeAndWait(Callable<T> task) throws Exception {
        Objects.requireNonNull(task, "task");
        if (Boolean.TRUE.equals(active.get())) {
            return task.call();
        }

        ProxyScope proxyScope = ProxyScope.capture();
        FutureTask<T> future = new FutureTask<>(task);
        taskExecutor.execute(() -> runInLane(proxyScope, future));
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("novel download lane failed", cause);
        }
    }

    private void runInLane(ProxyScope proxyScope, Runnable task) {
        proxyScope.run(() -> runWithActiveMarker(task));
    }

    private void runWithActiveMarker(Runnable task) {
        boolean previous = Boolean.TRUE.equals(active.get());
        active.set(true);
        try {
            task.run();
        } finally {
            if (previous) {
                active.set(true);
            } else {
                active.remove();
            }
        }
    }

    private record ProxyScope(boolean active, OutboundProxyEndpoint endpoint) {

        private static ProxyScope capture() {
            return new ProxyScope(OutboundProxyOverride.isActive(), OutboundProxyOverride.current());
        }

        private void run(Runnable task) {
            if (!active) {
                OutboundProxyOverride.runScoped(null, task);
            } else if (endpoint == null) {
                OutboundProxyOverride.runDirectScoped(task);
            } else {
                OutboundProxyOverride.runScoped(endpoint.hostName() + ":" + endpoint.port(), task);
            }
        }
    }
}
