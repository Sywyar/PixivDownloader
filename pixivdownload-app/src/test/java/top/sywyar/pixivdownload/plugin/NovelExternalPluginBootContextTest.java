package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.aop.support.AopUtils;
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
        "pixivdownload.plugins-dir=target/test-runtime/plugins-external-novel",
        "setup.browser.auto-open=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("externalNovelJarStaged")
@DisplayName("外置 novel 插件经真实上下文接入：EXTERNAL 来源 + 小说下载 / 展示贡献 classloader-aware 可见")
class NovelExternalPluginBootContextTest {

    private static final String NOVEL_CLASSES_PROPERTY = "novel.plugin.classes";
    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/plugins-external-novel");
    private static final boolean STAGED = stageExternalNovelJar();

    static {
        if (STAGED) {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
            System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
            System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, PLUGINS_DIR.toString());
        }
    }

    @SuppressWarnings("unused")
    static boolean externalNovelJarStaged() {
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
    @DisplayName("运行时状态 / 发现结果 Bean：POPULATED、started 含 novel、无失败、发现 novel")
    void runtimeStatusAndDiscoveryBeansSeeNovel() {
        assertThat(pluginRuntimeStatus.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(pluginRuntimeStatus.startedPluginIds()).contains("novel");
        assertThat(pluginRuntimeStatus.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.discovered())
                .extracting(DiscoveredFeaturePlugin::featurePluginId).contains("novel");
    }

    @Test
    @DisplayName("双来源 PluginRegistry Bean：内置 + 外置 novel，novel 来源 EXTERNAL")
    void pluginRegistryBeanContainsNovelAsExternal() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .containsExactlyInAnyOrder("core", "plugin-market", "novel");
        assertThat(pluginRegistry.source("novel")).contains(PluginSource.EXTERNAL);
        assertThat(externalNovelClassLoader()).isNotSameAs(getClass().getClassLoader());
    }

    @Test
    @DisplayName("novel 的路由 / 静态资源 / i18n 经外置插件 classloader 注册")
    void novelWebContributionsAreClassloaderAware() {
        ClassLoader externalCl = externalNovelClassLoader();

        assertThat(routeAccessRegistry.routes())
                .filteredOn(r -> r.pluginId().equals("novel"))
                .extracting(r -> r.route().pathPattern())
                .contains(
                        "/api/novel/download",
                        "/api/pixiv/novel-search**",
                        "/api/download/pixiv/novel",
                        "/pixiv-novel-gallery.html",
                        "/pixiv-novel.html",
                        "/pixiv-novel-gallery/**",
                        "/pixiv-novel/**",
                        "/api/gallery/novel/**",
                        "/api/gallery/novels/**",
                        "/api/gallery/novels");
        assertThat(staticResourceRegistry.resources())
                .filteredOn(s -> s.pluginId().equals("novel"))
                .allSatisfy(s -> assertThat(s.classLoader()).isSameAs(externalCl))
                .extracting(s -> s.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-novel-download/",
                        "/pixiv-novel-gallery.html",
                        "/pixiv-novel.html",
                        "/pixiv-novel-gallery/",
                        "/pixiv-novel/");

        WebI18nBundleRegistry.RegisteredBundle novelBundle = webI18nBundleRegistry.resolve("novel");
        assertThat(novelBundle).isNotNull();
        assertThat(novelBundle.pluginId()).isEqualTo("novel");
        WebI18nBundleRegistry.RegisteredBundle bundle = webI18nBundleRegistry.resolve("novel-gallery");
        assertThat(bundle).isNotNull();
        assertThat(bundle.pluginId()).isEqualTo("novel");
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE)).containsEntry("plugin.name", "小说");

        assertThat(externalCl.getResource("static/pixiv-novel-download/novel-queue-type.js")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-novel-gallery/novel-gallery-frontend.js")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-novel-gallery.html")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-novel/pixiv-novel-render.js")).isNotNull();
        assertThat(externalCl.getResource("i18n/web/novel.properties")).isNotNull();
        assertThat(externalCl.getResource("i18n/web/novel-gallery.properties")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("static/pixiv-novel-gallery.html")).isNull();
        assertThat(getClass().getClassLoader()
                .getResource("static/pixiv-novel-gallery/novel-gallery-frontend.js")).isNull();
    }

    @Test
    @DisplayName("novel 导航 / 落点贡献随外置插件注册")
    void novelNavigationAndEntryContributionsAreRegistered() {
        assertThat(navigationRegistry.navigation())
                .filteredOn(nav -> nav.pluginId().equals("novel"))
                .extracting(nav -> nav.navigation().id())
                .containsExactlyInAnyOrder("novel-gallery", "novel-type-switch");
        assertThat(landingRegistry.landings())
                .filteredOn(landing -> landing.pluginId().equals("novel"))
                .singleElement()
                .satisfies(landing -> assertThat(landing.landing().href()).isEqualTo("/pixiv-novel-gallery.html"));
    }

    @Test
    @DisplayName("novel 子上下文原子贡献 pixiv NOVEL 投影与 novel 详情能力")
    void novelCapabilityProviderIsRegisteredFromExternalChildContext() {
        assertThat(galleryCapabilityRegistry.snapshot().projections())
                .filteredOn(projection -> projection.sourceId().equals("pixiv"))
                .singleElement()
                .satisfies(projection -> {
                    assertThat(projection.kind()).isEqualTo(GalleryKind.NOVEL);
                    assertThat(projection.displayNamespace()).isEqualTo("novel-gallery");
                    assertThat(projection.displayI18nKey()).isEqualTo("source.pixiv");
                });
        assertThat(galleryCapabilityRegistry.snapshot().projectionProviders())
                .extracting(GalleryCapabilityRegistry.RegisteredProjectionProvider::providerId)
                .contains("pixiv-novel-capability");
        assertThat(galleryCapabilityRegistry.snapshot().works())
                .anySatisfy(work -> {
                    assertThat(work.sourceId()).isEqualTo("pixiv");
                    assertThat(work.sourceWorkNamespace()).isEqualTo("novel");
                });
        assertThat(galleryCapabilityRegistry.snapshot().frontendContributions())
                .filteredOn(frontend -> frontend.ownerPluginId().equals("novel"))
                .extracting(frontend -> frontend.contribution().contributionId())
                .containsExactlyInAnyOrder("novel.text-renderer", "novel.detail-actions");
        assertThat(galleryCapabilityRegistry.snapshot().diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("novel 子 ApplicationContext 托管下载和展示 service/controller，controller 动态注册进父分发表")
    void externalNovelChildContextHostsBeansAndControllerMapping() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("novel").orElseThrow();
        ClassLoader externalCl = externalNovelClassLoader();
        assertThat(child.getParent()).isSameAs(applicationContext);
        assertThat(child.getClassLoader()).isSameAs(externalCl);

        Class<?> downloadServiceClass = externalCl.loadClass("top.sywyar.pixivdownload.novel.download.NovelDownloadService");
        Class<?> downloadControllerClass =
                externalCl.loadClass("top.sywyar.pixivdownload.novel.controller.NovelDownloadController");
        Class<?> serviceClass = externalCl.loadClass("top.sywyar.pixivdownload.novelgallery.NovelGalleryService");
        Class<?> batchServiceClass = externalCl.loadClass("top.sywyar.pixivdownload.novelgallery.NovelBatchService");
        Class<?> controllerClass =
                externalCl.loadClass("top.sywyar.pixivdownload.novelgallery.controller.NovelGalleryController");
        Class<?> providerClass =
                externalCl.loadClass("top.sywyar.pixivdownload.novelgallery.PixivNovelGalleryCapabilityProvider");
        Class<?> frontendProviderClass = externalCl.loadClass(
                "top.sywyar.pixivdownload.novelgallery.frontend.NovelGalleryFrontendProvider");

        assertThat(child.getBeanNamesForType(downloadServiceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(downloadControllerClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(serviceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(batchServiceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(controllerClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(providerClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(frontendProviderClass)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(controllerClass)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(providerClass)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(frontendProviderClass)).isEmpty();
        assertThat(novelGalleryListHandlerBean()).isSameAs(child.getBean(controllerClass));
    }

    @Test
    @DisplayName("novel 子 ApplicationContext 中的事务 Bean 被 CGLIB 代理")
    void externalNovelTransactionalBeansAreProxied() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("novel").orElseThrow();
        ClassLoader externalCl = externalNovelClassLoader();

        assertTransactionalProxy(child, externalCl, "top.sywyar.pixivdownload.novel.db.NovelDatabase");
        assertTransactionalProxy(child, externalCl,
                "top.sywyar.pixivdownload.novel.translation.NovelGlossaryService");
        assertTransactionalProxy(child, externalCl,
                "top.sywyar.pixivdownload.novel.narration.NovelNarrationCastService");
    }

    private static void assertTransactionalProxy(ConfigurableApplicationContext child,
                                                 ClassLoader classLoader,
                                                 String className) throws Exception {
        Class<?> type = classLoader.loadClass(className);
        Object bean = child.getBean(type);
        assertThat(AopUtils.isAopProxy(bean)).as(className).isTrue();
        assertThat(AopUtils.isCglibProxy(bean)).as(className).isTrue();
        assertThat(type.isInstance(bean)).as(className).isTrue();
    }

    private Object novelGalleryListHandlerBean() {
        return requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().contains("/api/gallery/novels"))
                .map(e -> e.getValue().getBean())
                .findFirst().orElse(null);
    }

    private ClassLoader externalNovelClassLoader() {
        return pluginRegistry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("novel")).findFirst().orElseThrow().classLoader();
    }

    private static boolean stageExternalNovelJar() {
        try {
            String configured = System.getProperty(NOVEL_CLASSES_PROPERTY);
            if (configured == null || configured.isBlank()) {
                return false;
            }
            Path classes = Path.of(configured);
            if (!Files.isDirectory(classes) || !Files.exists(classes.resolve("plugin.properties"))) {
                return false;
            }
            deleteRecursivelyQuietly(PLUGINS_DIR);
            Files.createDirectories(PLUGINS_DIR);
            Path jar = PLUGINS_DIR.resolve("novel-plugin.jar");
            zipDirectoryAsJar(classes, jar);
            PluginTestProvenance.writeLocalUpload(PLUGINS_DIR, jar, "novel", "1.0.0");
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
