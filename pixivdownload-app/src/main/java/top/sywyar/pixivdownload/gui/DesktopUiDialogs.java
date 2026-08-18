package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;

/** Presentation bridge used by host-owned startup flows without depending on Swing. */
final class DesktopUiDialogs {
    static final int INFORMATION_MESSAGE = 1;
    static final int WARNING_MESSAGE = 2;
    static final int ERROR_MESSAGE = 3;
    private static final Logger log = LoggerFactory.getLogger(DesktopUiDialogs.class);

    private DesktopUiDialogs() {}

    static void invokeLater(Runnable action) {
        if (action != null) action.run();
    }

    static void showMessageDialog(Object owner, Object message, String title, int messageType) {
        DesktopUiSession ui = owner instanceof DesktopUiSession session ? session : GuiLauncher.activeUi();
        if (ui == null) {
            log.warn("Desktop UI message before provider startup: {}: {}", title, message);
            return;
        }
        DesktopUiSession.MessageLevel level = switch (messageType) {
            case WARNING_MESSAGE -> DesktopUiSession.MessageLevel.WARNING;
            case ERROR_MESSAGE -> DesktopUiSession.MessageLevel.ERROR;
            default -> DesktopUiSession.MessageLevel.INFO;
        };
        ui.showMessage(level, title, String.valueOf(message));
    }

    static void show(Object owner, String title, String message, Throwable failure) {
        if (failure != null) log.error("{}: {}", title, message, failure);
        showMessageDialog(owner, message, title, ERROR_MESSAGE);
    }
}
