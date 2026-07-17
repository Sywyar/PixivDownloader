package top.sywyar.pixivdownload.plugin.lifecycle.quiesce;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry;
import top.sywyar.pixivdownload.core.download.queue.QueueOperationRegistry.OwnedQueueOperations;
import top.sywyar.pixivdownload.core.schedule.capability.PluginScheduleContributionRegistrar;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueOperations;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.ScheduleContributionLifecycleAuthority;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 外置插件运行期任务清退器。固定顺序为精确撤回 schedule publication（同时拒绝新 lease、取消本代在途执行）→
 * 保存 owner 精确队列 generation drain → 关闭 SSE → 向已保存 generation 发取消。schedule / queue drain 都是
 * child context 安全关闭的前置条件；队列清退只依赖核心 owner 快照，不在 teardown 时回调插件 contribution getter。
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
     * 只撤回 schedule publication 并返回唯一 drain。生命周期必须先保存返回值，再执行其余清退。
     */
    public QuiesceResult withdrawSchedule(
            ScheduleContributionLifecycleAuthority authority,
            @Nullable ScheduleCapabilityPublication publication) {
        if (publication == null) {
            return new QuiesceResult(Optional.empty());
        }
        Optional<ScheduleGenerationDrain> drain =
                scheduleContributionRegistrar.withdraw(authority, publication);
        if (drain.isEmpty()) {
            throw new IllegalStateException("schedule publication is no longer active: "
                    + publication.owner() + "#" + publication.publicationId());
        }
        return new QuiesceResult(drain);
    }

    /** 新 serving 发布前重新开放推流；存在上次关闭失败残留时拒绝开放。 */
    public void resumeStreams(String pluginId) {
        pluginStreamRegistry.resume(pluginId);
    }

    /** 禁止新推流并重试关闭全部既有 / 迟到连接；最终复核必须确认没有 callback 残留。 */
    public void quiesceStreams(String pluginId) {
        Throwable failure = closeStreams(pluginId);
        int activeStreams = pluginStreamRegistry.activeStreamCount(pluginId);
        if (activeStreams != 0) {
            failure = mergeFailure(failure, new IllegalStateException(
                    "plugin streams remain after final quiesce: " + pluginId
                            + " (active=" + activeStreams + ")"));
        }
        rethrow(failure);
    }

    /**
     * 原子停止当前 owner 的每个队列操作实例接收新任务，并在每次返回后立刻交给生命周期保存。
     * {@code persistedDrains} 支持 fatal 后重试：已保存类型必须仍解析到同一 generation，且不会重复交给 recorder。
     * 此准备操作只调用声明为无取消 callback 的 {@link QueueOperations#prepareQuiesce(String)}。
     */
    public void prepareQueueDrains(
            String pluginId,
            List<QueueDrain> persistedDrains,
            Consumer<QueueDrain> recorder) {
        Objects.requireNonNull(recorder, "recorder");
        Map<String, QueueDrain> persisted = indexDrains(persistedDrains);
        Set<String> observed = new LinkedHashSet<>();
        for (OwnedQueueOperations owned : queueOperationRegistry.operationsForOwner(pluginId)) {
            String queueType = owned.queueType();
            QueueOperations operations = owned.operations();
            if (!observed.add(queueType)) {
                throw new IllegalStateException("duplicate queue operations for plugin '"
                        + pluginId + "': " + queueType);
            }
            QueueDrain drain = requireValidDrain(pluginId, queueType, Objects.requireNonNull(
                    operations.prepareQuiesce(queueType),
                    "queue prepareQuiesce returned null drain: " + queueType));
            QueueDrain saved = persisted.get(queueType);
            if (saved == null) {
                recorder.accept(drain);
            } else {
                requireSameGeneration(pluginId, saved, drain);
            }
        }
        for (String savedType : persisted.keySet()) {
            if (!observed.contains(savedType)) {
                throw new IllegalStateException("saved queue generation is no longer registered for plugin '"
                        + pluginId + "': " + savedType);
            }
        }
    }

    /** schedule 与 queue drains 已由生命周期持久化后，继续关闭 SSE 并发送队列取消。 */
    public void quiesceAfterScheduleWithdrawal(
            String pluginId, List<QueueDrain> queueDrains) {
        Throwable failure = closeStreams(pluginId);
        failure = cancelQueueTasks(pluginId, queueDrains, failure);
        rethrow(failure);
    }

    private Throwable closeStreams(String pluginId) {
        try {
            int closed = pluginStreamRegistry.closeForPlugin(pluginId);
            if (closed > 0) {
                log.info("Closed {} server-push stream(s) for plugin '{}'.", closed, pluginId);
            }
            return null;
        } catch (Throwable failure) {
            log.warn("Error closing server-push streams for plugin '{}' (failureType={})",
                    pluginId, failure.getClass().getName());
            return failure;
        }
    }

    private Throwable cancelQueueTasks(
            String pluginId, List<QueueDrain> queueDrains, Throwable failure) {
        Map<String, QueueOperations> current = new LinkedHashMap<>();
        for (OwnedQueueOperations owned : queueOperationRegistry.operationsForOwner(pluginId)) {
            String queueType = owned.queueType();
            QueueOperations operations = owned.operations();
            if (current.putIfAbsent(queueType, operations) != null) {
                failure = mergeFailure(failure, new IllegalStateException(
                        "duplicate queue operations for plugin '" + pluginId + "': " + queueType));
            }
        }
        for (QueueDrain expected : queueDrains == null ? List.<QueueDrain>of() : queueDrains) {
            QueueOperations operations = current.remove(expected.queueType());
            if (operations == null) {
                failure = mergeFailure(failure, new IllegalStateException(
                        "saved queue generation is no longer registered for plugin '" + pluginId
                                + "': " + expected.queueType()));
                continue;
            }
            try {
                QueueDrain actual = requireValidDrain(pluginId, expected.queueType(), Objects.requireNonNull(
                        operations.prepareQuiesce(expected.queueType()),
                        "queue prepareQuiesce returned null drain: " + expected.queueType()));
                requireSameGeneration(pluginId, expected, actual);
                operations.cancelQuiescedTasks();
            } catch (Throwable queueFailure) {
                log.warn("Error cancelling queue type '{}' for plugin '{}' (failureType={})",
                        expected.queueType(), pluginId, queueFailure.getClass().getName());
                failure = mergeFailure(failure, queueFailure);
            }
        }
        if (!current.isEmpty()) {
            failure = mergeFailure(failure, new IllegalStateException(
                    "queue operations appeared after drain preparation for plugin '"
                            + pluginId + "': " + current.keySet()));
        }
        return failure;
    }

    private static Map<String, QueueDrain> indexDrains(List<QueueDrain> drains) {
        Map<String, QueueDrain> indexed = new LinkedHashMap<>();
        if (drains == null) {
            return indexed;
        }
        for (QueueDrain drain : drains) {
            Objects.requireNonNull(drain, "saved queue drain");
            QueueDrain clash = indexed.putIfAbsent(drain.queueType(), drain);
            if (clash != null) {
                throw new IllegalStateException("duplicate saved queue drain: " + drain.queueType());
            }
        }
        return indexed;
    }

    private static void requireSameGeneration(
            String pluginId, QueueDrain expected, QueueDrain actual) {
        if (!expected.queueType().equals(actual.queueType())
                || expected.generation() != actual.generation()) {
            throw new IllegalStateException("queue generation changed while quiescing plugin '"
                    + pluginId + "': expected " + expected.queueType() + "#" + expected.generation()
                    + ", got " + actual.queueType() + "#" + actual.generation());
        }
    }

    private static QueueDrain requireValidDrain(String pluginId, String queueType, QueueDrain drain) {
        String drainType = drain.queueType();
        long generation = drain.generation();
        int activeCount = drain.activeCount();
        if (!queueType.equals(drainType)) {
            throw new IllegalStateException("queue drain type mismatch for plugin '" + pluginId
                    + "': expected " + queueType + ", got " + drainType);
        }
        if (generation < QueueDrain.COMPLETED_GENERATION) {
            throw new IllegalStateException("queue drain generation must not be negative for plugin '"
                    + pluginId + "': " + queueType);
        }
        if (activeCount < 0) {
            throw new IllegalStateException("queue drain active count must not be negative for plugin '"
                    + pluginId + "': " + queueType);
        }
        if (generation == QueueDrain.COMPLETED_GENERATION
                && (!drain.isDrained() || activeCount != 0)) {
            throw new IllegalStateException("queue drain generation 0 must be an already drained sentinel for plugin '"
                    + pluginId + "': " + queueType);
        }
        return drain;
    }

    private static Throwable mergeFailure(Throwable current, Throwable failure) {
        if (current == null) {
            return failure;
        }
        if (!isFatal(current) && isFatal(failure)) {
            addSuppressedSafely(failure, current);
            return failure;
        }
        if (current != failure) {
            addSuppressedSafely(current, failure);
        }
        return current;
    }

    private static boolean isFatal(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }

    private static void addSuppressedSafely(Throwable target, Throwable failure) {
        if (target == failure) {
            return;
        }
        try {
            target.addSuppressed(failure);
        } catch (Throwable ignored) {
            // 诊断附加失败不得覆盖主失败对象。
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("queue quiesce failed", failure);
    }
}
