package top.sywyar.pixivdownload.notificationbase;

/** 已渲染并持久化的管理员站内信。时间字段均为 epoch millis。 */
public record NotificationMessage(
        String id,
        String category,
        String severity,
        String scenarioId,
        String title,
        String body,
        String actionUrl,
        long createdTime,
        Long readTime
) {
}
