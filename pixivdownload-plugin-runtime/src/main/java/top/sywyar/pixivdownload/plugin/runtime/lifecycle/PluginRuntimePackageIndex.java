package top.sywyar.pixivdownload.plugin.runtime.lifecycle;

import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 拥有已加载插件包的代际、阶段、描述符与准入快照。
 * 物理加载与 PF4J 调用仍由运行时 manager 编排。
 */
public final class PluginRuntimePackageIndex {

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Long> generations = new LinkedHashMap<>();

    public boolean contains(String packageId) {
        return entries.containsKey(packageId);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public List<String> packageIds() {
        return List.copyOf(entries.keySet());
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    public Entry get(String packageId) {
        return entries.get(packageId);
    }

    public Entry add(
            String packageId,
            Path artifactPath,
            Path pf4jLoadPath,
            String version,
            PluginRuntimePackagePhase phase,
            PluginDescriptor descriptor,
            PluginArtifactSnapshot productionSnapshot
    ) {
        String id = Objects.requireNonNull(packageId, "packageId");
        if (entries.containsKey(id)) {
            throw new IllegalStateException("plugin package already indexed: " + id);
        }
        long generation = generations.merge(id, 1L, Long::sum);
        Entry entry = new Entry(
                id,
                Objects.requireNonNull(artifactPath, "artifactPath").toAbsolutePath().normalize(),
                Objects.requireNonNull(pf4jLoadPath, "pf4jLoadPath").toAbsolutePath().normalize(),
                version,
                generation,
                Objects.requireNonNull(phase, "phase"),
                Objects.requireNonNull(descriptor, "descriptor"),
                productionSnapshot
        );
        entries.put(id, entry);
        return entry;
    }

    public Entry remove(String packageId) {
        return entries.remove(packageId);
    }

    /** 清空当前 entry 但保留历史代际，供同一 manager 重新扫描时继续换代。 */
    public List<Entry> clearEntries() {
        List<Entry> previous = entries();
        entries.clear();
        return previous;
    }

    /** 进程级关闭同时清空 entry 与代际计数。 */
    public List<Entry> clearAll() {
        List<Entry> previous = clearEntries();
        generations.clear();
        return previous;
    }

    public void clearGenerations() {
        generations.clear();
    }

    public Map<String, PluginRuntimePackagePhase> packagePhases() {
        Map<String, PluginRuntimePackagePhase> result = new LinkedHashMap<>();
        entries.forEach((id, entry) -> result.put(id, entry.phase));
        return Collections.unmodifiableMap(result);
    }

    public Optional<Long> generation(String packageId) {
        Entry entry = entries.get(packageId);
        return entry == null ? Optional.empty() : Optional.of(entry.generation);
    }

    public Optional<Path> artifactPath(String packageId) {
        Entry entry = entries.get(packageId);
        return entry == null ? Optional.empty() : Optional.of(entry.artifactPath);
    }

    public boolean isDevelopmentArtifact(String packageId) {
        Entry entry = entries.get(packageId);
        return entry != null && entry.productionSnapshot == null;
    }

    public Optional<PluginDescriptor> descriptor(String packageId) {
        Entry entry = entries.get(packageId);
        return entry == null ? Optional.empty() : Optional.of(entry.descriptor);
    }

    public Map<String, PluginDescriptor> descriptors() {
        Map<String, PluginDescriptor> result = new LinkedHashMap<>();
        entries.forEach((id, entry) -> result.put(id, entry.descriptor));
        return Collections.unmodifiableMap(result);
    }

    public List<PluginArtifactSnapshot> productionSnapshots() {
        return entries.values().stream().map(Entry::productionSnapshot).toList();
    }

    /** 单个物理 generation 的可变运行期条目；只由 manager 在同步临界区内更新。 */
    public static final class Entry {
        private final String packageId;
        private final Path artifactPath;
        private final Path pf4jLoadPath;
        private final String version;
        private final long generation;
        private PluginRuntimePackagePhase phase;
        private PluginDescriptor descriptor;
        private PluginInventory contributionSnapshot;
        private final PluginArtifactSnapshot productionSnapshot;

        private Entry(
                String packageId,
                Path artifactPath,
                Path pf4jLoadPath,
                String version,
                long generation,
                PluginRuntimePackagePhase phase,
                PluginDescriptor descriptor,
                PluginArtifactSnapshot productionSnapshot
        ) {
            this.packageId = packageId;
            this.artifactPath = artifactPath;
            this.pf4jLoadPath = pf4jLoadPath;
            this.version = version;
            this.generation = generation;
            this.phase = phase;
            this.descriptor = descriptor;
            this.productionSnapshot = productionSnapshot;
        }

        public String packageId() {
            return packageId;
        }

        public Path artifactPath() {
            return artifactPath;
        }

        public Path pf4jLoadPath() {
            return pf4jLoadPath;
        }

        public String version() {
            return version;
        }

        public long generation() {
            return generation;
        }

        public PluginRuntimePackagePhase phase() {
            return phase;
        }

        public void updatePhase(PluginRuntimePackagePhase phase) {
            this.phase = Objects.requireNonNull(phase, "phase");
        }

        public PluginDescriptor descriptor() {
            return descriptor;
        }

        public void updateDescriptor(PluginDescriptor descriptor) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }

        public PluginInventory contributionSnapshot() {
            return contributionSnapshot;
        }

        public void updateContributionSnapshot(PluginInventory contributionSnapshot) {
            this.contributionSnapshot = Objects.requireNonNull(
                    contributionSnapshot, "contributionSnapshot");
        }

        public PluginArtifactSnapshot productionSnapshot() {
            return productionSnapshot;
        }
    }
}
