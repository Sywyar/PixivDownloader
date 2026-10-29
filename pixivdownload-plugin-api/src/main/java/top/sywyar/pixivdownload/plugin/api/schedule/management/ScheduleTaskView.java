package top.sywyar.pixivdownload.plugin.api.schedule.management;

import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;

/**
 * 计划任务对外视图（列表 / 详情）。
 *
 * <p>凭证只投影为中性的 {@code credentialPolicy} 元数据，secret 绝不回显。
 * {@code proxy} 是任务级单独代理（{@code host:port}，非凭证、不含账号口令），可回显供前端编辑；
 * {@code null} 表示使用执行计划确定的默认路由。
 *
 * <p>{@code runState} 是持久化的在途运行态（{@code QUEUED} / {@code RUNNING} /
 * {@code CANCEL_REQUESTED} / {@code null}）；同进程内的协调态只作为即时刷新覆盖。前端据它与正交的
 * {@code lastOutcome}、{@code suspendReason} 和 {@code enabled} 共同决定状态灯。
 *
 * <p>{@code runStartedTime} 仅保留给旧前端的忙碌哨兵；中断恢复以持久化的
 * {@code lastOutcome=INTERRUPTED} 为事实来源。版本化 checkpoint 与凭证 secret 均不暴露给前端。
 * @param id 持久任务标识
 * @param name 用户设置的任务名称
 * @param enabled 任务的启用开关，与运行态和挂起态正交
 * @param type 管理入口使用的兼容类型；没有兼容映射时为来源类型
 * @param sourceType 来源贡献的稳定类型
 * @param sourceOwnerPluginId 来源所属插件标识
 * @param definitionSchema 来源定义的 Schema 标识
 * @param definitionVersion 来源定义的版本
 * @param paramsJson 不含凭据的来源定义 JSON
 * @param presentationJson 不含凭据的持久展示 JSON
 * @param presentation 来源提供的安全展示投影
 * @param sourceAvailable 当前精确来源 publication 是否可用
 * @param sourceActivationToken 当前来源的激活令牌；不可用时为空，不能跨代复用
 * @param triggerKind 触发类型
 * @param intervalMinutes 间隔触发的分钟数；非间隔触发时可为空
 * @param cronExpr Cron 表达式；非 Cron 触发时可为空
 * @param credentialPolicy 不含凭据的绑定及策略状态
 * @param proxy 任务单独代理的主机和端口；为空时使用执行计划确定的默认路由
 * @param nextRunTime 下一次计划运行时间戳（毫秒），未安排时为空
 * @param lastRunTime 最近一次开始运行的时间戳（毫秒），未运行时为空
 * @param lastStatus 管理入口使用的兼容结果或挂起状态
 * @param lastMessage 兼容的安全结果或挂起说明
 * @param runStartedTime 兼容的忙碌哨兵；非在途任务时为空
 * @param pendingRetryArmed 兼容的待重试标记；当前投影固定为假
 * @param lastOutcome 最近一次执行结果，与当前运行态正交
 * @param outcomeCode 最近执行结果的稳定机器码
 * @param outcomeMessage 最近执行结果的安全说明
 * @param suspendReason 当前挂起原因；未挂起时为空
 * @param suspendCode 挂起原因的稳定机器码
 * @param suspendDetailJson 不含凭据的挂起详情 JSON
 * @param runState 当前在途运行态；无在途执行时为空
 * @param storageVersion 持久化结构版本
 * @param stateVersion 任务状态版本，用于并发修改校验
 * @param createdTime 创建时间戳（毫秒）
 */
public record ScheduleTaskView(
        Long id,
        String name,
        boolean enabled,
        String type,
        String sourceType,
        String sourceOwnerPluginId,
        String definitionSchema,
        Integer definitionVersion,
        String paramsJson,
        String presentationJson,
        ScheduledTaskPresentation presentation,
        boolean sourceAvailable,
        String sourceActivationToken,
        String triggerKind,
        Integer intervalMinutes,
        String cronExpr,
        ScheduleCredentialPolicyView credentialPolicy,
        String proxy,
        Long nextRunTime,
        Long lastRunTime,
        String lastStatus,
        String lastMessage,
        Long runStartedTime,
        boolean pendingRetryArmed,
        String lastOutcome,
        String outcomeCode,
        String outcomeMessage,
        String suspendReason,
        String suspendCode,
        String suspendDetailJson,
        String runState,
        int storageVersion,
        long stateVersion,
        long createdTime
) {
}
