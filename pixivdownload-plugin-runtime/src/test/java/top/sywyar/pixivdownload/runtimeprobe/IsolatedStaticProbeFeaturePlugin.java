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
import java.util.stream.IntStream;

/** 隔离 worker 纯值与资源代理测试夹具。 */
public final class IsolatedStaticProbeFeaturePlugin implements PixivFeaturePlugin {

    public static final int MAX_CONTRIBUTIONS = 256;

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
        return IntStream.range(0, MAX_CONTRIBUTIONS)
                .mapToObj(index -> WebRouteContribution.publicRoute(index == 0
                        ? "/isolated-static/**"
                        : "/isolated-static/route-" + index))
                .toList();
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return IntStream.range(0, MAX_CONTRIBUTIONS)
                .mapToObj(index -> new StaticResourceContribution(
                        "classpath:/static/isolated-static/",
                        index == 0 ? "/isolated-static/" : "/isolated-static/resources-" + index + "/"))
                .toList();
    }

    @Override
    public List<I18nContribution> i18n() {
        return IntStream.range(0, MAX_CONTRIBUTIONS)
                .mapToObj(index -> new I18nContribution(
                        index == 0 ? "isolated-static" : "isolated-static-" + index,
                        "i18n.web.isolatedstatic",
                        123 + index))
                .toList();
    }

    @Override
    public List<NavigationContribution> navigation() {
        return IntStream.range(0, MAX_CONTRIBUTIONS)
                .mapToObj(index -> new NavigationContribution(
                        index == 0 ? "isolated-static.home" : "isolated-static.item-" + index,
                        Set.of("app.top"),
                        "isolated-static",
                        "nav.home",
                        index == 0 ? "/isolated-static/index.html" : "/isolated-static/item-" + index,
                        "puzzle",
                        AccessPolicy.PUBLIC,
                        500 + index))
                .toList();
    }
}
