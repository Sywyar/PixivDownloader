package top.sywyar.pixivdownload.plugin.install;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginArtifactLoadPlan;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginInventorySnapshotter.Artifact;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 中性插件依赖满足性判断：只消费 descriptor 中的 {@link PluginDependencyRef}，不认识任何具体插件 id。
 */
@Component
public class PluginDependencyResolver {

    private final ExternalPluginInstaller installer;
    private final PluginLifecycleService lifecycleService;

    private final PluginRuntimeManager runtimeManager;
    private final PluginToggleProperties toggles;

    public PluginDependencyResolver(ExternalPluginInstaller installer) {
        this(installer, null);
    }

    public PluginDependencyResolver(ExternalPluginInstaller installer,
                                    PluginLifecycleService lifecycleService) {
        this(installer, lifecycleService, null, null);
    }

    @Autowired
    public PluginDependencyResolver(ExternalPluginInstaller installer,
                                   PluginLifecycleService lifecycleService, PluginRuntimeManager runtimeManager,
                                   PluginToggleProperties toggles) {
        this.runtimeManager = runtimeManager;
        this.toggles = toggles;
        this.installer = installer;
        this.lifecycleService = lifecycleService;
    }

    public boolean configuredDisabled(String id) {
        return toggles != null && !toggles.isEnabled(id);
    }

    /** 安装态满足性：依赖须为已落盘的插件包，版本满足即可。 */
    public List<PluginDependencyProblem> installedProblems(PluginDescriptor descriptor) {
        return problems(descriptor == null ? List.of() : descriptor.dependencies(), this::installedTargets);
    }

    /** 激活态满足性：依赖必须在场、版本满足，且当前处于可服务状态。 */
    public List<PluginDependencyProblem> activationProblems(PluginDescriptor descriptor) {
        return problems(descriptor == null ? List.of() : descriptor.dependencies(), this::activationTargets);
    }

    /** 使用同一次清点检查受影响的依赖方，并校验候选包下次启动及当前激活的必需依赖。 */
    public List<PluginDependencyProblem> installActivationProblems(PluginInstallResult install, boolean deferredByPolicy) {
        var candidate = install.descriptor();
        boolean changesProviders = install.outcome() == PluginInstallOutcome.UPGRADED
                || install.outcome() == PluginInstallOutcome.DOWNGRADED
                || install.outcome() != PluginInstallOutcome.DUPLICATE && !candidate.replaces().isEmpty();
        if (!changesProviders && candidate.dependencies().stream().allMatch(PluginDependencyRef::optional)) return List.of();
        var targets = activationTargets();
        var problems = new ArrayList<PluginDependencyProblem>();
        if (changesProviders) checkDependentRequirements(candidate, targets, problems);
        if (!problems.isEmpty()) return List.copyOf(problems);
        Map<String, String> restartStates = new LinkedHashMap<>();
        candidate.replaces().forEach(targets::remove);
        checkRequiredGraph(candidate.id(), candidate.dependencies(), targets, Map.of(), new LinkedHashSet<>(),
                new HashSet<>(), restartStates, problems);
        if (!problems.isEmpty() || deferredByPolicy) return List.copyOf(problems);

        // 下次启动的依赖图已通过；立即激活还要求当前进程中的直接依赖可用。
        for (var edge : candidate.dependencies()) {
            if (edge.optional()) continue;
            var live = targets.get(edge.pluginId());
            var problem = versionProblem(edge, live);
            if (problem == null && live.active()) continue;
            if (PluginDependencyProblem.deferredStatus(restartStates.get(edge.pluginId()))) {
                problems.add(PluginDependencyProblem.unavailable(edge, live.installed().version(),
                        PluginDependencyProblem.AWAITING_RESTART));
            } else {
                problems.add(problem != null ? problem : availabilityProblem(edge, live));
            }
        }
        return List.copyOf(problems);
    }

