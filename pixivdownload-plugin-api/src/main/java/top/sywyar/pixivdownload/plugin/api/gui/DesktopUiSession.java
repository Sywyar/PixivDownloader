package top.sywyar.pixivdownload.plugin.api.gui;

/** Active process-lifetime desktop UI session. */
public interface DesktopUiSession extends AutoCloseable {
    /** Severity for messages delivered to the active desktop UI. */
    enum MessageLevel {
        /** Informational message. */
        INFO,
        /** Warning message. */
        WARNING,
        /** Error message. */
        ERROR
    }

    /** Brings the existing desktop UI to the foreground. */
    void activate();

    /**
     * Displays a user-visible message.
     *
     * @param level message severity
     * @param title message title
     * @param message message body
     */
    void showMessage(MessageLevel level, String title, String message);

    /** Releases process-lifetime UI resources. */
    @Override default void close() {}
}
