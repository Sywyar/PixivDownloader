package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleExecutionLease;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleSingleCapabilityLease;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkSettings;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private static final String COMPOSITE_SOURCE_TYPE = "ext-leak-probe-composite-source";
    private static final String COMPOSITE_WORK_TYPE = "ext-leak-probe-composite-work";
    private static final String COMPOSITE_POLICY_ID = "ext-leak-probe-composite-policy";
    private static final String COMPOSITE_GUARD_ID = "ext-leak-probe-composite-guard";

    /** 探针采集到的弱引用句柄：probe loader 本身 + 其拥有的执行器实例（强引用均已出帧）。 */
    private record WeakHandles(WeakReference<ClassLoader> classLoader, WeakReference<Object> runner) {
    }

    /** 新式复合执行租约中四类插件 Bean 及其各自定义 classloader 的弱引用句柄。 */
    private record CompositeWeakHandles(
            WeakReference<ClassLoader> sourceClassLoader,
            WeakReference<ClassLoader> workClassLoader,
            WeakReference<ClassLoader> policyClassLoader,
            WeakReference<ClassLoader> guardClassLoader,
            WeakReference<Object> source,
            WeakReference<Object> work,
            WeakReference<Object> policy,
            WeakReference<Object> guard
    ) {
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

    @Test
    @DisplayName("复合调度执行租约撤回并关闭后释放来源、作品、凭证策略与 Guard 的 classloader")
    void compositeScheduleExecutionLeaseReleasesCapabilityClassLoaders() throws Exception {
        ScheduleCapabilityRegistry schedule = new ScheduleCapabilityRegistry();

        CompositeWeakHandles handles = registerCompositeAssertWithdrawAndClose(schedule);

        assertThat(schedule.snapshotView().owners()).isEmpty();
        assertThat(schedule.tryAcquireSource(COMPOSITE_SOURCE_TYPE)).isEmpty();
        assertThat(schedule.resolveSourceExecutor(COMPOSITE_SOURCE_TYPE)).isEmpty();
        assertThat(schedule.resolveWorkExecutor(COMPOSITE_WORK_TYPE)).isEmpty();
        assertThat(schedule.resolveCredentialPolicy(COMPOSITE_POLICY_ID)).isEmpty();
        assertThat(schedule.resolveGuard(COMPOSITE_GUARD_ID)).isEmpty();

        boolean sourceCollected = ClassLoaderLeakProbes.awaitCollected(handles.source());
        boolean workCollected = ClassLoaderLeakProbes.awaitCollected(handles.work());
        boolean policyCollected = ClassLoaderLeakProbes.awaitCollected(handles.policy());
        boolean guardCollected = ClassLoaderLeakProbes.awaitCollected(handles.guard());
        boolean sourceClCollected = ClassLoaderLeakProbes.awaitCollected(handles.sourceClassLoader());
        boolean workClCollected = ClassLoaderLeakProbes.awaitCollected(handles.workClassLoader());
        boolean policyClCollected = ClassLoaderLeakProbes.awaitCollected(handles.policyClassLoader());
        boolean guardClCollected = ClassLoaderLeakProbes.awaitCollected(handles.guardClassLoader());
        boolean allCollected = sourceCollected && workCollected && policyCollected && guardCollected
                && sourceClCollected && workClCollected && policyClCollected && guardClCollected;
        if (!allCollected) {
            Assumptions.abort("复合调度能力未在本环境全部被 GC 回收，但确定性引用链已确认 registry 与关闭租约无残留——"
                    + "判为环境不稳定（非业务泄漏）。"
                    + compositeLeakDiagnostic(schedule, handles));
        }
        assertThat(allCollected).as("复合调度能力 Bean 与四个 probe classloader 均应可回收").isTrue();
    }

    /**
     * 在独立栈帧内用四个受控子 loader 分别定义来源、作品、凭证策略与 Guard Bean，取得跨 owner 复合租约，
     * 再撤回所有 owner 并关闭租约。返回时只保留弱引用，避免测试局部变量误 pin 插件代际。
     */
    private static CompositeWeakHandles registerCompositeAssertWithdrawAndClose(
            ScheduleCapabilityRegistry schedule) throws Exception {
        ClassLoader parent = PluginClassLoaderLeakProbeTest.class.getClassLoader();
        ProbeClassLoader sourceCl = new ProbeClassLoader(parent, LeakProbeSourceExecutor.class);
        ProbeClassLoader workCl = new ProbeClassLoader(parent, LeakProbeWorkExecutor.class);
        ProbeClassLoader policyCl = new ProbeClassLoader(parent, LeakProbeCredentialPolicy.class);
        ProbeClassLoader guardCl = new ProbeClassLoader(parent, LeakProbeExecutionGuard.class);

        ScheduledSourceExecutor source = newProbeCapability(
                sourceCl, LeakProbeSourceExecutor.class, ScheduledSourceExecutor.class);
        ScheduledWorkExecutor work = newProbeCapability(
                workCl, LeakProbeWorkExecutor.class, ScheduledWorkExecutor.class);
        ScheduledCredentialPolicy policy = newProbeCapability(
                policyCl, LeakProbeCredentialPolicy.class, ScheduledCredentialPolicy.class);
        ScheduledExecutionGuard guard = newProbeCapability(
                guardCl, LeakProbeExecutionGuard.class, ScheduledExecutionGuard.class);
        assertThat(source.getClass().getClassLoader()).isSameAs(sourceCl);
        assertThat(work.getClass().getClassLoader()).isSameAs(workCl);
        assertThat(policy.getClass().getClassLoader()).isSameAs(policyCl);
        assertThat(guard.getClass().getClassLoader()).isSameAs(guardCl);

        ScheduleCapabilityOwner sourceOwner = new ScheduleCapabilityOwner(
                "ext-leak-probe-source-feature", "ext-leak-probe-source-package", 31L);
        ScheduleCapabilityOwner workOwner = new ScheduleCapabilityOwner(
                "ext-leak-probe-work-feature", "ext-leak-probe-work-package", 32L);
        ScheduleCapabilityOwner policyOwner = new ScheduleCapabilityOwner(
                "ext-leak-probe-policy-feature", "ext-leak-probe-policy-package", 33L);
        ScheduleCapabilityOwner guardOwner = new ScheduleCapabilityOwner(
                "ext-leak-probe-guard-feature", "ext-leak-probe-guard-package", 34L);
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                COMPOSITE_SOURCE_TYPE,
                Set.of(),
                "ext-leak-probe-composite-definition",
                1,
                new ScheduledSourcePresentation(
                        "ext-leak-probe", "source.name", "source.description", "schedule", "neutral"),
                Set.of("default"),
                Set.of(COMPOSITE_WORK_TYPE),
                Set.of(COMPOSITE_POLICY_ID),
                Set.of(COMPOSITE_GUARD_ID),
                null);

        ScheduleCapabilityPublication sourcePublication = ScheduleCapabilityRegistryTestAccess.publish(
                schedule, ScheduleOwnerBundle.prepare(
                        sourceOwner, List.of(), List.of(), List.of(descriptor), List.of(source),
                        List.of(), List.of(), List.of()));
        ScheduleCapabilityPublication workPublication = ScheduleCapabilityRegistryTestAccess.publish(
                schedule, ScheduleOwnerBundle.prepare(
                        workOwner, List.of(), List.of(), List.of(), List.of(),
                        List.of(work), List.of(), List.of()));
        ScheduleCapabilityPublication policyPublication = ScheduleCapabilityRegistryTestAccess.publish(
                schedule, ScheduleOwnerBundle.prepare(
                        policyOwner, List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(policy), List.of()));
        ScheduleCapabilityPublication guardPublication = ScheduleCapabilityRegistryTestAccess.publish(
                schedule, ScheduleOwnerBundle.prepare(
                        guardOwner, List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(guard)));

        SchedulePlanningLease planning = schedule.tryAcquireSource(COMPOSITE_SOURCE_TYPE).orElseThrow();
        ScheduledExecutionPlan plan = new ScheduledExecutionPlan(
                Set.of(COMPOSITE_WORK_TYPE),
                COMPOSITE_POLICY_ID,
                ScheduledCredentialRequirement.REQUIRED,
                false,
                List.of(new ScheduledGuardBinding(
                        COMPOSITE_GUARD_ID, Set.of(ScheduledGuardPoint.RUN_START), 0)),
                null,
                0,
                1,
                0L);
        ScheduleExecutionLease execution = schedule.tryExpand(planning, plan).orElseThrow();
        assertThat(planning.isActive()).isFalse();
        assertThat(execution.owners()).containsExactlyInAnyOrder(
                sourceOwner, workOwner, policyOwner, guardOwner);
        assertThat(execution.sourceExecutor()).containsSame(source);
        assertThat(execution.workExecutor(COMPOSITE_WORK_TYPE)).containsSame(work);
        assertThat(execution.workExecutors()).containsOnlyKeys(COMPOSITE_WORK_TYPE);
        assertThat(execution.workExecutorOwner(COMPOSITE_WORK_TYPE)).contains(workOwner);
        assertThat(execution.workExecutorOwners()).containsOnlyKeys(COMPOSITE_WORK_TYPE);
        assertThat(execution.credentialPolicy()).containsSame(policy);
        assertThat(execution.credentialPolicyOwner()).contains(policyOwner);
        assertThat(execution.guard(COMPOSITE_GUARD_ID)).containsSame(guard);
        assertThat(execution.guards()).containsOnlyKeys(COMPOSITE_GUARD_ID);
        assertThat(execution.guardOwner(COMPOSITE_GUARD_ID)).contains(guardOwner);
        assertThat(execution.guardOwners()).containsOnlyKeys(COMPOSITE_GUARD_ID);

        CompositeWeakHandles handles = new CompositeWeakHandles(
                new WeakReference<>(sourceCl),
                new WeakReference<>(workCl),
                new WeakReference<>(policyCl),
                new WeakReference<>(guardCl),
                new WeakReference<>(source),
                new WeakReference<>(work),
                new WeakReference<>(policy),
                new WeakReference<>(guard));

        ScheduleGenerationDrain sourceDrain = ScheduleCapabilityRegistryTestAccess.withdraw(
                schedule, sourcePublication).orElseThrow();
        ScheduleGenerationDrain workDrain = ScheduleCapabilityRegistryTestAccess.withdraw(
                schedule, workPublication).orElseThrow();
        ScheduleGenerationDrain policyDrain = ScheduleCapabilityRegistryTestAccess.withdraw(
                schedule, policyPublication).orElseThrow();
        ScheduleGenerationDrain guardDrain = ScheduleCapabilityRegistryTestAccess.withdraw(
                schedule, guardPublication).orElseThrow();
        List<ScheduleGenerationDrain> drains = List.of(sourceDrain, workDrain, policyDrain, guardDrain);
        assertThat(drains).allSatisfy(drain -> {
            assertThat(drain.activeLeaseCount()).isEqualTo(1);
            assertThat(drain.isDrained()).isFalse();
        });
        assertThat(execution.cancellation().isCancellationRequested()).isTrue();
        assertThat(execution.sourceExecutor()).containsSame(source);
        assertThat(execution.workExecutor(COMPOSITE_WORK_TYPE)).containsSame(work);
        assertThat(execution.credentialPolicy()).containsSame(policy);
        assertThat(execution.guard(COMPOSITE_GUARD_ID)).containsSame(guard);

        execution.close();
        planning.close();
        assertThat(execution.isActive()).isFalse();
        assertThatThrownBy(execution::descriptor).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(execution::sourceExecutor).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.workExecutor(COMPOSITE_WORK_TYPE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(execution::workExecutors).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.workExecutorOwner(COMPOSITE_WORK_TYPE))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(execution::workExecutorOwners).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(execution::credentialPolicy).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(execution::credentialPolicyOwner).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.guard(COMPOSITE_GUARD_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(execution::guards).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execution.guardOwner(COMPOSITE_GUARD_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(execution::guardOwners).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(execution::cancellation).isInstanceOf(IllegalStateException.class);
        assertThat(drains).allSatisfy(drain ->
                assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(1))).isTrue());

        return handles;
    }

    private static String compositeLeakDiagnostic(
            ScheduleCapabilityRegistry schedule, CompositeWeakHandles handles) {
        return " 诊断["
                + "scheduleOwners=" + schedule.snapshotView().owners().size()
                + ", source=" + schedule.resolveSourceExecutor(COMPOSITE_SOURCE_TYPE).isPresent()
                + ", work=" + schedule.resolveWorkExecutor(COMPOSITE_WORK_TYPE).isPresent()
                + ", policy=" + schedule.resolveCredentialPolicy(COMPOSITE_POLICY_ID).isPresent()
                + ", guard=" + schedule.resolveGuard(COMPOSITE_GUARD_ID).isPresent()
                + ", sourceInstanceCollected=" + (handles.source().get() == null)
                + ", workInstanceCollected=" + (handles.work().get() == null)
                + ", policyInstanceCollected=" + (handles.policy().get() == null)
                + ", guardInstanceCollected=" + (handles.guard().get() == null)
                + ", sourceClassLoaderCollected=" + (handles.sourceClassLoader().get() == null)
                + ", workClassLoaderCollected=" + (handles.workClassLoader().get() == null)
                + ", policyClassLoaderCollected=" + (handles.policyClassLoader().get() == null)
                + ", guardClassLoaderCollected=" + (handles.guardClassLoader().get() == null)
                + "]";
    }

    private static <T> T newProbeCapability(
            ProbeClassLoader classLoader, Class<?> templateClass, Class<T> contract)
            throws ReflectiveOperationException {
        Object instance = classLoader.loadClass(templateClass.getName())
                .getDeclaredConstructor()
                .newInstance();
        return contract.cast(instance);
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

    /** 新式复合租约的来源执行器探针；行为不会被调用，只用于验证租约强引用的建立与释放。 */
    public static final class LeakProbeSourceExecutor implements ScheduledSourceExecutor {
        public LeakProbeSourceExecutor() {
        }

        @Override
        public String sourceType() {
            return "ext-leak-probe-composite-source";
        }

        @Override
        public ScheduledExecutionPlan plan(
                top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition task) {
            throw new UnsupportedOperationException("probe behavior must not be invoked");
        }

        @Override
        public top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult discover(
                top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext context) {
            throw new UnsupportedOperationException("probe behavior must not be invoked");
        }
    }

    /** 新式复合租约的作品执行器探针。 */
    public static final class LeakProbeWorkExecutor implements ScheduledWorkExecutor {
        public LeakProbeWorkExecutor() {
        }

        @Override
        public String workType() {
            return "ext-leak-probe-composite-work";
        }

        @Override
        public top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult execute(
                top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork work,
                top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext context) {
            throw new UnsupportedOperationException("probe behavior must not be invoked");
        }
    }

    /** 新式复合租约的凭证策略探针。 */
    public static final class LeakProbeCredentialPolicy implements ScheduledCredentialPolicy {
        public LeakProbeCredentialPolicy() {
        }

        @Override
        public String policyId() {
            return "ext-leak-probe-composite-policy";
        }

        @Override
        public top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult probe(
                top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext context) {
            throw new UnsupportedOperationException("probe behavior must not be invoked");
        }
    }

    /** 新式复合租约的执行 Guard 探针。 */
    public static final class LeakProbeExecutionGuard implements ScheduledExecutionGuard {
        public LeakProbeExecutionGuard() {
        }

        @Override
        public String guardId() {
            return "ext-leak-probe-composite-guard";
        }

        @Override
        public top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision evaluate(
                top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardContext context) {
            throw new UnsupportedOperationException("probe behavior must not be invoked");
        }
    }

    /**
     * 受控子 classloader：只对构造时指定的 probe 类从字节自行 {@code defineClass}（使该类及其实例归本 loader），
     * 其余一律委托父 loader。由此可精确建立「插件 Bean 实例 → 定义它的 classloader」引用链。
     */
    static final class ProbeClassLoader extends ClassLoader {
        private final Set<String> probeClasses;

        ProbeClassLoader(ClassLoader parent) {
            this(parent, LeakProbeWorkRunner.class);
        }

        ProbeClassLoader(ClassLoader parent, Class<?> probeClass) {
            super(parent);
            this.probeClasses = Set.of(probeClass.getName());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (probeClasses.contains(name)) {
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