    private void checkDependentRequirements(PluginDescriptor candidate, Map<String, DependencyTarget> targets,
                                            List<PluginDependencyProblem> problems) {
        Set<String> affected = new HashSet<>(candidate.replaces());
        affected.add(candidate.id());
        var next = new DependencyTarget(candidate, true, "INSTALLED");
        for (var entry : targets.entrySet()) {
            if (affected.contains(entry.getKey())) continue;
            var consumer = restartTarget(entry.getKey(), entry.getValue());
            if (consumer == null) continue;
            for (var edge : consumer.descriptor().dependencies()) {
                if (edge.optional() || !affected.contains(edge.pluginId())) continue;
                var before = restartTarget(edge.pluginId(), targets.get(edge.pluginId()));
                var after = candidate.id().equals(edge.pluginId()) ? next : null;
                if (versionProblem(edge, before) == null && versionProblem(edge, after) != null) {
                    problems.add(PluginDependencyProblem.dependentConflict(entry.getKey(), edge,
                            before.descriptor().version(), after == null ? null : candidate.version()));
                }
            }
        }
        if (runtimeManager == null || !problems.isEmpty()) return;
        for (String replaced : candidate.replaces()) {
            for (String consumer : runtimeManager.activeDependents(replaced)) {
                if (affected.contains(consumer)) continue;
                var descriptor = runtimeManager.loadedDescriptor(consumer).orElseThrow();
                for (var edge : descriptor.dependencies()) {
                    if (!edge.optional() && edge.pluginId().equals(replaced)) {
                        var provider = targets.get(replaced);
                        problems.add(PluginDependencyProblem.dependentConflict(consumer, edge,
                                provider == null ? null : provider.descriptor().version(), null));
                    }
                }
            }
        }
    }

    private DependencyTarget restartTarget(String id, DependencyTarget target) {
        if (target == null) return null;
        if (target.installed() != null) {
            if (runtimeManager != null && runtimeManager.hasPendingInstall(id) && target.active()) {
                return new DependencyTarget(target.installed().descriptor(), false, "INSTALLED", target.installed());
            }
            return new DependencyTarget(target.installed().descriptor(), target.active(), target.status(), target.installed());
        }
        // 已移除的驻留包不会在下次启动出现。
        return runtimeManager == null || runtimeManager.isDevelopmentArtifact(id) ? target : null;
    }

    public boolean selectedAfterRestart(String id) {
        var inventory = installer.snapshotInstalledWithProvenance(InstalledPluginInventorySnapshot.MAX_RECORDS,
                InstalledPluginInventorySnapshot.MAX_PROVENANCE_BYTES);
        return selectedAfterRestart(inventory.entries(), Set.of(id)).contains(id);
    }

