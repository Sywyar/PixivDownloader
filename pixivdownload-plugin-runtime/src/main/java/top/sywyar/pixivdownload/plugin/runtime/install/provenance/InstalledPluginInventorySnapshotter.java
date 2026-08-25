package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 把同一锁域内检视过的安装 artifact 与 provenance 物化为有界管理快照。 */
public final class InstalledPluginInventorySnapshotter {

    private final PluginProvenanceStore provenanceStore;

    public InstalledPluginInventorySnapshotter(PluginProvenanceStore provenanceStore) {
        this.provenanceStore = Objects.requireNonNull(provenanceStore, "provenanceStore");
    }

    public InstalledPluginInventorySnapshot snapshot(
            List<Artifact> installed,
            int maximumRecords,
            long maximumBytes) {
        Objects.requireNonNull(installed, "installed");
        if (maximumRecords <= 0 || maximumBytes < 0L) {
            throw new IllegalArgumentException("management provenance limits are invalid");
        }
        List<InstalledPluginSnapshot> entries = new ArrayList<>(installed.size());
        int records = 0;
        long bytes = 0L;
        boolean exhausted = false;
        for (Artifact inspected : installed) {
            InstalledPlugin plugin = inspected.plugin();
            if (exhausted || records >= maximumRecords) {
                exhausted = true;
                entries.add(new InstalledPluginSnapshot(
                        plugin, inspected.artifactSizeBytes(), inspected.artifactSha256(),
                        ProvenanceSnapshotState.BUDGET_EXHAUSTED, null, 0L));
                continue;
            }
            records++;
            long remainingBytes = maximumBytes - bytes;
            try {
                var measured = provenanceStore.readMeasuredStrict(plugin.path(), remainingBytes);
                if (measured.isEmpty()) {
                    entries.add(new InstalledPluginSnapshot(
                            plugin, inspected.artifactSizeBytes(), inspected.artifactSha256(),
                            ProvenanceSnapshotState.ABSENT, null, 0L));
                    continue;
                }
                PluginProvenanceStore.MeasuredProvenance present = measured.orElseThrow();
                bytes = addConsumedBytes(bytes, present.byteCount(), maximumBytes);
                entries.add(new InstalledPluginSnapshot(
                        plugin, inspected.artifactSizeBytes(), inspected.artifactSha256(),
                        ProvenanceSnapshotState.PRESENT, present.record(), present.byteCount()));
            } catch (PluginProvenanceStore.ReadBudgetExceededException budgetFailure) {
                exhausted = true;
                ProvenanceSnapshotState state = budgetFailure.byteCount() <= 0L
                        ? ProvenanceSnapshotState.INVALID
                        : ProvenanceSnapshotState.BUDGET_EXHAUSTED;
                entries.add(new InstalledPluginSnapshot(
                        plugin, inspected.artifactSizeBytes(), inspected.artifactSha256(), state, null, 0L));
            } catch (PluginProvenanceStore.InvalidProvenanceException invalidProvenance) {
                try {
                    bytes = addConsumedBytes(bytes, invalidProvenance.byteCount(), maximumBytes);
                } catch (ArithmeticException overflow) {
                    exhausted = true;
                }
                entries.add(invalid(inspected));
            } catch (PluginProvenanceStore.ProvenanceReadException readFailure) {
                try {
                    bytes = addConsumedBytes(bytes, readFailure.byteCount(), maximumBytes);
                } catch (ArithmeticException overflow) {
                    exhausted = true;
                }
                entries.add(invalid(inspected));
            } catch (IOException invalidProvenance) {
                entries.add(invalid(inspected));
            } catch (IllegalStateException | ArithmeticException invalidProvenance) {
                exhausted = true;
                entries.add(invalid(inspected));
            }
        }
        return new InstalledPluginInventorySnapshot(entries, exhausted);
    }

    private static InstalledPluginSnapshot invalid(Artifact inspected) {
        return new InstalledPluginSnapshot(
                inspected.plugin(), inspected.artifactSizeBytes(), inspected.artifactSha256(),
                ProvenanceSnapshotState.INVALID, null, 0L);
    }

    private static long addConsumedBytes(long consumedBytes, long candidateBytes, long maximumBytes) {
        if (consumedBytes < 0L
                || consumedBytes > maximumBytes
                || candidateBytes < 0L
                || candidateBytes > maximumBytes - consumedBytes) {
            throw new ArithmeticException("management provenance read budget exceeded");
        }
        return consumedBytes + candidateBytes;
    }

    /** 已在 installer 锁域内完成文件 identity 复核的 artifact 事实。 */
    public record Artifact(
            InstalledPlugin plugin,
            long artifactSizeBytes,
            String artifactSha256) {

        public Artifact {
            plugin = Objects.requireNonNull(plugin, "plugin");
            if (artifactSizeBytes <= 0L) {
                throw new IllegalArgumentException("installed artifact size must be positive");
            }
            artifactSha256 = Objects.requireNonNull(artifactSha256, "artifactSha256");
        }
    }
}
