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
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;

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
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;

@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-external-duplicate",
        "setup.browser.auto-open=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("externalDuplicateJarStaged")
@DisplayName("外置 duplicate 插件经真实上下文接入：EXTERNAL 来源 + 下游注册中心 classloader-aware 可见")
class DuplicateExternalPluginBootContextTest {

    private static final String DUPLICATE_CLASSES_PROPERTY = "duplicate.plugin.classes";
    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/plugins-external-duplicate");
    private static final boolean STAGED = stageExternalDuplicateJar();

    static {
        if (STAGED) {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
            System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
            System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, PLUGINS_DIR.toString());
        }
    }

    @SuppressWarnings("unused")
    static boolean externalDuplicateJarStaged() {
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
    @DisplayName("运行时状态 / 发现结果 Bean：POPULATED、started 含 duplicate、无失败、发现 duplicate")
    void runtimeStatusAndDiscoveryBeansSeeDuplicate() {
        assertThat(pluginRuntimeStatus.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(pluginRuntimeStatus.startedPluginIds()).contains("duplicate");
        assertThat(pluginRuntimeStatus.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.discovered())
                .extracting(DiscoveredFeaturePlugin::featurePluginId).contains("duplicate");
    }

    @Test
    @DisplayName("双来源 PluginRegistry Bean：三内置 + 外置 duplicate，duplicate 来源 EXTERNAL")
    void pluginRegistryBeanContainsDuplicateAsExternal() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .containsExactlyInAnyOrder(
                        "core", "novel", "plugin-market", "duplicate");
        assertThat(pluginRegistry.source("duplicate")).contains(PluginSource.EXTERNAL);
        assertThat(externalDuplicateClassLoader()).isNotSameAs(getClass().getClassLoader());
    }

    @Test
    @DisplayName("duplicate 的路由 / 静态资源 / i18n 经外置插件 classloader 注册")
    void duplicateWebContributionsAreClassloaderAware() {
        ClassLoader externalCl = externalDuplicateClassLoader();

        assertThat(routeAccessRegistry.routes())
                .filteredOn(r -> r.pluginId().equals("duplicate"))
                .extracting(r -> r.route().pathPattern())
                .containsExactlyInAnyOrder("/pixiv-duplicates.html", "/pixiv-duplicates/**", "/api/duplicates/**");
        assertThat(staticResourceRegistry.resources())
                .filteredOn(s -> s.pluginId().equals("duplicate"))
                .allSatisfy(s -> assertThat(s.classLoader()).isSameAs(externalCl))
                .extracting(s -> s.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder("/pixiv-duplicates.html", "/pixiv-duplicates/");

        WebI18nBundleRegistry.RegisteredBundle bundle = webI18nBundleRegistry.resolve("duplicates");
        assertThat(bundle).isNotNull();
        assertThat(bundle.pluginId()).isEqualTo("duplicate");
        assertThat(bundle.classLoader()).isSameAs(externalCl);
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE)).containsEntry("plugin.name", "重复检测");

        assertThat(externalCl.getResource("static/pixiv-duplicates.html")).isNotNull();
        assertThat(externalCl.getResource("i18n/web/duplicates.properties")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("static/pixiv-duplicates.html")).isNull();
    }

    @Test
    @DisplayName("duplicate 子 ApplicationContext 托管 service/controller/回填任务，controller 动态注册进父分发表")
    void externalDuplicateChildContextHostsBeansAndControllerMapping() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("duplicate").orElseThrow();
        ClassLoader externalCl = externalDuplicateClassLoader();
        assertThat(child.getParent()).isSameAs(applicationContext);
        assertThat(child.getClassLoader()).isSameAs(externalCl);

        Class<?> serviceClass = externalCl.loadClass("top.sywyar.pixivdownload.duplicate.DuplicateService");
        Class<?> controllerClass = externalCl.loadClass("top.sywyar.pixivdownload.duplicate.DuplicateController");
        Class<?> backfillClass = externalCl.loadClass("top.sywyar.pixivdownload.duplicate.DuplicateHashBackfillTask");

        assertThat(child.getBeanNamesForType(serviceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(controllerClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(backfillClass)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(controllerClass)).isEmpty();
        assertThat(duplicateGroupsHandlerBean()).isSameAs(child.getBean(controllerClass));
    }

    private Object duplicateGroupsHandlerBean() {
        return requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().contains("/api/duplicates/groups"))
                .map(e -> e.getValue().getBean())
                .findFirst().orElse(null);
    }

    private ClassLoader externalDuplicateClassLoader() {
        return pluginRegistry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("duplicate")).findFirst().orElseThrow().classLoader();
    }

    private static boolean stageExternalDuplicateJar() {
        try {
            String configured = System.getProperty(DUPLICATE_CLASSES_PROPERTY);
            if (configured == null || configured.isBlank()) {
                return false;
            }
            Path duplicateClasses = Path.of(configured);
            if (!Files.isDirectory(duplicateClasses)
                    || !Files.exists(duplicateClasses.resolve("plugin.properties"))) {
                return false;
            }
            deleteRecursivelyQuietly(PLUGINS_DIR);
            Files.createDirectories(PLUGINS_DIR);
            Path jar = PLUGINS_DIR.resolve("duplicate-plugin.jar");
            zipDirectoryAsJar(duplicateClasses, jar);
            PluginTestProvenance.writeLocalUpload(PLUGINS_DIR, jar, "duplicate", "1.0.0");
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
