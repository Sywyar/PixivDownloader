package top.sywyar.pixivdownload.core.schedule;

import java.util.List;

/**
 * 核心拥有的单轮作品执行数据端口，供定时任务与手动批次复用执行引擎。
 * 实现负责各自运行身份的隔离与保留期限；凭据只供短生命周期执行读取，不进入公开投影。
 * 存储不可用必须抛错，不能将写入失败或读取失败解释为空结果。
 */
public interface ScheduledWorkStore {
    /**
     * 写入完整待重试作品；失败必须阻止本轮提交成功。
     *
     * @param pendingWork 当前运行及作品的重试记录
     * @return 受影响行数
     */
    int upsertPendingWork(ScheduledPendingWork pendingWork);

    /**
     * 返回本运行的待重试作品，不跨运行读取。
     *
     * @param taskId 运行标识
     * @return 本运行的待重试作品
     */
    List<ScheduledPendingWork> listPendingWork(long taskId);

    /**
     * 按作品复合身份删除本运行的待重试记录。
     *
     * @param taskId 运行标识
     * @param workType 作品类型
     * @param workId 作品标识
     * @return 受影响行数
     */
    int deletePendingWork(long taskId, String workType, String workId);

    /**
     * 返回精确 owner / policy 绑定的有序凭据副本，缺席时返回空集合。
     *
     * @param taskId 运行标识
     * @param owner 策略提供者标识
     * @param policy 凭据策略标识
     * @return 按优先顺序排列的候选凭据
     */
    List<ScheduledCredentialValue> findCredentialCandidates(long taskId, String owner, String policy);

    /**
     * 返回绑定同一策略账号的任务事实，供风控关联展示。
     *
     * @param policyOwnerPluginId 策略提供者标识
     * @param policyId 凭据策略标识
     * @param accountKey 策略内的账号标识
     * @return 绑定该账号的任务
     */
    List<ScheduledTask> findByCredentialAccount(String policyOwnerPluginId, String policyId, String accountKey);
}
