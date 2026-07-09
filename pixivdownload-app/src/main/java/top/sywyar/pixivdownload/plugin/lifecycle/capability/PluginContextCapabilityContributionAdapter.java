package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;

/** Batch adapter for capabilities that must be discovered and published in one atomic registry update. */
public interface PluginContextCapabilityContributionAdapter {

    String capabilityName();

    void register(String pluginId, ConfigurableApplicationContext context);

    void unregister(String pluginId);
}
