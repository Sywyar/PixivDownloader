package top.sywyar.pixivdownload.plugin.runtime.artifact;

import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;

import java.nio.file.Path;
import java.util.Objects;

/** 持有已经冻结、检视并验签的生产插件 artifact，直至其 snapshot 转交给运行时。 */
public final class PreparedPluginArtifact implements AutoCloseable {

    private PluginArtifactSnapshot snapshot;
    private final PluginPackageInspection inspection;
    private final String verifiedSha256;

    public PreparedPluginArtifact(
            PluginArtifactSnapshot snapshot,
            PluginPackageInspection inspection,
            String verifiedSha256) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.inspection = Objects.requireNonNull(inspection, "inspection");
        this.verifiedSha256 = Objects.requireNonNull(verifiedSha256, "verifiedSha256");
    }

    public Path originalArtifact() {
        return snapshot().originalArtifact();
    }

    public PluginPackageInspection inspection() {
        return inspection;
    }

    public String verifiedSha256() {
        return verifiedSha256;
    }

    public PluginArtifactSnapshot snapshot() {
        if (snapshot == null) {
            throw new IllegalStateException("prepared plugin artifact ownership was already transferred");
        }
        return snapshot;
    }

    public PluginArtifactLoadPlan.Entry loadPlanEntry() {
        return new PluginArtifactLoadPlan.Entry(originalArtifact(), inspection.descriptor());
    }

    public PluginArtifactSnapshot detachSnapshot() {
        PluginArtifactSnapshot detached = snapshot();
        snapshot = null;
        return detached;
    }

    @Override
    public void close() {
        PluginArtifactSnapshot current = snapshot;
        snapshot = null;
        if (current != null) {
            current.close();
        }
    }
}
