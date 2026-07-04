package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.registry.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-external-novel-gallery",
        "setup.browser.auto-open=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("externalNovelGalleryJarStaged")
@DisplayName("外置 novel-gallery 插件经真实上下文接入：EXTERNAL 来源 + 展示贡献 classloader-aware 可见")
class NovelGalleryExternalPluginBootContextTest {

    private static final String NOVEL_GALLERY_CLASSES_PROPERTY = "novel-gallery.plugin.classes";
    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/plugins-external-novel-gallery");
    private static final boolean STAGED = stageExternalNovelGalleryJar();

    static {
        if (STAGED) {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
            System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
            System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, PLUGINS_DIR.toString());
        }
    }

    @SuppressWarnings("unused")
    static boolean externalNovelGalleryJarStaged() {
        return STAGED;
    }

    @Autowired
    private PluginRuntimeManager pluginRuntimeManager;
    @Autowired
    private PluginRuntimeStatus pluginRuntimeStatus;
    @Autowired
    private PluginDiscoveryResult pluginDiscoveryResult;
    @Autowired
    private PluginRegistry pluginRegistry;
    @Autowired
    private RouteAccessRegistry routeAccessRegistry;
    @Autowired
    private StaticResourceRegistry staticResourceRegistry;
    @Autowired
    private WebI18nBundleRegistry webI18nBundleRegistry;
    @Autowired
    private NavigationRegistry navigationRegistry;
    @Autowired
    private LandingRegistry landingRegistry;
    @Autowired
    private ExternalPluginContextManager externalPluginContextManager;
    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;
    @Autowired
    private WebApplicationContext applicationContext;

    @AfterAll
    void releasePluginsAndCleanup() {
        if (pluginRuntimeManager != null) {
            pluginRuntimeManager.pluginManager().ifPresent(pm -> {
                try {
                    pm.stopPlugins();
                } catch (Exception ignored) {
                    // best-effort
                }
                try {
                    pm.unloadPlugins();
                } catch (Exception ignored) {
                    // best-effort
                }
            });
        }
        deleteRecursivelyQuietly(PLUGINS_DIR);
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
    }

    @Test
    @DisplayName("运行时状态 / 发现结果 Bean：POPULATED、started 含 novel-gallery、无失败、发现 novel-gallery")
    void runtimeStatusAndDiscoveryBeansSeeNovelGallery() {
        assertThat(pluginRuntimeStatus.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(pluginRuntimeStatus.startedPluginIds()).contains("novel-gallery");
        assertThat(pluginRuntimeStatus.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.discovered())
                .extracting(DiscoveredFeaturePlugin::featurePluginId).contains("novel-gallery");
    }

    @Test
    @DisplayName("双来源 PluginRegistry Bean：三内置 + 外置 novel-gallery，novel-gallery 来源 EXTERNAL")
    void pluginRegistryBeanContainsNovelGalleryAsExternal() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .containsExactlyInAnyOrder("core", "novel", "plugin-market", "novel-gallery");
        assertThat(pluginRegistry.source("novel-gallery")).contains(PluginSource.EXTERNAL);
        assertThat(externalNovelGalleryClassLoader()).isNotSameAs(getClass().getClassLoader());
    }

    @Test
    @DisplayName("novel-gallery 的路由 / 静态资源 / i18n 经外置插件 classloader 注册")
    void novelGalleryWebContributionsAreClassloaderAware() {
        ClassLoader externalCl = externalNovelGalleryClassLoader();

        assertThat(routeAccessRegistry.routes())
                .filteredOn(r -> r.pluginId().equals("novel-gallery"))
                .extracting(r -> r.route().pathPattern())
                .containsExactlyInAnyOrder(
                        "/pixiv-novel-gallery.html",
                        "/pixiv-novel.html",
                        "/pixiv-novel-gallery/**",
                        "/pixiv-novel/**",
                        "/api/gallery/novel/**",
                        "/api/gallery/novels/**",
                        "/api/gallery/novels");
        assertThat(staticResourceRegistry.resources())
                .filteredOn(s -> s.pluginId().equals("novel-gallery"))
                .allSatisfy(s -> assertThat(s.classLoader()).isSameAs(externalCl))
                .extracting(s -> s.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-novel-gallery.html",
                        "/pixiv-novel.html",
                        "/pixiv-novel-gallery/",
                        "/pixiv-novel/");

        WebI18nBundleRegistry.RegisteredBundle bundle = webI18nBundleRegistry.resolve("novel-gallery");
        assertThat(bundle).isNotNull();
        assertThat(bundle.pluginId()).isEqualTo("novel-gallery");
        assertThat(bundle.classLoader()).isSameAs(externalCl);
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE)).containsEntry("plugin.name", "小说");

        assertThat(externalCl.getResource("static/pixiv-novel-gallery.html")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-novel/pixiv-novel-render.js")).isNotNull();
        assertThat(externalCl.getResource("i18n/web/novel-gallery.properties")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("static/pixiv-novel-gallery.html")).isNull();
    }

    @Test
    @DisplayName("novel-gallery 导航 / 落点贡献随外置插件注册")
    void novelGalleryNavigationAndEntryContributionsAreRegistered() {
        assertThat(navigationRegistry.navigation())
                .filteredOn(nav -> nav.pluginId().equals("novel-gallery"))
                .extracting(nav -> nav.navigation().id())
                .containsExactlyInAnyOrder("novel-gallery", "novel-type-switch");
        assertThat(landingRegistry.landings())
                .filteredOn(landing -> landing.pluginId().equals("novel-gallery"))
                .singleElement()
                .satisfies(landing -> assertThat(landing.landing().href()).isEqualTo("/pixiv-novel-gallery.html"));
    }

    @Test
    @DisplayName("novel-gallery 子 ApplicationContext 托管 service/controller，controller 动态注册进父分发表")
    void externalNovelGalleryChildContextHostsBeansAndControllerMapping() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("novel-gallery").orElseThrow();
        ClassLoader externalCl = externalNovelGalleryClassLoader();
        assertThat(child.getParent()).isSameAs(applicationContext);
        assertThat(child.getClassLoader()).isSameAs(externalCl);

        Class<?> serviceClass = externalCl.loadClass("top.sywyar.pixivdownload.novelgallery.NovelGalleryService");
        Class<?> batchServiceClass = externalCl.loadClass("top.sywyar.pixivdownload.novelgallery.NovelBatchService");
        Class<?> controllerClass =
                externalCl.loadClass("top.sywyar.pixivdownload.novelgallery.controller.NovelGalleryController");

        assertThat(child.getBeanNamesForType(serviceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(batchServiceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(controllerClass)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(controllerClass)).isEmpty();
        assertThat(novelGalleryListHandlerBean()).isSameAs(child.getBean(controllerClass));
    }

    private Object novelGalleryListHandlerBean() {
        return requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().contains("/api/gallery/novels"))
                .map(e -> e.getValue().getBean())
                .findFirst().orElse(null);
    }

    private ClassLoader externalNovelGalleryClassLoader() {
        return pluginRegistry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("novel-gallery")).findFirst().orElseThrow().classLoader();
    }

    private static boolean stageExternalNovelGalleryJar() {
        try {
            String configured = System.getProperty(NOVEL_GALLERY_CLASSES_PROPERTY);
            if (configured == null || configured.isBlank()) {
                return false;
            }
            Path classes = Path.of(configured);
            if (!Files.isDirectory(classes) || !Files.exists(classes.resolve("plugin.properties"))) {
                return false;
            }
            deleteRecursivelyQuietly(PLUGINS_DIR);
            Files.createDirectories(PLUGINS_DIR);
            Path jar = PLUGINS_DIR.resolve("novel-gallery-plugin.jar");
            zipDirectoryAsJar(classes, jar);
            PluginTestProvenance.writeLocalUpload(PLUGINS_DIR, jar, "novel-gallery", "1.0.0");
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static void zipDirectoryAsJar(Path sourceDir, Path jarPath) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarPath);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            List<Path> files;
            try (var walk = Files.walk(sourceDir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
