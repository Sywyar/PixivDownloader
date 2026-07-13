package top.sywyar.pixivdownload.plugin.lifecycle.request;

/**
 * A pure-value identity for one externally served plugin generation.
 *
 * <p>The serving id changes for every stop/start cycle even when the physical plugin generation is reused. Keeping
 * this key free of plugin objects and class loaders lets request drains outlive withdrawal without pinning child code.
 */
public record PluginRequestOwner(String pluginId, long generation, long servingId) {

    public PluginRequestOwner {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("blank plugin request owner id");
        }
        if (generation < 0L) {
            throw new IllegalArgumentException("negative plugin generation: " + generation);
        }
        if (servingId <= 0L) {
            throw new IllegalArgumentException("non-positive plugin serving id: " + servingId);
        }
    }
}
