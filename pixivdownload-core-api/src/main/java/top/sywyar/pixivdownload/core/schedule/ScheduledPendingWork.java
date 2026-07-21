package top.sywyar.pixivdownload.core.schedule;

/**
 * 可跨重启和插件 reload 重试的中性作品 envelope。
 *
 * <p>{@code workId} 始终按 TEXT 保真；作品身份由 {@code workType + workId} 共同构成。
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
