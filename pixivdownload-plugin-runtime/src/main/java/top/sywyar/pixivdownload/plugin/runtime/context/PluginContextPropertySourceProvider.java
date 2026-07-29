package top.sywyar.pixivdownload.plugin.runtime.context;

/** Supplies the owner-scoped property snapshot for one plugin child context. */
@FunctionalInterface
public interface PluginContextPropertySourceProvider {

    PluginContextPropertySourceProvider EMPTY = ownerPluginId -> PluginContextPropertySnapshot.empty();

    PluginContextPropertySnapshot snapshotFor(String ownerPluginId);
}
