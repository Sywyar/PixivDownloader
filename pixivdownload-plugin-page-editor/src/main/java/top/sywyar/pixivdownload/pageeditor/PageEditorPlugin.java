package top.sywyar.pixivdownload.pageeditor;

import java.util.Map;
import top.sywyar.pixivdownload.plugin.api.web.ProgramPermission;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import java.util.*;

/** 页面编辑器的资源、导航与管理入口归本插件。 */
public final class PageEditorPlugin extends Plugin implements PixivPluginProvider {
    @Override public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            public String id() { return "page-editor"; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.summary"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<I18nContribution> i18n() { return List.of(new I18nContribution("page-editor", "i18n.web.page-editor")); }
            public List<WebRouteContribution> routes() {
                return List.of(
                WebRouteContribution.admin("/api/page-editor/catalog").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("pages.read", "permission.pages.read"))),
                WebRouteContribution.admin("/api/page-editor/documents/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("pages.read", "permission.pages.read"))),
                WebRouteContribution.admin("/api/page-editor/documents/*/default").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("pages.read", "permission.pages.read"))),
                WebRouteContribution.admin("/api/page-editor/documents/*/save").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("pages.write", "permission.pages.write"))),
                WebRouteContribution.admin("/api/page-editor/documents/*/publish").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("pages.publish", "permission.pages.publish"))),
                WebRouteContribution.admin("/api/page-editor/documents/*/restore").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("pages.publish", "permission.pages.publish"))),
                WebRouteContribution.admin("/api/page-editor/documents/*/prepare").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("pages.preview", "permission.pages.preview"))),
                WebRouteContribution.admin("/api/page-editor/documents/*/preview").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("pages.preview", "permission.pages.preview"))),
                WebRouteContribution.admin("/api/page-editor/migrate").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("pages.preview", "permission.pages.preview"))),
                WebRouteContribution.admin("/api/page-editor/styles/parse").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("pages.preview", "permission.pages.preview"))),
                WebRouteContribution.admin("/api/page-editor/components").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("components.read", "permission.components.read"))),
                WebRouteContribution.admin("/api/page-editor/components/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("components.read", "permission.components.read"))),
                WebRouteContribution.admin("/api/page-editor/components/*/versions/*").withProgramPermissions(Map.of(HttpMethod.GET, new ProgramPermission("components.read", "permission.components.read"))),
                WebRouteContribution.admin("/api/page-editor/components/save").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("components.write", "permission.components.write"))),
                WebRouteContribution.admin("/api/page-editor/components/*/publish").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("components.publish", "permission.components.publish"))),
                WebRouteContribution.admin("/api/page-editor/components/*/availability").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("components.publish", "permission.components.publish"))),
                WebRouteContribution.admin("/api/page-editor/components/preview").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("components.preview", "permission.components.preview"))),
                WebRouteContribution.admin("/api/page-editor/components/previews/*/close").withProgramPermissions(Map.of(HttpMethod.POST, new ProgramPermission("components.preview", "permission.components.preview"))),WebRouteContribution.admin("/page-editor/**"), WebRouteContribution.admin("/api/page-editor/**"),
                        new WebRouteContribution("/page-components/**", AccessPolicy.PUBLIC, Set.of(HttpMethod.GET, HttpMethod.HEAD), false));
            }
            public List<StaticResourceContribution> staticResources() { return List.of(new StaticResourceContribution("classpath:/web/", "/page-editor/"),
                    new StaticResourceContribution("classpath:/components/", "/page-components/")); }
            public List<NavigationContribution> navigation() {
                return List.of(new NavigationContribution("page-editor", Set.of("app.top"), "page-editor", "plugin.name",
                        "/page-editor/index.html", "pen", AccessPolicy.ADMIN, 100));
            }
        };
    }
}
