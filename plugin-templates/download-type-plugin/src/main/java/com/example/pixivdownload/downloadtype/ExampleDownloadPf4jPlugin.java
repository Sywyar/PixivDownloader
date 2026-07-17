package com.example.pixivdownload.downloadtype;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

/** PF4J entry point named by {@code plugin.properties}. */
public final class ExampleDownloadPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public List<PixivFeaturePlugin> featurePlugins() {
        return List.of(new ExampleDownloadPlugin());
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(ExampleDownloadConfiguration.class);
    }
}
