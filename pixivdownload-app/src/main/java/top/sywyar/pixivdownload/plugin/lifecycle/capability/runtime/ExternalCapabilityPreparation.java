package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opaque identity token for an owner batch that has not become invokable yet. */
public final class ExternalCapabilityPreparation {

    private final ExternalCapabilityOwner owner;
    private final AtomicBoolean discarded = new AtomicBoolean();

    ExternalCapabilityPreparation(ExternalCapabilityOwner owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public ExternalCapabilityOwner owner() {
        return owner;
    }

    void markDiscarded() {
        discarded.set(true);
    }

    boolean isDiscarded() {
        return discarded.get();
    }

    @Override
    public String toString() {
        return "ExternalCapabilityPreparation[owner=" + owner + "]";
    }
}
