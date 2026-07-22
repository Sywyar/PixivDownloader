package top.sywyar.pixivdownload.plugin.schema;

import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

/** 在外置插件业务足迹启动前同步其受管 schema 新代。 */
@FunctionalInterface
public interface PluginSchemaLifecycle {

    PluginSchemaLifecycle NO_OP = registered -> {
    };

    void activate(PluginRegistry.RegisteredPlugin registered);
}
