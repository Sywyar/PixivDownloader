package top.sywyar.pixivdownload.plugin.lifecycle.request;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Host-owned admission and drain registry for requests entering external plugin serving generations.
 *
 * <p>Only pure owner keys and host synchronization state are retained. A withdrawn generation remains addressable
 * until its exact owner is retired so lifecycle retries can recover the same drain; it can never affect a later
 * serving id for the same plugin.
 */
@Component
public final class PluginRequestLeaseRegistry {

    private final Object lock = new Object();
    private final Map<PluginRequestOwner, PluginRequestLeaseState> states = new HashMap<>();
    private final Map<String, PluginRequestOwner> currentOwners = new HashMap<>();
    private final Runnable postAcquireProbe;
    private final Runnable beforeAcquirePublishProbe;
    private final Runnable beforeReleaseProbe;
    private final Runnable afterReleaseProbe;

    public PluginRequestLeaseRegistry() {
        this(() -> {
        }, () -> {
        }, () -> {
        }, () -> {
        });
    }

    PluginRequestLeaseRegistry(Runnable postAcquireProbe) {
        this(postAcquireProbe, () -> {
        }, () -> {
        }, () -> {
        });
    }

    PluginRequestLeaseRegistry(
            Runnable postAcquireProbe,
            Runnable beforeAcquirePublishProbe,
            Runnable beforeReleaseProbe,
            Runnable afterReleaseProbe) {
        this.postAcquireProbe = Objects.requireNonNull(postAcquireProbe, "request lease acquire probe");
        this.beforeAcquirePublishProbe = Objects.requireNonNull(
                beforeAcquirePublishProbe, "request acquire publish probe");
        this.beforeReleaseProbe = Objects.requireNonNull(beforeReleaseProbe, "request before-release probe");
        this.afterReleaseProbe = Objects.requireNonNull(afterReleaseProbe, "request after-release probe");
    }

    /** Publishes a fresh serving identity. The same plugin cannot have two request-admitting generations. */
    public void publish(PluginRequestOwner owner) {
        Objects.requireNonNull(owner, "plugin request owner");
        synchronized (lock) {
            PluginRequestOwner current = currentOwners.get(owner.pluginId());
            if (current != null) {
                throw new IllegalStateException("plugin request owner is already current: " + current);
            }
            if (states.containsKey(owner)) {
                throw new IllegalStateException("plugin request owner was already published: " + owner);
            }
            states.put(owner, new PluginRequestLeaseState(
                    beforeAcquirePublishProbe, beforeReleaseProbe, afterReleaseProbe));
            currentOwners.put(owner.pluginId(), owner);
        }
    }

    /** Allocate the exact lease handle before admission changes, so later fatal failures can close it. */
    public Optional<PluginRequestLease> prepareLease(PluginRequestOwner owner) {
        Objects.requireNonNull(owner, "plugin request owner");
        PluginRequestLeaseState state;
        synchronized (lock) {
            state = states.get(owner);
        }
        return state == null ? Optional.empty() : Optional.of(new PluginRequestLease(owner, state));
    }

    /** Activate a preallocated exact lease without allocating after the admission counter changes. */
    public boolean activate(PluginRequestLease lease) {
        Objects.requireNonNull(lease, "plugin request lease");
        return lease.tryActivate(postAcquireProbe);
    }

    /** Closes admission for the exact serving identity and returns the same drain on every retry. */
    public Optional<PluginRequestGenerationDrain> withdraw(PluginRequestOwner owner) {
        Objects.requireNonNull(owner, "plugin request owner");
        PluginRequestLeaseState state;
        synchronized (lock) {
            state = states.get(owner);
            if (state == null) {
                return Optional.empty();
            }
            currentOwners.remove(owner.pluginId(), owner);
        }
        return Optional.of(state.withdraw(owner));
    }

    /** Removes a withdrawn, fully drained serving identity without touching any later generation. */
    public boolean retire(PluginRequestOwner owner) {
        Objects.requireNonNull(owner, "plugin request owner");
        synchronized (lock) {
            PluginRequestLeaseState state = states.get(owner);
            if (state == null) {
                return false;
            }
            if (state.isAccepting()) {
                throw new IllegalStateException("cannot retire an accepting plugin request owner: " + owner);
            }
            if (!state.isDrained()) {
                throw new IllegalStateException("cannot retire a plugin request owner with active leases: " + owner);
            }
            currentOwners.remove(owner.pluginId(), owner);
            states.remove(owner);
            return true;
        }
    }

    public Optional<PluginRequestOwner> currentOwner(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            return Optional.ofNullable(currentOwners.get(pluginId));
        }
    }

}
