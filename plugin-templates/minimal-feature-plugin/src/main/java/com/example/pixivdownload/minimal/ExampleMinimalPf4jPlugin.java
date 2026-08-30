package com.example.pixivdownload.minimal;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

/** PF4J 入口；框架代码只停留在入口层，不进入共享插件契约。 */
public final class ExampleMinimalPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new ExampleMinimalPlugin();
    }
}
