package top.sywyar.pixivdownload.plugin.api.schedule.management;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskPresentation;

/**
 * 计划任务所绑定凭证策略的非敏感投影；可用态只描述当前 publication，不授权后续操作。
 *
 * @param ownerPluginId 策略所属插件标识；未绑定时可为空
 * @param policyId 策略标识；未绑定时可为空
 * @param accountKey 非敏感账号身份；未绑定时可为空
 * @param bound 是否存在持久凭证绑定；策略缺席时仍可为真
 * @param available 当前精确策略 publication 是否可用
 * @param publicationId 当前 publication 标识；不可用时为空
 * @param statusCode 策略提供的安全状态码；无状态时为空
 * @param acknowledgedEventTime 已确认事件的时间戳（毫秒）；没有确认时为空
 */
public record ScheduleCredentialPolicyView(
        String ownerPluginId,
        String policyId,
        String accountKey,
        boolean bound,
        boolean available,
        Long publicationId,
        String statusCode,
        Long acknowledgedEventTime
) {

    /**
     * 保留绑定身份并投影策略缺席、停止或换代后的不可用状态。
     * @param ownerPluginId 策略所属插件标识
     * @param policyId 策略标识
     * @param accountKey 非敏感账号身份
     * @param bound 是否仍有持久绑定
     * @return 不包含活动 publication 或策略状态的投影
     */
    public static ScheduleCredentialPolicyView unavailable(
            String ownerPluginId,
            String policyId,
            String accountKey,
            boolean bound) {
        return new ScheduleCredentialPolicyView(
                ownerPluginId, policyId, accountKey, bound,
                false, null, null, null);
    }

    /**
     * 根据宿主已取得的精确策略 publication 生成当前投影。
     * @param ownerPluginId 策略所属插件标识
     * @param policyId 策略标识
     * @param accountKey 非敏感账号身份
     * @param bound 是否存在持久绑定
     * @param publicationId 当前可用的 publication 标识
     * @param presentation 策略提供的非敏感状态；为空时使用空状态
     * @return 绑定到所给 publication 的可用投影
     */
    public static ScheduleCredentialPolicyView available(
            String ownerPluginId,
            String policyId,
            String accountKey,
            boolean bound,
            long publicationId,
            ScheduledCredentialTaskPresentation presentation) {
        ScheduledCredentialTaskPresentation safe = presentation == null
                ? ScheduledCredentialTaskPresentation.empty()
                : presentation;
        return new ScheduleCredentialPolicyView(
                ownerPluginId, policyId, accountKey, bound,
                true, publicationId, safe.statusCode(), safe.acknowledgedEventTime());
    }
}
