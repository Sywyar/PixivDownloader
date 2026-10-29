package top.sywyar.pixivdownload.schedule.execution;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 宿主进程内按作品类型共享的并发限制器。同一实例应作为宿主单例复用，使不同计划任务仍受同一
 * {@code workType} 容量约束；不同作品类型各自计数、互不占用彼此的许可。
 */
public final class ScheduleWorkConcurrencyLimiter {

    private static final long CANCELLATION_POLL_MILLIS = 50L;

    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition capacityChanged = lock.newCondition();
    private final Map<String, TypeState> states = new HashMap<>();

    /**
     * 等待指定作品类型出现容量并取得一个许可。等待期间每隔很短时间检查一次取消信号；调用方动态传入更低
     * 上限时，既有执行可以自然排空，但在途数降到新上限以下之前不会再发放许可。
     */
    public Permit acquire(
            String workType,
            int limit,
            ScheduledCancellation cancellation) throws ScheduledExecutionException {
        String normalizedWorkType = normalizeWorkType(workType);
        if (limit <= 0) {
            throw new IllegalArgumentException("work concurrency limit must be positive");
        }
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancellationRequested();

        lock.lock();
        TypeState state = null;
        boolean requestRegistered = false;
        try {
            cancellation.throwIfCancellationRequested();
            state = states.computeIfAbsent(normalizedWorkType, ignored -> new TypeState());
            state.register(limit);
            requestRegistered = true;
            state.waiters++;
            capacityChanged.signalAll();
            while (state.active >= state.effectiveLimit()) {
                cancellation.throwIfCancellationRequested();
                try {
                    capacityChanged.await(CANCELLATION_POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ScheduledExecutionException.cancelled();
                }
            }
            cancellation.throwIfCancellationRequested();
            state.waiters--;
            state.active++;
            requestRegistered = false;
            return new Permit(this, normalizedWorkType, limit, state);
        } finally {
            if (requestRegistered) {
                state.waiters--;
                state.unregister(limit);
                removeIdleState(normalizedWorkType, state);
                capacityChanged.signalAll();
            }
            lock.unlock();
        }
    }

    private void release(Permit permit) {
        lock.lock();
        try {
            if (permit.closed) {
                return;
            }
            permit.closed = true;
            if (permit.state.active <= 0) {
                throw new IllegalStateException("work concurrency permit accounting underflow");
            }
            permit.state.active--;
            permit.state.unregister(permit.limit);
            removeIdleState(permit.workType, permit.state);
            capacityChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void removeIdleState(String workType, TypeState state) {
        if (state.active == 0 && state.waiters == 0
                && state.limits.isEmpty() && states.get(workType) == state) {
            states.remove(workType);
        }
    }

    private static String normalizeWorkType(String workType) {
        if (workType == null || workType.isBlank()) {
            throw new IllegalArgumentException("work type must not be blank");
        }
        return workType.trim();
    }

    private static final class TypeState {
        private final NavigableMap<Integer, Integer> limits = new TreeMap<>();
        private int active;
        private int waiters;

        private void register(int limit) {
            limits.merge(limit, 1, Integer::sum);
        }

        private void unregister(int limit) {
            Integer count = limits.get(limit);
            if (count == null || count <= 0) {
                throw new IllegalStateException("work concurrency limit accounting underflow");
            }
            if (count == 1) {
                limits.remove(limit);
            } else {
                limits.put(limit, count - 1);
            }
        }

        private int effectiveLimit() {
            return limits.firstKey();
        }
    }

    /** 可用于 try-with-resources、允许跨线程关闭且重复关闭无副作用的并发许可。 */
    public static final class Permit implements AutoCloseable {
        private final ScheduleWorkConcurrencyLimiter owner;
        private final String workType;
        private final int limit;
        private final TypeState state;
        private boolean closed;

        private Permit(
                ScheduleWorkConcurrencyLimiter owner,
                String workType,
                int limit,
                TypeState state) {
            this.owner = owner;
            this.workType = workType;
            this.limit = limit;
            this.state = state;
        }

        @Override
        public void close() {
            owner.release(this);
        }
    }
}
