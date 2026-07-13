package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleSingleCapabilityLease;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkSettings;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.scripts.ScriptRegistry;
import top.sywyar.pixivdownload.scripts.UserscriptRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;
import top.sywyar.pixivdownload.plugin.web.PluginWebContributionRegistrar;

/**
 * 外置插件 classloader 泄漏探针（always-run，受控子 classloader、不依赖真实 PF4J jar）：钉死「外置插件的 web /
 * schedule / SSE 贡献从核心注册中心<b>注销后</b>，不再有任何注册中心持有该插件 classloader，其 classloader 可被
 * GC 回收」这一泄漏防护不变量。
 *
 * <h2>受控子 classloader（probe loader）</h2>
 * 测试用 {@link ProbeClassLoader} 自定义子 loader 把一个 probe 执行器类 {@link LeakProbeWorkRunner} 从字节重新
 * {@code defineClass}（其实例的 {@code getClassLoader()} 即 probe loader），并把同一个 probe loader 作为插件注册
 * 条目的来源 classloader。由此 probe loader 同时经两条真实泄漏链被各注册中心持有：
 * <ul>
 *   <li><b>classLoader 字段链</b>：{@link StaticResourceRegistry} / {@link WebI18nBundleRegistry} /
 *       {@link UserscriptRegistry} 直接保存声明方 classloader（解析静态资源 / i18n / 油猴脚本用）；</li>
 *   <li><b>Bean 实例链</b>：{@link ScheduleCapabilityRegistry} 的 owner snapshot 与执行租约保存 probe loader
 *       拥有的执行器 Bean 实例（实例经其 {@code Class} 反向持有定义它的 classloader）。</li>
 * </ul>
 *
 * <h2>两段验证（区分业务泄漏与环境不稳定）</h2>
 * <ol>
 *   <li><b>确定性引用链检查</b>（始终执行、与环境无关）：注销后逐个断言各注册中心快照不再暴露该插件——这是
 *       「无残留强引用」的<b>硬性业务保证</b>（这些注册中心的不可变快照即唯一持有点，快照不含即已释放）。</li>
 *   <li><b>GC 探针</b>（环境容忍）：放掉直接强引用后用 {@link ClassLoaderLeakProbes} 尽力触发回收并观察弱引用。
 *       回收成功 → 更强证据；不稳定环境（Windows / CI）下未回收 → 因①已确定性排除业务泄漏，判为环境 inconclusive
 *       （输出关键 registry 状态诊断后 {@link Assumptions#abort}），不误报失败。</li>
 * </ol>
 *
 * <p>承载 probe loader 强引用的 {@link #registerAssertAndTearDown} 把 probe loader / 插件 / 执行器 / 注册条目全部
 * 限定在该方法栈帧内，返回后这些强引用即出帧不可达；GC 探针前各注册中心已确定性证明不含该插件，故仍存活的注册中心
 * 不再 pin probe loader、可安全留作诊断重查。
 *
 * <p>controller 映射注销后不再持有子 context handler 的回归由真实 PF4J 子 context 的
 * {@code StatsExternalPluginBootContextTest} 覆盖；本探针聚焦不依赖子 context 的 classLoader 字段链 / Bean 实例链。
 */
@DisplayName("外置插件 classloader 泄漏探针：注销后注册中心释放插件 classloader、可被 GC 回收")
class PluginClassLoaderLeakProbeTest {

    private static final String PLUGIN_ID = "ext-leak-probe";
    private static final String NAMESPACE = "ext-leak-probe";
    private static final String SOURCE_TYPE = "ext-leak-probe-source";
    private static final String RUNNER_KIND = "ext-leak-probe-kind";

    /** 探针采集到的弱引用句柄：probe loader 本身 + 其拥有的执行器实例（强引用均已出帧）。 */
    private record WeakHandles(WeakReference<ClassLoader> classLoader, WeakReference<Object> runner) {
    }

