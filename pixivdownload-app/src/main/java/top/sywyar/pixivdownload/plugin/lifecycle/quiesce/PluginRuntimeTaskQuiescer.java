package top.sywyar.pixivdownload.plugin.lifecycle.quiesce;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;

import java.util.List;
import java.util.Optional;

/**
 * 外置插件运行期任务清退器。固定顺序为精确撤回 schedule publication（同时拒绝新 lease、取消本代在途执行）→
 * 关闭 SSE → 排空插件队列。schedule 撤回是 child context 安全关闭的前置条件，失败不得吞掉；SSE 与队列清退仍按
 * 既有 best-effort 语义彼此隔离。
 */
@Slf4j
@Component
public class PluginRuntimeTaskQuiescer {

    /** 本次 quiesce 获得的精确 schedule drain；无 schedule publication 时为空。 */
    public record QuiesceResult(Optional<ScheduleGenerationDrain> scheduleDrain) {
        public QuiesceResult {
            scheduleDrain = scheduleDrain == null ? Optional.empty() : scheduleDrain;
        }
    }

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
     * 发起指定插件的运行期清退。若传入 publication，则必须精确撤回并取得 drain；token 已过期视为安全错误，
     * 不能继续关闭 child context。
     */
    public QuiesceResult quiesce(String pluginId,
                                 @Nullable ScheduleCapabilityPublication publication,
                                 Optional<PixivFeaturePlugin> plugin) {
        Optional<ScheduleGenerationDrain> drain = withdrawSchedule(publication);
        closeStreams(pluginId);
        drainQueueTasks(pluginId, plugin);
        return new QuiesceResult(drain);
    }

    private Optional<ScheduleGenerationDrain> withdrawSchedule(
            @Nullable ScheduleCapabilityPublication publication) {
        if (publication == null) {
            return Optional.empty();
        }
        Optional<ScheduleGenerationDrain> drain = scheduleContributionRegistrar.withdraw(publication);
        if (drain.isEmpty()) {
            throw new IllegalStateException("schedule publication is no longer active: "
                    + publication.owner() + "#" + publication.publicationId());
        }
        return drain;
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
