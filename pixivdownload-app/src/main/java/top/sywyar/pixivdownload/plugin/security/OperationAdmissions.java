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
        return enter(Set.of(operation));
    }
    public synchronized Lease enter(Set<String> operations) {
        if (operations.stream().anyMatch(closed::contains)) throw new IllegalStateException("OPERATION_PUBLICATION_BUSY");
        operations.forEach(operation -> active.merge(operation, 1, Integer::sum));
        return new Lease(Set.copyOf(operations));
    }

    public final class Lease implements AutoCloseable {
        private Set<String> operations;
        private Lease(Set<String> operations) { this.operations = operations; }
        private OperationAdmissions owner() { return OperationAdmissions.this; }
        @Override public void close() {
            synchronized (OperationAdmissions.this) {
                if (operations == null) return;
                operations.forEach(operation -> active.compute(operation, (key, count) -> count == 1 ? null : count - 1));
                operations = null; OperationAdmissions.this.notifyAll();
            }
        }
    }

    public void closeAndDrain(Set<String> operations, Duration timeout) {
        closeAndDrain(operations, timeout, null);
    }

    /** 当前发布请求只免于等待自身，租约计数仍保留到请求和回调实际结束。 */
    public synchronized void closeAndDrain(Set<String> operations, Duration timeout, Lease publisher) {
        if (publisher != null && publisher.owner() != this) throw new IllegalArgumentException("OPERATION_ADMISSION_INVALID");
        if (operations.stream().anyMatch(closed::contains)) throw new IllegalStateException("OPERATION_PUBLICATION_BUSY");
        closed.addAll(operations);
        long until = System.nanoTime() + timeout.toNanos();
        try {
            while (operations.stream().anyMatch(operation -> active.getOrDefault(operation, 0)
                    > (publisher != null && publisher.operations != null && publisher.operations.contains(operation) ? 1 : 0))) {
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
