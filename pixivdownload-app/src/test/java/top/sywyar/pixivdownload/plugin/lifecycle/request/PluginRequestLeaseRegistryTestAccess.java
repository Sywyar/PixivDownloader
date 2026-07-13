package top.sywyar.pixivdownload.plugin.lifecycle.request;

/** Package bridge for fatal-path probes used by request-gateway tests. */
public final class PluginRequestLeaseRegistryTestAccess {

    private PluginRequestLeaseRegistryTestAccess() {
    }

    public static PluginRequestLeaseRegistry withAcquireProbe(Runnable postAcquireProbe) {
        return new PluginRequestLeaseRegistry(postAcquireProbe);
    }
}
