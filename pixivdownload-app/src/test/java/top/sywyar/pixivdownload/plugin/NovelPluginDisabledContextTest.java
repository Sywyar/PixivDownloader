package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;
import top.sywyar.pixivdownload.core.hash.ArtworkHashService;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 禁用语义（真实外置插件 + Spring 上下文）：novel artifact 已安装并由 PF4J 加载，
 * 但 {@code plugins.novel.enabled=false} 使其只留在安装 / 禁用快照，不进入活动快照，
 * 不发布下载类型、UI 槽位、队列操作或计划作品执行器。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-external-novel-disabled",
        "setup.browser.auto-open=false",
        "plugins.novel.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("externalNovelJarStaged")
@DisplayName("已安装但禁用 novel 外置插件的真实上下文语义")
class NovelPluginDisabledContextTest {

    private static final String NOVEL_CLASSES_PROPERTY = "novel.plugin.classes";
    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/plugins-external-novel-disabled");
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

    @Autowired
    private PluginRuntimeManager pluginRuntimeManager;
    @Autowired
    private PluginRuntimeStatus pluginRuntimeStatus;
    @Autowired
    private PluginRegistry pluginRegistry;
    @Autowired
    private NavigationRegistry navigationRegistry;
    @Autowired
    private RouteAccessRegistry routeAccessRegistry;
    @Autowired
    private StaticResourceRegistry staticResourceRegistry;
    @Autowired
    private DownloadExtensionRegistry downloadExtensionRegistry;
    @Autowired
    private QueueOperationRegistry queueOperationRegistry;
    @Autowired
    private ScheduleCapabilityRegistry scheduleCapabilityRegistry;
    @Autowired
    private DatabaseSchemaRegistry databaseSchemaRegistry;
    @Autowired
    private ArtworkHashService artworkHashService;
    @Autowired
    private GalleryCapabilityRegistry galleryCapabilityRegistry;

    @Test
    @DisplayName("novel artifact 已加载且来源为 EXTERNAL，但只进入安装 / 禁用快照")
    void novelInstalledButExcludedFromActiveSnapshot() {
        assertThat(pluginRuntimeStatus.startedPluginIds()).contains("novel");
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id).doesNotContain("novel");
        assertThat(pluginRegistry.allPlugins()).extracting(PixivFeaturePlugin::id).contains("novel");
        assertThat(pluginRegistry.disabledPlugins()).extracting(PixivFeaturePlugin::id).contains("novel");
        assertThat(pluginRegistry.allRegisteredPlugins())
                .filteredOn(registered -> registered.id().equals("novel"))
                .singleElement()
                .satisfies(registered -> assertThat(registered.source()).isEqualTo(PluginSource.EXTERNAL));
    }

    @Test
    @DisplayName("禁用 novel 不发布下载类型 / UI 槽位 / 队列操作 / 计划执行器")
    void novelContributionsAbsent() {
        assertThat(navigationRegistry.navigation())
                .extracting(NavigationRegistry.RegisteredNavigation::pluginId).doesNotContain("novel");
        assertThat(routeAccessRegistry.routes())
                .extracting(RouteAccessRegistry.RegisteredRoute::pluginId).doesNotContain("novel");
        assertThat(staticResourceRegistry.resources())
                .extracting(StaticResourceRegistry.RegisteredStaticResource::pluginId).doesNotContain("novel");
        assertThat(downloadExtensionRegistry.snapshot().downloadTypes())
                .extracting(item -> item.owner().featurePluginId()).doesNotContain("novel");
        assertThat(downloadExtensionRegistry.snapshot().uiSlots())
                .extracting(item -> item.owner().featurePluginId()).doesNotContain("novel");
        assertThat(queueOperationRegistry.operationsForOwner("novel")).isEmpty();
        assertThat(queueOperationRegistry.resolve("novel")).isEmpty();
        assertThat(scheduleCapabilityRegistry.snapshotView().owners())
                .extracting(owner -> owner.owner().featurePluginId()).doesNotContain("novel");
        assertThat(scheduleCapabilityRegistry.resolveWorkExecutor("novel")).isEmpty();
        assertThat(routeAccessRegistry.isDeclared("/api/novel/download")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/pixiv/novel/7/meta")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/pixiv/novel-search")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/download/pixiv/novel")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/pixiv-novel-gallery.html")).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/api/gallery/novels")).isFalse();
    }

    @Test
    @DisplayName("禁用 novel 不影响其它核心 Bean 与核心 schema")
    void coreUnaffected() {
        assertThat(databaseSchemaRegistry.mergedSchema().tables().keySet())
                .isEqualTo(DatabaseSchemaRegistry.forBuiltInPlugins().mergedSchema().tables().keySet())
                .contains("novels");
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .contains("core", "plugin-market")
                .doesNotContain("novel");
        assertThat(artworkHashService).isNotNull();
    }

    @Test
    @DisplayName("禁用 novel 时主画廊内部不注册小说投影与详情能力")
    void galleryCapabilitiesExcludeNovelWhenDisabled() {
        assertThat(galleryCapabilityRegistry.snapshot().projectionProviders())
                .noneSatisfy(provider -> assertThat(provider.providerId()).contains("novel"));
        assertThat(galleryCapabilityRegistry.snapshot().workProviders())
                .noneSatisfy(provider -> assertThat(provider.providerId()).contains("novel"));
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
