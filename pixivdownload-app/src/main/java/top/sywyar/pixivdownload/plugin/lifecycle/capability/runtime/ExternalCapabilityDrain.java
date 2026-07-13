package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import java.time.Duration;
import java.util.Objects;

/** Drain handle returned after admission has been withdrawn for one exact owner publication. */
public final class ExternalCapabilityDrain {

    private final ExternalCapabilityOwner owner;
    private final ExternalCapabilityLeaseState leaseState;

    ExternalCapabilityDrain(ExternalCapabilityOwner owner, ExternalCapabilityLeaseState leaseState) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.leaseState = Objects.requireNonNull(leaseState, "leaseState");
    }

    public ExternalCapabilityOwner owner() {
        return owner;
    }

    public boolean isDrained() {
        return leaseState.isDrained();
    }

    public boolean awaitDrained(long deadlineNanos) {
        return leaseState.awaitDrained(deadlineNanos);
    }

    public boolean awaitDrained() {
        return leaseState.awaitDrained();
    }

    public int activeLeaseCount() {
        return leaseState.activeLeaseCount();
    }

    public boolean await(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("external capability drain timeout must not be negative");
        }
        return leaseState.await(timeout);
    }

    ExternalCapabilityLeaseState leaseState() {
        return leaseState;
    }
}
