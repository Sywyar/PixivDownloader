package top.sywyar.pixivdownload.plugin.lifecycle.request;

import java.util.Objects;

/** A request-scoped lease that keeps one serving generation alive until synchronous or Servlet async completion. */
public final class PluginRequestLease implements AutoCloseable {

    private enum Phase {
        NEW,
        ACTIVATING,
        ACTIVE,
        CLOSED
    }

    private final PluginRequestOwner owner;
    private final PluginRequestLeaseState state;
    private final PluginRequestLeaseState.LeaseToken leaseToken = new PluginRequestLeaseState.LeaseToken();
    private Phase phase = Phase.NEW;

    PluginRequestLease(PluginRequestOwner owner, PluginRequestLeaseState state) {
        this.owner = Objects.requireNonNull(owner, "plugin request owner");
        this.state = Objects.requireNonNull(state, "plugin request lease state");
    }

    public PluginRequestOwner owner() {
        return owner;
    }

    public synchronized boolean isActive() {
        return phase == Phase.ACTIVE && leaseToken.isActive();
    }

    /** Activate a fully allocated lease. Failure leaves this handle safely closeable and inactive. */
    synchronized boolean tryActivate(Runnable postAcquireProbe) {
        if (phase != Phase.NEW) {
            throw new IllegalStateException("plugin request lease activation was already attempted");
        }
        phase = Phase.ACTIVATING;
        try {
            if (!state.tryAcquire(leaseToken)) {
                closeToken();
                return false;
            }
            phase = Phase.ACTIVE;
            postAcquireProbe.run();
            return true;
        } catch (Throwable failure) {
            Throwable cleanupFailure = null;
            try {
                closeToken();
            } catch (Throwable cleanup) {
                cleanupFailure = cleanup;
            }
            if (!isFatal(failure) && isFatal(cleanupFailure)) {
                rethrow(cleanupFailure);
            }
            rethrow(failure);
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (phase == Phase.CLOSED) {
            return;
        }
        closeToken();
    }

    private void closeToken() {
        Throwable failure = null;
        while (leaseToken.isActive()) {
            try {
                state.release(leaseToken);
            } catch (Throwable releaseFailure) {
                failure = preferred(failure, releaseFailure);
            }
        }
        phase = Phase.CLOSED;
        rethrow(failure);
    }

    private static Throwable preferred(Throwable current, Throwable candidate) {
        if (current == null || (!isFatal(current) && isFatal(candidate))) {
            return candidate;
        }
        return current;
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("plugin request lease cleanup failed", failure);
    }
}
