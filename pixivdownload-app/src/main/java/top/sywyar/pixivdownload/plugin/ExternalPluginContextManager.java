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
 * <p>本管理器只负责子 context 的建立 / 持有 / 关闭与可观测（{@link #pluginIds()} / {@link #contextFor(String)}）；
 * 子 context 中 controller 等向核心请求分发表的动态注册是另行处理的后续接线，<b>不</b>在本管理器内进行。无外置插件
 * （插件目录缺失 / 空 / 无声明配置类的插件）时本管理器为空、透明无副作用。
 */
@Slf4j
@Component
public class ExternalPluginContextManager implements SmartLifecycle {

    private final ApplicationContext parent;
    private final PluginRuntimeManager pluginRuntimeManager;
    private final PluginApplicationContextFactory contextFactory;

    private final Object lock = new Object();
    /** 按建立顺序保存的子 context（键为外置插件包 id）。仅在 {@link #lock} 内变更，读路径取其只读视图。 */
    private final Map<String, ConfigurableApplicationContext> contexts = new LinkedHashMap<>();
    private volatile boolean running;

    public ExternalPluginContextManager(ApplicationContext parent,
                                        PluginRuntimeManager pluginRuntimeManager,
                                        PluginApplicationContextFactory contextFactory) {
        this.parent = parent;
        this.pluginRuntimeManager = pluginRuntimeManager;
        this.contextFactory = contextFactory;
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
        if (contexts.containsKey(module.sourcePluginId())) {
            log.warn("Duplicate plugin context module for '{}' - keeping the first, skipping.",
                    module.sourcePluginId());
            return;
        }
        try {
            contexts.put(module.sourcePluginId(), contextFactory.create(parent, module));
        } catch (Exception e) {
            // 隔离失败：单个外置插件子 context 建立失败不影响其它插件、不致核心壳启动失败。
            log.error("Failed to start plugin context for '{}': {}",
                    module.sourcePluginId(), e.toString(), e);
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (!running) {
                return;
            }
            List<Map.Entry<String, ConfigurableApplicationContext>> ordered = new ArrayList<>(contexts.entrySet());
            Collections.reverse(ordered); // 逆序关闭
            for (Map.Entry<String, ConfigurableApplicationContext> entry : ordered) {
                try {
                    entry.getValue().close();
                } catch (Exception e) {
                    log.warn("Error closing plugin context for '{}': {}", entry.getKey(), e.toString());
                }
            }
            contexts.clear();
            running = false;
        }
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
