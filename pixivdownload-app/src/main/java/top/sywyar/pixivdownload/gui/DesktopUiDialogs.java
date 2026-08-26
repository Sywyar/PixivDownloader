package top.sywyar.pixivdownload.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Label;
import java.awt.Panel;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 宿主启动流程使用且不依赖 Swing 的呈现桥。 */
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

    /** 在桌面 provider 尚不存在时使用 JDK AWT 呈现唯一的恢复入口。 */
    static boolean showBootstrapConfirmDialog(String title, String message, String confirmLabel) {
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("Desktop UI bootstrap message in a headless environment: {}: {}", title, message);
            return false;
        }
        if (EventQueue.isDispatchThread()) {
            return showBootstrapConfirmDialogOnEventThread(title, message, confirmLabel);
        }
        AtomicBoolean confirmed = new AtomicBoolean();
        try {
            EventQueue.invokeAndWait(() -> confirmed.set(
                    showBootstrapConfirmDialogOnEventThread(title, message, confirmLabel)));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            log.warn("Desktop UI bootstrap dialog was interrupted", failure);
        } catch (InvocationTargetException failure) {
            log.warn("Desktop UI bootstrap dialog failed", failure.getCause());
        }
        return confirmed.get();
    }

    private static boolean showBootstrapConfirmDialogOnEventThread(
            String title, String message, String confirmLabel) {
        AtomicBoolean confirmed = new AtomicBoolean();
        Dialog dialog = new Dialog((Frame) null, title, true);
        dialog.setLayout(new BorderLayout());

        Panel body = new Panel(new FlowLayout(FlowLayout.CENTER, 24, 20));
        body.add(new Label(message));
        dialog.add(body, BorderLayout.CENTER);

        Button confirm = new Button(confirmLabel);
        confirm.addActionListener(event -> {
            confirmed.set(true);
            dialog.dispose();
        });
        Panel actions = new Panel(new FlowLayout(FlowLayout.CENTER, 12, 12));
        actions.add(confirm);
        dialog.add(actions, BorderLayout.SOUTH);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                dialog.dispose();
            }
        });

        dialog.pack();
        dialog.setLocationRelativeTo(null);
        confirm.requestFocus();
        dialog.setVisible(true);
        return confirmed.get();
    }
}
