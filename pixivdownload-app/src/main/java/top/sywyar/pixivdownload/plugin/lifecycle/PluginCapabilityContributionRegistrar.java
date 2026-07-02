package top.sywyar.pixivdownload.plugin.lifecycle;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginCapabilityContributionAdapter;

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

    public PluginCapabilityContributionRegistrar(List<PluginCapabilityContributionAdapter<?>> adapters) {
        this.adapters = adapters == null ? List.of() : adapters.stream()
                .sorted(Comparator.comparing(PluginCapabilityContributionAdapter::capabilityName))
                .toList();
    }

    public void register(String pluginId, ConfigurableApplicationContext context) {
        if (context == null) {
            unregister(pluginId);
            return;
        }
        List<PluginCapabilityContributionAdapter<?>> registered = new ArrayList<>();
        try {
            for (PluginCapabilityContributionAdapter<?> adapter : adapters) {
                registered.add(adapter);
                registerOne(pluginId, context, adapter);
            }
        } catch (RuntimeException e) {
            rollback(pluginId, registered);
            throw e;
        }
    }

    public void unregister(String pluginId) {
        for (PluginCapabilityContributionAdapter<?> adapter : adapters) {
            adapter.unregister(pluginId);
        }
    }

    public List<String> capabilityNames() {
        return adapters.stream()
                .map(PluginCapabilityContributionAdapter::capabilityName)
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
}
