package top.sywyar.pixivdownload.plugin.api.schedule.guard;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionContext;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;

import java.util.Optional;

/** Guard 在固定检查点收到的只读上下文。失败检查点以安全投影代替插件 {@link Throwable}。 */
public interface ScheduledGuardContext extends ScheduledExecutionContext {

    ScheduledGuardPoint point();

    long attemptedWorkCount();

    /**
     * 当前已解析 credential policy 的非敏感、不透明耐久状态；本轮没有 policy 时为空。
     * Guard 只读该快照，不得把它当作凭证 secret，也不得直接修改宿主持久化。
     */
    Optional<String> credentialPolicyStateJson();

    /** 仅 {@link ScheduledGuardPoint#RUN_FAILURE} 非空。 */
    ScheduledFailure failure();
}
