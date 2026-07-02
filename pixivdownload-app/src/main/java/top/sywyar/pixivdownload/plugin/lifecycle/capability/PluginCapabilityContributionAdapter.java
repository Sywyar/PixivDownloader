package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import java.util.List;

/**
 * Host-side adapter for runtime capability beans contributed by an external plugin child context.
 * <p>
 * The lifecycle core only knows this neutral contract. Each concrete capability keeps its own
 * registry and conflict rules behind a small adapter bean.
 */
public interface PluginCapabilityContributionAdapter<T> {

    /** Bean type to discover from the plugin child context. */
    Class<T> beanType();

    /** Stable name used for deterministic registration order and diagnostics. */
    default String capabilityName() {
        return beanType().getName();
    }

    /** Register all beans of this capability for the plugin. Empty lists should remove prior entries. */
    void register(String pluginId, List<T> beans);

    /** Remove all beans of this capability for the plugin. Must be idempotent. */
    void unregister(String pluginId);
}
