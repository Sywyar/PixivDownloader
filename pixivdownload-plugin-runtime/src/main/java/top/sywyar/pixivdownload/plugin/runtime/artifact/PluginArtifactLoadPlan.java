package top.sywyar.pixivdownload.plugin.runtime.artifact;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageInspection;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;

import java.nio.file.Path;
import java.util.ArrayDeque;
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
                        .map(entry -> entry.artifactPath().toString())
                        .sorted()
                        .collect(java.util.stream.Collectors.joining(", "));
                String reason = "duplicate plugin id " + group.getKey() + " provided by: " + sources;
                blockedIds.add(group.getKey());
                blockReasons.put(group.getKey(), reason);
                for (Entry duplicate : group.getValue()) {
                    failures.add(new PluginLoadFailure(duplicate.pluginId(), reason,
                            top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus.FAILED,
                            "plugin-conflict", 0L, duplicate.descriptor().version(), 1, null));
                }
                continue;
            }
            Entry entry = group.getValue().get(0);
            entries.add(entry);
            byId.put(entry.pluginId(), entry);
        }

        propagateBlockedRequiredDependencies(entries, byId, blockedIds, blockReasons, failures);
        blockDependencyCycles(entries, cyclePluginIds(byId, blockedIds, false), blockedIds, blockReasons, failures);
        propagateBlockedRequiredDependencies(entries, byId, blockedIds, blockReasons, failures);

        List<Entry> ordered = dependencyFirstOrder(entries, byId, blockedIds);
        return new PluginArtifactLoadPlan(ordered, failures, blockedIds);
    }

    /** PF4J 启动 wrapper 时会跟随已存在的可选依赖。只检查入选的 wrapper。 */
    public PluginArtifactLoadPlan forStartup(boolean headless, java.util.function.Predicate<String> enabled) {
        var selected = headless ? headlessEntries(enabled) : orderedEntries;
        // 仅包含必需依赖的图已经无环。
        var cycles = selected.stream().anyMatch(e -> e.descriptor().dependencies().stream().anyMatch(PluginDependencyRef::optional))
                ? pf4jCycleIds(selected) : Set.<String>of();
        if (cycles.isEmpty()) return new PluginArtifactLoadPlan(selected, failures, skippedPluginIds);
        var blocked = new LinkedHashSet<>(skippedPluginIds);
        var reasons = new LinkedHashMap<String, String>();
        var diagnostics = new ArrayList<>(failures);
        blockDependencyCycles(selected, cycles, blocked, reasons, diagnostics);
        var all = new LinkedHashMap<String, Entry>();
        selected.forEach(entry -> all.put(entry.pluginId(), entry));
        propagateBlockedRequiredDependencies(selected, all, blocked, reasons, diagnostics);
        var surviving = new PluginArtifactLoadPlan(selected.stream().filter(e -> !blocked.contains(e.pluginId())).toList(),
                diagnostics, blocked);
        return headless ? new PluginArtifactLoadPlan(surviving.headlessEntries(), diagnostics, blocked) : surviving;
    }

    public List<Entry> orderedEntries() {
        return orderedEntries;
    }

    /** 无头启动从非桌面专用包出发，仅沿必需依赖保留桌面包，沿用已验证的加载顺序。 */
    public List<Entry> headlessEntries() {
        return headlessEntries(id -> true);
    }

    public List<Entry> headlessEntries(java.util.function.Predicate<String> enabled) {
        Map<String, Entry> byId = new HashMap<>();
        Set<String> required = new LinkedHashSet<>();
        var pending = new ArrayDeque<Entry>();
        for (Entry entry : orderedEntries) {
            // 计划按依赖优先排序。禁用的消费方不会触发提供方加载。
            if (!enabled.test(entry.pluginId()) || entry.descriptor().dependencies().stream()
                    .anyMatch(edge -> !edge.optional() && !byId.containsKey(edge.pluginId()))) continue;
            byId.put(entry.pluginId(), entry);
            if (!entry.descriptor().desktopOnly()) {
                required.add(entry.pluginId());
                pending.add(entry);
            }
        }
        while (!pending.isEmpty()) {
            for (PluginDependencyRef dependency : pending.remove().descriptor().dependencies()) {
                if (!dependency.optional() && required.add(dependency.pluginId())) {
                    pending.add(byId.get(dependency.pluginId()));
                }
            }
        }
        return orderedEntries.stream().filter(entry -> required.contains(entry.pluginId())).toList();
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
        VersionRequirement required = dependency.requirement();
        VersionRequirement actual = VersionRequirement.parse(target.descriptor().version());
        if (required.isSatisfiedBy(actual.major(), actual.minor())) {
            return null;
        }
        return "required dependency " + dependency.pluginId() + " needs version "
                + required.display() + ", but installed version is " + target.descriptor().version();
    }

    private static void blockDependencyCycles(List<Entry> entries, Set<String> cycleIds,
                                              Set<String> blockedIds,
                                              Map<String, String> blockReasons,
                                              List<PluginLoadFailure> failures) {
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

    private static Set<String> cyclePluginIds(Map<String, Entry> byId, Set<String> blocked, boolean includeOptional) {
        // 先记录遍历完成顺序，再沿反向图遍历以找出完整的强连通分量。
        var graph = new HashMap<String, List<String>>();
        var reverse = new HashMap<String, List<String>>();
        for (var entry : byId.values()) {
            if (blocked.contains(entry.pluginId())) continue;
            var targets = new ArrayList<String>();
            graph.put(entry.pluginId(), targets);
            for (var edge : entry.descriptor().dependencies()) {
                if ((!edge.optional() || includeOptional) && byId.containsKey(edge.pluginId()) && !blocked.contains(edge.pluginId())) {
                    targets.add(edge.pluginId());
                    reverse.computeIfAbsent(edge.pluginId(), ignored -> new ArrayList<>()).add(entry.pluginId());
                }
            }
        }
        var order = new ArrayList<String>();
        var seen = new java.util.HashSet<String>();
        for (String id : graph.keySet()) collectPostorder(id, graph, seen, order);
        seen.clear();
        var cycles = new java.util.TreeSet<String>();
        for (String id : order.reversed()) {
            if (seen.contains(id)) continue;
            var component = new ArrayList<String>();
            collectPostorder(id, reverse, seen, component);
            if (component.size() > 1 || graph.get(id).contains(id)) cycles.addAll(component);
        }
        return cycles;
    }

    private static void collectPostorder(String id, Map<String, List<String>> graph, Set<String> seen, List<String> order) {
        if (!seen.add(id)) return;
        for (String next : graph.getOrDefault(id, List.of())) collectPostorder(next, graph, seen, order);
        order.add(id);
    }

    /** 热加载可能补齐现有 PF4J wrapper 之间原先缺失的可选依赖。 */
    public static Set<String> pf4jCycleIds(List<Entry> entries) {
        var wrappers = new LinkedHashMap<String, Entry>();
        for (var entry : entries) {
            if (entry.descriptor().executionMode() != PluginExecutionMode.DECLARATIVE_PROCESS) wrappers.put(entry.pluginId(), entry);
        }
        return cyclePluginIds(wrappers, Set.of(), true);
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
            if (dependency.optional()) continue;
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

}
