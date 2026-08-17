package top.sywyar.pixivdownload.plugin.api.schedule.credential;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionContext;

/** 凭证策略在绑定验证或运行前主动探活时收到的受控上下文。 */
public interface ScheduledCredentialContext extends ScheduledExecutionContext {

    /**
     * 返回对应值。
     *
     * @return 方法返回的 {@code Purpose} 实例
     */
    Purpose purpose();

    /**
     * 创建 {@code Purpose} 实例。
     */
    enum Purpose {
        /**
         * 表示 {@code BIND} 状态。
         */
        BIND,
        /**
         * 表示 {@code RUN_START}。
         */
        RUN_START
    }
}
