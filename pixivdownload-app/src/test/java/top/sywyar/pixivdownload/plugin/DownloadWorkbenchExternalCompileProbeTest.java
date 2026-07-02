package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;

import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("download-workbench 外置 descriptor 编译探针")
class DownloadWorkbenchExternalCompileProbeTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("descriptor 贡献面只依赖 plugin-api 即可编译，不借 app 扁平 classpath")
    void descriptorCompilesWithPluginApiOnly() throws Exception {
        Path source = tempDir.resolve("probe/DownloadWorkbenchProbePlugin.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package probe;

                import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
                import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
                import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
                import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
                import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
                import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
                import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
                import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
                import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
                import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
                import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
                import top.sywyar.pixivdownload.plugin.api.web.TabContribution;
                import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
                import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

                import java.util.List;
                import java.util.Set;

                public final class DownloadWorkbenchProbePlugin implements PixivFeaturePlugin {
                    public String id() { return "download-workbench"; }
                    public String displayName() { return "plugin.label"; }
                    public String description() { return "plugin.summary"; }
                    public String iconKey() { return "download"; }
                    public String colorToken() { return "pixiv"; }
                    public PluginKind kind() { return PluginKind.FEATURE; }
                    public boolean required() { return true; }
                    public List<WebRouteContribution> routes() {
                        return List.of(
                                WebRouteContribution.visitor("/pixiv-batch.html"),
                                WebRouteContribution.visitor("/pixiv-batch/**"),
                                WebRouteContribution.visitor("/api/download/pixiv"),
                                WebRouteContribution.visitor("/api/download/extensions"));
                    }
                    public List<StaticResourceContribution> staticResources() {
                        return List.of(new StaticResourceContribution(id(), "classpath:/static/pixiv-batch/", "/pixiv-batch/"));
                    }
                    public List<StartupRouteContribution> startupRoutes() {
                        return List.of(new StartupRouteContribution(id(), "/pixiv-batch.html", 10, Set.of(StartupRouteContext.MULTI)));
                    }
                    public List<NavigationContribution> navigation() {
                        return List.of(new NavigationContribution(id(), NavigationPlacements.APP_TOP,
                                "batch", "nav.label", "/pixiv-batch.html", "download", AccessPolicy.VISITOR, 10));
                    }
                    public List<I18nContribution> i18n() {
                        return List.of(new I18nContribution("batch", "i18n.web.batch", 5));
                    }
                    public List<UserscriptContribution> userscripts() {
                        return List.of(new UserscriptContribution(id(), "classpath:/static/userscripts/*.user.js"));
                    }
                    public List<QueueTypeContribution> queueTypes() {
                        return List.of(new QueueTypeContribution(id(), "illust", "novel", "batch.user.kind-illust", 10, null));
                    }
                    public List<TabContribution> downloadTabs() {
                        return List.of(new TabContribution(id(), "quick-fetch", 10, List.of("illust", "novel")));
                    }
                    public List<ScheduledSourceProvider> scheduledSources() {
                        return List.of();
                    }
                }
                """, StandardCharsets.UTF_8);

        Path out = tempDir.resolve("classes");
        Files.createDirectories(out);
        String pluginApiClasspath = classpathEntry(PixivFeaturePlugin.class);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("编译探针需要 JDK javac").isNotNull();
        int exit = compiler.run(null, null, null,
                "-encoding", "UTF-8",
                "-classpath", pluginApiClasspath,
                "-d", out.toString(),
                source.toString());

        assertThat(exit).isZero();
        assertThat(out.resolve("probe/DownloadWorkbenchProbePlugin.class")).isRegularFile();
    }

    private static String classpathEntry(Class<?> type) throws Exception {
        URI uri = type.getProtectionDomain().getCodeSource().getLocation().toURI();
        return Path.of(uri).toString();
    }
}
