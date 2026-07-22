package com.example.pixivdownload.minimal;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.util.List;

/** PF4J entrypoint. Framework code stays here and out of the shared plugin API. */
public final class ExampleMinimalPf4jPlugin extends Plugin implements PixivPluginProvider {

    @Override
    public PixivFeaturePlugin featurePlugin() {
        return new ExampleMinimalPlugin();
    }

    @Override
    public List<Class<?>> configurationClasses() {
        return List.of(ExampleMinimalConfiguration.class);
    }
}
