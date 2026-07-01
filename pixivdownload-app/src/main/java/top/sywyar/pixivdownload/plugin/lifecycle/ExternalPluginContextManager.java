package top.sywyar.pixivdownload.plugin.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

/**
 * 外置插件子 {@code ApplicationContext} 生命周期的 {@code SmartLifecycle} 驱动：在核心应用 context 刷新完成后
 * 触发外置插件服务足迹的建立、在核心 context 关闭（早于核心 Bean 销毁）时触发拆除。具体的「按插件建立 / 拆除 /
 * 热启停 / quiesce」编排收口在 {@link PluginLifecycleService}，本类只负责把核心壳的启动 / 关闭时机桥接到它，
 * 并转发可观测查询（子 context 持有情况）。
 *
 * <ul>
 *   <li><b>建立时机：</b>{@link SmartLifecycle#start()}（核心 context 刷新完成、核心 Bean 就绪、外置插件已由 PF4J
 *       启动期加载 / 启动并经发现桥接接入 {@link PluginRegistry} 之后）→ {@link PluginLifecycleService#startAll()}。</li>
 *   <li><b>关闭时机：</b>{@link SmartLifecycle#stop()}（核心 context 关闭、早于核心 Bean 销毁）→
 *       {@link PluginLifecycleService#stopAll()}，逆序拆除全部外置插件服务足迹。</li>
 * </ul>
 *
 * <p>无外置插件时透明无副作用。运行期按 pluginId 的热启停 / quiesce 由 {@link PluginLifecycleService} 提供，不在本类。
 */
@Slf4j
@Component
public class ExternalPluginContextManager implements SmartLifecycle {

    private final PluginLifecycleService lifecycleService;
    private volatile boolean running;

    public ExternalPluginContextManager(PluginLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Override
    public void start() {
        lifecycleService.startAll();
        running = true;
    }

    @Override
    public void stop() {
        lifecycleService.stopAll();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 已建立子 context 的外置插件包 id（按建立顺序），只读快照。 */
    public Set<String> pluginIds() {
        return lifecycleService.servingPluginIds();
    }

    /** 指定外置插件包的子 context（未建立 / 已关闭时为空）。 */
    public Optional<ConfigurableApplicationContext> contextFor(String pluginId) {
        return lifecycleService.contextFor(pluginId);
    }

    /** 当前持有的子 context 数量。 */
    public int count() {
        return lifecycleService.contextCount();
    }
}
