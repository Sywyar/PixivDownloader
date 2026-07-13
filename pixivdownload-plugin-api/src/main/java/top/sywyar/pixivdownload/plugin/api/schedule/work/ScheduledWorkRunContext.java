package top.sywyar.pixivdownload.plugin.api.schedule.work;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionContext;

/**
 * 单个作品类型的轮末上下文。它只暴露宿主控制面与安全计数，不携带插件私有对象或本轮作品集合。
 */
public interface ScheduledWorkRunContext extends ScheduledExecutionContext {

    /** 当前轮末动作对应的 canonical work type。 */
    String workType();

    /** 当前 work type 在本轮 drain 完成后的最终记账统计。 */
    ScheduledWorkRunStatistics statistics();
}
