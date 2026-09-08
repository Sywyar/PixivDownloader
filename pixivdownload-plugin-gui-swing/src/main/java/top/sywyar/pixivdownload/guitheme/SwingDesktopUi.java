package top.sywyar.pixivdownload.guitheme;

import top.sywyar.pixivdownload.gui.MainFrame;
import top.sywyar.pixivdownload.gui.SystemTrayManager;
import top.sywyar.pixivdownload.gui.config.GuiConfigContributionAggregator;
import top.sywyar.pixivdownload.gui.entry.GuiWebEntryContributionAggregator;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.gui.onboarding.GuiOnboardingContributionAggregator;
import top.sywyar.pixivdownload.gui.theme.GuiThemeManager;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class SwingDesktopUi {
    private SwingDesktopUi() {}

    static DesktopUiSession launch(DesktopUiContext context) throws Exception {
        SwingHost.install(context);
        AtomicReference<MainFrame> frameRef = new AtomicReference<>();
        Runnable create = () -> {
            var startup = context.startupPluginSnapshots();
            GuiThemeManager.applyBeforeFirstWindow(context.host().applicationConfig(),
                    GuiThemeManager.readPersistedThemeId(context.host().applicationConfig()), startup);
            MainFrame frame = new MainFrame(context.serverPort(), context.rootFolder(), context.configPath(),
                    () -> GuiConfigContributionAggregator.fromRegisteredPlugins(
                            context.currentPluginSnapshots()),
                    () -> GuiWebEntryContributionAggregator.fromRegisteredPlugins(
                            context.currentPluginSnapshots()),
                    GuiOnboardingContributionAggregator.fromRegisteredPlugins(startup));
            frameRef.set(frame);
            boolean trayInstalled = SystemTrayManager.install(frame, context.rootFolder());
            frame.setTrayAvailable(trayInstalled);
            if (!context.startupLaunch() || !trayInstalled) frame.showWindow();
        };
        try {
            runAndWait(create);
            return new Session(Objects.requireNonNull(frameRef.get(), "frame"), context);
        } catch (Exception | Error failure) {
            try {
                runAndWait(() -> {
                    SystemTrayManager.uninstall();
                    MainFrame frame = frameRef.get();
                    if (frame != null) frame.dispose();
                });
            } catch (Exception | Error cleanupFailure) {
                if (cleanupFailure instanceof VirtualMachineError fatal) throw fatal;
                if (cleanupFailure instanceof ThreadDeath fatal) throw fatal;
                if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void runAndWait(Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    private record Session(MainFrame frame, DesktopUiContext context) implements DesktopUiSession {
        @Override public void activate() { onEdt(frame::showWindow); }
        @Override public void showMessage(MessageLevel level, String title, String message) {
            int type = switch (level == null ? MessageLevel.INFO : level) {
                case INFO -> JOptionPane.INFORMATION_MESSAGE;
                case WARNING -> JOptionPane.WARNING_MESSAGE;
                case ERROR -> JOptionPane.ERROR_MESSAGE;
            };
            onEdt(() -> JOptionPane.showMessageDialog(frame, message, title, type));
        }
        @Override public void close() {
            try {
                runAndWait(() -> {
                    SystemTrayManager.uninstall();
                    try { frame.persistWindowState(); } finally { frame.dispose(); }
                });
            } catch (Exception failure) {
                throw new IllegalStateException("Unable to close the Swing desktop UI", failure);
            }
        }
        private void onEdt(Runnable action) {
            Runnable guarded = () -> {
                try { action.run(); } catch (Throwable failure) { context.reportFailure(failure); }
            };
            if (SwingUtilities.isEventDispatchThread()) guarded.run(); else SwingUtilities.invokeLater(guarded);
        }
    }
}
