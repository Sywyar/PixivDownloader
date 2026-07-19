package top.sywyar.pixivdownload.plugin.api.schedule.work;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;

import java.util.Map;

/** 插件 child context 中的同步作品执行器。手动队列与计划任务应复用其底层 blocking 执行接缝。 */
public interface ScheduledWorkExecutor {

    String workType();

    ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context)
            throws ScheduledExecutionException;

    /**
     * 本执行器允许宿主同时调用 {@link #execute(ScheduledWork, ScheduledWorkContext)} 的容量上限。
     * 宿主仍拥有线程池、任务级 {@code maxInFlight} 与背压，并取二者较小值；执行器只声明自身下游资源的硬上限。
     * 默认不额外收紧。
     */
    default int maxConcurrency() {
        return Integer.MAX_VALUE;
    }

    /**
     * 本执行器在一轮来源发现正常结束、全部在途作品排空且失败作品已耐久记入 pending 后的轮末动作。
     * 宿主在 {@code RUN_END} Guard 与 checkpoint 提交前、仍持有全部相关 owner 执行租约时，对每个本轮所需
     * work type 恰好调用一次。实现需要系列合订等轮级后处理时可覆盖；默认无动作。
     *
     * <p>若轮末动作属于 best-effort，实现应在边界内自行收敛失败；未收敛的
     * {@link ScheduledExecutionException} 会使本轮失败且不得提交 checkpoint。
     */
    default void finishRun(ScheduledWorkRunContext context)
            throws ScheduledExecutionException {
    }

    /**
     * 本轮在来源发现、Guard、取消或正常轮末动作中异常终止后的纯内存清理回调。宿主只会在作品全部
     * 排空后、仍持有相关 owner 执行租约时调用；每个所需 work type 至多一次。实现不得在这里执行网络
     * 请求或业务后处理，且必须允许部分初始化。异常只按 best-effort 收敛，不会覆盖本轮原始失败。
     * 默认无动作。
     */
    default void abortRun(ScheduledTaskDefinition task) {
    }

    /**
     * 非阻塞读取一件作品当前可公开的运行状态属性，供宿主队列投影叠加。返回值只能包含安全机器码、原始数值或
     * 受控 token，不得包含凭证、异常、已本地化文案或插件对象；宿主仍须执行数量、UTF-8 大小与凭证材料校验后复制。
     * 默认返回空的不可变 Map，表示不提供实时状态。
     */
    default Map<String, String> status(ScheduledWorkKey key) {
        return Map.of();
    }
}
