package top.sywyar.pixivdownload.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 核心壳侧的外置插件子 {@code ApplicationContext} 生命周期管理器：为每个已启动且核心 API 兼容的外置插件包建立一个
 * 子 context（父 context 为核心应用），在其中实例化插件声明的 {@code @Configuration} 配置类（其 {@code @Bean} 装配
 * 插件自有 Bean），并把子 context 的生命周期与外置插件启停对齐——核心壳关闭时随之关闭全部子 context。
 *
 * <h2>装配与生命周期</h2>
 * <ul>
 *   <li><b>建立时机：</b>{@link SmartLifecycle#start()}（核心应用 context 刷新完成、全部核心 Bean 就绪后）。此时
 *       PF4J 已在启动期加载 / 启动外置插件（{@code PluginRuntimeStatus} Bean 已在刷新期触发
 *       {@link PluginRuntimeManager#start()}），故清点到的子 context 装配定义已就绪，且子 context 能向父 context
 *       解析核心 API / 服务 Bean。</li>
 *   <li><b>建立粒度：</b>每个外置插件包一个子 context（声明了 {@code @Configuration} 配置类的才建立）。插件自有
 *       Bean 上的 {@code @ConditionalOnPluginEnabled} 经子 context 从父环境继承的属性源解析 {@code plugins.<id>.enabled}，
 *       故被禁用插件的条件托管 Bean 在子 context 中同样缺席。</li>
 *   <li><b>隔离失败：</b>单个外置插件子 context 建立失败被收敛为日志、不影响其它插件、<b>不</b>致核心壳启动失败。</li>
 *   <li><b>关闭时机：</b>{@link SmartLifecycle#stop()}（核心 context 关闭、早于核心 Bean 销毁），按建立的逆序关闭
 *       全部子 context，释放其 Bean 与对父 Bean 的引用。</li>
 * </ul>
 *
 * <p>子 context 建立后，其中的 controller 经 {@link PluginControllerRegistrar} 动态注册进核心壳（父 context）的
 * 请求分发表，关闭前按插件注销——故子 context 的建立 / controller 注册是按插件<b>原子</b>的：controller 注册失败
 *（典型：某映射缺路由声明）即关闭刚建立的子 context、不保留该插件，与其它插件隔离。本管理器负责子 context 的建立 /
 * 持有 / 关闭、controller 注册 / 注销的编排，以及可观测（{@link #pluginIds()} / {@link #contextFor(String)}）。
 * 无外置插件（插件目录缺失 / 空 / 无声明配置类的插件）时本管理器为空、透明无副作用。
 *
 * <p>外置插件的 web 贡献（route / static / i18n / navigation / userscript）由各下游注册中心在<b>构造期</b>从
 * {@link PluginRegistry} 活动快照接入（启动期接入路径，内置插件行为不变）。本管理器负责其<b>注销</b>编排：
 * {@link #stop()} 与上述失败路径都经 {@link PluginWebContributionRegistrar} 按 pluginId 撤销该插件的全部 web 贡献
 *（并清其 classloader 的 i18n 缓存、刷新脚本层），顺序为<b>先注销 controller、再注销 web 贡献、最后关闭子 context</b>。
 * 没有子 context 的纯贡献外置插件也在 {@code stop()} 中被注销，故注销后这些贡献无残留、静态资源 URL 经
 * {@code AuthFilter}「未声明即 404」不可达（与禁用语义一致）。
 */
@Slf4j
@Component
public class ExternalPluginContextManager implements SmartLifecycle {

    private final ApplicationContext parent;
    private final PluginRuntimeManager pluginRuntimeManager;
    private final PluginApplicationContextFactory contextFactory;
    private final PluginControllerRegistrar controllerRegistrar;
    private final PluginRegistry pluginRegistry;
    private final PluginWebContributionRegistrar webContributionRegistrar;

    private final Object lock = new Object();
    /** 按建立顺序保存的子 context（键为外置插件包 id）。仅在 {@link #lock} 内变更，读路径取其只读视图。 */
    private final Map<String, ConfigurableApplicationContext> contexts = new LinkedHashMap<>();
    private volatile boolean running;

    public ExternalPluginContextManager(ApplicationContext parent,
                                        PluginRuntimeManager pluginRuntimeManager,
                                        PluginApplicationContextFactory contextFactory,
                                        PluginControllerRegistrar controllerRegistrar,
                                        PluginRegistry pluginRegistry,
                                        PluginWebContributionRegistrar webContributionRegistrar) {
        this.parent = parent;
        this.pluginRuntimeManager = pluginRuntimeManager;
        this.contextFactory = contextFactory;
        this.controllerRegistrar = controllerRegistrar;
        this.pluginRegistry = pluginRegistry;
        this.webContributionRegistrar = webContributionRegistrar;
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            for (PluginContextModule module : pluginRuntimeManager.inspectContextModules()) {
                createContext(module);
            }
            running = true;
        }
        log.info("External plugin contexts started: {} child ApplicationContext(s) {}.",
                contexts.size(), contexts.keySet());
    }

    private void createContext(PluginContextModule module) {
        String pluginId = module.sourcePluginId();
        if (contexts.containsKey(pluginId)) {
            log.warn("Duplicate plugin context module for '{}' - keeping the first, skipping.", pluginId);
            return;
        }
        ConfigurableApplicationContext child;
        try {
            child = contextFactory.create(parent, module);
        } catch (Exception e) {
            // 隔离失败：单个外置插件子 context 建立失败不影响其它插件、不致核心壳启动失败。该插件不可用，
            // 撤掉它在各注册中心构造期接入的 web 贡献（route/static/i18n/navigation/userscript），避免留下
            // 路由声明在却无 controller / 页面的半可用状态——按插件原子，与禁用语义一致（其 URL 转「未声明即 404」）。
            log.error("Failed to start plugin context for '{}': {}", pluginId, e.toString(), e);
            webContributionRegistrar.unregister(pluginId, module.classLoader());
            return;
        }
        try {
            controllerRegistrar.registerControllers(pluginId, child);
        } catch (Exception e) {
            // controller 注册失败（典型：映射缺路由声明，或某条 registerMapping 与已有 handler 冲突）→ 按插件原子隔离：
            // 先注销该插件可能残留的映射（registerControllers 失败时已自行回滚，此处兜底），再撤掉其 web 贡献，
            // 最后关闭刚建立的子 context、不保留——杜绝父分发表 / 注册中心留下指向即将关闭插件的陈旧 handler / 贡献。
            log.error("Failed to register controllers for plugin '{}': {} - closing its child context.",
                    pluginId, e.toString(), e);
            controllerRegistrar.unregisterControllers(pluginId);
            webContributionRegistrar.unregister(pluginId, module.classLoader());
            closeQuietly(pluginId, child);
            return;
        }
        contexts.put(pluginId, child);
    }

    private void closeQuietly(String pluginId, ConfigurableApplicationContext child) {
        try {
            child.close();
        } catch (Exception e) {
            log.warn("Error closing plugin context for '{}' after a failed controller registration: {}",
                    pluginId, e.toString());
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            // 活动外置插件包 id → 来源 classloader（清其 i18n ResourceBundle 缓存用）。无外置插件时为空。
            Map<String, ClassLoader> externalClassLoaders = externalClassLoadersById();

            // 1) 有子 context 的插件（逆建立序）：先注销 controller，再注销 web 贡献（含清 i18n 缓存 + 刷新脚本层），
            //    最后关闭子 context——停止新请求命中即将销毁的 handler / 已注销的路由。
            List<String> contextIds = new ArrayList<>(contexts.keySet());
            Collections.reverse(contextIds);
            for (String pluginId : contextIds) {
                ConfigurableApplicationContext child = contexts.get(pluginId);
                controllerRegistrar.unregisterControllers(pluginId);
                webContributionRegistrar.unregister(pluginId,
                        externalClassLoaders.getOrDefault(pluginId, child.getClassLoader()));
                try {
                    child.close();
                } catch (Exception e) {
                    log.warn("Error closing plugin context for '{}': {}", pluginId, e.toString());
                }
            }
            contexts.clear();

            // 2) 没有子 context 的纯贡献外置插件（逆注册序）：仅注销其 web 贡献（步骤 1 已处理的跳过）。
            List<Map.Entry<String, ClassLoader>> externalReversed = new ArrayList<>(externalClassLoaders.entrySet());
            Collections.reverse(externalReversed);
            for (Map.Entry<String, ClassLoader> entry : externalReversed) {
                if (!contextIds.contains(entry.getKey())) {
                    webContributionRegistrar.unregister(entry.getKey(), entry.getValue());
                }
            }
            running = false;
        }
    }

    /** 当前活动外置插件包 id → 来源 classloader（保持注册顺序）。供 {@link #stop()} 按插件注销 web 贡献。 */
    private Map<String, ClassLoader> externalClassLoadersById() {
        Map<String, ClassLoader> byId = new LinkedHashMap<>();
        for (PluginRegistry.RegisteredPlugin registered : pluginRegistry.registeredPlugins()) {
            if (registered.source() == PluginSource.EXTERNAL) {
                byId.put(registered.id(), registered.classLoader());
            }
        }
        return byId;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 已建立子 context 的外置插件包 id（按建立顺序），只读快照。 */
    public Set<String> pluginIds() {
        synchronized (lock) {
            return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(contexts.keySet()));
        }
    }

    /** 指定外置插件包的子 context（未建立时为空）。供可观测与后续接线消费。 */
    public Optional<ConfigurableApplicationContext> contextFor(String pluginId) {
        synchronized (lock) {
            return Optional.ofNullable(contexts.get(pluginId));
        }
    }

    /** 当前持有的子 context 数量。 */
    public int count() {
        synchronized (lock) {
            return contexts.size();
        }
    }
}
