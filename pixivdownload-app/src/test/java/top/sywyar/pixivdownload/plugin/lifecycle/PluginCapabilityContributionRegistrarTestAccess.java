package top.sywyar.pixivdownload.plugin.lifecycle;

import top.sywyar.pixivdownload.plugin.lifecycle.capability.ExternalRuntimeCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.PluginContextCapabilityContributionAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;

import java.util.List;

/** Package bridge for central cleanup failure probes used by publication-boundary tests. */
public final class PluginCapabilityContributionRegistrarTestAccess {

    private PluginCapabilityContributionRegistrarTestAccess() {
    }

    public static PluginCapabilityContributionRegistrar withCentralCleanupProbe(
            List<PluginCapabilityContributionAdapter<?>> legacyAdapters,
            List<PluginContextCapabilityContributionAdapter> legacyContextAdapters,
            List<ExternalRuntimeCapabilityAdapter> runtimeAdapters,
            ExternalCapabilityInvocationRegistry invocationRegistry,
            Runnable centralCleanupProbe) {
        return new PluginCapabilityContributionRegistrar(
                legacyAdapters,
                legacyContextAdapters,
                runtimeAdapters,
                invocationRegistry,
                centralCleanupProbe);
    }
}
