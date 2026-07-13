package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-only admission state. It never contains a plugin target or classloader. */
final class ExternalCapabilityLeaseState {

    /** Preallocated tombstone token; release is one allocation-free CAS. */
    static final class LeaseToken {
        private final AtomicBoolean active = new AtomicBoolean(true);

        boolean isActive() {
            return active.get();
        }

        boolean deactivate() {
            return active.compareAndSet(true, false);
        }
    }

    private record State(boolean accepting, LeaseToken[] tokens) {
    }

    private static final long AWAIT_RECHECK_MILLIS = 50L;

    private final Runnable beforeAcquirePublishProbe;
    private final Runnable beforeReleaseProbe;
    private final Runnable afterReleaseProbe;
    private final Runnable afterWithdrawPublishProbe;
    private State state = new State(false, new LeaseToken[0]);

    ExternalCapabilityLeaseState() {
        this(() -> {
        }, () -> {
        }, () -> {
        }, () -> {
        });
    }

    ExternalCapabilityLeaseState(
            Runnable beforeAcquirePublishProbe,
            Runnable beforeReleaseProbe,
            Runnable afterReleaseProbe) {
        this(beforeAcquirePublishProbe, beforeReleaseProbe, afterReleaseProbe, () -> {
        });
    }

    ExternalCapabilityLeaseState(
            Runnable beforeAcquirePublishProbe,
            Runnable beforeReleaseProbe,
            Runnable afterReleaseProbe,
            Runnable afterWithdrawPublishProbe) {
        this.beforeAcquirePublishProbe = Objects.requireNonNull(
                beforeAcquirePublishProbe, "capability acquire publish probe");
        this.beforeReleaseProbe = Objects.requireNonNull(
                beforeReleaseProbe, "capability before-release probe");
        this.afterReleaseProbe = Objects.requireNonNull(
                afterReleaseProbe, "capability after-release probe");
        this.afterWithdrawPublishProbe = Objects.requireNonNull(
                afterWithdrawPublishProbe, "capability withdraw publish probe");
    }

    synchronized void publish() {
        State current = state;
        if (current.accepting() || activeCount(current.tokens()) != 0) {
            throw new IllegalStateException("external capability lease state cannot be republished");
        }
        state = new State(true, compactActive(current.tokens()));
    }

    synchronized boolean tryAcquire(LeaseToken token) {
        State current = state;
        if (!current.accepting()) {
            return false;
        }
        requireFresh(token);
        State next = new State(true, appendActive(current.tokens(), token));
        beforeAcquirePublishProbe.run();
        state = next;
        return true;
    }

    synchronized void withdraw() {
        State current = state;
        if (!current.accepting()) {
            return;
        }
        state = new State(false, current.tokens());
        afterWithdrawPublishProbe.run();
        if (activeCount(current.tokens()) == 0) {
            notifyAll();
        }
    }

    synchronized void release(LeaseToken token) {
        if (!contains(state.tokens(), token)) {
            token.deactivate();
            return;
        }
        if (!token.isActive()) {
            return;
        }
        beforeReleaseProbe.run();
        if (!token.deactivate()) {
            return;
        }
        afterReleaseProbe.run();
        notifyAll();
    }

    synchronized boolean isDrained() {
        State current = state;
        return !current.accepting() && activeCount(current.tokens()) == 0;
    }

    synchronized int activeLeaseCount() {
        return activeCount(state.tokens());
    }

    synchronized boolean awaitDrained(long deadlineNanos) {
        while (!isDrainedState(state)) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return false;
            }
            long bounded = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(AWAIT_RECHECK_MILLIS));
            long millis = TimeUnit.NANOSECONDS.toMillis(bounded);
            int nanos = (int) (bounded - TimeUnit.MILLISECONDS.toNanos(millis));
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
        while (!isDrainedState(state)) {
            try {
                wait(AWAIT_RECHECK_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    synchronized boolean await(Duration timeout) throws InterruptedException {
        if (isDrainedState(state)) {
            return true;
        }
        long remaining = timeout.toNanos();
        long deadline = System.nanoTime() + remaining;
        while (!isDrainedState(state) && remaining > 0L) {
            long bounded = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(AWAIT_RECHECK_MILLIS));
            long millis = TimeUnit.NANOSECONDS.toMillis(bounded);
            int nanos = (int) (bounded - TimeUnit.MILLISECONDS.toNanos(millis));
            wait(millis, nanos);
            remaining = deadline - System.nanoTime();
        }
        return isDrainedState(state);
    }

    private static boolean isDrainedState(State state) {
        return !state.accepting() && activeCount(state.tokens()) == 0;
    }

    private static void requireFresh(LeaseToken token) {
        if (token == null || !token.isActive()) {
            throw new IllegalStateException("invalid external capability lease token");
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
