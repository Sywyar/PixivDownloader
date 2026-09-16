package top.sywyar.pixivdownload.plugin.api.schedule.work;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionContext;

/** 单作品同步执行上下文。成功返回前必须已完成文件、历史、关系与必要后置动作。 */
public interface ScheduledWorkContext extends ScheduledExecutionContext {
    /**
     * 本轮由管理员为当前作品选择的操作码；由作品执行器解释，宿主不解释业务含义。
     * 未选择时为空。操作按失败原因隔离，只在本轮执行租约内有效，不沿用到下一轮。
     *
     * @param reasonCode 需要用户处理的失败原因码
     * @return 当前作品或本轮已授权的操作码；未授权时为空
     */
    default java.util.Optional<String> userAction(String reasonCode) {
        return java.util.Optional.empty();
    }
}
