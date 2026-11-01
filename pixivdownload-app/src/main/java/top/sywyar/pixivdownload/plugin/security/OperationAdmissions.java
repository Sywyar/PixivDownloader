package top.sywyar.pixivdownload.plugin.security;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 发布只封闭受影响的操作；等待排空时释放本锁，不占用 registry 或生命周期锁。 */
@Component
public final class OperationAdmissions {
    private final Map<String, Integer> active = new HashMap<>();
    private final Set<String> closed = new HashSet<>();

    public synchronized Lease enter(String operation) {
        if (closed.contains(operation)) throw new IllegalStateException("OPERATION_PUBLICATION_BUSY");
        active.merge(operation, 1, Integer::sum);
        return new Lease(operation);
    }

    public final class Lease implements AutoCloseable {
        private String operation;
        private Lease(String operation) { this.operation = operation; }
        @Override public void close() {
            synchronized (OperationAdmissions.this) {
                if (operation == null) return;
                active.compute(operation, (key, count) -> count == 1 ? null : count - 1);
                operation = null; OperationAdmissions.this.notifyAll();
            }
        }
    }

    public synchronized void closeAndDrain(Set<String> operations, Duration timeout) {
        if (operations.stream().anyMatch(closed::contains)) throw new IllegalStateException("OPERATION_PUBLICATION_BUSY");
        closed.addAll(operations);
        long until = System.nanoTime() + timeout.toNanos();
        try {
            while (operations.stream().anyMatch(active::containsKey)) {
                long left = until - System.nanoTime();
                if (left <= 0) throw new IllegalStateException("OPERATION_DRAIN_TIMEOUT");
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); closed.removeAll(operations);
            throw new IllegalStateException("OPERATION_DRAIN_INTERRUPTED");
        } catch (RuntimeException failure) { closed.removeAll(operations); throw failure; }
    }

    public synchronized void reopen(Set<String> operations) { closed.removeAll(operations); }
}
