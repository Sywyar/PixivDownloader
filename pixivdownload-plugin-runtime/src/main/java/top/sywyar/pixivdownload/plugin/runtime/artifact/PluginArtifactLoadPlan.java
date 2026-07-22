package top.sywyar.pixivdownload.plugin.runtime.artifact;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 启动扫描阶段的外置 artifact 加载计划：先读取全部候选包描述符，再按插件依赖做拓扑排序。
 */
public final class PluginArtifactLoadPlan {

    private final List<Entry> orderedEntries;
    private final List<PluginLoadFailure> failures;
    private final Set<String> skippedPluginIds;

    private PluginArtifactLoadPlan(List<Entry> orderedEntries, List<PluginLoadFailure> failures,
                                   Set<String> skippedPluginIds) {
        this.orderedEntries = List.copyOf(orderedEntries);
        this.failures = List.copyOf(failures);
        this.skippedPluginIds = Set.copyOf(skippedPluginIds);
    }

    public static PluginArtifactLoadPlan create(List<Path> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new PluginArtifactLoadPlan(List.of(), List.of(), Set.of());
        }
        List<Entry> inspectedEntries = new ArrayList<>();
        List<PluginLoadFailure> failures = new ArrayList<>();
        for (Path candidate : candidates) {
            try {
                PluginPackageInspection inspection = PluginPackageReader.inspect(candidate);
                inspectedEntries.add(new Entry(candidate, inspection.descriptor()));
            } catch (RuntimeException e) {
                failures.add(failure(candidate, describe(e)));
            }
        }
        return createValidated(inspectedEntries, failures);
    }

    /**
     * 从同一批已冻结 snapshot 得到的描述符创建计划，不再按公开 artifact 路径重新读取字节。
     */
    public static PluginArtifactLoadPlan createInspected(List<Entry> inspectedEntries) {
        if (inspectedEntries == null || inspectedEntries.isEmpty()) {
            return new PluginArtifactLoadPlan(List.of(), List.of(), Set.of());
        }
        return createValidated(inspectedEntries, new ArrayList<>());
    }

    private static PluginArtifactLoadPlan createValidated(List<Entry> inspectedEntries,
                                                          List<PluginLoadFailure> failures) {
        Map<String, List<Entry>> candidatesById = new LinkedHashMap<>();
        for (Entry entry : inspectedEntries) {
            Objects.requireNonNull(entry, "inspected entry");
            List<String> validationErrors = entry.descriptor().externalValidationErrors();
            if (!validationErrors.isEmpty()) {
                failures.add(failure(entry.artifactPath(), "invalid plugin descriptor: "
                        + String.join("; ", validationErrors)));
                continue;
            }
            candidatesById.computeIfAbsent(entry.pluginId(), ignored -> new ArrayList<>()).add(entry);
        }

        List<Entry> entries = new ArrayList<>();
        Map<String, Entry> byId = new LinkedHashMap<>();
        Set<String> blockedIds = new LinkedHashSet<>();
        Map<String, String> blockReasons = new LinkedHashMap<>();
        for (Map.Entry<String, List<Entry>> group : candidatesById.entrySet()) {
            if (group.getValue().size() > 1) {
                String sources = group.getValue().stream()
                        .map(entry -> entry.artifactPath().getFileName().toString())
                        .sorted()
                        .collect(java.util.stream.Collectors.joining(", "));
                String reason = "duplicate plugin id " + group.getKey() + " provided by: " + sources;
                blockedIds.add(group.getKey());
                blockReasons.put(group.getKey(), reason);
                for (Entry duplicate : group.getValue()) {
                    failures.add(failure(duplicate.artifactPath(), reason));
                }
                continue;
            }
            Entry entry = group.getValue().get(0);
            entries.add(entry);
            byId.put(entry.pluginId(), entry);
        }

        propagateBlockedRequiredDependencies(entries, byId, blockedIds, blockReasons, failures);
        blockDependencyCycles(entries, byId, blockedIds, blockReasons, failures);
        propagateBlockedRequiredDependencies(entries, byId, blockedIds, blockReasons, failures);

        List<Entry> ordered = dependencyFirstOrder(entries, byId, blockedIds);
        return new PluginArtifactLoadPlan(ordered, failures, blockedIds);
    }

    public List<Entry> orderedEntries() {
        return orderedEntries;
    }

    public List<PluginLoadFailure> failures() {
        return failures;
    }

    public Set<String> skippedPluginIds() {
        return skippedPluginIds;
    }

    public Optional<PluginLoadFailure> blockedByFailedRequiredDependency(Entry entry, Set<String> failedPluginIds) {
        Objects.requireNonNull(entry, "entry");
        if (failedPluginIds == null || failedPluginIds.isEmpty()) {
            return Optional.empty();
        }
        for (PluginDependencyRef dependency : entry.descriptor().dependencies()) {
            if (!dependency.optional() && failedPluginIds.contains(dependency.pluginId())) {
                return Optional.of(failure(entry.artifactPath(),
                        "required dependency " + dependency.pluginId() + " failed to load"));
            }
        }
        return Optional.empty();
    }

    private static void propagateBlockedRequiredDependencies(List<Entry> entries, Map<String, Entry> byId,
                                                             Set<String> blockedIds,
                                                             Map<String, String> blockReasons,
                                                             List<PluginLoadFailure> failures) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Entry entry : entries) {
                if (blockedIds.contains(entry.pluginId())) {
                    continue;
                }
                String reason = firstRequiredDependencyBlock(entry, byId, blockedIds, blockReasons);
                if (reason != null) {
                    blockedIds.add(entry.pluginId());
                    blockReasons.put(entry.pluginId(), reason);
                    failures.add(failure(entry.artifactPath(), reason));
                    changed = true;
                }
            }
        }
    }

    private static String firstRequiredDependencyBlock(Entry entry, Map<String, Entry> byId,
                                                       Set<String> blockedIds,
                                                       Map<String, String> blockReasons) {
        for (PluginDependencyRef dependency : entry.descriptor().dependencies()) {
            if (dependency.optional()) {
                continue;
            }
            Entry target = byId.get(dependency.pluginId());
            if (target == null) {
                return "missing required dependency: " + dependency.pluginId();
            }
            if (blockedIds.contains(target.pluginId())) {
                return "required dependency " + dependency.pluginId()
                        + " is not loadable: " + blockReasons.get(target.pluginId());
            }
            String versionProblem = dependencyVersionProblem(dependency, target);
            if (versionProblem != null) {
                return versionProblem;
            }
        }
        return null;
    }

    private static String dependencyVersionProblem(PluginDependencyRef dependency, Entry target) {
        PluginApiRequirement required = dependency.requirement();
        PluginApiRequirement actual = PluginApiRequirement.parse(target.descriptor().version());
        if (required.isSatisfiedBy(actual.major(), actual.minor())) {
            return null;
        }
        return "required dependency " + dependency.pluginId() + " needs version "
                + required.display() + ", but installed version is " + target.descriptor().version();
    }

    private static void blockDependencyCycles(List<Entry> entries, Map<String, Entry> byId,
                                              Set<String> blockedIds,
                                              Map<String, String> blockReasons,
                                              List<PluginLoadFailure> failures) {
        Set<String> cycleIds = cyclePluginIds(entries, byId, blockedIds);
        if (cycleIds.isEmpty()) {
            return;
        }
        String reason = "cyclic plugin dependency involving: " + String.join(", ", cycleIds);
        for (Entry entry : entries) {
            if (cycleIds.contains(entry.pluginId()) && blockedIds.add(entry.pluginId())) {
                blockReasons.put(entry.pluginId(), reason);
                failures.add(failure(entry.artifactPath(), reason));
            }
        }
    }

    private static Set<String> cyclePluginIds(List<Entry> entries, Map<String, Entry> byId, Set<String> blockedIds) {
        Map<String, VisitState> states = new HashMap<>();
        List<String> stack = new ArrayList<>();
        Set<String> cycleIds = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (!blockedIds.contains(entry.pluginId())) {
                collectCycleIds(entry, byId, blockedIds, states, stack, cycleIds);
            }
        }
        return cycleIds;
    }

    private static void collectCycleIds(Entry entry, Map<String, Entry> byId, Set<String> blockedIds,
                                        Map<String, VisitState> states, List<String> stack,
                                        Set<String> cycleIds) {
        String pluginId = entry.pluginId();
        VisitState state = states.get(pluginId);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            int index = stack.indexOf(pluginId);
            if (index >= 0) {
                cycleIds.addAll(stack.subList(index, stack.size()));
            }
            return;
        }
        states.put(pluginId, VisitState.VISITING);
        stack.add(pluginId);
        for (PluginDependencyRef dependency : entry.descriptor().dependencies()) {
            if (dependency.optional()) {
                continue;
            }
            Entry target = byId.get(dependency.pluginId());
            if (target != null && !blockedIds.contains(target.pluginId())) {
                collectCycleIds(target, byId, blockedIds, states, stack, cycleIds);
            }
        }
        stack.remove(stack.size() - 1);
        states.put(pluginId, VisitState.VISITED);
    }

    private static List<Entry> dependencyFirstOrder(List<Entry> entries, Map<String, Entry> byId,
                                                    Set<String> blockedIds) {
        List<Entry> ordered = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (Entry entry : entries) {
            appendDependencyFirst(entry, byId, blockedIds, visited, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void appendDependencyFirst(Entry entry, Map<String, Entry> byId, Set<String> blockedIds,
                                              Set<String> visited, List<Entry> ordered) {
        if (blockedIds.contains(entry.pluginId()) || !visited.add(entry.pluginId())) {
            return;
        }
        for (PluginDependencyRef dependency : entry.descriptor().dependencies()) {
            Entry target = byId.get(dependency.pluginId());
            if (target != null && !blockedIds.contains(target.pluginId())) {
                appendDependencyFirst(target, byId, blockedIds, visited, ordered);
            }
        }
        ordered.add(entry);
    }

    private static PluginLoadFailure failure(Path artifactPath, String reason) {
        return new PluginLoadFailure(artifactPath.getFileName().toString(), reason);
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getName() : error.getMessage();
    }

    public record Entry(Path artifactPath, PluginDescriptor descriptor) {

        public Entry {
            Objects.requireNonNull(artifactPath, "artifactPath");
            Objects.requireNonNull(descriptor, "descriptor");
        }

        public String pluginId() {
            return descriptor.id();
        }
    }

    private enum VisitState {
        VISITING,
        VISITED
    }
}
