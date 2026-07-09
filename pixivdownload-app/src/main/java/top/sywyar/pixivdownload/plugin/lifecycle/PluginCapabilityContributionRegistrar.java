package top.sywyar.pixivdownload.plugin.lifecycle;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginContextCapabilityContributionAdapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;

/**
 * Registers runtime capability beans contributed by an external plugin child context.
 */
@Component
public class PluginCapabilityContributionRegistrar {

    private final List<PluginCapabilityContributionAdapter<?>> adapters;
    private final List<PluginContextCapabilityContributionAdapter> contextAdapters;

    public PluginCapabilityContributionRegistrar(List<PluginCapabilityContributionAdapter<?>> adapters) {
        this(adapters, List.of());
    }

    @Autowired
    public PluginCapabilityContributionRegistrar(
            List<PluginCapabilityContributionAdapter<?>> adapters,
            List<PluginContextCapabilityContributionAdapter> contextAdapters) {
        this.adapters = adapters == null ? List.of() : adapters.stream()
                .sorted(Comparator.comparing(PluginCapabilityContributionAdapter::capabilityName))
                .toList();
        this.contextAdapters = contextAdapters == null ? List.of() : contextAdapters.stream()
                .sorted(Comparator.comparing(PluginContextCapabilityContributionAdapter::capabilityName))
                .toList();
    }

    public void register(String pluginId, ConfigurableApplicationContext context) {
        if (context == null) {
            unregister(pluginId);
            return;
        }
        List<PluginCapabilityContributionAdapter<?>> registered = new ArrayList<>();
        List<PluginContextCapabilityContributionAdapter> registeredContexts = new ArrayList<>();
        try {
            for (PluginCapabilityContributionAdapter<?> adapter : adapters) {
                registered.add(adapter);
                registerOne(pluginId, context, adapter);
            }
            for (PluginContextCapabilityContributionAdapter adapter : contextAdapters) {
                registeredContexts.add(adapter);
                adapter.register(pluginId, context);
            }
        } catch (RuntimeException e) {
            rollbackContexts(pluginId, registeredContexts);
            rollback(pluginId, registered);
            throw e;
        }
    }

    public void unregister(String pluginId) {
        for (PluginCapabilityContributionAdapter<?> adapter : adapters) {
            adapter.unregister(pluginId);
        }
        for (PluginContextCapabilityContributionAdapter adapter : contextAdapters) {
            adapter.unregister(pluginId);
        }
    }

    public List<String> capabilityNames() {
        return java.util.stream.Stream.concat(
                        adapters.stream().map(PluginCapabilityContributionAdapter::capabilityName),
                        contextAdapters.stream().map(PluginContextCapabilityContributionAdapter::capabilityName))
                .sorted()
                .toList();
    }

    private static <T> List<T> beans(ConfigurableApplicationContext context, Class<T> type) {
        return List.copyOf(context.getBeansOfType(type).values());
    }

    private static <T> void registerOne(String pluginId, ConfigurableApplicationContext context,
                                        PluginCapabilityContributionAdapter<T> adapter) {
        adapter.register(pluginId, beans(context, adapter.beanType()));
    }

    private static void rollback(String pluginId, List<PluginCapabilityContributionAdapter<?>> registered) {
        ListIterator<PluginCapabilityContributionAdapter<?>> iterator = registered.listIterator(registered.size());
        while (iterator.hasPrevious()) {
            iterator.previous().unregister(pluginId);
        }
    }

    private static void rollbackContexts(
            String pluginId, List<PluginContextCapabilityContributionAdapter> registered) {
        ListIterator<PluginContextCapabilityContributionAdapter> iterator =
                registered.listIterator(registered.size());
        while (iterator.hasPrevious()) {
            iterator.previous().unregister(pluginId);
        }
    }
}
