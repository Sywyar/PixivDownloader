package top.sywyar.pixivdownload.plugin.lifecycle.request;

import java.util.Objects;

/** A withdrawal drain that contains only host-owned request state and the pure serving identity. */
public final class PluginRequestGenerationDrain {

    private final PluginRequestOwner owner;
    private final PluginRequestLeaseState state;

    PluginRequestGenerationDrain(PluginRequestOwner owner, PluginRequestLeaseState state) {
        this.owner = Objects.requireNonNull(owner, "plugin request owner");
        this.state = Objects.requireNonNull(state, "plugin request lease state");
    }

    public PluginRequestOwner owner() {
        return owner;
    }

    public boolean awaitDrained(long deadlineNanos) {
        return state.awaitDrained(deadlineNanos);
    }

    public boolean awaitDrained() {
        return state.awaitDrained();
    }

    public boolean isDrained() {
        return state.isDrained();
    }

    public int activeLeaseCount() {
        return state.activeLeaseCount();
    }
}
