package top.sywyar.pixivdownload.core.schedule;

/**
 * 可跨重启和插件 reload 重试的中性作品 envelope。
 *
 * <p>{@code workId} 始终按 TEXT 保真；作品身份由 {@code workType + workId} 共同构成。
 *
 * @param taskId 任务标识
 * @param workType 作品类型
 * @param workId 作品标识
 * @param payloadSchema 作品载荷的格式标识
 * @param payloadVersion 载荷版本
 * @param payloadJson 作品载荷 JSON
 * @param relationsJson 作品关系 JSON
 * @param presentationJson 安全展示快照 JSON
 * @param reasonCode 待重试原因机器码
 * @param reasonDetailJson 安全失败详情 JSON
 * @param attempts 尝试次数
 * @param firstSeenTime 首次登记为待处理作品的时间
 * @param lastAttemptTime 最近一次尝试的时间
 */
public record ScheduledPendingWork(
        long taskId,
        String workType,
        String workId,
        String payloadSchema,
        int payloadVersion,
        String payloadJson,
        String relationsJson,
        String presentationJson,
        String reasonCode,
        String reasonDetailJson,
        int attempts,
        Long firstSeenTime,
        Long lastAttemptTime
) {
    /**
     * 创建 {@code ScheduledPendingWork} 实例。
     *
     * @param taskId 任务标识
     * @param workType 工作类型
     * @param workId 作品标识
     * @param payloadSchema 载荷模式定义
     * @param payloadVersion 载荷版本
     * @param payloadJson 载荷JSON
     * @param relationsJson 关系列表JSON
     * @param presentationJson 展示信息JSON
     * @param reasonCode 原因代码
     * @param reasonDetailJson 原因详情JSON
     * @param attempts 尝试次数
     * @param firstSeenTime {@code firstSeenTime} 对应的值
     * @param lastAttemptTime {@code lastAttemptTime} 对应的值
     */
    public ScheduledPendingWork {
        requireText(workType, "workType");
        requireText(workId, "workId");
        requireText(payloadSchema, "payloadSchema");
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("payloadVersion must be positive");
        }
        if (payloadJson == null) {
            throw new IllegalArgumentException("payloadJson must not be null");
        }
        relationsJson = relationsJson == null ? "[]" : relationsJson;
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
