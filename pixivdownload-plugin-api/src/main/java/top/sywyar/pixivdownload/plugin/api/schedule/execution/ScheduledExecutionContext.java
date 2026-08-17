package top.sywyar.pixivdownload.plugin.api.schedule.execution;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;

/** 来源、作品、凭证与 Guard 上下文共享的宿主控制面。 */
public interface ScheduledExecutionContext {

    /**
     * 返回任务。
     *
     * @return 方法返回的 {@code ScheduledTaskDefinition} 实例
     */
    ScheduledTaskDefinition task();

    /**
     * 已解析为 DIRECT 或 PROXY，且同一轮所有能力调用收到同一个对象。
     *
     * @return 方法返回的 {@code ScheduledNetworkRoute} 实例
     */
    ScheduledNetworkRoute route();

    /**
     * 当前能力调用独享的短生命周期句柄；宿主不会把同一可关闭句柄共享给并发调用。调用方必须遵守
     * {@link ScheduledCredentialHandle} 的不保留、不返回、不提交和不抛出凭据材料约束。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    ScheduledCredentialHandle credential();

    /**
     * 返回取消状态。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    ScheduledCancellation cancellation();
}
