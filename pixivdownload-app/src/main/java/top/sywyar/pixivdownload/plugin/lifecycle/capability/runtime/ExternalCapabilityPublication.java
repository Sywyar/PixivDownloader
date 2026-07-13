package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import java.util.Objects;

/** Opaque host token authorizing mutation of one exact published owner. */
public final class ExternalCapabilityPublication {

    private final ExternalCapabilityOwner owner;

    ExternalCapabilityPublication(ExternalCapabilityOwner owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public ExternalCapabilityOwner owner() {
        return owner;
    }

    @Override
    public String toString() {
        return "ExternalCapabilityPublication[owner=" + owner + "]";
    }
}
