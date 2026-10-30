package top.sywyar.pixivdownload.pageeditor;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import java.util.*;

/** 页面编辑器的资源、导航与管理入口归本插件。 */
public final class PageEditorPlugin extends Plugin implements PixivPluginProvider {
    @Override public List<Class<?>> configurationClasses() { return List.of(PageEditorConfiguration.class); }
    @Override public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            public String id() { return "page-editor"; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.summary"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<I18nContribution> i18n() { return List.of(new I18nContribution("page-editor", "i18n.web.page-editor")); }
            public List<WebRouteContribution> routes() {
                return List.of(WebRouteContribution.admin("/page-editor/**"), WebRouteContribution.admin("/api/page-editor/**"));
            }
            public List<StaticResourceContribution> staticResources() { return List.of(new StaticResourceContribution("classpath:/web/", "/page-editor/")); }
            public List<NavigationContribution> navigation() {
                return List.of(new NavigationContribution("page-editor", Set.of("app.top"), "page-editor", "plugin.name",
                        "/page-editor/index.html", "pen", AccessPolicy.ADMIN, 100));
            }
        };
    }
}
