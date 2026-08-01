package top.sywyar.pixivdownload.plugin.api.schedule.guard;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;

/**
 * 插件贡献的站点风险、限流、挑战和凭证状态 Guard。调用位置由宿主固定；Guard 结果及任何抛出异常均不得包含
 * 原始凭据或可逆派生材料。
 */
public interface ScheduledExecutionGuard {

    /** 全局唯一、建议带插件命名空间的 Guard id。 */
    String guardId();

    /** 返回稳定决定及可选的受控证据。 */
    ScheduledGuardResult evaluate(ScheduledGuardContext context)
            throws ScheduledExecutionException;
}
