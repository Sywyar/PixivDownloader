package top.sywyar.pixivdownload.plugin.runtime.context;

import java.util.Map;

/** Supplies owner-scoped properties that must only be visible inside one plugin child context. */
@FunctionalInterface
public interface PluginContextPropertySourceProvider {

    PluginContextPropertySourceProvider EMPTY = ownerPluginId -> Map.of();

    Map<String, Object> propertiesFor(String ownerPluginId);
}
