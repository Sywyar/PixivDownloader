package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import java.util.List;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.api.gui.*;

/** 官方外部下载采集与本地导入插件。 */
public final class PixivBatchDownloaderImportPlugin extends Plugin implements PixivPluginProvider {
    @Override public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            @Override public String id() { return "pixiv-batch-downloader-import"; }
            @Override public String displayName() { return "plugin.name"; }
            @Override public String description() { return "plugin.summary"; }
            @Override public String iconKey() { return "download"; }
            @Override public String colorToken() { return "blue"; }
            @Override public PluginKind kind() { return PluginKind.FEATURE; }
            @Override public List<WebRouteContribution> routes() {
                return List.of(new WebRouteContribution("/api/pixiv-batch-downloader-import", AccessPolicy.LOCAL,
                        Set.of(HttpMethod.POST), false, Set.of("https://www.pixiv.net", "https://pixiv.net")),
                        new WebRouteContribution("/api/pixiv-batch-downloader-import/token", AccessPolicy.LOCAL, Set.of(HttpMethod.GET), false));
            }
            @Override public List<I18nContribution> i18n() {
                return List.of(new I18nContribution("pixiv-batch-downloader-import", "i18n.web.pixiv-batch-downloader-import"));
            }
            @Override public List<GuiConfigContribution> guiConfigContributions() {
                return List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution("pixiv-batch-downloader-import", "plugin.name", 75)),
                        List.of(new GuiConfigFieldContribution("pixiv-batch-downloader-import.source-root", "pixiv-batch-downloader-import",
                                "config.root.label", "config.root.help", GuiConfigFieldType.PATH_DIR, "", 10, false,
                                GuiConfigEffect.HOT_RELOAD))));
            }
            @Override public List<UserscriptContribution> userscripts() {
                return List.of(new UserscriptContribution("pixiv-batch-downloader-import",
                        "classpath:/userscripts/pixiv-batch-downloader-import.user.js", "pixiv-batch-downloader-import"));
            }
        };
    }
    @Override public List<Class<?>> configurationClasses() { return List.of(PixivBatchDownloaderImportConfiguration.class); }
}
