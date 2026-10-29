package top.sywyar.pixivdownload.schedule;

import org.springframework.context.SmartLifecycle;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;

import java.util.Objects;

/** 应用关闭时先封闭计划准入、取消在途运行，待编排收尾后再关闭插件与执行池。 */
public final class ScheduleHostLifecycle implements SmartLifecycle {
    private final ScheduleRunState runState;
    private volatile boolean running;
    private int leases;

    public ScheduleHostLifecycle(ScheduleRunState runState) {
        this.runState = Objects.requireNonNull(runState, "runState");
    }

    @Override
    public synchronized void start() {
        if (leases != 0) throw new IllegalStateException("schedule host is still draining");
        running = true;
    }

    public synchronized Lease prepare() {
        if (!running) return null;
        leases++;
        return new Lease();
    }

    public synchronized boolean activate(Lease lease) {
        return lease != null && lease.host() == this && !lease.closed && running;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public void stop() {
        boolean interrupted = false;
        synchronized (this) {
            running = false;
            runState.requestCancelAll();
            // Spring 的异步 phase 超时不能让数据库或插件先于计划收尾被销毁。
            while (leases != 0) {
                try {
                    wait();
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** 只约束宿主编排生命周期；插件行为仍由自己的精确 capability lease 保护。 */
    public final class Lease implements AutoCloseable {
        private boolean closed;

        private ScheduleHostLifecycle host() {
            return ScheduleHostLifecycle.this;
        }

        public ScheduledCancellation cancellation() {
            return () -> !running;
        }

        @Override
        public void close() {
            synchronized (ScheduleHostLifecycle.this) {
                if (closed) return;
                closed = true;
                leases--;
                ScheduleHostLifecycle.this.notifyAll();
            }
        }
    }
}
