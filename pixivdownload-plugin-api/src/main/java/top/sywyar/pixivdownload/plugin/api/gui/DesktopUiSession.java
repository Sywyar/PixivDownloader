package top.sywyar.pixivdownload.plugin.api.gui;

/** 活动的进程级桌面界面会话。 */
public interface DesktopUiSession extends AutoCloseable {
    /** 发送给活动桌面界面的消息级别。 */
    enum MessageLevel {
        /** 信息消息。 */
        INFO,
        /** 警告消息。 */
        WARNING,
        /** 错误消息。 */
        ERROR
    }

    /** 将现有桌面界面置于前台。 */
    void activate();

    /**
     * 显示一条用户可见消息。
     *
     * @param level 消息级别
     * @param title 消息标题
     * @param message 消息正文
     */
    void showMessage(MessageLevel level, String title, String message);

    /** 释放进程级界面资源。 */
    @Override default void close() {}
}
