package top.sywyar.pixivdownload.notification;

/**
 * 通知场景的中性严重程度。它只描述业务语义，不绑定任何发送介质。
 * 邮件、推送等介质可在自身边界内映射到颜色、优先级或其它展示形式。
 */
public enum NotificationSeverity {
    INFO,
    WARNING,
    ERROR
}
