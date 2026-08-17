package top.sywyar.pixivdownload.posthog;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

/** PostHog browser SDK and caller-configured survey client adapter. */
public class PostHogPlugin implements PixivFeaturePlugin {

    public static final String ID = "posthog";

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
        return "purple";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(WebRouteContribution.publicRoute("/pixiv-posthog/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution("classpath:/static/pixiv-posthog/", "/pixiv-posthog/"),
                new StaticResourceContribution(
                        "classpath:/static/vendor/posthog-js/", "/pixiv-posthog/vendor/posthog-js/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution(ID, "i18n.web.posthog", 17));
    }
}
