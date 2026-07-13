package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 单次 owner publication 的计数与取消槽；只保存宿主状态，不保存插件 Bean。 */
final class ScheduleLeaseState {

    static final class CancellationSignal implements ScheduledCancellation {
        private volatile boolean cancelled;

        @Override
        public boolean isCancellationRequested() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
        }
    }

    private boolean accepting = true;
    private int activeLeases;
    private final Set<CancellationSignal> signals =
            Collections.newSetFromMap(new IdentityHashMap<>());

    synchronized boolean tryAcquire(CancellationSignal signal) {
        if (!accepting) {
            return false;
        }
        activeLeases++;
        signals.add(signal);
        return true;
    }

    synchronized void release(CancellationSignal signal) {
        if (!signals.remove(signal)) {
            return;
        }
        activeLeases--;
        if (activeLeases < 0) {
            throw new IllegalStateException("schedule lease count became negative");
        }
        if (activeLeases == 0) {
            notifyAll();
        }
    }

    synchronized void retire() {
        accepting = false;
        for (CancellationSignal signal : signals) {
            signal.cancel();
        }
        if (activeLeases == 0) {
            notifyAll();
        }
    }

    synchronized boolean isAccepting() {
        return accepting;
    }

    synchronized boolean isDrained() {
        return activeLeases == 0;
    }

    synchronized int activeLeaseCount() {
        return activeLeases;
    }

    synchronized boolean awaitDrained(long deadlineNanos) {
        while (activeLeases != 0) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            long millis = remaining / 1_000_000L;
            int nanos = (int) (remaining % 1_000_000L);
            try {
                wait(millis, nanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    synchronized boolean awaitDrained() {
        while (activeLeases != 0) {
            try {
                wait();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
