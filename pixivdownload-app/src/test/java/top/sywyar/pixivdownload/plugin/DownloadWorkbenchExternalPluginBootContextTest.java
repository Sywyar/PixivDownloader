package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginContextManager;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
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
import top.sywyar.pixivdownload.plugin.web.PluginControllerRegistrar;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

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
        "pixivdownload.config-dir=target/test-runtime/config-external-download-workbench",
        "pixivdownload.state-dir=target/test-runtime/state-external-download-workbench",
        "pixivdownload.data-dir=target/test-runtime/data-external-download-workbench",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-external-download-workbench",
        "setup.browser.auto-open=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("externalDownloadWorkbenchJarStaged")
@DisplayName("外置 download-workbench 经真实上下文接入宿主稳定能力")
class DownloadWorkbenchExternalPluginBootContextTest {

    private static final String PLUGIN_ID = "download-workbench";
    private static final String WORKBENCH_CLASSES_PROPERTY =
            "download-workbench.plugin.classes";
    private static final Path PLUGINS_DIR =
            Path.of("target/test-runtime/plugins-external-download-workbench");
    private static final Set<String> SOURCE_TYPES = Set.of(
            "user-new",
            "user-request",
            "search",
            "series",
            "my-bookmarks",
            "follow-latest",
            "collection");
    private static final Set<String> USERSCRIPT_IDS = Set.of(
            "all-in-one",
            "artwork-java",
            "artwork-local",
            "user-batch",
            "page-batch",
            "import-batch",
            "experience-toolbox");
    private static final Set<String> MATERIALIZED_USERSCRIPT_IDS = Set.of(
            "artwork-java",
            "artwork-local",
            "user-batch",
            "page-batch",
            "import-batch",
            "experience-toolbox");
    private static final boolean STAGED = stageExternalDownloadWorkbenchJar();

