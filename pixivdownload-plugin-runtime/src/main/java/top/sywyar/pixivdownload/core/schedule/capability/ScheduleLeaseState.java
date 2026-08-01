package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;

import java.util.Objects;

/** 单次 owner publication 的 admission 与取消槽；只保存宿主状态，不保存插件 Bean。 */
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

    /** 预分配的宿主 token；存活性只由共享根状态与可选分支决定。 */
    static final class LeaseToken {
        private final ScheduleLeaseRoot root;
        private final ScheduleLeaseBranch branch;

        static LeaseToken root(ScheduleLeaseRoot root) {
            return new LeaseToken(root, null);
        }

        static LeaseToken branch(ScheduleLeaseRoot root, ScheduleLeaseBranch branch) {
            return new LeaseToken(root, branch);
        }

        private LeaseToken(ScheduleLeaseRoot root, ScheduleLeaseBranch branch) {
            this.root = Objects.requireNonNull(root, "schedule lease root");
            this.branch = branch;
        }

        CancellationSignal signal() {
            return root.cancellation();
        }

        boolean canRegister() {
            return branch == null ? root.canActivateRoot() : branch.canRegister();
        }

        boolean isActive() {
            return branch == null ? root.isLive() : branch.isActive();
        }
    }

    private record State(boolean accepting, LeaseToken[] tokens) {
    }

    private static final long AWAIT_RECHECK_MILLIS = 50L;

    private final Runnable beforeAcquirePublishProbe;
    private final Runnable beforeReleaseProbe;
    private final Runnable afterReleaseProbe;
    private State state = new State(true, new LeaseToken[0]);

    ScheduleLeaseState() {
        this(() -> {
        }, () -> {
        }, () -> {
        });
    }

    ScheduleLeaseState(
            Runnable beforeAcquirePublishProbe,
            Runnable beforeReleaseProbe,
            Runnable afterReleaseProbe) {
        this.beforeAcquirePublishProbe = Objects.requireNonNull(
                beforeAcquirePublishProbe, "schedule acquire publish probe");
        this.beforeReleaseProbe = Objects.requireNonNull(
                beforeReleaseProbe, "schedule before-release probe");
        this.afterReleaseProbe = Objects.requireNonNull(
                afterReleaseProbe, "schedule after-release probe");
    }

    synchronized boolean tryAcquire(LeaseToken token) {
        State current = state;
        if (!current.accepting() || token == null || !token.canRegister()) {
            return false;
        }
        requireNotRegistered(current.tokens(), token);
        State next = new State(true, appendActive(current.tokens(), token));
        beforeAcquirePublishProbe.run();
        state = next;
        return true;
    }

    synchronized void release(LeaseToken token) {
        if (!contains(state.tokens(), token)) {
            return;
        }
        beforeReleaseProbe.run();
        state = new State(state.accepting(), remove(state.tokens(), token));
        afterReleaseProbe.run();
        notifyAll();
    }

    synchronized void retire() {
        State current = state;
        LeaseToken[] active = compactActive(current.tokens());
        state = new State(false, active);
        for (int index = 0; index < active.length; index++) {
            active[index].signal().cancel();
        }
        if (activeCount(active) == 0) {
            notifyAll();
        }
    }

    synchronized boolean isAccepting() {
        return state.accepting();
    }

    synchronized boolean isDrained() {
        return activeCount(state.tokens()) == 0;
    }

    synchronized int activeLeaseCount() {
        return activeCount(state.tokens());
    }

    synchronized boolean awaitDrained(long deadlineNanos) {
        while (activeCount(state.tokens()) != 0) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return false;
            }
            long bounded = Math.min(remaining, AWAIT_RECHECK_MILLIS * 1_000_000L);
            long millis = bounded / 1_000_000L;
            int nanos = (int) (bounded % 1_000_000L);
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
        while (activeCount(state.tokens()) != 0) {
            try {
                wait(AWAIT_RECHECK_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static void requireNotRegistered(LeaseToken[] current, LeaseToken token) {
        if (contains(current, token)) {
            throw new IllegalStateException("invalid schedule lease token");
        }
    }

    private static LeaseToken[] appendActive(LeaseToken[] current, LeaseToken added) {
        int active = activeCount(current);
        LeaseToken[] next = new LeaseToken[active + 1];
        int target = 0;
        for (int index = 0; index < current.length; index++) {
            if (current[index].isActive()) {
                next[target++] = current[index];
            }
        }
        next[target] = added;
        return next;
    }

    private static LeaseToken[] compactActive(LeaseToken[] current) {
        int active = activeCount(current);
        if (active == current.length) {
            return current;
        }
        LeaseToken[] next = new LeaseToken[active];
        int target = 0;
        for (int index = 0; index < current.length; index++) {
            if (current[index].isActive()) {
                next[target++] = current[index];
            }
        }
        return next;
    }

    private static LeaseToken[] remove(LeaseToken[] current, LeaseToken removed) {
        LeaseToken[] next = new LeaseToken[current.length - 1];
        int target = 0;
        for (int index = 0; index < current.length; index++) {
            if (current[index] != removed) {
                next[target++] = current[index];
            }
        }
        return next;
    }

    private static boolean contains(LeaseToken[] tokens, LeaseToken expected) {
        for (int index = 0; index < tokens.length; index++) {
            if (tokens[index] == expected) {
                return true;
            }
        }
        return false;
    }

    private static int activeCount(LeaseToken[] tokens) {
        int count = 0;
        for (int index = 0; index < tokens.length; index++) {
            if (tokens[index].isActive()) {
                count = Math.incrementExact(count);
            }
        }
        return count;
    }
}
