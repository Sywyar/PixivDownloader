package com.example.pixivdownload.minimal;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.SchemaContribution;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

/** Stable, framework-light declarations consumed by the host plugin registry. */
public final class ExampleMinimalPlugin implements PixivFeaturePlugin {

    public static final String ID = "example-minimal";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public String iconKey() {
        return "puzzle";
    }

    @Override
    public String colorToken() {
        return "blue";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.admin("/example-minimal.html"),
                WebRouteContribution.admin("/example-minimal/**"),
                WebRouteContribution.admin("/api/example-minimal/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution("classpath:/static/", "/example-minimal.html", true),
                new StaticResourceContribution(
                        "classpath:/static/example-minimal/", "/example-minimal/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.example-minimal"));
    }

    @Override
    public List<SchemaContribution> schema() {
        return List.of(ExampleMinimalSchema.contribution());
    }
}