    /** 只复验可能影响候选包的工件，和冷启动一样按工件准入后再建图判重。 */
    public Set<String> selectedAfterRestart(List<InstalledPluginSnapshot> installed, Set<String> candidates) {
        if (candidates.isEmpty()) return Set.of();
        var neighbors = new HashMap<String, Set<String>>();
        boolean headless = runtimeManager != null && runtimeManager.headlessStartup();
        var enabled = installed.stream().filter(item -> !configuredDisabled(item.plugin().id())).toList();
        for (var item : enabled) {
            var plugin = item.plugin();
            for (var edge : plugin.descriptor().dependencies()) {
                neighbors.computeIfAbsent(plugin.id(), ignored -> new HashSet<>()).add(edge.pluginId());
                neighbors.computeIfAbsent(edge.pluginId(), ignored -> new HashSet<>()).add(plugin.id());
            }
        }
        var relevant = reachable(candidates, neighbors);
        var entries = enabled.stream().filter(item -> relevant.contains(item.plugin().id()))
                .filter(item -> runtimeManager == null || runtimeManager.admitsInventoryEntry(item))
                .map(item -> item.plugin())
                .map(plugin -> new PluginArtifactLoadPlan.Entry(plugin.path(), plugin.descriptor())).toList();
        var plan = PluginArtifactLoadPlan.createInspected(entries);
        return plan.forStartup(headless, id -> true).orderedEntries().stream()
                .map(PluginArtifactLoadPlan.Entry::pluginId).filter(candidates::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> reachable(Set<String> seeds, Map<String, Set<String>> edges) {
        var visited = new HashSet<>(seeds);
        var pending = new ArrayDeque<>(seeds);
        while (!pending.isEmpty()) {
            for (String id : edges.getOrDefault(pending.remove(), Set.of())) {
                if (visited.add(id)) pending.add(id);
            }
        }
        return visited;
    }

    /** 按目录的提交顺序预检。之前的候选已检查过其依赖，复用磁盘包时执行安装器的完整准入检查。 */
    public record PlannedPackage(String id, String version, List<PluginDependencyRef> dependencies) {}

    public Map<String, List<PluginDependencyProblem>> previewProblems(List<PlannedPackage> packages, List<InstalledPlugin> installed) {
        if (packages.stream().allMatch(pkg -> pkg.dependencies().stream().allMatch(PluginDependencyRef::optional))) return Map.of();
        var targets = activationTargets(installed);
        var planned = new LinkedHashMap<String, PlannedPackage>();
        var blocked = new LinkedHashMap<String, List<PluginDependencyProblem>>();
        // 同一计划的多条依赖链可复用同一签名工件。
        Map<String, String> restartStates = new LinkedHashMap<>();
        for (var pkg : packages) {
            for (var edge : pkg.dependencies()) {
                if (edge.optional()) continue;
                var problems = new ArrayList<PluginDependencyProblem>();
                checkRequiredGraph(pkg.id(), List.of(edge), targets, planned, new LinkedHashSet<>(),
                        new HashSet<>(), restartStates, problems);
                if (!problems.isEmpty()) blocked.computeIfAbsent(edge.pluginId(), ignored -> new ArrayList<>()).addAll(problems);
            }
            planned.put(pkg.id(), pkg);
        }
        return blocked;
    }

    private void checkRequiredGraph(String owner, List<PluginDependencyRef> dependencies, Map<String, DependencyTarget> targets,
                                    Map<String, PlannedPackage> planned, Set<String> path, Set<String> checked,
                                    Map<String, String> restartStates, List<PluginDependencyProblem> problems) {
        path.add(owner);
        for (var edge : dependencies) {
            if (edge.optional()) continue;
            String id = edge.pluginId();
            if (path.contains(id)) {
                problems.add(PluginDependencyProblem.cycle(edge, String.join(" -> ", path) + " -> " + id));
                continue;
            }
            var candidate = planned.get(id);
            if (candidate != null) {
                var version = candidate.version();
                var actual = VersionRequirement.parse(version);
                if (!edge.requirement().isSatisfiedBy(actual.major(), actual.minor()))
                    problems.add(PluginDependencyProblem.versionUnsatisfied(edge, version));
                else if (toggles != null && !toggles.isEnabled(id))
                    problems.add(PluginDependencyProblem.unavailable(edge, version, "DISABLED"));
                else if (checked.add(id))
                    checkRequiredGraph(id, candidate.dependencies(), targets, planned, path, checked, restartStates, problems);
                continue;
            }
            var target = restartTarget(id, targets.get(id));
            var problem = versionProblem(edge, target);
            if (problem != null) {
                problems.add(problem);
            } else if (checked.add(id)) {
                String state = restartStates.computeIfAbsent(id, ignored -> restartState(edge, target));
                if (!PluginRuntimePhase.STARTED.name().equals(state) && !PluginDependencyProblem.deferredStatus(state)) {
                    String version = target.descriptor().version();
                    problems.add("ADMISSION_REJECTED".equals(state) ? PluginDependencyProblem.restartAdmissionRejected(edge, version)
                            : PluginDependencyProblem.unavailable(edge, version, state));
                    continue;
                }
                checkRequiredGraph(id, target.descriptor().dependencies(), targets, planned, path, checked, restartStates, problems);
            }
        }
        path.remove(owner);
    }

    private String restartState(PluginDependencyRef edge, DependencyTarget target) {
        if (toggles != null && !toggles.isEnabled(edge.pluginId())) return "DISABLED";
        if (!target.active()) return availabilityProblem(edge, target).status();
        if (target.installed() != null && runtimeManager != null && !runtimeManager.admitsInstalledArtifact(target.installed()))
            return "ADMISSION_REJECTED";
        return PluginRuntimePhase.STARTED.name();
    }

    /** 市场直接安装入口按同版本已安装工件的实测摘要选择附件。 */
    public Optional<Artifact> installedArtifactForVersion(String pluginId, String version) {
        return installer.installedArtifactForVersion(pluginId, version);
    }

    /** 安装目录中指定插件的包级 descriptor。 */
    public Optional<PluginDescriptor> installedDescriptor(String pluginId) {
        if (pluginId == null) {
            return Optional.empty();
        }
        return installer.listInstalled().stream()
                .filter(plugin -> pluginId.equals(plugin.id()))
                .map(InstalledPlugin::descriptor)
                .findFirst();
    }

    /**
     * 指定受管插件当前可用于运行期激活校验的描述符。当前已加载 generation 是实际运行来源，优先于可能被
     * 开发模式忽略的同 id 磁盘包；插件尚未加载时再回退安装清单。
     */
    public Optional<PluginDescriptor> activationDescriptor(String pluginId) {
        if (pluginId == null) {
            return Optional.empty();
        }
        if (lifecycleService != null) {
            Optional<PluginDescriptor> loaded = lifecycleService.descriptor(pluginId)
                    .filter(descriptor -> pluginId.equals(descriptor.id()));
            if (loaded.isPresent()) {
                return loaded;
            }
        }
        return installedDescriptor(pluginId);
    }

    /** 当前安装态中指定依赖是否已满足。 */
    public boolean installedDependencySatisfied(PluginDependencyRef dependency) {
        return problems(List.of(dependency), this::installedTargets).isEmpty();
    }

    private List<PluginDependencyProblem> problems(List<PluginDependencyRef> dependencies,
                                                   Supplier<Map<String, DependencyTarget>> targetSupplier) {
        if (dependencies.stream().allMatch(PluginDependencyRef::optional)) return List.of();
        var targets = targetSupplier.get();
        var problems = new ArrayList<PluginDependencyProblem>();
        for (var dependency : dependencies) {
            if (dependency.optional()) continue;
            var target = targets.get(dependency.pluginId());
            var problem = versionProblem(dependency, target);
            if (problem == null) problem = availabilityProblem(dependency, target);
            if (problem != null) problems.add(problem);
        }
        return List.copyOf(problems);
    }

    private static PluginDependencyProblem versionProblem(PluginDependencyRef dependency, DependencyTarget target) {
        if (BuiltInPlugins.isBuiltIn(dependency.pluginId())) return PluginDependencyProblem.builtIn(dependency);
        if (target == null) return PluginDependencyProblem.missing(dependency);
        String version = target.descriptor().version();
        var actual = VersionRequirement.parse(version);
        return dependency.requirement().isSatisfiedBy(actual.major(), actual.minor()) ? null
                : PluginDependencyProblem.versionUnsatisfied(dependency, version);
    }

    private PluginDependencyProblem availabilityProblem(PluginDependencyRef dependency, DependencyTarget target) {
        if (target.active()) return null;
        String status = target.status();
        if (target.installed() != null && runtimeManager != null && ("INSTALLED".equals(status) || "LOADED".equals(status))) {
            if (!toggles.isEnabled(dependency.pluginId())) status = "DISABLED";
            else {
                status = switch (runtimeManager.deferredStartStatus(target.installed())) {
                    case INITIALIZATION_FAILED -> "INITIALIZATION_FAILED";
                    case START_FAILED -> "START_FAILED";
                    case AVAILABLE -> runtimeManager.hasPendingInstall(dependency.pluginId())
                            ? PluginDependencyProblem.AWAITING_RESTART : PluginDependencyProblem.HEADLESS_SKIPPED;
                    case UNAVAILABLE -> status;
                };
            }
        }
        return PluginDependencyProblem.unavailable(dependency, target.descriptor().version(), status);
    }

    private Map<String, DependencyTarget> installedTargets() {
        Map<String, DependencyTarget> targets = new LinkedHashMap<>();
        for (var installed : installer.listInstalled()) {
            targets.put(installed.id(), new DependencyTarget(installed.descriptor(), true, "INSTALLED", installed));
        }
        return targets;
    }

    private Map<String, DependencyTarget> activationTargets() {
        return activationTargets(installer.listInstalled());
    }

    private Map<String, DependencyTarget> activationTargets(List<InstalledPlugin> installations) {
        Map<String, DependencyTarget> targets = new LinkedHashMap<>();
        for (InstalledPlugin installed : installations) {
            PluginRuntimePhase phase = lifecycleService == null ? null : lifecycleService.phase(installed.id()).orElse(null);
            boolean active = lifecycleService == null || phase == PluginRuntimePhase.STARTED;
            targets.put(installed.id(), new DependencyTarget(installed.descriptor(), active,
                    phase != null ? phase.name() : "INSTALLED", installed));
        }
        if (lifecycleService == null) return targets;
        for (String pluginId : lifecycleService.managedPluginIds()) {
            lifecycleService.descriptor(pluginId).filter(descriptor -> pluginId.equals(descriptor.id())).ifPresent(descriptor -> {
                PluginRuntimePhase phase = lifecycleService.phase(pluginId).orElse(null);
                boolean active = phase == PluginRuntimePhase.STARTED;
                var disk = targets.get(pluginId);
                var installed = disk != null ? disk.installed() : null;
                targets.put(pluginId, new DependencyTarget(descriptor, active,
                        phase != null ? phase.name() : "LOADED", installed));
            });
        }
        return targets;
    }

    private record DependencyTarget(PluginDescriptor descriptor, boolean active, String status, InstalledPlugin installed) {
        DependencyTarget(PluginDescriptor descriptor, boolean active, String status) {
            this(descriptor, active, status, null);
        }
    }
}
