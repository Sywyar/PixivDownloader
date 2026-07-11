package top.sywyar.pixivdownload.plugin.api.schedule.work;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionContext;

/** 单作品同步执行上下文。成功返回前必须已完成文件、历史、关系与必要后置动作。 */
public interface ScheduledWorkContext extends ScheduledExecutionContext {
}
