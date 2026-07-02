package top.sywyar.pixivdownload.push;

import top.sywyar.pixivdownload.notification.NotificationSeverity;

/**
 * 推送消息的介质内严重级别。共享通知场景使用 {@link NotificationSeverity}；本枚举保留在 push 边界内，
 * 作为过渡适配与各 {@link PushChannel} 映射颜色、Bark level 等表现形式。
 */
public enum PushLevel {
    INFO,
    WARNING,
    ERROR;

    /** 将中性通知严重程度映射为 push 介质内部级别。 */
    public static PushLevel from(NotificationSeverity severity) {
        if (severity == null) {
            return INFO;
        }
        return switch (severity) {
            case INFO -> INFO;
            case WARNING -> WARNING;
            case ERROR -> ERROR;
        };
    }
}