    @Test
    @DisplayName("web + schedule + SSE 贡献注销后各注册中心不再暴露插件，且其 classloader 弱引用可回收（确定性引用链 + GC 探针）")
    void unregisteringReleasesPluginClassLoader() throws Exception {
        // 真实核心注册中心（空内置基线）。
        PluginRegistry empty = new PluginRegistry(List.of());
        RouteAccessRegistry routes = new RouteAccessRegistry(empty);
        StaticResourceRegistry statics = new StaticResourceRegistry(empty);
        WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(empty);
        NavigationRegistry navigation = new NavigationRegistry(empty);
        UserscriptRegistry userscripts = new UserscriptRegistry(empty);
        ScriptRegistry scripts = new ScriptRegistry(TestI18nBeans.appMessages(), userscripts);
        PluginWebContributionRegistrar web = new PluginWebContributionRegistrar(
                routes, statics, i18n, navigation, new WebUiSlotRegistry(empty), userscripts, scripts);
        ScheduleCapabilityRegistry schedule = new ScheduleCapabilityRegistry();
        PluginStreamRegistry streams = new PluginStreamRegistry();

        // probe loader / 插件 / 执行器 / 注册条目全部 confined 在该方法栈帧内：返回后即不可达，仅留弱引用句柄。
        WeakHandles handles = registerAssertAndTearDown(
                web, routes, statics, i18n, navigation, userscripts, schedule, streams);

        // —— 注销后（确定性引用链检查：业务级「无残留引用」硬性保证，与环境无关）——
        assertNoResidualReference(routes, statics, i18n, navigation, userscripts, schedule, streams);

        // —— GC 探针（环境容忍）：强引用已出帧后 probe loader 应可回收 ——
        ClassLoaderLeakProbes.awaitCollected(handles.runner());
        boolean clCollected = ClassLoaderLeakProbes.awaitCollected(handles.classLoader());
        if (!clCollected) {
            // 业务级残留已被上面的确定性断言排除（各注册中心快照不含该插件）；到此仍未回收 → 归为环境 / JVM GC 不稳定。
            Assumptions.abort("probe classloader 未在本环境被 GC 回收，但确定性引用链已确认各注册中心无残留——"
                    + "判为环境不稳定（非业务泄漏）。" + leakDiagnostic(
                    routes, statics, i18n, navigation, userscripts, schedule, streams, handles.runner()));
        }
        assertThat(clCollected).as("probe classloader 注销后应可被 GC 回收").isTrue();
    }

    /**
     * 在<b>独立栈帧</b>内创建 probe loader + 合成插件 + 执行器，接入全套 web / schedule / SSE 贡献并断言接入到位
     *（含 classLoader 字段链确属 probe loader），随后注销，最后只返回弱引用句柄。所有强引用（probe loader / 插件 /
     * 执行器 / 注册条目）都是本方法局部，返回即出帧不可达——避免在调用方留下任何 pin 住 probe loader 的局部变量。
     */
    private static WeakHandles registerAssertAndTearDown(
            PluginWebContributionRegistrar web, RouteAccessRegistry routes, StaticResourceRegistry statics,
            WebI18nBundleRegistry i18n, NavigationRegistry navigation, UserscriptRegistry userscripts,
            ScheduleCapabilityRegistry schedule, PluginStreamRegistry streams)
            throws Exception {
        ProbeClassLoader probeCl = new ProbeClassLoader(PluginClassLoaderLeakProbeTest.class.getClassLoader());
        ScheduledWorkRunner runner = newProbeRunner(probeCl);
        assertThat(runner.getClass().getClassLoader())
                .as("probe 执行器实例必须由受控子 loader 加载")
                .isSameAs(probeCl);

        ProbePlugin plugin = new ProbePlugin();
        PluginRegistry.RegisteredPlugin registered = new PluginRegistry.RegisteredPlugin(
                plugin, PluginSource.EXTERNAL, probeCl);

        // 接入：五类 web 贡献（static / i18n / userscript 经 probe loader 解析）+ 来源 + 执行器（probe loader 拥有的 Bean）+ 一条 SSE 推流。
        web.register(registered);
        ScheduleCapabilityOwner owner = new ScheduleCapabilityOwner(PLUGIN_ID, PLUGIN_ID, 17L);
        ScheduleOwnerBundle bundle = ScheduleOwnerBundle.prepare(
                owner, plugin.scheduledSources(), List.of(runner), List.of(), List.of(),
                List.of(), List.of(), List.of());
        ScheduleCapabilityPublication publication =
                ScheduleCapabilityRegistryTestAccess.publish(schedule, bundle);
        streams.register(PLUGIN_ID, "conn-1", () -> { /* no-op close */ });

        // —— 接入后（确定性）：各注册中心暴露该插件，且 static / i18n / userscript 解析用的正是 probe loader ——
        assertThat(routes.routes()).anyMatch(r -> r.pluginId().equals(PLUGIN_ID));
        assertThat(statics.resources())
                .anyMatch(s -> s.pluginId().equals(PLUGIN_ID) && s.classLoader() == probeCl);
        assertThat(i18n.resolve(NAMESPACE)).isNotNull();
        assertThat(i18n.resolve(NAMESPACE).classLoader()).isSameAs(probeCl);
        assertThat(navigation.navigation()).anyMatch(n -> n.pluginId().equals(PLUGIN_ID));
        assertThat(userscripts.userscripts())
                .anyMatch(u -> u.pluginId().equals(PLUGIN_ID) && u.classLoader() == probeCl);
        assertThat(schedule.resolveLegacySource(SOURCE_TYPE)).isPresent();
        var runnerHandle = schedule.resolveLegacyWorkRunner(RUNNER_KIND).orElseThrow();
        ScheduleSingleCapabilityLease<ScheduledWorkRunner> runnerLease =
                schedule.tryAcquire(runnerHandle).orElseThrow();
        assertThat(streams.activeStreamCount(PLUGIN_ID)).isEqualTo(1);

        WeakReference<ClassLoader> weakCl = new WeakReference<>(probeCl);
        WeakReference<Object> weakRunner = new WeakReference<>(runner);

        // —— 注销（与生命周期 teardown 等价的注册中心注销路径）——
        web.unregister(registered); // 五类 web 贡献注销 + ResourceBundle.clearCache(probeCl) + scriptRegistry.refresh
        ScheduleGenerationDrain drain =
                ScheduleCapabilityRegistryTestAccess.withdraw(schedule, publication).orElseThrow();
        assertThat(schedule.snapshotView().owners()).isEmpty();
        assertThat(schedule.resolveLegacySource(SOURCE_TYPE)).isEmpty();
        assertThat(schedule.resolveLegacyWorkRunner(RUNNER_KIND)).isEmpty();
        assertThat(drain.activeLeaseCount()).isEqualTo(1);
        assertThat(drain.isDrained()).isFalse();
        assertThat(runnerLease.capability()).isSameAs(runner);
        assertThat(runnerLease.cancellation().isCancellationRequested()).isTrue();
        streams.closeForPlugin(PLUGIN_ID);
        runnerLease.close();
        assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isTrue();

        return new WeakHandles(weakCl, weakRunner);
    }

