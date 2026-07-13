package top.sywyar.pixivdownload.plugin.lifecycle.request;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Request admission state for one serving identity; contains no plugin-owned object. */
final class PluginRequestLeaseState {

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

    private record State(
            boolean accepting,
            LeaseToken[] tokens,
            PluginRequestGenerationDrain drain) {
    }

    private static final long AWAIT_RECHECK_MILLIS = 50L;

    private final Runnable beforeAcquirePublishProbe;
    private final Runnable beforeReleaseProbe;
    private final Runnable afterReleaseProbe;
    private State state = new State(true, new LeaseToken[0], null);

    PluginRequestLeaseState() {
        this(() -> {
        }, () -> {
        }, () -> {
        });
    }

    PluginRequestLeaseState(
            Runnable beforeAcquirePublishProbe,
            Runnable beforeReleaseProbe,
            Runnable afterReleaseProbe) {
        this.beforeAcquirePublishProbe = Objects.requireNonNull(
                beforeAcquirePublishProbe, "request acquire publish probe");
        this.beforeReleaseProbe = Objects.requireNonNull(
                beforeReleaseProbe, "request before-release probe");
        this.afterReleaseProbe = Objects.requireNonNull(
                afterReleaseProbe, "request after-release probe");
    }

    synchronized boolean tryAcquire(LeaseToken token) {
        State current = state;
        if (!current.accepting()) {
            return false;
        }
        requireFresh(token);
        State next = new State(true, appendActive(current.tokens(), token), current.drain());
        beforeAcquirePublishProbe.run();
        state = next;
        return true;
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

    synchronized PluginRequestGenerationDrain withdraw(PluginRequestOwner owner) {
        State current = state;
        PluginRequestGenerationDrain drain = current.drain() == null
                ? new PluginRequestGenerationDrain(owner, this)
                : current.drain();
        state = new State(false, current.tokens(), drain);
        if (isDrainedState(state)) {
            notifyAll();
        }
        return drain;
    }

    synchronized boolean isAccepting() {
        return state.accepting();
    }

    synchronized boolean isDrained() {
        return isDrainedState(state);
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

    private static void requireFresh(LeaseToken token) {
        if (token == null || !token.isActive()) {
            throw new IllegalStateException("invalid plugin request lease token");
        }
    }

    private static LeaseToken[] appendActive(LeaseToken[] current, LeaseToken added) {
        int active = activeCount(current);
        LeaseToken[] next = new LeaseToken[active + 1];
        int index = 0;
        for (LeaseToken token : current) {
            if (token.isActive()) {
                next[index++] = token;
            }
        }
        next[index] = added;
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

    private static boolean isDrainedState(State state) {
        return activeCount(state.tokens()) == 0;
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
