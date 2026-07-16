package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.lifecycle.request.PluginRequestLeaseRegistry;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

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

/**
 * stats 外置插件启动期加载的端到端集成测试：用<b>真实</b> stats 插件 jar（由本模块编译产物 + 根部
 * {@code plugin.properties} 组装）放进临时 {@code plugins/} 目录，经 {@link PluginRuntimeManager} 真实加载 / 启动，
 * 验证：
 * <ol>
 *   <li>运行时加载 / 启动到位（{@code POPULATED}、loaded / started 含 {@code stats}、无失败诊断）；</li>
 *   <li>清点出统一描述符（id / sourceId / 合法 semver 版本 / requires 兼容 / plugin-class）、基线状态 {@code STARTED}，
 *       且 classloader 是外置插件自己的、<b>不是</b>核心壳应用 classloader；</li>
 *   <li>发现桥接把 stats 以 {@code EXTERNAL} 来源接入 {@link PluginRegistry}；</li>
 *   <li>stats 的 route / static / i18n / navigation contribution 经各下游注册中心注册到位；静态资源保留来源
 *       classloader，i18n 在注册时经该 loader 物化后只发布宿主 map。</li>
 * </ol>
 *
 * <p>控范围：本测试只验证<b>启动期加载</b>与 contribution 注册。stats {@code @RestController} / {@code @Service}
 * 的 child-context Bean 装配，以及 {@code /api/stats/**} 处理器随生命周期动态注册 / 注销，
 * 由 {@link StatsExternalPluginBootContextTest} 单独验证，不在本类重复启动父子 Spring context。
 *
 * <p>stats 构建产物目录经 surefire 系统属性 {@code stats.plugin.classes} 传入（指向
 * {@code pixivdownload-plugin-stats/target/classes}，reactor 中先于 app 构建）；未就绪时（如 IDE 未触发 reactor
 * 构建）整类 {@link Assumptions assume} 跳过。Windows 下 PF4J 加载 jar 会持有文件锁，故 {@link #unloadAndCleanup()}
 * 先停止 / 卸载插件释放 classloader 再删除临时目录。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("stats 外置 PF4J 插件启动期加载 + EXTERNAL 接入 + classloader-aware contribution")
class StatsExternalPluginIntegrationTest {

    private static final String STATS_CLASSES_PROPERTY = "stats.plugin.classes";

    private Path tempPluginsDir;
    private PluginRuntimeManager manager;
    private PluginRuntimeStatus status;

    @BeforeAll
    void loadExternalStatsJar() throws IOException {
        Path statsClasses = locateStatsClasses();
        Assumptions.assumeTrue(statsClasses != null && Files.isDirectory(statsClasses),
                "stats 插件构建产物未就绪（需 reactor 先构建 pixivdownload-plugin-stats），跳过真实 jar 加载验证");
        // 关键前提：外置 jar 里绝不能含共享契约（plugin-api 等），否则桥接 instanceof 会因同名异 loader 而失败。
        assertThat(statsClasses.resolve("top/sywyar/pixivdownload/plugin/api")).doesNotExist();

        tempPluginsDir = Files.createTempDirectory("pixiv-plugins-it");
        Path jar = tempPluginsDir.resolve("stats-plugin-0.0.1.jar");
        zipDirectoryAsJar(statsClasses, jar);
        PluginTestProvenance.writeLocalUpload(tempPluginsDir, jar, "stats", "1.0.0");

        manager = new PluginRuntimeManager(tempPluginsDir);
        status = manager.start();
    }

    @AfterAll
    void unloadAndCleanup() {
        if (manager != null) {
            // 先停止 / 卸载，释放 PF4J 插件 classloader 对 jar 的文件锁（Windows 下否则删不掉临时目录）。
            manager.pluginManager().ifPresent(pm -> {
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
        deleteRecursivelyQuietly(tempPluginsDir);
    }

    @Test
    @DisplayName("PluginRuntimeManager 真实加载并启动外置 stats 插件（POPULATED、loaded / started 含 stats、无失败）")
    void runtimeManagerLoadsAndStartsExternalStats() {
        assertThat(status.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(status.loadedPluginIds()).contains("stats");
        assertThat(status.startedPluginIds()).contains("stats");
        assertThat(status.failures()).isEmpty();
    }

    @Test
    @DisplayName("清点产出统一描述符（id / 版本 / requires 兼容 / plugin-class）+ 基线状态 STARTED + 外置 classloader")
    void inventoryExposesStartedCompatibleDescriptor() {
        PluginInventory inventory = manager.inspectPlugins();
        assertThat(inventory.failures()).isEmpty();

        PluginInstallation stats = inventory.installations().stream()
                .filter(installation -> installation.id().equals("stats"))
                .findFirst().orElseThrow();

        assertThat(stats.status()).isEqualTo(PluginStatus.STARTED);
        assertThat(stats.registrable()).isTrue();
        assertThat(stats.plugin()).isInstanceOf(PixivFeaturePlugin.class);

        PluginDescriptor descriptor = stats.descriptor();
        assertThat(descriptor.id()).isEqualTo("stats");
        assertThat(descriptor.sourcePluginId()).isEqualTo("stats");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
        assertThat(descriptor.pluginClass()).isEqualTo("top.sywyar.pixivdownload.stats.StatsPf4jPlugin");
        assertThat(descriptor.requires().present()).isTrue();
        assertThat(descriptor.isApiCompatible()).isTrue();
        assertThat(descriptor.externalValidationErrors()).isEmpty();

        // classloader 边界：解析用的是外置插件自己的 classloader，绝不是核心壳应用 classloader。
        assertThat(stats.classLoader()).isNotSameAs(getClass().getClassLoader());
    }

    @Test
    @DisplayName("发现桥接把 stats 以 EXTERNAL 来源接入 PluginRegistry（来源标记 + 外置 classloader）")
    void discoveryBridgesStatsAsExternalSource() {
        PluginDiscoveryResult discovery = manager.discoverFeaturePlugins();
        assertThat(discovery.hasFailures()).isFalse();
        assertThat(discovery.discovered()).extracting(DiscoveredFeaturePlugin::featurePluginId).contains("stats");

        PluginRegistry registry = new PluginRegistry(List.of(), new PluginToggleProperties(), discovery);
        assertThat(registry.plugins()).extracting(PixivFeaturePlugin::id).contains("stats");
        assertThat(registry.source("stats")).contains(PluginSource.EXTERNAL);
        assertThat(registry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("stats")).findFirst().orElseThrow().classLoader())
                .isNotSameAs(getClass().getClassLoader());
    }

    @Test
    @DisplayName("stats 的 route / navigation / static / i18n contribution 经下游注册中心注册，静态资源 classloader-aware 走外置 loader")
    void externalStatsContributionsRegisterClassloaderAware() {
        PluginRegistry registry = new PluginRegistry(
                List.of(), new PluginToggleProperties(), manager.discoverFeaturePlugins());
        ClassLoader externalCl = registry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("stats")).findFirst().orElseThrow().classLoader();
        RouteAccessRegistry routes = new RouteAccessRegistry(registry);
        StaticResourceRegistry staticResources = new StaticResourceRegistry(registry);
        WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(registry);
        NavigationRegistry navigation = new NavigationRegistry(registry);
        WebUiSlotRegistry uiSlots = new WebUiSlotRegistry(registry);
        UserscriptRegistry userscripts = new UserscriptRegistry(registry);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        DownloadExtensionRegistry downloads = new DownloadExtensionRegistry(
                registry, staticResources, new PluginOwnedWebAssetValidator(staticResources));
        PluginRequestLeaseRegistry requestLeases = new PluginRequestLeaseRegistry();
        new PluginWebContributionRegistrar(
                routes, staticResources, i18n, navigation, uiSlots, userscripts, scripts,
                registry, downloads, requestLeases);

        // route：外置路由由 Web registrar 以 exact request owner 发布，不经过 owner-null boot 快照。
        assertThat(routes.routes())
                .filteredOn(r -> r.pluginId().equals("stats"))
                .allSatisfy(route -> assertThat(route.requestOwner()).isNotNull())
                .extracting(r -> r.route().pathPattern())
                .containsExactlyInAnyOrder("/pixiv-stats.html", "/pixiv-stats/**", "/api/stats/**");
        assertThat(requestLeases.currentOwner("stats")).isPresent();

        // navigation：stats 导航项进入 NavigationRegistry
        assertThat(navigation.navigation())
                .anyMatch(n -> n.pluginId().equals("stats"));

        // static：stats 静态资源进入 StaticResourceRegistry，且其解析用 classloader 是外置插件 loader
        List<StaticResourceRegistry.RegisteredStaticResource> statsResources =
                staticResources.resources().stream()
                        .filter(s -> s.pluginId().equals("stats")).toList();
        assertThat(statsResources).hasSize(2);
        assertThat(statsResources)
                .extracting(s -> s.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder("/pixiv-stats/", "/pixiv-stats.html");
        statsResources.forEach(s ->
                assertThat(s.classLoader()).isSameAs(externalCl));

        // i18n：stats namespace 经外置插件 loader 物化后进入纯宿主快照。
        WebI18nBundleRegistry.RegisteredBundle bundle = i18n.resolve("stats");
        assertThat(bundle).isNotNull();
        assertThat(bundle.load(Locale.SIMPLIFIED_CHINESE)).containsEntry("plugin.name", "统计");

        // 来源隔离实证：stats 原始静态资源 / i18n 文件只存在外置 loader，核心壳应用 loader 解析不到。
        assertThat(externalCl.getResource("static/pixiv-stats/pixiv-stats.css")).isNotNull();
        assertThat(externalCl.getResource("i18n/web/stats.properties")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("static/pixiv-stats/pixiv-stats.css")).isNull();
        assertThat(getClass().getClassLoader().getResource("i18n/web/stats.properties")).isNull();
    }

    // --- helpers ---

    private static Path locateStatsClasses() {
        String configured = System.getProperty(STATS_CLASSES_PROPERTY);
        return (configured == null || configured.isBlank()) ? null : Path.of(configured);
    }

    /** 把一个目录（stats 的 {@code target/classes}，根部已含 {@code plugin.properties}）打成 PF4J 可加载的 jar。 */
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
                    // best-effort：临时目录，残留由 OS 清理
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
