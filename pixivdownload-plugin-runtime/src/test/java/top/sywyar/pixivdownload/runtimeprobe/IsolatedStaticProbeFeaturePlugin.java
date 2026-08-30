package top.sywyar.pixivdownload.runtimeprobe;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;

/** 隔离 worker 纯值与资源代理测试夹具。 */
public final class IsolatedStaticProbeFeaturePlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "isolated-static-probe";
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
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(WebRouteContribution.publicRoute("/isolated-static/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(new StaticResourceContribution(
                "classpath:/static/isolated-static/", "/isolated-static/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution("isolated-static", "i18n.web.isolatedstatic", 123));
    }

    @Override
    public List<NavigationContribution> navigation() {
        return List.of(new NavigationContribution(
                "isolated-static.home", Set.of("app.top"), "isolated-static", "nav.home",
                "/isolated-static/index.html", "puzzle", AccessPolicy.PUBLIC, 500));
    }
}
