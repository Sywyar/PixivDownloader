package top.sywyar.pixivdownload.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunnerRegistry;
import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外置插件 schedule 贡献（计划任务来源 {@link ScheduledSourceProvider} + 作品类型执行器 {@link ScheduledWorkRunner}）
 * 的统一注册 / 注销入口。把一个插件向两类核心调度注册中心——{@link ScheduledSourceRegistry}（来源身份 / legacy 映射）
 * 与 {@link ScheduledWorkRunnerRegistry}（按作品类型 {@code kind} 解析的下载执行器）——的接入收口到一处，供
 * {@link PluginLifecycleService} 在外置插件 start / stop / unload / reload 时随其生命周期可逆地热插拔 schedule 贡献。
 *
 * <h2>两类贡献的来源不同</h2>
 * <ul>
 *   <li><b>来源（source）</b>来自 {@link PluginRegistry.RegisteredPlugin#plugin()} 的
 *       {@link top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin#scheduledSources() scheduledSources()}
 *       ——纯元数据，随注册条目即可读取，与有无子 context 无关。</li>
 *   <li><b>执行器（runner）</b>是业务 Bean，活在每外置插件的子 {@code ApplicationContext} 中（不进父 context 根扫描），
 *       故从传入的 child context 用 {@code getBeansOfType} 发现；{@code getBeansOfType} <b>不含祖先 context 的 Bean</b>，
 *       故不会误把父 context 的内置执行器（插画 / 小说）重复注册。纯贡献插件（无子 context、{@code childContext == null}）
 *       只贡献来源、无执行器。</li>
 * </ul>
 *
 * <h2>来源接入的幂等（与 web 贡献一致的启动期 / 运行期分层）</h2>
 * 外置插件的来源在<b>启动期</b>已由 {@link ScheduledSourceRegistry} 构造期从 {@link PluginRegistry} 活动快照接入
 * （与 route / static / i18n 等 web 贡献同一路径），故 {@link #register} 仅当来源<b>尚未注册</b>
 * （{@link ScheduledSourceRegistry#isRegistered}）时才注册——启动期接入跳过来源、只发现并注册执行器；运行期重启
 * （{@code stop} 已注销来源）时来源已不在，则重新注册。执行器无启动期对应物（子 context 在足迹建立时才创建），故启动期 /
 * 运行期都从 child context 发现并注册。{@link #unregister} 则无条件从两个注册中心注销（幂等：未注册过为安全 no-op）。
 *
 * <h2>按插件原子</h2>
 * {@link #register} 内来源与执行器各自经其注册中心的<b>批量原子</b>接口接入（{@link ScheduledSourceRegistry#register}
 * 一批来源全成或全不生效、{@link ScheduledWorkRunnerRegistry#register} 一批执行器全成或全不生效，二者均冲突即抛、不污染
 * 既有快照），故不存在「部分来源 / 部分执行器注册成功」的中间态。若来源注册成功但执行器注册失败，则<b>撤回本次刚注册的
 * 来源</b>再抛出（启动期已存在的来源不属本次、由 {@link PluginLifecycleService} 的足迹回滚经 {@link #unregister} 统一清退）。
 *
 * <h2>不持插件强引用（避免 classloader 泄漏）</h2>
 * 本注册中心<b>只记录 {@code pluginId → 该插件注册的 runner kind 列表}（纯字符串元数据）</b>，供 stop / unload 时精准
 * 注销对应 {@code kind}；<b>绝不</b>持有插件 Bean / classloader / 子 context 引用。执行器 Bean 实例由
 * {@link ScheduledWorkRunnerRegistry} 持有（调度器解析所需），注销后即释放——本注册中心退出后不残留为插件 classloader
 * 的泄漏点。来源由 {@link ScheduledSourceRegistry} 按 pluginId 持有 / 注销，本注册中心不另存来源引用。
 *
 * <p>本类是核心 {@code @Component}、非 {@code @PluginManagedBean}，不碰 DB / mapper / {@code DataSource}；
 * register / unregister 在本对象锁内串行，写入各注册中心复用其内部锁。
 */
@Slf4j
@Component
public class PluginScheduleContributionRegistrar {

    private final ScheduledSourceRegistry sourceRegistry;
    private final ScheduledWorkRunnerRegistry workRunnerRegistry;

    private final Object lock = new Object();
    /** pluginId → 该插件注册的 runner kind 列表（仅字符串元数据，供精准注销；不持 runner / context 引用）。 */
    private final Map<String, List<String>> runnerKindsByPlugin = new LinkedHashMap<>();

    public PluginScheduleContributionRegistrar(ScheduledSourceRegistry sourceRegistry,
                                               ScheduledWorkRunnerRegistry workRunnerRegistry) {
        this.sourceRegistry = sourceRegistry;
        this.workRunnerRegistry = workRunnerRegistry;
    }

    /**
     * 注册一个外置插件的 schedule 贡献：来源（来自插件 {@code scheduledSources()}，幂等——已注册则跳过）+ 执行器
     * （从 child context 发现）。按插件原子：执行器注册失败时撤回本次刚注册的来源后抛出，不留半接入。
     *
     * @param registered   插件注册条目（读其 {@code scheduledSources()}）
     * @param childContext 该插件的子 {@code ApplicationContext}（发现 {@link ScheduledWorkRunner} Bean）；纯贡献插件无子 context 时为 {@code null}
     * @throws RuntimeException 来源 type 冲突 / 执行器 kind 冲突等由对应注册中心透传（已回滚本次接入）
     */
    public void register(PluginRegistry.RegisteredPlugin registered, @Nullable ConfigurableApplicationContext childContext) {
        String pluginId = registered.id();
        List<ScheduledSourceProvider> sources = scheduledSourcesOf(registered);
        List<ScheduledWorkRunner> runners = discoverRunners(childContext);
        synchronized (lock) {
            boolean sourcesRegisteredHere = false;
            if (!sources.isEmpty() && !sourceRegistry.isRegistered(pluginId)) {
                sourceRegistry.register(pluginId, sources); // 批量原子：全成或全不生效
                sourcesRegisteredHere = true;
            }
            try {
                if (!runners.isEmpty()) {
                    workRunnerRegistry.register(runners); // 批量原子：kind 冲突即抛、不留半注册
                }
            } catch (RuntimeException e) {
                // 执行器注册失败 → 撤回本次刚注册的来源（启动期既有来源不属本次，由 lifecycle 足迹回滚经 unregister 清退）。
                if (sourcesRegisteredHere) {
                    sourceRegistry.unregister(pluginId);
                }
                throw e;
            }
            if (!runners.isEmpty()) {
                runnerKindsByPlugin.put(pluginId, runners.stream().map(ScheduledWorkRunner::kind).toList());
            }
        }
        if (!sources.isEmpty() || !runners.isEmpty()) {
            log.info("Registered schedule contributions for plugin '{}': {} source(s), {} work runner(s).",
                    pluginId, sources.size(), runners.size());
        }
    }

    /**
     * 注销一个外置插件的全部 schedule 贡献（幂等）：从 {@link ScheduledSourceRegistry} 按 pluginId 注销来源、
     * 从 {@link ScheduledWorkRunnerRegistry} 按已记录的 {@code kind} 逐个注销执行器。未注册过 / 空白 pluginId 为安全 no-op。
     */
    public void unregister(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            return;
        }
        synchronized (lock) {
            sourceRegistry.unregister(pluginId); // 幂等：未注册过则不改快照
            List<String> kinds = runnerKindsByPlugin.remove(pluginId);
            if (kinds != null) {
                for (String kind : kinds) {
                    workRunnerRegistry.unregister(kind); // 幂等
                }
            }
        }
    }

    /** 某插件当前注册的 runner kind 列表（只读观测 / 测试）；未注册过为空列表。 */
    public List<String> runnerKinds(String pluginId) {
        synchronized (lock) {
            return List.copyOf(runnerKindsByPlugin.getOrDefault(pluginId, List.of()));
        }
    }

    private static List<ScheduledSourceProvider> scheduledSourcesOf(PluginRegistry.RegisteredPlugin registered) {
        List<ScheduledSourceProvider> sources = registered.plugin().scheduledSources();
        return sources == null ? List.of() : sources;
    }

    /**
     * 从插件子 context 发现 {@link ScheduledWorkRunner} Bean。{@code getBeansOfType} 只看本 context 自有 Bean、
     * 不含祖先（父核心 context 的内置执行器不会被误纳），故跨 classloader 安全。无子 context 时返回空。
     */
    private static List<ScheduledWorkRunner> discoverRunners(@Nullable ConfigurableApplicationContext childContext) {
        if (childContext == null) {
            return List.of();
        }
        return List.copyOf(childContext.getBeansOfType(ScheduledWorkRunner.class).values());
    }
}
