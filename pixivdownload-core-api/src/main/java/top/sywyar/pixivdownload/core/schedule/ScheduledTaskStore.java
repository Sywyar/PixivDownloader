package top.sywyar.pixivdownload.core.schedule;

import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 核心 owned 的计划任务语义 Store。
 *
 * <p>插件托管的调度宿主只依赖本接口，不触达 mapper、连接或自由 SQL。所有运行转换都以
 * {@code claimToken + stateVersion} 做 CAS；管理员变更一旦推进版本，旧运行就不能覆盖它。
 * 凭证 secret 只经专用裸标量方法读取，绝不进入任务、pending 或元数据投影。
 */
public interface ScheduledTaskStore {

    List<ScheduledTask> findAll();

    ScheduledTask findById(long id);

    int countAll();

    /** 只返回 canonical、已启用、未挂起、无在途认领且已到期的任务。 */
    List<ScheduledTask> findDue(long now);

    List<ScheduledTask> findByCredentialAccount(String policyOwnerPluginId,
                                                 String policyId,
                                                 String accountKey);

    /** 创建 canonical 任务并返回数据库生成的 id。 */
    long create(ScheduledTaskCreate command);

    /** 周期 tick 的原子 due + 可运行条件复核与认领。 */
    Optional<ScheduleRunToken> tryQueueDue(long id,
                                           long expectedStateVersion,
                                           String claimToken,
                                           long now);

    /** run-now 的原子可运行条件复核与认领。 */
    Optional<ScheduleRunToken> tryQueueNow(long id,
                                           long expectedStateVersion,
                                           String claimToken);

    /** 仅同一个 QUEUED token 能进入 RUNNING；返回转换后的新版本 token。 */
    Optional<ScheduleRunToken> startRun(long id, ScheduleRunToken queuedToken);

    /**
     * 正常完成 CAS。outcome、下一次运行时间与 checkpoint 在同一条写入中提交；并发挂起会令 CAS 失败。
     */
    OptionalLong completeRun(long id,
                             ScheduleRunToken runningToken,
                             ScheduleRunCompletion completion);

    /**
     * 取消收尾 CAS，不提交 checkpoint。若行已被并发挂起，以数据库中的挂起原因决定 outcome/code/detail，
     * 避免旧执行方覆盖管理员意图；否则使用调用方提供的 CANCELLED、ERROR 或 INTERRUPTED。
     */
    OptionalLong finishCancelled(long id,
                                 ScheduleRunToken activeToken,
                                 ScheduleLastOutcome outcome,
                                 long finishedTime,
                                 String outcomeCode,
                                 String outcomeMessage,
                                 Long nextRunTime);

    /** 异步提交失败等尚未开始执行的 QUEUED 认领释放。 */
    OptionalLong releaseQueued(long id, ScheduleRunToken queuedToken, Long nextRunTime);

    /** 精确版本挂起；在途 QUEUED/RUNNING 会原子转为 CANCEL_REQUESTED 并保留 claim token。 */
    OptionalLong suspend(long id,
                         long expectedStateVersion,
                         ScheduleSuspendReason reason,
                         String code,
                         String detailJson);

    /** reason + code + stateVersion 精确匹配且无在途运行时才恢复。 */
    OptionalLong resume(long id,
                        long expectedStateVersion,
                        ScheduleSuspendReason expectedReason,
                        String expectedCode,
                        Long nextRunTime);

    /** 同一 credential policy/account 下尚未挂起的任务批量挂起。 */
    int suspendByCredentialAccount(String policyOwnerPluginId,
                                   String policyId,
                                   String accountKey,
                                   ScheduleSuspendReason reason,
                                   String code,
                                   String detailJson);

    /** 仅精确匹配 reason + code 的同账号任务批量恢复。 */
    int resumeByCredentialAccount(String policyOwnerPluginId,
                                  String policyId,
                                  String accountKey,
                                  ScheduleSuspendReason expectedReason,
                                  String expectedCode,
                                  Long nextRunTime);

    /**
     * CAS 编辑已经当前 owner 校验的定义，并在同一事务清空 checkpoint 与该任务全部中性 pending。
     * 同时解除可由有效定义修复的迁移、来源或执行器不可用挂起；人工、凭证、策略与 QUIESCED 挂起保持不变。
     */
    OptionalLong updateDefinition(long id,
                                  long expectedStateVersion,
                                  ScheduleTaskDefinitionUpdate update);

    OptionalLong updateEnabled(long id, long expectedStateVersion, boolean enabled);

    OptionalLong updateProxy(long id, long expectedStateVersion, String proxySnapshot);

    /** 事务聚合删除任务、credential 与中性 pending；版本不匹配时什么都不删。 */
    boolean deleteAggregate(long id, long expectedStateVersion);

    /** 启动恢复：把上次进程遗留的认领标为 INTERRUPTED 并立即重新到期。 */
    int recoverInterruptedRuns(long now);

    OptionalLong bindCredential(long taskId,
                                long expectedStateVersion,
                                String policyOwnerPluginId,
                                String policyId,
                                String accountKey,
                                String policyStateJson,
                                String secret,
                                String secretReference,
                                long updatedTime);

    OptionalLong removeCredential(long taskId,
                                  long expectedStateVersion,
                                  String expectedPolicyOwnerPluginId,
                                  String expectedPolicyId);

    OptionalLong updateCredentialPolicyState(long taskId,
                                             long expectedStateVersion,
                                             String expectedPolicyOwnerPluginId,
                                             String expectedPolicyId,
                                             String expectedPolicyStateJson,
                                             String newPolicyStateJson,
                                             long updatedTime);

    ScheduledTaskCredential findCredentialMetadata(long taskId);

    /** 敏感 secret 专用裸标量读取；owner/id 不匹配时返回 null。 */
    String findCredentialSecret(long taskId, String policyOwnerPluginId, String policyId);

    int upsertPendingWork(ScheduledPendingWork pendingWork);

    ScheduledPendingWork findPendingWork(long taskId, String workType, String workId);

    List<ScheduledPendingWork> listPendingWork(long taskId);

    /** 原子累加并返回新 attempts；行不存在时返回 null。 */
    Integer incrementPendingAttempts(long taskId, String workType, String workId, long now);

    int deletePendingWork(long taskId, String workType, String workId);

    /**
     * 管理员在任务空闲时清除一条 pending，并在同一事务推进 task stateVersion。
     * 版本推进与 durable claim 争用同一 CAS，保证清除和新一轮运行只会有一方成功。
     */
    OptionalLong clearPendingWork(long taskId,
                                  long expectedStateVersion,
                                  String workType,
                                  String workId);

    int deleteAllPendingWork(long taskId);
}