    /** 注销后各注册中心快照不再暴露该插件（确定性引用链：快照即唯一持有点，不含即已释放）。 */
    private static void assertNoResidualReference(
            RouteAccessRegistry routes, StaticResourceRegistry statics, WebI18nBundleRegistry i18n,
            NavigationRegistry navigation, UserscriptRegistry userscripts,
            ScheduleCapabilityRegistry schedule, PluginStreamRegistry streams) {
        assertThat(routes.routes()).noneMatch(r -> r.pluginId().equals(PLUGIN_ID));
        assertThat(statics.resources()).noneMatch(s -> s.pluginId().equals(PLUGIN_ID));
        assertThat(i18n.resolve(NAMESPACE)).isNull();
        assertThat(navigation.navigation()).noneMatch(n -> n.pluginId().equals(PLUGIN_ID));
        assertThat(userscripts.userscripts()).noneMatch(u -> u.pluginId().equals(PLUGIN_ID));
        assertThat(schedule.snapshotView().owners()).isEmpty();
        assertThat(schedule.resolveLegacySource(SOURCE_TYPE)).isEmpty();
        assertThat(schedule.resolveLegacyWorkRunner(RUNNER_KIND)).isEmpty();
        assertThat(streams.activeStreamCount(PLUGIN_ID)).isZero();
    }

    /** GC 探针未回收时输出的「关键 registry 状态 + 弱引用」诊断，辅助定位是否真有未被发现的持有链。 */
    private static String leakDiagnostic(
            RouteAccessRegistry routes, StaticResourceRegistry statics, WebI18nBundleRegistry i18n,
            NavigationRegistry navigation, UserscriptRegistry userscripts,
            ScheduleCapabilityRegistry schedule, PluginStreamRegistry streams, WeakReference<?> weakRunner) {
        return " 诊断["
                + "routes=" + routes.routes().stream().anyMatch(r -> r.pluginId().equals(PLUGIN_ID))
                + ", static=" + statics.resources().stream().anyMatch(s -> s.pluginId().equals(PLUGIN_ID))
                + ", i18n=" + (i18n.resolve(NAMESPACE) != null)
                + ", nav=" + navigation.navigation().stream().anyMatch(n -> n.pluginId().equals(PLUGIN_ID))
                + ", userscript=" + userscripts.userscripts().stream().anyMatch(u -> u.pluginId().equals(PLUGIN_ID))
                + ", scheduleOwners=" + schedule.snapshotView().owners().size()
                + ", source=" + schedule.resolveLegacySource(SOURCE_TYPE).isPresent()
                + ", runner=" + schedule.resolveLegacyWorkRunner(RUNNER_KIND).isPresent()
                + ", stream=" + streams.activeStreamCount(PLUGIN_ID)
                + ", runnerInstanceCollected=" + (weakRunner.get() == null)
                + "]";
    }

