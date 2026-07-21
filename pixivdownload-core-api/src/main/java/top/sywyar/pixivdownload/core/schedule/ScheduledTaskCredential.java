package top.sywyar.pixivdownload.core.schedule;

/**
 * 任务凭证的非敏感元数据投影。
 *
 * <p>本模型故意不含 secret；secret 只允许经 {@link ScheduledTaskStore#findCredentialSecret} 的专用裸标量入口读取。
 */
public record ScheduledTaskCredential(
        long taskId,
        String policyOwnerPluginId,
        String policyId,
        String accountKey,
        String policyStateJson,
        String secretReference,
        Long updatedTime
) {
}
