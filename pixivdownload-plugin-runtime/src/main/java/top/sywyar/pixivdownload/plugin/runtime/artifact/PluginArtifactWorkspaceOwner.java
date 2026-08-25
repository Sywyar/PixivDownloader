package top.sywyar.pixivdownload.plugin.runtime.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** 生产插件 artifact 私有 workspace 的引用确认、释放与遗留清理所有者。 */
public final class PluginArtifactWorkspaceOwner {

    private static final Logger log = LoggerFactory.getLogger(PluginArtifactWorkspaceOwner.class);

    private final PluginRuntimeLayout layout;
    private final Set<PluginArtifactSnapshot> unconfirmedSnapshots =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean abandonedCleanupCompleted;
    private boolean abandonedCleanupSafe = true;

    public PluginArtifactWorkspaceOwner(PluginRuntimeLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    public boolean hasUnconfirmedSnapshots() {
        return !unconfirmedSnapshots.isEmpty();
    }

    public void cleanupAbandoned(boolean runtimeEntriesEmpty) {
        if (abandonedCleanupCompleted) {
            return;
        }
        if (!abandonedCleanupSafe || !runtimeEntriesEmpty) {
            log.warn("Skipping abandoned plugin artifact workspace cleanup because wrapper release is unconfirmed");
            return;
        }
        PluginArtifactSnapshot.cleanupAbandonedWorkspaces(layout);
        abandonedCleanupCompleted = true;
    }

    public void retainUnconfirmed(PluginArtifactSnapshot snapshot) {
        if (snapshot != null) {
            unconfirmedSnapshots.add(snapshot);
            abandonedCleanupSafe = false;
        }
    }

    public void discard(PluginArtifactSnapshot snapshot) {
        if (snapshot != null) {
            snapshot.close();
        }
    }

    public void release(
            PluginArtifactSnapshot snapshot,
            Collection<PluginArtifactSnapshot> activeSnapshots) {
        if (snapshot == null || unconfirmedSnapshots.contains(snapshot)) {
            return;
        }
        boolean stillReferenced = Objects.requireNonNull(activeSnapshots, "activeSnapshots").stream()
                .anyMatch(candidate -> candidate == snapshot);
        if (!stillReferenced) {
            discard(snapshot);
        }
    }

    public void closeAll(
            Collection<PluginArtifactSnapshot> snapshots,
            boolean runtimeReleased,
            String action) {
        Set<PluginArtifactSnapshot> unconfirmed = identitySet(unconfirmedSnapshots);
        Set<PluginArtifactSnapshot> releasable = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PluginArtifactSnapshot snapshot : Objects.requireNonNull(snapshots, "snapshots")) {
            if (snapshot != null && !unconfirmed.contains(snapshot)) {
                releasable.add(snapshot);
            }
        }
        boolean cleanupWasSafe = abandonedCleanupSafe;
        unconfirmedSnapshots.clear();
        abandonedCleanupCompleted = false;
        abandonedCleanupSafe = cleanupWasSafe && runtimeReleased;
        if (!unconfirmed.isEmpty()) {
            log.warn("Retaining {} plugin artifact workspace(s) because their classloader release is unconfirmed",
                    unconfirmed.size());
        }
        if (releasable.isEmpty()) {
            return;
        }
        if (!runtimeReleased) {
            log.warn("Retaining {} plugin artifact workspace(s) because runtime {} did not release cleanly",
                    releasable.size(), action);
            return;
        }
        releasable.forEach(this::discard);
    }

    private static Set<PluginArtifactSnapshot> identitySet(
            Collection<PluginArtifactSnapshot> snapshots) {
        Set<PluginArtifactSnapshot> result = Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(snapshots);
        return result;
    }
}