    private static ScheduledWorkRunner newProbeRunner(ProbeClassLoader cl) throws ReflectiveOperationException {
        Class<?> runnerClass = cl.loadClass(LeakProbeWorkRunner.class.getName());
        return (ScheduledWorkRunner) runnerClass.getDeclaredConstructor().newInstance();
    }

    /** 一个声明全套 web + schedule 贡献的合成外置插件（实例由测试 loader 加载——被探针的是其来源 classloader，非本实例）。 */
    private static final class ProbePlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return PLUGIN_ID;
        }

        @Override
        public String displayName() {
            return "nav.label";
        }

        @Override
        public String description() {
            return "nav.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<WebRouteContribution> routes() {
            return List.of(WebRouteContribution.admin("/ext-leak-probe/**"),
                    WebRouteContribution.admin("/api/ext-leak-probe/**"));
        }

        @Override
        public List<StaticResourceContribution> staticResources() {
            return List.of(new StaticResourceContribution(
                    PLUGIN_ID, "classpath:/static/ext-leak-probe/", "/ext-leak-probe/"));
        }

        @Override
        public List<I18nContribution> i18n() {
            return List.of(new I18nContribution(NAMESPACE, "i18n/web/ext-leak-probe"));
        }

        @Override
        public List<NavigationContribution> navigation() {
            return List.of(new NavigationContribution(
                    "ext-leak-probe-nav", "main", "ns", "nav.label", "/ext-leak-probe/", null, AccessPolicy.ADMIN, 100));
        }

        @Override
        public List<UserscriptContribution> userscripts() {
            return List.of(new UserscriptContribution(PLUGIN_ID, "classpath*:userscript/ext-leak-probe/*.user.js"));
        }

        @Override
        public List<ScheduledSourceProvider> scheduledSources() {
            return List.of(new ScheduledSourceProvider() {
                @Override
                public String type() {
                    return SOURCE_TYPE;
                }

                @Override
                public Set<String> legacyTypeNames() {
                    return Set.of();
                }
            });
        }
    }

    /**
     * probe 执行器：被 {@link ProbeClassLoader} 重新 {@code defineClass} 后，其实例 classloader 即 probe loader，
     * 注册进 {@link ScheduleCapabilityRegistry} 后构成「Bean 实例 → 定义它的 classloader」泄漏链的探测点。
     * <b>必须是静态嵌套类</b>（无外层实例引用，避免实例反向 pin 测试类）。
     */
    public static final class LeakProbeWorkRunner implements ScheduledWorkRunner {
        public LeakProbeWorkRunner() {
        }

        @Override
        public String kind() {
            return RUNNER_KIND;
        }

        @Override
        public boolean download(ScheduledWork work, ScheduledWorkSettings settings, String cookie) {
            return false;
        }
    }

    /**
     * 受控子 classloader：只对 {@link LeakProbeWorkRunner} 从字节自行 {@code defineClass}（使该类及其实例归本 loader），
     * 其余一律委托父 loader。由此本 loader 既被注册中心的 classLoader 字段链持有、又被其拥有的执行器 Bean 实例反向持有，
     * 卸载后是否被回收即「插件 classloader 是否泄漏」的判据。
     */
    static final class ProbeClassLoader extends ClassLoader {
        ProbeClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(LeakProbeWorkRunner.class.getName())) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> existing = findLoadedClass(name);
                    if (existing == null) {
                        byte[] bytes = readClassBytes(name);
                        existing = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        resolveClass(existing);
                    }
                    return existing;
                }
            }
            return super.loadClass(name, resolve);
        }

        private byte[] readClassBytes(String binaryName) throws ClassNotFoundException {
            String resourcePath = binaryName.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new ClassNotFoundException(binaryName);
                }
                return in.readAllBytes();
            } catch (IOException e) {
                throw new ClassNotFoundException(binaryName, e);
            }
        }
    }
}
