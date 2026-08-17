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

    /**
     * 返回全部。
     *
     * @return 方法返回的列表
     */
    List<ScheduledTask> findAll();

    /**
     * 返回对应值。
     *
     * @param id 标识
     * @return 方法返回的 {@code ScheduledTask} 实例
     */
    ScheduledTask findById(long id);

    /**
     * 返回数量全部。
     *
     * @return 方法返回的数值
     */
    int countAll();

    /**
     * 只返回 canonical、已启用、未挂起、无在途认领且已到期的任务。
     *
     * @param now 当前时间
     * @return 方法返回的列表
     */
    List<ScheduledTask> findDue(long now);

    /**
     * 返回对应值。
     *
     * @param policyOwnerPluginId 策略所有者插件标识
     * @param policyId 策略标识
     * @param accountKey 账号键
     * @return 方法返回的列表
     */
    List<ScheduledTask> findByCredentialAccount(String policyOwnerPluginId,
                                                 String policyId,
                                                 String accountKey);

    /**
     * 创建 canonical 任务并返回数据库生成的 id。
     *
     * @param command 命令
     * @return 方法返回的数值
     */
    long create(ScheduledTaskCreate command);

    /**
     * 周期 tick 的原子 due + 可运行条件复核与认领。
     *
     * @param id 标识
     * @param expectedStateVersion 期望值状态版本
     * @param claimToken 认领令牌
     * @param now 当前时间
     * @return 匹配的可选值
     */
    Optional<ScheduleRunToken> tryQueueDue(long id,
                                           long expectedStateVersion,
                                           String claimToken,
                                           long now);

    /**
     * run-now 的原子可运行条件复核与认领。
     *
     * @param id 标识
     * @param expectedStateVersion 期望值状态版本
     * @param claimToken 认领令牌
     * @return 匹配的可选值
     */
    Optional<ScheduleRunToken> tryQueueNow(long id,
                                           long expectedStateVersion,
                                           String claimToken);

    /**
     * 仅同一个 QUEUED token 能进入 RUNNING；返回转换后的新版本 token。
     *
     * @param id 标识
     * @param queuedToken 排队状态令牌
     * @return 匹配的可选值
     */
    Optional<ScheduleRunToken> startRun(long id, ScheduleRunToken queuedToken);

    /**
     * 正常完成 CAS。outcome、下一次运行时间与 checkpoint 在同一条写入中提交；并发挂起会令 CAS 失败。
     *
     * @param id 标识
     * @param runningToken 运行中令牌
     * @param completion 完成结果
     * @return 匹配的可选值
     */
    OptionalLong completeRun(long id,
                             ScheduleRunToken runningToken,
                             ScheduleRunCompletion completion);

    /**
     * 取消收尾 CAS，不提交 checkpoint。若行已被并发挂起，以数据库中的挂起原因决定 outcome/code/detail，
     * 避免旧执行方覆盖管理员意图；否则使用调用方提供的 CANCELLED、ERROR 或 INTERRUPTED。
     *
     * @param id 标识
     * @param activeToken 激活状态令牌
     * @param outcome 执行结果
     * @param finishedTime 完成时间
     * @param outcomeCode 结果代码
     * @param outcomeMessage 结果消息
     * @param nextRunTime 下次值运行时间
     * @return 匹配的可选值
     */
    OptionalLong finishCancelled(long id,
                                 ScheduleRunToken activeToken,
                                 ScheduleLastOutcome outcome,
                                 long finishedTime,
                                 String outcomeCode,
                                 String outcomeMessage,
                                 Long nextRunTime);

    /**
     * 异步提交失败等尚未开始执行的 QUEUED 认领释放。
     *
     * @param id 标识
     * @param queuedToken 排队状态令牌
     * @param nextRunTime 下次值运行时间
     * @return 匹配的可选值
     */
    OptionalLong releaseQueued(long id, ScheduleRunToken queuedToken, Long nextRunTime);

    /**
     * 精确版本挂起；在途 QUEUED/RUNNING 会原子转为 CANCEL_REQUESTED 并保留 claim token。
     *
     * @param id 标识
     * @param expectedStateVersion 期望值状态版本
     * @param reason 原因
     * @param code 代码
     * @param detailJson 详情JSON
     * @return 匹配的可选值
     */
    OptionalLong suspend(long id,
                         long expectedStateVersion,
                         ScheduleSuspendReason reason,
                         String code,
                         String detailJson);

    /**
     * reason + code + stateVersion 精确匹配且无在途运行时才恢复。
     *
     * @param id 标识
     * @param expectedStateVersion 期望值状态版本
     * @param expectedReason 期望值原因
     * @param expectedCode 期望值代码
     * @param nextRunTime 下次值运行时间
     * @return 匹配的可选值
     */
    OptionalLong resume(long id,
                        long expectedStateVersion,
                        ScheduleSuspendReason expectedReason,
                        String expectedCode,
                        Long nextRunTime);

    /**
     * CAS 编辑已经当前 owner 校验的定义，并在同一事务清空 checkpoint 与该任务全部中性 pending。
     * 同时解除可由有效定义修复的迁移、来源或执行器不可用挂起；人工、凭证、策略与 QUIESCED 挂起保持不变。
     *
     * @param id 标识
     * @param expectedStateVersion 期望值状态版本
     * @param update 更新内容
     * @return 匹配的可选值
     */
    OptionalLong updateDefinition(long id,
                                  long expectedStateVersion,
                                  ScheduleTaskDefinitionUpdate update);

    /**
     * 更新启用状态。
     *
     * @param id 标识
     * @param expectedStateVersion 期望值状态版本
     * @param enabled 启用状态
     * @return 匹配的可选值
     */
    OptionalLong updateEnabled(long id, long expectedStateVersion, boolean enabled);

    /**
     * 更新代理。
     *
     * @param id 标识
     * @param expectedStateVersion 期望值状态版本
     * @param proxySnapshot 代理快照
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    OptionalLong updateProxy(long id, long expectedStateVersion, String proxySnapshot);

    /**
     * 事务聚合删除任务、credential 与中性 pending；版本不匹配时什么都不删。
     *
     * @param id 标识
     * @param expectedStateVersion 期望值状态版本
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean deleteAggregate(long id, long expectedStateVersion);

    /**
     * 执行对应操作并返回结果。
     *
     * @param taskId 任务标识
     * @param expectedStateVersion 期望值状态版本
     * @param policyOwnerPluginId 策略所有者插件标识
     * @param policyId 策略标识
     * @param accountKey 账号键
     * @param policyStateJson 策略状态JSON
     * @param secret 密钥
     * @param secretReference 密钥引用
     * @param updatedTime 更新时间
     * @return 匹配的可选值
     */
    OptionalLong bindCredential(long taskId,
                                long expectedStateVersion,
                                String policyOwnerPluginId,
                                String policyId,
                                String accountKey,
                                String policyStateJson,
                                String secret,
                                String secretReference,
                                long updatedTime);

    /**
     * 注销对应能力。
     *
     * @param taskId 任务标识
     * @param expectedStateVersion 期望值状态版本
     * @param expectedPolicyOwnerPluginId 期望值策略所有者插件标识
     * @param expectedPolicyId 期望值策略标识
     * @return 匹配的可选值
     */
    OptionalLong removeCredential(long taskId,
                                  long expectedStateVersion,
                                  String expectedPolicyOwnerPluginId,
                                  String expectedPolicyId);

    /**
     * 更新凭证策略状态。
     *
     * @param taskId 任务标识
     * @param expectedStateVersion 期望值状态版本
     * @param expectedPolicyOwnerPluginId 期望值策略所有者插件标识
     * @param expectedPolicyId 期望值策略标识
     * @param expectedPolicyStateJson 期望值策略状态JSON
     * @param newPolicyStateJson 新值策略状态JSON
     * @param updatedTime 更新时间
     * @return 匹配的可选值
     */
    OptionalLong updateCredentialPolicyState(long taskId,
                                             long expectedStateVersion,
                                             String expectedPolicyOwnerPluginId,
                                             String expectedPolicyId,
                                             String expectedPolicyStateJson,
                                             String newPolicyStateJson,
                                             long updatedTime);

    /**
     * 敏感 secret 专用裸标量读取；owner/id 不匹配时返回 null。
     *
     * @param taskId 任务标识
     * @param policyOwnerPluginId 策略所有者插件标识
     * @param policyId 策略标识
     * @return 方法返回的字符串
     */
    String findCredentialSecret(long taskId, String policyOwnerPluginId, String policyId);

    /**
     * 更新待处理项作品。
     *
     * @param pendingWork 待处理项作品
     * @return 方法返回的数值
     */
    int upsertPendingWork(ScheduledPendingWork pendingWork);

    /**
     * 查询并返回列表待处理项作品。
     *
     * @param taskId 任务标识
     * @return 方法返回的列表
     */
    List<ScheduledPendingWork> listPendingWork(long taskId);

    /**
     * 执行对应操作并返回结果。
     *
     * @param taskId 任务标识
     * @param workType 工作类型
     * @param workId 作品标识
     * @return 方法返回的数值
     */
    int deletePendingWork(long taskId, String workType, String workId);

    /**
     * 管理员在任务空闲时清除一条 pending，并在同一事务推进 task stateVersion。
     * 版本推进与 durable claim 争用同一 CAS，保证清除和新一轮运行只会有一方成功。
     *
     * @param taskId 任务标识
     * @param expectedStateVersion 期望值状态版本
     * @param workType 工作类型
     * @param workId 作品标识
     * @return 匹配的可选值
     */
    OptionalLong clearPendingWork(long taskId,
                                  long expectedStateVersion,
                                  String workType,
                                  String workId);
}
