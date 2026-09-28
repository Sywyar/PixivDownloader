package top.sywyar.pixivdownload.externalimport;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import java.util.List;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.api.gui.*;

/** 官方外部下载采集与本地导入插件。 */
public final class ExternalImportPlugin extends Plugin implements PixivPluginProvider {
    @Override public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            @Override public String id() { return "external-download-import"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.summary"; }
            @Override public String iconKey() { return "download"; }
            @Override public String colorToken() { return "blue"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<WebRouteContribution> routes() {
                return List.of(new WebRouteContribution("/api/external-download-import", AccessPolicy.LOCAL,
                        Set.of(HttpMethod.POST), false, Set.of("https://www.pixiv.net", "https://pixiv.net")),
                        new WebRouteContribution("/api/external-download-import/token", AccessPolicy.LOCAL, Set.of(HttpMethod.GET), false));
            }
            @Override public List<I18nContribution> i18n() {
                return List.of(new I18nContribution("external-import", "i18n.web.external-import"));
            }
            @Override public List<GuiConfigContribution> guiConfigContributions() {
                return List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution("external-import", "plugin.name", 75)),
                        List.of(new GuiConfigFieldContribution("external-download-import.source-root", "external-import",
                                "config.root.label", "config.root.help", GuiConfigFieldType.PATH_DIR, "", 10, false,
                                GuiConfigEffect.BACKEND_RESTART))));
            }
            @Override public List<UserscriptContribution> userscripts() {
                return List.of(new UserscriptContribution("external-download-observer",
                        "classpath:/userscripts/external-download-observer.user.js", "external-import"));
            }
        };
    }
    @Override public List<Class<?>> configurationClasses() { return List.of(ExternalImportConfiguration.class); }
}
