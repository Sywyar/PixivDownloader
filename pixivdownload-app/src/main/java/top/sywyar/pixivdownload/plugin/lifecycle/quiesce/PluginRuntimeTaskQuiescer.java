package top.sywyar.pixivdownload.plugin.lifecycle.quiesce;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;

import java.util.List;
import java.util.Optional;

/**
 * 外置插件运行期任务清退器：在生命周期服务完成 quiesce 标记后，按固定顺序屏蔽新的计划任务派发、关闭该插件的
 * 服务端推流，再按插件声明的 queue type 排空在途下载任务。
 *
 * <p>三个职责步骤分别隔离异常，任一步失败只记录诊断，不阻断后续清退。底层 schedule 注销、推流关闭与队列清空
 * 均沿用既有幂等语义，因此 quiesce 后再 stop 时可以安全重复调用。本组件只编排运行期任务资源，不持有生命周期状态，
 * 也不负责 controller、web 贡献、子 context 或插件自身 {@code start/stop} 回调。</p>
 */
@Slf4j
@Component
public class PluginRuntimeTaskQuiescer {

    private final PluginScheduleContributionRegistrar scheduleContributionRegistrar;
    private final PluginStreamRegistry pluginStreamRegistry;
    private final QueueOperationRegistry queueOperationRegistry;

    public PluginRuntimeTaskQuiescer(PluginScheduleContributionRegistrar scheduleContributionRegistrar,
                                     PluginStreamRegistry pluginStreamRegistry,
                                     QueueOperationRegistry queueOperationRegistry) {
        this.scheduleContributionRegistrar = scheduleContributionRegistrar;
        this.pluginStreamRegistry = pluginStreamRegistry;
        this.queueOperationRegistry = queueOperationRegistry;
    }

    /**
     * 清退指定插件的运行期任务资源。调用顺序固定为 schedule 派发屏蔽 → SSE 关闭 → queue drain。
     *
     * @param pluginId 插件 id
     * @param plugin   插件描述实例；缺少核心注册条目时为空，此时仍屏蔽 schedule 并关闭推流
     */
    public void quiesce(String pluginId, Optional<PixivFeaturePlugin> plugin) {
        shieldScheduleDispatch(pluginId);
        closeStreams(pluginId);
        drainQueueTasks(pluginId, plugin);
    }

    private void shieldScheduleDispatch(String pluginId) {
        try {
            scheduleContributionRegistrar.unregister(pluginId);
        } catch (RuntimeException e) {
            log.warn("Error unregistering schedule contributions for plugin '{}' during quiesce: {}",
                    pluginId, e.toString());
        }
    }

    private void closeStreams(String pluginId) {
        try {
            int closed = pluginStreamRegistry.closeForPlugin(pluginId);
            if (closed > 0) {
                log.info("Closed {} server-push stream(s) for plugin '{}'.", closed, pluginId);
            }
        } catch (RuntimeException e) {
            log.warn("Error closing server-push streams for plugin '{}': {}", pluginId, e.toString());
        }
    }

    private void drainQueueTasks(String pluginId, Optional<PixivFeaturePlugin> plugin) {
        if (plugin.isEmpty()) {
            return;
        }

        List<QueueTypeContribution> queueTypes;
        try {
            queueTypes = plugin.get().queueTypes();
        } catch (RuntimeException e) {
            log.warn("Error reading queue types for plugin '{}': {}", pluginId, e.toString());
            return;
        }
        if (queueTypes == null) {
            log.warn("Plugin '{}' returned null queue types during runtime task quiesce.", pluginId);
            return;
        }

        for (QueueTypeContribution queueType : queueTypes) {
            if (queueType == null) {
                log.warn("Plugin '{}' returned a null queue type during runtime task quiesce.", pluginId);
                continue;
            }
            String type = queueType.type();
            try {
                queueOperationRegistry.resolve(type).ifPresent(operations -> {
                    int drained = operations.clearAll();
                    if (drained > 0) {
                        log.info("Drained {} in-flight task(s) of queue type '{}' for plugin '{}'.",
                                drained, type, pluginId);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("Error draining queue type '{}' for plugin '{}': {}", type, pluginId, e.toString());
            }
        }
    }
}
