package top.sywyar.pixivdownload.plugin.install;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 中性插件依赖满足性判断：只消费 descriptor 中的 {@link PluginDependencyRef}，不认识任何具体插件 id。
 */
@Component
public class PluginDependencyResolver {

    private final ExternalPluginInstaller installer;
    private final PluginRegistry pluginRegistry;
    private final PluginLifecycleService lifecycleService;

    public PluginDependencyResolver(ExternalPluginInstaller installer) {
        this(installer, null, null);
    }

    @Autowired
    public PluginDependencyResolver(ExternalPluginInstaller installer,
                                    PluginRegistry pluginRegistry,
                                    PluginLifecycleService lifecycleService) {
        this.installer = installer;
        this.pluginRegistry = pluginRegistry;
        this.lifecycleService = lifecycleService;
    }

    /** 安装态满足性：依赖只需已内置或已落盘，版本满足即可。 */
    public List<PluginDependencyProblem> installedProblems(PluginDescriptor descriptor) {
        return problems(descriptor, installedTargets(), false);
    }

    /** 激活态满足性：依赖必须在场、版本满足，且当前处于可服务状态。 */
    public List<PluginDependencyProblem> activationProblems(PluginDescriptor descriptor) {
        return problems(descriptor, activationTargets(), true);
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
     * 指定受管插件当前可用于运行期激活校验的描述符。正常安装优先读取安装清单；显式开发模式没有落盘安装包，
     * 此时读取当前已加载 generation 保留的纯值描述符。
     */
    public Optional<PluginDescriptor> activationDescriptor(String pluginId) {
        Optional<PluginDescriptor> installed = installedDescriptor(pluginId);
        if (installed.isPresent() || pluginId == null || lifecycleService == null) {
            return installed;
        }
        return lifecycleService.descriptor(pluginId)
                .filter(descriptor -> pluginId.equals(descriptor.id()));
    }

    /** 当前安装态中指定依赖是否已满足。 */
    public boolean installedDependencySatisfied(PluginDependencyRef dependency) {
        return problems(new PluginDescriptor("dependency-check", "dependency-check", "0.0.0",
                PluginApiRequirement.unspecified(), List.of(dependency), null,
                null, "dependency-check", null, null, null,
                top.sywyar.pixivdownload.plugin.api.plugin.PluginKind.FEATURE),
                installedTargets(), false).isEmpty();
    }

    private List<PluginDependencyProblem> problems(PluginDescriptor descriptor,
                                                   Map<String, DependencyTarget> targets,
                                                   boolean requireActive) {
        if (descriptor == null || descriptor.dependencies().isEmpty()) {
            return List.of();
        }
        List<PluginDependencyProblem> problems = new ArrayList<>();
        for (PluginDependencyRef dependency : descriptor.dependencies()) {
            if (dependency.optional()) {
                continue;
            }
            DependencyTarget target = targets.get(dependency.pluginId());
            if (target == null) {
                problems.add(PluginDependencyProblem.missing(dependency));
                continue;
            }
            PluginApiRequirement required = dependency.requirement();
            PluginApiRequirement actual = PluginApiRequirement.parse(target.descriptor().version());
            if (!required.isSatisfiedBy(actual.major(), actual.minor())) {
                problems.add(PluginDependencyProblem.versionUnsatisfied(dependency, target.descriptor().version()));
                continue;
            }
            if (requireActive && !target.active()) {
                problems.add(PluginDependencyProblem.unavailable(dependency,
                        target.descriptor().version(), target.status()));
            }
        }
        return List.copyOf(problems);
    }

    private Map<String, DependencyTarget> installedTargets() {
        Map<String, DependencyTarget> targets = new LinkedHashMap<>();
        for (PixivFeaturePlugin plugin : BuiltInPlugins.createAll()) {
            PluginDescriptor descriptor = PluginDescriptor.forBuiltIn(plugin);
            targets.put(descriptor.id(), new DependencyTarget(descriptor, true, "INSTALLED"));
        }
        for (InstalledPlugin installed : installer.listInstalled()) {
            targets.put(installed.id(), new DependencyTarget(installed.descriptor(), true, "INSTALLED"));
        }
        return targets;
    }

    private Map<String, DependencyTarget> activationTargets() {
        if (pluginRegistry == null || lifecycleService == null) {
            return installedTargets();
        }
        Set<String> activeBuiltIns = pluginRegistry.plugins().stream()
                .map(PixivFeaturePlugin::id)
                .filter(BuiltInPlugins::isBuiltIn)
                .collect(Collectors.toSet());
        Map<String, DependencyTarget> targets = new LinkedHashMap<>();
        for (PixivFeaturePlugin plugin : BuiltInPlugins.createAll()) {
            PluginDescriptor descriptor = PluginDescriptor.forBuiltIn(plugin);
            boolean active = activeBuiltIns.contains(descriptor.id());
            targets.put(descriptor.id(), new DependencyTarget(descriptor, active,
                    active ? PluginRuntimePhase.STARTED.name() : "DISABLED"));
        }
        for (InstalledPlugin installed : installer.listInstalled()) {
            PluginRuntimePhase phase = lifecycleService.phase(installed.id()).orElse(null);
            boolean active = phase == PluginRuntimePhase.STARTED;
            targets.put(installed.id(), new DependencyTarget(installed.descriptor(), active,
                    phase != null ? phase.name() : "INSTALLED"));
        }
        for (String pluginId : lifecycleService.managedPluginIds()) {
            activationDescriptor(pluginId).ifPresent(descriptor -> {
                PluginRuntimePhase phase = lifecycleService.phase(pluginId).orElse(null);
                boolean active = phase == PluginRuntimePhase.STARTED;
                targets.put(pluginId, new DependencyTarget(descriptor, active,
                        phase != null ? phase.name() : "LOADED"));
            });
        }
        return targets;
    }

    private record DependencyTarget(PluginDescriptor descriptor, boolean active, String status) {
    }
}