    static {
        if (STAGED) {
            System.setProperty(
                    RuntimeFiles.CONFIG_DIR_PROPERTY,
                    "target/test-runtime/config-external-download-workbench");
            System.setProperty(
                    RuntimeFiles.STATE_DIR_PROPERTY,
                    "target/test-runtime/state-external-download-workbench");
            System.setProperty(
                    RuntimeFiles.DATA_DIR_PROPERTY,
                    "target/test-runtime/data-external-download-workbench");
            System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, PLUGINS_DIR.toString());
        }
    }

    @SuppressWarnings("unused")
    static boolean externalDownloadWorkbenchJarStaged() {
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
    private StartupRouteRegistry startupRouteRegistry;
    @Autowired
    private UserscriptRegistry userscriptRegistry;
    @Autowired
    private ScriptRegistry scriptRegistry;
    @Autowired
    private DownloadExtensionRegistry downloadExtensionRegistry;
    @Autowired
    private ScheduleCapabilityRegistry scheduleCapabilityRegistry;
    @Autowired
    private QueueOperationRegistry queueOperationRegistry;
    @Autowired
    private PluginLifecycleService pluginLifecycleService;
    @Autowired
    private ExternalPluginLifecycleCoordinator lifecycleCoordinator;
    @Autowired
    private ExternalPluginContextManager externalPluginContextManager;
    @Autowired
    private PluginControllerRegistrar pluginControllerRegistrar;
    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;
    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @AfterAll
    void releasePluginsAndCleanup() {
        try {
            if (pluginRuntimeManager != null
                    && lifecycleCoordinator != null
                    && pluginRuntimeManager.loadedDescriptor(PLUGIN_ID).isPresent()) {
                lifecycleCoordinator.unload(PLUGIN_ID);
            }
        } finally {
            deleteRecursivelyQuietly(PLUGINS_DIR);
            System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
            System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
            System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
            System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
        }
    }

    @Test
    @DisplayName("PF4J 发现并启动 required 工作台，注册来源为 EXTERNAL 且使用独立 classloader")
    void runtimeDiscoversDownloadWorkbenchAsExternalPlugin() {
        assertThat(pluginRuntimeStatus.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(pluginRuntimeStatus.startedPluginIds()).contains(PLUGIN_ID);
        assertThat(pluginRuntimeStatus.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.discovered())
                .extracting(DiscoveredFeaturePlugin::featurePluginId)
                .contains(PLUGIN_ID);
        assertThat(pluginRuntimeManager.loadedDescriptor(PLUGIN_ID))
                .get()
                .satisfies(descriptor -> assertThat(descriptor.version()).isEqualTo("1.0.0"));

        assertThat(pluginRegistry.plugins())
                .extracting(PixivFeaturePlugin::id)
                .contains(PLUGIN_ID);
        assertThat(pluginRegistry.source(PLUGIN_ID)).contains(PluginSource.EXTERNAL);
        assertThat(externalDownloadWorkbenchClassLoader())
                .isNotSameAs(getClass().getClassLoader());
    }

    @Test
    @DisplayName("工作台 controller 与计划执行器只由外置 child context 托管")
    void childContextOwnsWorkbenchControllersAndScheduleExecutors() throws Exception {
        ClassLoader externalClassLoader = externalDownloadWorkbenchClassLoader();
        ConfigurableApplicationContext child =
                externalPluginContextManager.contextFor(PLUGIN_ID).orElseThrow();
        assertThat(child.getParent()).isSameAs(applicationContext);
        assertThat(child.getClassLoader()).isSameAs(externalClassLoader);

        Class<?> queueController = externalClassLoader.loadClass(
                "top.sywyar.pixivdownload.download.controller.DownloadQueueController");
        Class<?> scheduleController = externalClassLoader.loadClass(
                "top.sywyar.pixivdownload.schedule.controller.ScheduleController");
        assertThat(child.getBeanNamesForType(queueController)).isNotEmpty();
        assertThat(child.getBeanNamesForType(scheduleController)).isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(queueController)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(scheduleController)).isEmpty();

        assertThat(child.getBeansOfType(ScheduledSourceExecutor.class).values())
                .hasSize(7)
                .allSatisfy(executor ->
                        assertThat(executor.getClass().getClassLoader())
                                .isSameAs(externalClassLoader))
                .extracting(ScheduledSourceExecutor::sourceType)
                .containsExactlyInAnyOrderElementsOf(SOURCE_TYPES);
        assertThat(child.getBeansOfType(ScheduledWorkExecutor.class).values())
                .filteredOn(executor -> executor.workType().equals("illust"))
                .singleElement()
                .satisfies(executor ->
                        assertThat(executor.getClass().getClassLoader())
                                .isSameAs(externalClassLoader));
        assertThat(applicationContext.getBeansOfType(ScheduledSourceExecutor.class).values())
                .noneMatch(executor -> SOURCE_TYPES.contains(executor.sourceType()));

        ThreadPoolTaskScheduler scheduler = child.getBean(
                "downloadWorkbenchTaskScheduler", ThreadPoolTaskScheduler.class);
        assertThat(child.containsLocalBean("downloadWorkbenchTaskScheduler")).isTrue();
        assertThat(scheduler).isNotSameAs(applicationContext.getBean("taskScheduler"));
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(5);
        assertThat(scheduler.getThreadNamePrefix())
                .isEqualTo("download-workbench-scheduler-");

        assertThat(pluginControllerRegistrar.registeredPluginIds()).contains(PLUGIN_ID);
        assertThat(handlerBean("/api/download/queue/clear").getClass().getClassLoader())
                .isSameAs(externalClassLoader);
        assertThat(handlerBean("/api/schedule/sources").getClass().getClassLoader())
                .isSameAs(externalClassLoader);
    }

    @Test
    @DisplayName("工作台路由、静态资源、i18n、userscript、导航与启动页均由外置包贡献")
    void webContributionsAreClassloaderAware() {
        ClassLoader externalClassLoader = externalDownloadWorkbenchClassLoader();

        assertRoute("/pixiv-batch.html", AccessPolicy.VISITOR);
        assertRoute("/pixiv-batch/**", AccessPolicy.VISITOR);
        assertRoute("/pixiv-batch-alt.html", AccessPolicy.VISITOR);
        assertRoute("/pixiv-batch-alt/**", AccessPolicy.VISITOR);
        assertRoute("/api/download/pixiv", AccessPolicy.VISITOR);
        assertRoute("/api/schedule/**", AccessPolicy.ADMIN);
        assertThat(routeAccessRegistry.isDeclared(
                "/api/schedule/sources", HttpMethod.GET)).isTrue();

        assertThat(staticResourceRegistry.resources())
                .filteredOn(resource -> resource.pluginId().equals(PLUGIN_ID))
                .hasSize(6)
                .allSatisfy(resource ->
                        assertThat(resource.classLoader()).isSameAs(externalClassLoader))
                .extracting(resource ->
                        resource.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-batch.html", "/pixiv-batch/",
                        "/pixiv-batch-alt.html", "/pixiv-batch-alt/",
                        "/pixiv-layout-feedback/", "/vendor/posthog-js/");
        assertThat(externalClassLoader.getResource("static/pixiv-batch.html")).isNotNull();
        assertThat(externalClassLoader.getResource(
                "static/pixiv-batch/pixiv-queue-type.js")).isNotNull();
        assertThat(externalClassLoader.getResource("static/pixiv-batch-alt.html")).isNotNull();
        assertThat(externalClassLoader.getResource(
                "static/pixiv-batch-alt/alt-core.js")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("static/pixiv-batch.html"))
                .isNull();

        WebI18nBundleRegistry.RegisteredBundle batch =
                webI18nBundleRegistry.resolve("batch");
        assertThat(batch).isNotNull();
        assertThat(batch.pluginId()).isEqualTo(PLUGIN_ID);
        assertThat(batch.load(Locale.SIMPLIFIED_CHINESE))
                .containsEntry("plugin.name", "下载工作台")
                .containsEntry("nav.label", "下载");
        assertThat(webI18nBundleRegistry.resolve("userscript"))
                .isNotNull()
                .extracting(WebI18nBundleRegistry.RegisteredBundle::pluginId)
                .isEqualTo(PLUGIN_ID);
        assertThat(webI18nBundleRegistry.resolve("batch-alt"))
                .isNotNull()
                .extracting(WebI18nBundleRegistry.RegisteredBundle::pluginId)
                .isEqualTo(PLUGIN_ID);

        assertThat(userscriptRegistry.userscripts())
                .filteredOn(script -> script.pluginId().equals(PLUGIN_ID))
                .hasSize(7)
                .allSatisfy(script ->
                        assertThat(script.classLoader()).isSameAs(externalClassLoader))
                .extracting(script -> script.contribution().id())
                .containsExactlyInAnyOrderElementsOf(USERSCRIPT_IDS);
        assertThat(scriptRegistry.scripts())
                .extracting(script -> script.id())
                .containsAll(MATERIALIZED_USERSCRIPT_IDS);

        assertThat(navigationRegistry.navigation())
                .filteredOn(item -> item.pluginId().equals(PLUGIN_ID))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.navigation().id()).isEqualTo(PLUGIN_ID);
                    assertThat(item.navigation().href()).isEqualTo("/pixiv-batch.html");
                    assertThat(item.navigation().placements()).contains(
                            NavigationPlacements.APP_TOP,
                            NavigationPlacements.APP_SIDEBAR);
                });
        assertThat(startupRouteRegistry.startupRoutes())
                .filteredOn(route -> route.pluginId().equals(PLUGIN_ID))
                .singleElement()
                .satisfies(route -> {
                    assertThat(route.route().path()).isEqualTo("/pixiv-batch.html");
                    assertThat(route.route().preferredContexts())
                            .containsExactly(StartupRouteContext.MULTI);
                });
    }

    @Test
    @DisplayName("工作台原子发布七类计划来源、illust 执行器与 owner-scoped 队列能力")
    void scheduleAndQueueCapabilitiesBelongToWorkbenchPublication() {
        ClassLoader externalClassLoader = externalDownloadWorkbenchClassLoader();

        assertThat(scheduleCapabilityRegistry.snapshotView().owners())
                .filteredOn(owner ->
                        owner.owner().featurePluginId().equals(PLUGIN_ID))
                .singleElement()
                .satisfies(owner -> {
                    assertThat(owner.owner().packageId()).isEqualTo(PLUGIN_ID);
                    assertThat(owner.sourceTypes())
                            .containsExactlyInAnyOrderElementsOf(SOURCE_TYPES);
                    assertThat(owner.sourceAliases()).containsExactlyInAnyOrder(
                            "USER_NEW",
                            "USER_REQUEST",
                            "SEARCH",
                            "SERIES",
                            "MY_BOOKMARKS",
                            "FOLLOW_LATEST",
                            "COLLECTION");
                    assertThat(owner.workTypes()).containsExactly("illust");
                    assertThat(owner.credentialPolicyIds())
                            .containsExactly("pixiv-cookie");
                    assertThat(owner.guardIds()).containsExactly("pixiv-overuse");
                });

        try (SchedulePlanningLease planning =
                     scheduleCapabilityRegistry.prepareSource("collection").orElseThrow()) {
            assertThat(scheduleCapabilityRegistry.activate(planning)).isTrue();
            assertThat(planning.owner().featurePluginId()).isEqualTo(PLUGIN_ID);
            assertThat(planning.sourceExecutor()).isPresent();
            assertThat(planning.sourceExecutor().orElseThrow()
                    .getClass().getClassLoader()).isSameAs(externalClassLoader);
        }

        var workHandle =
                scheduleCapabilityRegistry.resolveWorkExecutor("illust").orElseThrow();
        assertThat(workHandle.owner().featurePluginId()).isEqualTo(PLUGIN_ID);
        try (var workLease =
                     scheduleCapabilityRegistry.prepareAcquire(workHandle).orElseThrow()) {
            assertThat(scheduleCapabilityRegistry.activate(workLease)).isTrue();
            assertThat(workLease.capability().getClass().getClassLoader())
                    .isSameAs(externalClassLoader);
        }

        assertThat(queueOperationRegistry.operationsForOwner(PLUGIN_ID))
                .singleElement()
                .satisfies(owned -> {
                    assertThat(owned.queueType()).isEqualTo("illust");
                    assertThat(owned.operations().getClass().getClassLoader())
                            .isSameAs(externalClassLoader);
                });
        assertThat(downloadExtensionRegistry.resolveDownloadType("illust"))
                .get()
                .satisfies(type -> {
                    assertThat(type.owner().featurePluginId()).isEqualTo(PLUGIN_ID);
                    assertThat(type.descriptor().cancelSupported()).isTrue();
                });
    }

    @Test
    @DisplayName("工作台 stop/start 撤回并恢复服务足迹，reload 更换 generation 与 classloader")
    void lifecycleStopStartAndReloadAreReversible() {
        ClassLoader initialClassLoader = externalDownloadWorkbenchClassLoader();
        ConfigurableApplicationContext initialContext =
                externalPluginContextManager.contextFor(PLUGIN_ID).orElseThrow();
        ThreadPoolTaskScheduler initialScheduler = initialContext.getBean(
                "downloadWorkbenchTaskScheduler", ThreadPoolTaskScheduler.class);
        long initialGeneration =
                pluginLifecycleService.generation(PLUGIN_ID).orElseThrow();
        assertThat(initialScheduler.getScheduledThreadPoolExecutor().isShutdown()).isFalse();
        assertServiceFootprintPresent(initialClassLoader);

        lifecycleCoordinator.stop(PLUGIN_ID);

        assertThat(pluginLifecycleService.phase(PLUGIN_ID))
                .contains(PluginRuntimePhase.STOPPED);
        assertThat(externalPluginContextManager.contextFor(PLUGIN_ID)).isEmpty();
        assertThat(initialContext.isActive()).isFalse();
        assertThat(initialScheduler.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
        assertServiceFootprintAbsent(initialClassLoader);

        lifecycleCoordinator.start(PLUGIN_ID);

        assertThat(pluginLifecycleService.phase(PLUGIN_ID))
                .contains(PluginRuntimePhase.STARTED);
        assertThat(pluginLifecycleService.generation(PLUGIN_ID))
                .contains(initialGeneration);
        assertThat(externalDownloadWorkbenchClassLoader())
                .isSameAs(initialClassLoader);
        ConfigurableApplicationContext restartedContext =
                externalPluginContextManager.contextFor(PLUGIN_ID).orElseThrow();
        ThreadPoolTaskScheduler restartedScheduler = restartedContext.getBean(
                "downloadWorkbenchTaskScheduler", ThreadPoolTaskScheduler.class);
        assertThat(restartedContext).isNotSameAs(initialContext);
        assertThat(restartedScheduler).isNotSameAs(initialScheduler);
        assertThat(restartedScheduler.getScheduledThreadPoolExecutor().isShutdown()).isFalse();
        assertServiceFootprintPresent(initialClassLoader);

        lifecycleCoordinator.reload(PLUGIN_ID);

        ClassLoader reloadedClassLoader = externalDownloadWorkbenchClassLoader();
        ConfigurableApplicationContext reloadedContext =
                externalPluginContextManager.contextFor(PLUGIN_ID).orElseThrow();
        ThreadPoolTaskScheduler reloadedScheduler = reloadedContext.getBean(
                "downloadWorkbenchTaskScheduler", ThreadPoolTaskScheduler.class);
        assertThat(pluginLifecycleService.phase(PLUGIN_ID))
                .contains(PluginRuntimePhase.STARTED);
        assertThat(pluginLifecycleService.generation(PLUGIN_ID).orElseThrow())
                .isGreaterThan(initialGeneration);
        assertThat(reloadedClassLoader).isNotSameAs(initialClassLoader);
        assertThat(reloadedContext).isNotSameAs(restartedContext);
        assertThat(restartedContext.isActive()).isFalse();
        assertThat(restartedScheduler.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
        assertThat(reloadedScheduler).isNotSameAs(restartedScheduler);
        assertThat(reloadedScheduler.getScheduledThreadPoolExecutor().isShutdown()).isFalse();
        assertThat(anyHandlerLoadedBy(initialClassLoader)).isFalse();
        assertServiceFootprintPresent(reloadedClassLoader);
    }

    private void assertServiceFootprintPresent(ClassLoader expectedClassLoader) {
        assertThat(routeAccessRegistry.routes())
                .anyMatch(route -> route.pluginId().equals(PLUGIN_ID));
        assertThat(staticResourceRegistry.resources())
                .filteredOn(resource -> resource.pluginId().equals(PLUGIN_ID))
                .isNotEmpty()
                .allSatisfy(resource ->
                        assertThat(resource.classLoader())
                                .isSameAs(expectedClassLoader));
        assertThat(navigationRegistry.navigation())
                .anyMatch(item -> item.pluginId().equals(PLUGIN_ID));
        assertThat(startupRouteRegistry.startupRoutes())
                .anyMatch(route -> route.pluginId().equals(PLUGIN_ID));
        assertThat(userscriptRegistry.userscripts())
                .filteredOn(script -> script.pluginId().equals(PLUGIN_ID))
                .isNotEmpty()
                .allSatisfy(script ->
                        assertThat(script.classLoader())
                                .isSameAs(expectedClassLoader));
        assertThat(scriptRegistry.scripts())
                .extracting(script -> script.id())
                .containsAll(MATERIALIZED_USERSCRIPT_IDS);
        assertThat(scheduleCapabilityRegistry.snapshotView().owners())
                .anyMatch(owner -> owner.owner().featurePluginId().equals(PLUGIN_ID));
        assertThat(queueOperationRegistry.operationsForOwner(PLUGIN_ID))
                .singleElement()
                .satisfies(owned ->
                        assertThat(owned.operations().getClass().getClassLoader())
                                .isSameAs(expectedClassLoader));
        assertThat(downloadExtensionRegistry.resolveDownloadType("illust")).isPresent();
        assertThat(pluginControllerRegistrar.registeredPluginIds()).contains(PLUGIN_ID);
        assertThat(anyHandlerLoadedBy(expectedClassLoader)).isTrue();
    }

    private void assertServiceFootprintAbsent(ClassLoader previousClassLoader) {
        assertThat(routeAccessRegistry.routes())
                .noneMatch(route -> route.pluginId().equals(PLUGIN_ID));
        assertThat(staticResourceRegistry.resources())
                .noneMatch(resource -> resource.pluginId().equals(PLUGIN_ID));
        assertThat(navigationRegistry.navigation())
                .noneMatch(item -> item.pluginId().equals(PLUGIN_ID));
        assertThat(startupRouteRegistry.startupRoutes())
                .noneMatch(route -> route.pluginId().equals(PLUGIN_ID));
        assertThat(userscriptRegistry.userscripts())
                .noneMatch(script -> script.pluginId().equals(PLUGIN_ID));
        assertThat(scriptRegistry.scripts())
                .extracting(script -> script.id())
                .doesNotContainAnyElementsOf(USERSCRIPT_IDS);
        assertThat(scheduleCapabilityRegistry.snapshotView().owners())
                .noneMatch(owner -> owner.owner().featurePluginId().equals(PLUGIN_ID));
        assertThat(queueOperationRegistry.operationsForOwner(PLUGIN_ID)).isEmpty();
        assertThat(downloadExtensionRegistry.resolveDownloadType("illust")).isEmpty();
        assertThat(pluginControllerRegistrar.registeredPluginIds())
                .doesNotContain(PLUGIN_ID);
        assertThat(anyHandlerLoadedBy(previousClassLoader)).isFalse();
    }

    private void assertRoute(String pattern, AccessPolicy policy) {
        assertThat(routeAccessRegistry.routes())
                .filteredOn(route -> route.pluginId().equals(PLUGIN_ID)
                        && route.route().pathPattern().equals(pattern))
                .singleElement()
                .satisfies(route ->
                        assertThat(route.route().accessPolicy()).isEqualTo(policy));
    }

    private Object handlerBean(String pattern) {
        return requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getKey().getPatternValues().contains(pattern))
                .map(entry -> entry.getValue().getBean())
                .findFirst()
                .orElseThrow();
    }

    private boolean anyHandlerLoadedBy(ClassLoader classLoader) {
        return requestMappingHandlerMapping.getHandlerMethods().values().stream()
                .map(handler -> handler.getBean())
                .filter(bean -> !(bean instanceof String))
                .anyMatch(bean -> bean.getClass().getClassLoader() == classLoader);
    }

    private ClassLoader externalDownloadWorkbenchClassLoader() {
        return pluginRegistry.registeredPlugins().stream()
                .filter(plugin -> plugin.id().equals(PLUGIN_ID))
                .findFirst()
                .orElseThrow()
                .classLoader();
    }

    private static boolean stageExternalDownloadWorkbenchJar() {
        String configured = System.getProperty(WORKBENCH_CLASSES_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return false;
        }
        Path workbenchClasses = Path.of(configured);
        if (!Files.isDirectory(workbenchClasses)) {
            throw new IllegalStateException(
                    "Configured download-workbench classes directory does not exist: "
                            + workbenchClasses);
        }
        if (!Files.isRegularFile(workbenchClasses.resolve("plugin.properties"))) {
            throw new IllegalStateException(
                    "Configured download-workbench classes directory has no plugin.properties: "
                            + workbenchClasses);
        }
        try {
            deleteRecursively(PLUGINS_DIR);
            Files.createDirectories(PLUGINS_DIR);
            Path jar = PLUGINS_DIR.resolve("download-workbench-plugin.jar");
            zipDirectoryAsJar(workbenchClasses, jar);
            PluginTestProvenance.writeLocalUpload(
                    PLUGINS_DIR, jar, PLUGIN_ID, "1.0.0");
            return true;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not stage the configured download-workbench classes", failure);
        }
    }

    private static void zipDirectoryAsJar(Path sourceDir, Path jarPath)
            throws IOException {
        try (OutputStream output = Files.newOutputStream(jarPath);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            List<Path> files;
            try (var walk = Files.walk(sourceDir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                String entryName =
                        sourceDir.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        try {
            deleteRecursively(root);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
