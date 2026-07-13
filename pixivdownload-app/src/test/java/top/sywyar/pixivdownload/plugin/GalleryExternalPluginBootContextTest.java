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
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.registry.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StartupRouteRegistry;
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
        "pixivdownload.plugins-dir=target/test-runtime/plugins-external-gallery",
        "setup.browser.auto-open=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("externalGalleryJarStaged")
@DisplayName("外置 gallery 插件经真实上下文接入：EXTERNAL 来源 + 下游注册中心 classloader-aware 可见")
class GalleryExternalPluginBootContextTest {

    private static final String GALLERY_CLASSES_PROPERTY = "gallery.plugin.classes";
    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/plugins-external-gallery");
    private static final boolean STAGED = stageExternalGalleryJar();

    static {
        if (STAGED) {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
            System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
            System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, PLUGINS_DIR.toString());
        }
    }

    @SuppressWarnings("unused")
    static boolean externalGalleryJarStaged() {
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
    private StartupRouteRegistry startupRouteRegistry;
    @Autowired
    private ExternalPluginContextManager externalPluginContextManager;
    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;
    @Autowired
    private WebApplicationContext applicationContext;
    @Autowired
    private GalleryCapabilityRegistry galleryCapabilityRegistry;

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
    @DisplayName("运行时状态 / 发现结果 Bean：POPULATED、started 含 gallery、无失败、发现 gallery")
    void runtimeStatusAndDiscoveryBeansSeeGallery() {
        assertThat(pluginRuntimeStatus.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(pluginRuntimeStatus.startedPluginIds()).contains("gallery");
        assertThat(pluginRuntimeStatus.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.discovered())
                .extracting(DiscoveredFeaturePlugin::featurePluginId).contains("gallery");
    }

    @Test
    @DisplayName("双来源 PluginRegistry Bean：三内置 + 外置 gallery，gallery 来源 EXTERNAL")
    void pluginRegistryBeanContainsGalleryAsExternal() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .containsExactlyInAnyOrder("core", "plugin-market", "gallery");
        assertThat(pluginRegistry.source("gallery")).contains(PluginSource.EXTERNAL);
        assertThat(externalGalleryClassLoader()).isNotSameAs(getClass().getClassLoader());
    }

    @Test
    @DisplayName("gallery 的路由 / 静态资源 / i18n 经外置插件 classloader 注册")
    void galleryWebContributionsAreClassloaderAware() {
        ClassLoader externalCl = externalGalleryClassLoader();

        assertThat(routeAccessRegistry.routes())
                .filteredOn(r -> r.pluginId().equals("gallery"))
                .extracting(r -> r.route().pathPattern())
                .containsExactlyInAnyOrder(
                        "/pixiv-gallery.html",
                        "/pixiv-artwork.html",
                        "/pixiv-showcase.html",
                        "/pixiv-series.html",
                        "/pixiv-gallery/**",
                        "/pixiv-artwork/**",
                        "/pixiv-showcase/**",
                        "/pixiv-series/**",
                        "/api/gallery/artwork**",
                        "/api/gallery/tags**",
                        "/api/gallery/unified/**");
        assertThat(staticResourceRegistry.resources())
                .filteredOn(s -> s.pluginId().equals("gallery"))
                .allSatisfy(s -> assertThat(s.classLoader()).isSameAs(externalCl))
                .extracting(s -> s.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-gallery.html",
                        "/pixiv-artwork.html",
                        "/pixiv-showcase.html",
                        "/pixiv-series.html",
                        "/pixiv-gallery/",
                        "/pixiv-artwork/",
                        "/pixiv-showcase/",
                        "/pixiv-series/");

        WebI18nBundleRegistry.RegisteredBundle bundle = webI18nBundleRegistry.resolve("gallery");
        assertThat(bundle).isNotNull();
        assertThat(bundle.pluginId()).isEqualTo("gallery");
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE)).containsEntry("plugin.name", "画廊");
        assertThat(webI18nBundleRegistry.resolve("artwork").load(Locale.SIMPLIFIED_CHINESE)).isNotEmpty();
        assertThat(webI18nBundleRegistry.resolve("showcase").load(Locale.SIMPLIFIED_CHINESE)).isNotEmpty();
        assertThat(webI18nBundleRegistry.resolve("series").load(Locale.SIMPLIFIED_CHINESE)).isNotEmpty();

        assertThat(externalCl.getResource("static/pixiv-gallery.html")).isNotNull();
        assertThat(externalCl.getResource("static/unified-gallery.html")).isNull();
        assertThat(externalCl.getResource("static/unified-gallery/unified-gallery.css")).isNull();
        assertThat(externalCl.getResource("static/pixiv-gallery/pixiv-gallery.css")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-gallery/gallery-frontend-runtime.js")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-gallery/gallery-generic-view.js")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-gallery/pixiv-gallery-frontend.js")).isNotNull();
        assertThat(externalCl.getResource("i18n/web/gallery.properties")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("static/pixiv-gallery.html")).isNull();
    }

    @Test
    @DisplayName("gallery 导航 / 落点 / 启动页贡献随外置插件注册")
    void galleryNavigationAndEntryContributionsAreRegistered() {
        assertThat(navigationRegistry.navigation())
                .filteredOn(nav -> nav.pluginId().equals("gallery"))
                .extracting(nav -> nav.navigation().id())
                .contains("gallery", "gallery-gui-open", "gallery-invite-manage-back");
        assertThat(navigationRegistry.navigation())
                .filteredOn(nav -> nav.pluginId().equals("gallery") && nav.navigation().id().equals("gallery"))
                .singleElement()
                .satisfies(nav -> assertThat(nav.navigation().href())
                        .isEqualTo("/pixiv-gallery.html?view=all"));
        assertThat(navigationRegistry.navigation())
                .filteredOn(nav -> nav.pluginId().equals("gallery")
                        && nav.navigation().id().equals("gallery-gui-open"))
                .singleElement()
                .satisfies(nav -> assertThat(nav.navigation().href()).isEqualTo("/pixiv-gallery.html"));
        assertThat(navigationRegistry.navigation())
                .filteredOn(nav -> nav.pluginId().equals("gallery")
                        && nav.navigation().id().equals("gallery-invite-manage-back"))
                .singleElement()
                .satisfies(nav -> assertThat(nav.navigation().href())
                        .isEqualTo("/pixiv-gallery.html?view=all"));
        assertThat(landingRegistry.landings())
                .filteredOn(landing -> landing.pluginId().equals("gallery"))
                .singleElement()
                .satisfies(landing -> assertThat(landing.landing().href()).isEqualTo("/pixiv-gallery.html"));
        assertThat(startupRouteRegistry.startupRoutes())
                .filteredOn(route -> route.pluginId().equals("gallery"))
                .singleElement()
                .satisfies(route -> assertThat(route.route().path()).isEqualTo("/pixiv-gallery.html"));
    }

    @Test
    @DisplayName("gallery 子上下文原子贡献 pixiv IMAGE 投影、详情与前端能力")
    void galleryDataProviderIsRegisteredFromExternalChildContext() {
        assertThat(galleryCapabilityRegistry.snapshot().projections())
                .filteredOn(projection -> projection.sourceId().equals("pixiv"))
                .singleElement()
                .satisfies(projection -> {
                    assertThat(projection.kind()).isEqualTo(GalleryKind.IMAGE);
                    assertThat(projection.displayNamespace()).isEqualTo("gallery");
                    assertThat(projection.displayI18nKey()).isEqualTo("source.pixiv");
                });
        assertThat(galleryCapabilityRegistry.snapshot().projectionProviders())
                .extracting(GalleryCapabilityRegistry.RegisteredProjectionProvider::providerId)
                .contains("pixiv-image-capability");
        assertThat(galleryCapabilityRegistry.snapshot().works())
                .anySatisfy(work -> {
                    assertThat(work.sourceId()).isEqualTo("pixiv");
                    assertThat(work.sourceWorkNamespace()).isEqualTo("artwork");
                });
        assertThat(galleryCapabilityRegistry.snapshot().frontendContributions())
                .extracting(frontend -> frontend.contribution().contributionId())
                .containsExactlyInAnyOrder("pixiv.card", "pixiv.media", "pixiv.detail-actions");
        assertThat(galleryCapabilityRegistry.snapshot().frontendContributions())
                .allSatisfy(frontend -> assertThat(frontend.ownerPluginId()).isEqualTo("gallery"));
        assertThat(galleryCapabilityRegistry.snapshot().diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("gallery 子 ApplicationContext 托管 service/controller，controller 动态注册进父分发表")
    void externalGalleryChildContextHostsBeansAndControllerMapping() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("gallery").orElseThrow();
        ClassLoader externalCl = externalGalleryClassLoader();
        assertThat(child.getParent()).isSameAs(applicationContext);
        assertThat(child.getClassLoader()).isSameAs(externalCl);

        Class<?> serviceClass = externalCl.loadClass("top.sywyar.pixivdownload.gallery.GalleryService");
        Class<?> batchServiceClass = externalCl.loadClass("top.sywyar.pixivdownload.gallery.GalleryBatchService");
        Class<?> controllerClass = externalCl.loadClass("top.sywyar.pixivdownload.gallery.GalleryController");
        Class<?> providerClass = externalCl.loadClass("top.sywyar.pixivdownload.gallery.PixivImageGalleryDataProvider");
        Class<?> frontendProviderClass = externalCl.loadClass(
                "top.sywyar.pixivdownload.gallery.frontend.PixivGalleryFrontendProvider");

        assertThat(child.getBeanNamesForType(serviceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(batchServiceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(controllerClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(providerClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(frontendProviderClass)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(controllerClass)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(providerClass)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(frontendProviderClass)).isEmpty();
        assertThat(galleryArtworksHandlerBean()).isSameAs(child.getBean(controllerClass));
    }

    private Object galleryArtworksHandlerBean() {
        return requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().contains("/api/gallery/artworks"))
                .map(e -> e.getValue().getBean())
                .findFirst().orElse(null);
    }

    private ClassLoader externalGalleryClassLoader() {
        return pluginRegistry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("gallery")).findFirst().orElseThrow().classLoader();
    }

    private static boolean stageExternalGalleryJar() {
        try {
            String configured = System.getProperty(GALLERY_CLASSES_PROPERTY);
            if (configured == null || configured.isBlank()) {
                return false;
            }
            Path galleryClasses = Path.of(configured);
            if (!Files.isDirectory(galleryClasses)
                    || !Files.exists(galleryClasses.resolve("plugin.properties"))) {
                return false;
            }
            deleteRecursivelyQuietly(PLUGINS_DIR);
            Files.createDirectories(PLUGINS_DIR);
            Path jar = PLUGINS_DIR.resolve("gallery-plugin.jar");
            zipDirectoryAsJar(galleryClasses, jar);
            PluginTestProvenance.writeLocalUpload(PLUGINS_DIR, jar, "gallery", "1.0.0");
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
