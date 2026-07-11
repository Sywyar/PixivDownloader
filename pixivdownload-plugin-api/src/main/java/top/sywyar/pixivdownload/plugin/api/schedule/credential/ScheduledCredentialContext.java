package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionContext;

/** 凭证策略在绑定验证或运行前主动探活时收到的受控上下文。 */
public interface ScheduledCredentialContext extends ScheduledExecutionContext {

    Purpose purpose();

    enum Purpose {
        BIND,
        RUN_START
    }
}
