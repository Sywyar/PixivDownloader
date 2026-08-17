package top.sywyar.pixivdownload.plugin.api.schedule.work;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;

import java.util.Map;

/**
 * 插件 child context 中的同步作品执行器。手动队列与计划任务应复用其底层 blocking 执行接缝。作品结果、
 * 实时状态及任何抛出异常均不得包含原始凭据或可逆派生材料。
 */
public interface ScheduledWorkExecutor {

    /**
     * 返回作品类型。
     *
     * @return 方法返回的字符串
     */
    String workType();

    /**
     * 执行对应操作。
     *
     * @param work 工作项
     * @param context 上下文
     * @return 方法返回的 {@code ScheduledWorkResult} 实例
     * @throws ScheduledExecutionException 执行失败时抛出
     */
    ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context)
            throws ScheduledExecutionException;

    /**
     * 本执行器允许宿主同时调用 {@link #execute(ScheduledWork, ScheduledWorkContext)} 的容量上限。
     * 宿主仍拥有线程池、任务级 {@code maxInFlight} 与背压，并取二者较小值；执行器只声明自身下游资源的硬上限。
     * 默认不额外收紧。
     *
     * @return 方法返回的数值
     */
    default int maxConcurrency() {
        return Integer.MAX_VALUE;
    }

    /**
     * 同步提供作品进入异步通知时的安全展示投影。实现必须快速返回，不得访问网络、文件或其它外部系统，
     * 不得产生外部副作用，也不得读取、派生或保留任何凭证材料。默认不提供展示信息。
     *
     * @param work 已通过计划作品信封校验的作品
     * @return 仅含受控展示 token 与安全 HTTPS 引用的纯值投影
     */
    default ScheduledWorkNotificationPresentation notificationPresentation(ScheduledWork work) {
        return ScheduledWorkNotificationPresentation.empty();
    }

    /**
     * 本执行器在一轮来源发现正常结束、全部在途作品排空且失败作品已耐久记入 pending 后的轮末动作。
     * 宿主在 {@code RUN_END} Guard 与 checkpoint 提交前、仍持有全部相关 owner 执行租约时，对每个本轮所需
     * work type 恰好调用一次。实现需要系列合订等轮级后处理时可覆盖；默认无动作。
     *
     * <p>若轮末动作属于 best-effort，实现应在边界内自行收敛失败；未收敛的
     * {@link ScheduledExecutionException} 会使本轮失败且不得提交 checkpoint。
     *
     * @param context 上下文
     * @throws top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException 执行失败时抛出
     */
    default void finishRun(ScheduledWorkRunContext context)
            throws ScheduledExecutionException {
    }

    /**
     * 本轮在来源发现、Guard、取消或正常轮末动作中异常终止后的纯内存清理回调。宿主只会在作品全部
     * 排空后、仍持有相关 owner 执行租约时调用；每个所需 work type 至多一次。实现不得在这里执行网络
     * 请求或业务后处理，且必须允许部分初始化。异常只按 best-effort 收敛，不会覆盖本轮原始失败。
     * 默认无动作。
     *
     * @param task 任务
     */
    default void abortRun(ScheduledTaskDefinition task) {
    }

    /**
     * 非阻塞读取一件作品当前可公开的运行状态属性，供宿主队列投影叠加。返回值只能包含安全机器码、原始数值或
     * 受控 token，不得包含凭证、异常、已本地化文案或插件对象；宿主仍须执行数量、UTF-8 大小与凭证材料校验后复制。
     *
     * <p>宿主只会在同一轮该作品的已校验 {@link ScheduledWorkResult} 明确声明
     * {@link ScheduledWorkResult#liveStatusAvailable()} 为 {@code true} 时调用本方法；执行器不得依赖宿主
     * 按作品类型或结果属性猜测是否需要状态叠加。
     *
     * 默认返回空的不可变 Map，表示不提供实时状态。
     *
     * @param key 键
     * @return 方法返回的映射
     */
    default Map<String, String> status(ScheduledWorkKey key) {
        return Map.of();
    }
}
