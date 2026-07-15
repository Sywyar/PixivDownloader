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
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterField;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
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
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-external-douyin",
        "setup.browser.auto-open=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("externalDouyinJarStaged")
@DisplayName("外置 douyin 插件经真实上下文接入中性能力")
class DouyinExternalPluginBootContextTest {

    private static final String DOUYIN_CLASSES_PROPERTY = "douyin.plugin.classes";
    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/plugins-external-douyin");
    private static final Set<String> DOUYIN_SCHEDULE_SOURCE_TYPES = Set.of(
            "douyin.user",
            "douyin.search",
            "douyin.collection",
            "douyin.music",
            "douyin.account.own-works",
            "douyin.account.liked-works",
            "douyin.account.favorite-works",
            "douyin.account.favorite-folder",
            "douyin.account.favorite-collection");
    private static final boolean STAGED = stageExternalDouyinJar();

    static {
        if (STAGED) {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
            System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
            System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, PLUGINS_DIR.toString());
        }
    }

    @SuppressWarnings("unused")
    static boolean externalDouyinJarStaged() {
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
    private NavigationRegistry navigationRegistry;
    @Autowired
    private ExternalPluginContextManager externalPluginContextManager;
    @Autowired
    private WebApplicationContext applicationContext;
    @Autowired
    private GalleryCapabilityRegistry galleryCapabilityRegistry;
    @Autowired
    private WebI18nBundleRegistry webI18nBundleRegistry;
    @Autowired
    private ScheduleCapabilityRegistry scheduleCapabilityRegistry;

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
    @DisplayName("运行时状态 / 发现结果 Bean：POPULATED、started 含 douyin、无失败、发现 douyin")
    void runtimeStatusAndDiscoveryBeansSeeDouyin() {
        assertThat(pluginRuntimeStatus.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(pluginRuntimeStatus.startedPluginIds()).contains("douyin");
        assertThat(pluginRuntimeStatus.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.discovered())
                .extracting(DiscoveredFeaturePlugin::featurePluginId).contains("douyin");
        assertThat(pluginRuntimeManager.loadedDescriptor("douyin"))
                .get()
                .satisfies(descriptor -> assertThat(descriptor.version()).isEqualTo("1.0.1"));
    }

    @Test
    @DisplayName("PluginRegistry Bean 包含外置 douyin")
    void pluginRegistryBeanContainsDouyinAsExternal() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .contains("douyin");
        assertThat(pluginRegistry.source("douyin")).contains(PluginSource.EXTERNAL);
        assertThat(externalDouyinClassLoader()).isNotSameAs(getClass().getClassLoader());
    }

    @Test
    @DisplayName("douyin 子上下文经中性注册中心原子发布九类计划来源")
    void douyinScheduleSourcesArePublishedFromExternalChildContext() {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("douyin").orElseThrow();
        ClassLoader externalCl = externalDouyinClassLoader();
        var sourceExecutors = child.getBeansOfType(ScheduledSourceExecutor.class).values();

        assertThat(sourceExecutors).hasSize(9)
                .allSatisfy(executor -> assertThat(executor.getClass().getClassLoader())
                        .isSameAs(externalCl));
        assertThat(sourceExecutors)
                .extracting(ScheduledSourceExecutor::sourceType)
                .containsExactlyInAnyOrderElementsOf(DOUYIN_SCHEDULE_SOURCE_TYPES);
        assertThat(applicationContext.getBeansOfType(ScheduledSourceExecutor.class).values())
                .noneMatch(executor -> DOUYIN_SCHEDULE_SOURCE_TYPES.contains(executor.sourceType()));

        assertThat(scheduleCapabilityRegistry.snapshotView().owners())
                .filteredOn(owner -> owner.owner().featurePluginId().equals("douyin"))
                .singleElement()
                .satisfies(owner -> {
                    assertThat(owner.owner().packageId()).isEqualTo("douyin");
                    assertThat(owner.sourceTypes())
                            .containsExactlyInAnyOrderElementsOf(DOUYIN_SCHEDULE_SOURCE_TYPES);
                    assertThat(owner.sourceDescriptors())
                            .extracting(descriptor -> descriptor.sourceType())
                            .containsExactlyInAnyOrderElementsOf(DOUYIN_SCHEDULE_SOURCE_TYPES);
                    assertThat(owner.workTypes()).containsExactly("douyin");
                    assertThat(owner.credentialPolicyIds()).containsExactly("douyin.cookie");
                    assertThat(owner.guardIds()).containsExactly("douyin.risk");
                    assertThat(owner.sourceDescriptors())
                            .filteredOn(descriptor -> descriptor.sourceType()
                                    .equals("douyin.account.favorite-folder"))
                            .singleElement()
                            .satisfies(descriptor -> assertThat(descriptor.frontend().moduleUrl())
                                    .isEqualTo("/pixiv-douyin-download/douyin-schedule-sources.js"));
                });

        try (SchedulePlanningLease planning = scheduleCapabilityRegistry
                .prepareSource("douyin.account.favorite-folder").orElseThrow()) {
            assertThat(scheduleCapabilityRegistry.activate(planning)).isTrue();
            assertThat(planning.owner().featurePluginId()).isEqualTo("douyin");
            assertThat(planning.sourceExecutor()).isPresent();
            assertThat(planning.sourceExecutor().orElseThrow().getClass().getClassLoader())
                    .isSameAs(externalCl);
        }
    }

    @Test
    @DisplayName("douyin 子上下文原子贡献图片视频投影与 aweme 作品能力")
    void douyinDataProviderIsRegisteredFromExternalChildContext() {
        assertThat(galleryCapabilityRegistry.snapshot().projections())
                .filteredOn(source -> source.sourceId().equals("douyin"))
                .hasSize(2)
                .allSatisfy(source -> {
                    assertThat(source.dataAccess()).isEqualTo(GalleryDataAccess.ADMIN_ONLY);
                    assertThat(source.displayNamespace()).isEqualTo("douyin");
                    assertThat(source.displayI18nKey()).isEqualTo("source.douyin");
                    assertThat(source.filterCapabilities()).containsKeys(
                            GalleryFilterField.AUTHOR,
                            GalleryFilterField.TAG,
                            GalleryFilterField.AI_STATUS,
                            GalleryFilterField.CONTENT_RATING,
                            GalleryFilterField.CONTAINED_MEDIA_KIND);
                });
        assertThat(galleryCapabilityRegistry.snapshot().projections())
                .filteredOn(source -> source.sourceId().equals("douyin"))
                .extracting(source -> source.kind())
                .containsExactlyInAnyOrder(GalleryKind.IMAGE, GalleryKind.VIDEO);
        assertThat(galleryCapabilityRegistry.snapshot().works())
                .filteredOn(work -> work.sourceId().equals("douyin"))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.sourceWorkNamespace()).isEqualTo("aweme");
                });
        assertThat(galleryCapabilityRegistry.snapshot().projectionProviders())
                .extracting(GalleryCapabilityRegistry.RegisteredProjectionProvider::providerId)
                .contains("douyin-gallery");
        assertThat(galleryCapabilityRegistry.snapshot().workProviders())
                .extracting(GalleryCapabilityRegistry.RegisteredWorkProvider::providerId)
                .contains("douyin-gallery");
        assertThat(galleryCapabilityRegistry.snapshot().frontendContributions())
                .filteredOn(frontend -> frontend.ownerPluginId().equals("douyin"))
                .extracting(frontend -> frontend.contribution().contributionId())
                .containsExactlyInAnyOrder("douyin.card", "douyin.media");
        assertThat(galleryCapabilityRegistry.snapshot().diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("douyin provider 由外置子 ApplicationContext 托管")
    void externalDouyinChildContextHostsProviderBean() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("douyin").orElseThrow();
        ClassLoader externalCl = externalDouyinClassLoader();
        assertThat(child.getParent()).isSameAs(applicationContext);
        assertThat(child.getClassLoader()).isSameAs(externalCl);

        Class<?> providerClass =
                externalCl.loadClass("top.sywyar.pixivdownload.douyin.gallery.DouyinGalleryDataProvider");
        Class<?> frontendProviderClass = externalCl.loadClass(
                "top.sywyar.pixivdownload.douyin.gallery.frontend.DouyinGalleryFrontendProvider");

        assertThat(child.getBeanNamesForType(providerClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(frontendProviderClass)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(providerClass)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(frontendProviderClass)).isEmpty();
    }

    @Test
    @DisplayName("douyin i18n 提供来源显示名")
    void douyinI18nProvidesSourceDisplayName() {
        WebI18nBundleRegistry.RegisteredBundle bundle = webI18nBundleRegistry.resolve("douyin");

        assertThat(bundle).isNotNull();
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE)).containsEntry("source.douyin", "抖音");
        assertThat(bundle.load(Locale.ENGLISH)).containsEntry("source.douyin", "Douyin");
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE))
                .containsEntry("nav.gallery", "抖音")
                .containsEntry("gallery.page.title", "抖音画廊")
                .containsEntry("detail.page-title", "抖音作品详情");
        assertThat(externalDouyinClassLoader()
                .getResource("static/pixiv-douyin-download/douyin-gallery-frontend.js")).isNotNull();
        assertThat(getClass().getClassLoader()
                .getResource("static/pixiv-douyin-download/douyin-gallery-frontend.js")).isNull();
    }

    @Test
    @DisplayName("douyin 管理员画廊路由、静态资源与类型切换导航经外置 classloader 注册")
    void douyinGalleryWebContributionsAreClassloaderAware() {
        ClassLoader externalCl = externalDouyinClassLoader();
        List<String> adminRoutes = List.of(
                "/pixiv-douyin-gallery.html", "/pixiv-douyin-gallery/**",
                "/pixiv-douyin.html", "/pixiv-douyin/**", "/api/douyin/gallery/**");

        assertThat(routeAccessRegistry.routes())
                .filteredOn(route -> route.pluginId().equals("douyin")
                        && adminRoutes.contains(route.route().pathPattern()))
                .hasSize(adminRoutes.size())
                .allSatisfy(route -> assertThat(route.route().accessPolicy()).isEqualTo(AccessPolicy.ADMIN));
        assertThat(staticResourceRegistry.resources())
                .filteredOn(resource -> resource.pluginId().equals("douyin"))
                .allSatisfy(resource -> assertThat(resource.classLoader()).isSameAs(externalCl))
                .extracting(resource -> resource.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-douyin-gallery.html", "/pixiv-douyin.html",
                        "/pixiv-douyin-gallery/", "/pixiv-douyin/", "/pixiv-douyin-download/");
        assertThat(navigationRegistry.navigation())
                .filteredOn(item -> item.pluginId().equals("douyin"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.navigation().id()).isEqualTo("douyin-gallery-type-switch");
                    assertThat(item.navigation().placements())
                            .containsExactly(NavigationPlacements.GALLERY_TYPE_SWITCH);
                    assertThat(item.navigation().visibleTo()).isEqualTo(AccessPolicy.ADMIN);
                    assertThat(item.navigation().href()).isEqualTo("/pixiv-douyin-gallery.html?view=all");
                });
        assertThat(externalCl.getResource("static/pixiv-douyin-gallery.html")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-douyin-gallery/pixiv-douyin-gallery.css")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-douyin.html")).isNotNull();
        assertThat(externalCl.getResource("static/pixiv-douyin/pixiv-douyin.css")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("static/pixiv-douyin.html")).isNull();
    }

    private ClassLoader externalDouyinClassLoader() {
        return pluginRegistry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("douyin")).findFirst().orElseThrow().classLoader();
    }

    private static boolean stageExternalDouyinJar() {
        try {
            String configured = System.getProperty(DOUYIN_CLASSES_PROPERTY);
            if (configured == null || configured.isBlank()) {
                return false;
            }
            Path classes = Path.of(configured);
            if (!Files.isDirectory(classes) || !Files.exists(classes.resolve("plugin.properties"))) {
                return false;
            }
            deleteRecursivelyQuietly(PLUGINS_DIR);
            Files.createDirectories(PLUGINS_DIR);
            Path jar = PLUGINS_DIR.resolve("douyin-plugin.jar");
            zipDirectoryAsJar(classes, jar);
            PluginTestProvenance.writeLocalUpload(PLUGINS_DIR, jar, "douyin", "1.0.1");
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
