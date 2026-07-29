package top.sywyar.pixivdownload.plugin.api.schedule.execution;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;

/** 来源、作品、凭证与 Guard 上下文共享的宿主控制面。 */
public interface ScheduledExecutionContext {

    ScheduledTaskDefinition task();

    /** 已解析为 DIRECT 或 PROXY，且同一轮所有能力调用收到同一个对象。 */
    ScheduledNetworkRoute route();

    /**
     * 当前能力调用独享的短生命周期句柄；宿主不会把同一可关闭句柄共享给并发调用。调用方必须遵守
     * {@link ScheduledCredentialHandle} 的不保留、不返回、不提交和不抛出凭据材料约束。
     */
    ScheduledCredentialHandle credential();

    ScheduledCancellation cancellation();
}
