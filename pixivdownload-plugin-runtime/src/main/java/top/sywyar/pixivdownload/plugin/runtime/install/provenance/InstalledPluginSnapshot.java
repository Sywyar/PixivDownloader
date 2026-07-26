package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;

import java.util.Locale;
import java.util.Objects;

/** 同一安装器锁域内取得的 artifact 身份与 provenance 纯值快照。 */
public record InstalledPluginSnapshot(
        InstalledPlugin plugin,
        long artifactSizeBytes,
        String artifactSha256,
        ProvenanceSnapshotState provenanceState,
        PluginProvenanceRecord provenance,
        long provenanceBytes) {

    public InstalledPluginSnapshot {
        plugin = Objects.requireNonNull(plugin, "plugin");
        if (artifactSizeBytes <= 0L) {
            throw new IllegalArgumentException("snapshot artifact size must be positive");
        }
        artifactSha256 = Objects.requireNonNull(artifactSha256, "artifactSha256")
                .toLowerCase(Locale.ROOT);
        if (!artifactSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("snapshot artifact digest must be SHA-256");
        }
        provenanceState = Objects.requireNonNull(provenanceState, "provenanceState");
        if ((provenanceState == ProvenanceSnapshotState.PRESENT) != (provenance != null)) {
            throw new IllegalArgumentException("present provenance state must bind exactly one record");
        }
        if (provenanceBytes < 0L
                || provenanceState != ProvenanceSnapshotState.PRESENT && provenanceBytes != 0L) {
            throw new IllegalArgumentException("invalid provenance byte count for snapshot state");
        }
    }
}
