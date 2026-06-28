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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.runtime.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.PluginDiscoveryResult;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 外置 stats 插件经<b>真实 Spring 上下文</b>的端到端接线验证：用真实 stats 插件 jar（由本模块 reactor 兄弟模块
 * {@code pixivdownload-plugin-stats} 的编译产物 + 根部 {@code plugin.properties} 组装）放进固定的工作目录
 * {@code target/test-runtime/plugins-external-stats}，经系统属性 {@code pixivdownload.plugins-dir} 指向它，
 * 启动完整应用上下文，断言<b>生产装配链路</b>把外置 stats 接入到位：
 * <ul>
 *   <li>{@code pixivdownload.plugins-dir} → {@link RuntimeFiles#pluginsDirectory()} →
 *       {@code PluginRuntimeConfiguration} 的 {@link PluginRuntimeManager} / {@link PluginRuntimeStatus} /
 *       {@link PluginDiscoveryResult} Bean → 双来源 {@link PluginRegistry} Bean；</li>
 *   <li>{@link PluginRegistry} Bean 同时含七个内置插件与外置 {@code stats}，{@code stats} 来源为
 *       {@link PluginSource#EXTERNAL}、解析 classloader 是外置插件自己的（非核心壳应用 classloader）；</li>
 *   <li>下游注册中心 Bean（{@code @Component} 的 {@link RouteAccessRegistry} / {@link NavigationRegistry} /
 *       {@link StaticResourceRegistry} / {@link WebI18nBundleRegistry}）都能看到 stats 的 route / navigation /
 *       static / i18n 贡献，且静态资源 / i18n 的解析走外置插件 classloader。</li>
 * </ul>
 *
 * <p>这条用例覆盖的是组件级 {@link StatsExternalPluginIntegrationTest} <b>没有</b>覆盖的那一段——后者手工
 * {@code new PluginRegistry(...)} 注入发现结果，本用例验证的是配置类把属性、运行时管理器与双来源注册中心
 * Bean 串起来后、真实容器里 stats 确实以 {@code EXTERNAL} 在场并贡献到位。
 *
 * <p>控范围：本用例验证<b>启动期加载</b>、contribution 注册，以及外置 stats 的 {@code @RestController} /
 * {@code @Service} Bean 经 {@code StatsPluginConfiguration} 在 stats 专属子 {@code ApplicationContext} 中装配
 *（其 service 向父 context 解析核心 {@code StatsQueryStore}）。这些插件 Bean 只在子 context、<b>不</b>是父（核心
 * 应用）context 的 Bean；其 {@code /api/stats/**} controller 经 {@link PluginControllerRegistrar} 动态注册进父
 * context 的请求分发表（{@code RequestMappingHandlerMapping}）——{@code /api/stats/dashboard} 安装后命中子 context
 * 的 {@code StatsController}、注销后不再命中、可逆，均在此验证。
 *
 * <p>stats 构建产物目录经 surefire 系统属性 {@code stats.plugin.classes} 传入（reactor 中先于 app 构建）；未就绪时
 * （如 IDE 未触发 reactor 构建）经类级 {@link EnabledIf} 整类跳过，不加载上下文。Windows 下 PF4J 加载 jar 会持有
 * 文件锁，故 {@link #releasePluginsAndCleanup()} 先停止 / 卸载插件释放 classloader 再删除临时目录，并用
 * {@link DirtiesContext} 在类结束后关闭这套一次性上下文。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-external-stats",
        "setup.browser.auto-open=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("externalStatsJarStaged")
@DisplayName("外置 stats 插件经真实上下文接入：EXTERNAL 来源 + 下游注册中心 classloader-aware 可见")
class StatsExternalPluginBootContextTest {

    private static final String STATS_CLASSES_PROPERTY = "stats.plugin.classes";
    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/plugins-external-stats");
    /** 类初始化时组装真实 stats 插件 jar；产物未就绪（reactor 未构建 stats）时为 false，整类经 {@link EnabledIf} 跳过。 */
    private static final boolean STAGED = stageExternalStatsJar();

    static {
        // 仅在 jar 组装成功（本类将真正运行）时设置系统属性，避免被跳过时把目录属性泄漏给其它用例。
        if (STAGED) {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
            System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
            System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, PLUGINS_DIR.toString());
        }
    }

    /** 类级 {@link EnabledIf} 条件：仅当真实 stats 插件 jar 组装就绪时才加载上下文运行本类。 */
    @SuppressWarnings("unused")
    static boolean externalStatsJarStaged() {
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
    private NavigationRegistry navigationRegistry;
    @Autowired
    private StaticResourceRegistry staticResourceRegistry;
    @Autowired
    private WebI18nBundleRegistry webI18nBundleRegistry;
    @Autowired
    private ExternalPluginContextManager externalPluginContextManager;
    @Autowired
    private PluginControllerRegistrar pluginControllerRegistrar;
    @Autowired
    private PluginWebContributionRegistrar pluginWebContributionRegistrar;
    @Autowired
    private PluginLifecycleService pluginLifecycleService;
    @Autowired
    private ExternalPluginLifecycleCoordinator lifecycleCoordinator;
    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;
    @Autowired
    private WebApplicationContext applicationContext;

    @AfterAll
    void releasePluginsAndCleanup() {
        if (pluginRuntimeManager != null) {
            // 先停止 / 卸载，释放 PF4J 插件 classloader 对 jar 的文件锁（Windows 下否则删不掉临时目录）。
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
    @DisplayName("运行时状态 / 发现结果 Bean：POPULATED、started 含 stats、无失败、发现 stats")
    void runtimeStatusAndDiscoveryBeansSeeStats() {
        assertThat(pluginRuntimeStatus.state()).isEqualTo(PluginDirectoryState.POPULATED);
        assertThat(pluginRuntimeStatus.startedPluginIds()).contains("stats");
        assertThat(pluginRuntimeStatus.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.hasFailures()).isFalse();
        assertThat(pluginDiscoveryResult.discovered())
                .extracting(DiscoveredFeaturePlugin::featurePluginId).contains("stats");
    }

    @Test
    @DisplayName("双来源 PluginRegistry Bean：七内置 + 外置 stats，stats 来源 EXTERNAL、classloader 为外置插件 loader")
    void pluginRegistryBeanContainsStatsAsExternal() {
        assertThat(pluginRegistry.plugins()).extracting(PixivFeaturePlugin::id)
                .containsExactlyInAnyOrder(
                        "core", "download-workbench", "schedule", "gallery", "novel", "duplicate", "plugin-market", "stats");
        assertThat(pluginRegistry.source("stats")).contains(PluginSource.EXTERNAL);
        assertThat(externalStatsClassLoader()).isNotSameAs(getClass().getClassLoader());
        // 内置七插件来源仍为内置（外置接入不改内置语义）。
        assertThat(pluginRegistry.registeredPlugins())
                .filteredOn(rp -> !rp.id().equals("stats"))
                .allSatisfy(rp -> assertThat(rp.source()).isEqualTo(PluginSource.BUILT_IN));
    }

    @Test
    @DisplayName("RouteAccessRegistry Bean：stats 三条路由进入快照")
    void routeRegistryBeanExposesStatsRoutes() {
        assertThat(routeAccessRegistry.routes())
                .filteredOn(r -> r.pluginId().equals("stats"))
                .extracting(r -> r.route().pathPattern())
                .containsExactlyInAnyOrder("/pixiv-stats.html", "/pixiv-stats/**", "/api/stats/**");
    }

    @Test
    @DisplayName("NavigationRegistry Bean：stats 导航项进入快照")
    void navigationRegistryBeanExposesStats() {
        assertThat(navigationRegistry.navigation()).anyMatch(n -> n.pluginId().equals("stats"));
    }

    @Test
    @DisplayName("StaticResourceRegistry / WebI18nBundleRegistry Bean：stats 资源 / i18n 经外置插件 classloader 解析")
    void staticAndI18nRegistryBeansAreClassloaderAware() {
        ClassLoader externalCl = externalStatsClassLoader();

        StaticResourceRegistry.RegisteredStaticResource staticResource =
                staticResourceRegistry.resources().stream()
                        .filter(s -> s.pluginId().equals("stats")
                                && s.contribution().publicPathPrefix().equals("/pixiv-stats/")).findFirst().orElseThrow();
        assertThat(staticResource.contribution().publicPathPrefix()).isEqualTo("/pixiv-stats/");
        assertThat(staticResource.classLoader()).isSameAs(externalCl);

        assertThat(staticResourceRegistry.resources().stream()
                .anyMatch(s -> s.pluginId().equals("stats")
                        && s.contribution().publicPathPrefix().equals("/pixiv-stats.html")))
                .isTrue();

        WebI18nBundleRegistry.RegisteredBundle bundle = webI18nBundleRegistry.resolve("stats");
        assertThat(bundle).isNotNull();
        assertThat(bundle.classLoader()).isSameAs(externalCl);

        // classloader-aware 实证：stats 资源只能经外置 loader 解析到，核心壳应用 loader 解析不到。
        assertThat(externalCl.getResource("static/pixiv-stats/pixiv-stats.css")).isNotNull();
        assertThat(externalCl.getResource("i18n/web/stats.properties")).isNotNull();
        assertThat(getClass().getClassLoader().getResource("static/pixiv-stats/pixiv-stats.css")).isNull();
        assertThat(getClass().getClassLoader().getResource("i18n/web/stats.properties")).isNull();
    }

    @Test
    @DisplayName("外置 stats 子 ApplicationContext：StatsPluginConfiguration 的 service/controller Bean 在子 context、不在父 context")
    void externalStatsChildContextHostsStatsBeans() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("stats").orElseThrow();
        assertThat(child.isActive()).isTrue();
        // 子 context 的父就是核心应用 context，classloader 是外置插件自己的
        assertThat(child.getParent()).isSameAs(applicationContext);
        ClassLoader externalCl = externalStatsClassLoader();
        assertThat(child.getClassLoader()).isSameAs(externalCl);

        Class<?> statsServiceClass = externalCl.loadClass("top.sywyar.pixivdownload.stats.StatsService");
        Class<?> statsControllerClass = externalCl.loadClass("top.sywyar.pixivdownload.stats.StatsController");

        // 子 context 实例化了 stats 的 service / controller Bean（其 service 注入父 context 的 StatsQueryStore）
        assertThat(child.getBeanNamesForType(statsServiceClass)).isNotEmpty();
        assertThat(child.getBeanNamesForType(statsControllerClass)).isNotEmpty();
        assertThat(child.getBean(statsControllerClass)).isNotNull();

        // 这些插件 Bean 不是父（核心应用）context 的 Bean —— 它们只在子 context（controller 经请求分发表接入父，
        // 见 externalStatsControllerIsDynamicallyRegistered，但不作为父 context 的 Bean 定义存在）
        assertThat(applicationContext.getBeanNamesForType(statsControllerClass)).isEmpty();
        assertThat(applicationContext.getBeanNamesForType(statsServiceClass)).isEmpty();
    }

    @Test
    @DisplayName("外置 stats controller 动态注册：/api/stats/dashboard 命中子 context 的 StatsController；注销后不再命中、可逆")
    void externalStatsControllerIsDynamicallyRegistered() throws Exception {
        ConfigurableApplicationContext child = externalPluginContextManager.contextFor("stats").orElseThrow();
        Class<?> statsControllerClass =
                externalStatsClassLoader().loadClass("top.sywyar.pixivdownload.stats.StatsController");
        Object statsControllerBean = child.getBean(statsControllerClass);

        // 安装后：父 context 的请求分发表已注册 /api/stats/dashboard，handler 就是子 context 的 StatsController 实例
        assertThat(pluginControllerRegistrar.registeredPluginIds()).contains("stats");
        assertThat(statsDashboardHandlerBean()).isSameAs(statsControllerBean);

        // 注销后：同 URL 不再命中 controller
        pluginControllerRegistrar.unregisterControllers("stats");
        assertThat(pluginControllerRegistrar.registeredPluginIds()).doesNotContain("stats");
        assertThat(statsDashboardHandlerBean()).isNull();

        // 可逆：重新注册恢复（也还原本类其它用例 / AfterAll 依赖的已注册状态）
        int restored = pluginControllerRegistrar.registerControllers("stats", child);
        assertThat(restored).isGreaterThanOrEqualTo(1);
        assertThat(statsDashboardHandlerBean()).isSameAs(statsControllerBean);
    }

    @Test
    @DisplayName("外置 stats web 贡献注销后 route/static/i18n/navigation 不再暴露（URL 未声明即 AuthFilter 404）、可逆")
    void externalStatsWebContributionsAreRevocable() {
        PluginRegistry.RegisteredPlugin stats = pluginRegistry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("stats")).findFirst().orElseThrow();

        // 注销前：stats 的 web 贡献在场，其 API / 静态 URL 已被路由声明（AuthFilter 据此放行而非 404）。
        assertThat(routeAccessRegistry.isDeclared("/api/stats/dashboard", HttpMethod.GET)).isTrue();
        assertThat(routeAccessRegistry.isDeclared("/pixiv-stats/pixiv-stats.css", HttpMethod.GET)).isTrue();

        pluginWebContributionRegistrar.unregister(stats);

        // 注销后：route/static/i18n/navigation 快照不再含 stats，其 URL「未声明」→ AuthFilter「未声明即 404」、资源不可达。
        assertThat(routeAccessRegistry.isDeclared("/api/stats/dashboard", HttpMethod.GET)).isFalse();
        assertThat(routeAccessRegistry.isDeclared("/pixiv-stats/pixiv-stats.css", HttpMethod.GET)).isFalse();
        assertThat(routeAccessRegistry.routes()).noneMatch(r -> r.pluginId().equals("stats"));
        assertThat(staticResourceRegistry.resources()).noneMatch(s -> s.pluginId().equals("stats"));
        assertThat(webI18nBundleRegistry.resolve("stats")).isNull();
        assertThat(navigationRegistry.navigation()).noneMatch(n -> n.pluginId().equals("stats"));

        // 可逆：重新接入恢复（也还原本类其它用例 / AfterAll 依赖的已注册状态）。
        pluginWebContributionRegistrar.register(stats);
        assertThat(routeAccessRegistry.isDeclared("/api/stats/dashboard", HttpMethod.GET)).isTrue();
        assertThat(staticResourceRegistry.resources()).anyMatch(s -> s.pluginId().equals("stats"));
        assertThat(webI18nBundleRegistry.resolve("stats")).isNotNull();
        assertThat(navigationRegistry.navigation()).anyMatch(n -> n.pluginId().equals("stats"));
    }

    @Test
    @DisplayName("外置 stats 运行期热启停：quiesce 保留路由声明，stop 拆除服务足迹（controller / route / 子 context 全去），start 可逆重建")
    void externalStatsLifecycleQuiesceStopStartIsReversible() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        // 前置：stats 在服务，controller / route 在场。
        assertThat(pluginLifecycleService.phase("stats")).contains(PluginRuntimePhase.STARTED);
        assertThat(routeAccessRegistry.isDeclared("/api/stats/dashboard", HttpMethod.GET)).isTrue();
        assertThat(pluginControllerRegistrar.registeredPluginIds()).contains("stats");
        mockMvc.perform(get("/pixiv-stats.html"))
                .andExpect(status().isOk());

        // quiesce：状态转 QUIESCED；路由声明仍在（新请求由 PluginQuiesceGate 拦截、不靠注销路由）。
        pluginLifecycleService.quiesce("stats");
        assertThat(pluginLifecycleService.phase("stats")).contains(PluginRuntimePhase.QUIESCED);
        assertThat(routeAccessRegistry.isDeclared("/api/stats/dashboard", HttpMethod.GET)).isTrue();

        // stop：按序拆除服务足迹——controller 注销、web 贡献注销（URL「未声明」即 AuthFilter 404）、子 context 关闭。
        ConfigurableApplicationContext beforeStop = externalPluginContextManager.contextFor("stats").orElseThrow();
        pluginLifecycleService.stop("stats");
        assertThat(pluginLifecycleService.phase("stats")).contains(PluginRuntimePhase.STOPPED);
        assertThat(pluginControllerRegistrar.registeredPluginIds()).doesNotContain("stats");
        assertThat(statsDashboardHandlerBean()).isNull();
        assertThat(routeAccessRegistry.isDeclared("/api/stats/dashboard", HttpMethod.GET)).isFalse();
        assertThat(staticResourceRegistry.resources()).noneMatch(s -> s.pluginId().equals("stats"));
        assertThat(webI18nBundleRegistry.resolve("stats")).isNull();
        assertThat(externalPluginContextManager.contextFor("stats")).isEmpty();
        assertThat(beforeStop.isActive()).isFalse();
        mockMvc.perform(get("/pixiv-stats.html"))
                .andExpect(status().isNotFound());

        // start：可逆重建——子 context 重建、controller / web 贡献重新接入。
        pluginLifecycleService.start("stats");
        assertThat(pluginLifecycleService.phase("stats")).contains(PluginRuntimePhase.STARTED);
        assertThat(routeAccessRegistry.isDeclared("/api/stats/dashboard", HttpMethod.GET)).isTrue();
        assertThat(pluginControllerRegistrar.registeredPluginIds()).contains("stats");
        assertThat(staticResourceRegistry.resources()).anyMatch(s -> s.pluginId().equals("stats"));
        assertThat(webI18nBundleRegistry.resolve("stats")).isNotNull();
        ConfigurableApplicationContext afterStart = externalPluginContextManager.contextFor("stats").orElseThrow();
        assertThat(afterStart.isActive()).isTrue();
        assertThat(statsDashboardHandlerBean()).isNotNull();
        mockMvc.perform(get("/pixiv-stats.html"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("外置 stats 重启保留 generation/classloader，物理重载更换 generation/classloader 且旧 handler 全部撤回")
    void externalStatsReloadCycleLeavesNoHandlerFromPluginClassLoader() {
        ClassLoader statsCl = externalStatsClassLoader();
        long initialGeneration = pluginLifecycleService.generation("stats").orElseThrow();

        // restart：只重建服务足迹，保留代码 generation 与 classloader。
        lifecycleCoordinator.restart("stats");
        assertThat(pluginLifecycleService.phase("stats")).contains(PluginRuntimePhase.STARTED);
        assertThat(pluginLifecycleService.generation("stats")).contains(initialGeneration);
        assertThat(externalStatsClassLoader()).isSameAs(statsCl);

        ConfigurableApplicationContext beforeReload = externalPluginContextManager.contextFor("stats").orElseThrow();
        lifecycleCoordinator.reload("stats");
        assertThat(statsDashboardHandlerBean()).isNotNull();
        ConfigurableApplicationContext afterReload = externalPluginContextManager.contextFor("stats").orElseThrow();
        assertThat(afterReload).isNotSameAs(beforeReload);
        assertThat(beforeReload.isActive()).isFalse();
        assertThat(pluginLifecycleService.generation("stats").orElseThrow()).isGreaterThan(initialGeneration);
        assertThat(afterReload.getClassLoader()).isNotSameAs(statsCl);
        assertThat(anyHandlerLoadedBy(statsCl)).isFalse();

        // stop：classloader 级泄漏回归——请求分发表中不再有任何 handler Bean 由 stats classloader 加载（强于按 URL 判定）。
        ConfigurableApplicationContext beforeStop = externalPluginContextManager.contextFor("stats").orElseThrow();
        pluginLifecycleService.stop("stats");
        assertThat(pluginLifecycleService.phase("stats")).contains(PluginRuntimePhase.STOPPED);
        assertThat(anyHandlerLoadedBy(statsCl))
                .as("stop 后请求分发表不应残留任何由 stats classloader 加载的 handler Bean")
                .isFalse();
        assertThat(externalPluginContextManager.contextFor("stats")).isEmpty(); // lifecycle 不再持有子 context
        assertThat(beforeStop.isActive()).isFalse();                            // 子 context 已关闭
        // 下游 web 注册中心快照不再暴露 stats（route/static/i18n/navigation 一并清退）。
        assertThat(routeAccessRegistry.routes()).noneMatch(r -> r.pluginId().equals("stats"));
        assertThat(staticResourceRegistry.resources()).noneMatch(s -> s.pluginId().equals("stats"));
        assertThat(webI18nBundleRegistry.resolve("stats")).isNull();
        assertThat(navigationRegistry.navigation()).noneMatch(n -> n.pluginId().equals("stats"));

        // start：恢复服务足迹（也还原本类其它用例 / AfterAll 依赖的已启动状态）。
        lifecycleCoordinator.start("stats");
        assertThat(pluginLifecycleService.phase("stats")).contains(PluginRuntimePhase.STARTED);
        assertThat(statsDashboardHandlerBean()).isNotNull();
        assertThat(anyHandlerLoadedBy(afterReload.getClassLoader())).isTrue();
        assertThat(routeAccessRegistry.isDeclared("/api/stats/dashboard", HttpMethod.GET)).isTrue();
    }

    /** 请求分发表中是否存在<b>任何</b>由给定 classloader 加载的 handler Bean（classloader 级泄漏判据）。 */
    private boolean anyHandlerLoadedBy(ClassLoader classLoader) {
        return requestMappingHandlerMapping.getHandlerMethods().values().stream()
                .map(handlerMethod -> handlerMethod.getBean())
                .filter(bean -> !(bean instanceof String)) // handler 可能以 Bean 名（String）登记，跳过
                .anyMatch(bean -> bean.getClass().getClassLoader() == classLoader);
    }

    /** 父 context 的请求分发表中映射到 {@code /api/stats/dashboard} 的 handler Bean；未注册时为 null。 */
    private Object statsDashboardHandlerBean() {
        return requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().contains("/api/stats/dashboard"))
                .map(e -> e.getValue().getBean())
                .findFirst().orElse(null);
    }

    private ClassLoader externalStatsClassLoader() {
        return pluginRegistry.registeredPlugins().stream()
                .filter(rp -> rp.id().equals("stats")).findFirst().orElseThrow().classLoader();
    }

    // --- helpers ---

    /**
     * 若 stats 构建产物就绪，则把它（连同根部 {@code plugin.properties}）组装成 PF4J 可加载的 thin 插件 jar，
     * 放进 {@link #PLUGINS_DIR}；返回是否成功。任何缺失 / IO 异常都收敛为 false（整类经 {@link EnabledIf} 跳过、不报错）。
     */
    private static boolean stageExternalStatsJar() {
        try {
            String configured = System.getProperty(STATS_CLASSES_PROPERTY);
            if (configured == null || configured.isBlank()) {
                return false;
            }
            Path statsClasses = Path.of(configured);
            if (!Files.isDirectory(statsClasses)
                    || !Files.exists(statsClasses.resolve("plugin.properties"))) {
                return false;
            }
            deleteRecursivelyQuietly(PLUGINS_DIR);
            Files.createDirectories(PLUGINS_DIR);
            zipDirectoryAsJar(statsClasses, PLUGINS_DIR.resolve("stats-plugin.jar"));
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
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
                    // best-effort：临时目录，残留由 OS / 下次 mvn clean 清理
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
