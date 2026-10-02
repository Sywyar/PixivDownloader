package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.gui.controlcenter.DesktopControlCenterRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopAutomationSource;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDashboardSource;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDirectorySuggestionSource;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.gui.controlcenter.DirectorySuggestionCapability;
import top.sywyar.pixivdownload.gui.controlcenter.PluginDirectorySuggestion;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPreparation;

import java.util.List;
import java.util.Map;

/** 把 child context 的控制中心事实源代理接入精确 owner publication 生命周期。 */
@Component
public final class DesktopControlCenterCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {

    private record Prepared(ExternalCapabilityOwner owner,
                            List<DesktopDashboardSource> dashboards,
                            List<DesktopAutomationSource> automations,
                            DirectorySuggestionCapability directories,
                            DesktopUiText displayName) implements PreparedContribution {
        private Prepared {
            dashboards = List.copyOf(dashboards);
            automations = List.copyOf(automations);
        }
    }

    private final DesktopControlCenterRegistry registry;
    private final ExternalCapabilityInvocationRegistry invocations;
    private final PluginRegistry plugins;

    public DesktopControlCenterCapabilityAdapter(DesktopControlCenterRegistry registry,
                                                 ExternalCapabilityInvocationRegistry invocations) {
        this(registry, invocations, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DesktopControlCenterCapabilityAdapter(DesktopControlCenterRegistry registry,
                                                 ExternalCapabilityInvocationRegistry invocations,
                                                 PluginRegistry plugins) {
        this.registry = registry;
        this.invocations = invocations;
        this.plugins = plugins;
    }

    @Override
    public String capabilityName() {
        return DesktopDashboardSource.class.getName();
    }

    @Override
    public PreparedContribution prepare(ExternalCapabilityPreparation preparation,
                                        ConfigurableApplicationContext context) {
        Map<String, DesktopDirectorySuggestionSource> sources = context.getBeansOfType(DesktopDirectorySuggestionSource.class);
        if (sources.size() > 1) throw new IllegalArgumentException("multiple directory suggestion sources");
        DirectorySuggestionCapability directories = null;
        DesktopUiText displayName = DesktopUiText.raw(preparation.owner().pluginId());
        if (!sources.isEmpty()) {
            var owner = preparation.owner();
            var registered = plugins.registeredPlugins().stream().filter(value -> value.id().equals(owner.pluginId())
                    && value.packageId().equals(owner.packageId()) && value.generation() == owner.pluginGeneration())
                    .findFirst().orElseThrow(() -> new IllegalStateException("directory source owner unavailable"));
            var feature = registered.plugin();
            var fields = feature.guiConfigContributions().stream().flatMap(value -> value.fields().stream()).toList();
            directories = invocations.prepareProxy(preparation, DirectorySuggestionCapability.class,
                    new PluginDirectorySuggestion(owner.pluginId(), sources.values().iterator().next(),
                            RuntimeFiles.resolvePluginConfigPath(owner.pluginId(), "properties"), fields));
            displayName = new DesktopUiText(feature.displayNamespace(), feature.displayName(), "", List.of());
        }
        return new Prepared(preparation.owner(),
                proxySingle(preparation, context.getBeansOfType(DesktopDashboardSource.class),
                        DesktopDashboardSource.class),
                proxySingle(preparation, context.getBeansOfType(DesktopAutomationSource.class),
                        DesktopAutomationSource.class), directories, displayName);
    }

    @Override
    public void publish(PreparedContribution contribution) {
        Prepared prepared = requirePrepared(contribution);
        registry.registerPrepared(prepared.owner(), prepared.dashboards(), prepared.automations(),
                prepared.directories(), prepared.displayName());
    }

    @Override
    public void withdraw(ExternalCapabilityOwner owner) {
        registry.unregisterPrepared(owner);
    }

    private <T> List<T> proxySingle(ExternalCapabilityPreparation preparation,
                                    Map<String, T> beans,
                                    Class<T> type) {
        if (beans.size() != 1) {
            if (beans.size() > 1) {
                throw new IllegalArgumentException("multiple desktop control-center source beans: "
                        + type.getName());
            }
            return List.of();
        }
        T target = beans.values().iterator().next();
        return List.of(invocations.prepareProxy(preparation, type, target));
    }

    private static Prepared requirePrepared(PreparedContribution contribution) {
        if (contribution instanceof Prepared prepared) {
            return prepared;
        }
        throw new IllegalArgumentException("invalid prepared desktop control-center contribution");
    }
}
