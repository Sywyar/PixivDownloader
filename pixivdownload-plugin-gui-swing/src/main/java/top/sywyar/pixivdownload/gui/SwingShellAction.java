package top.sywyar.pixivdownload.gui;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 每个界面 owner 只保留一个后台打开动作，失败提示交回 EDT。 */
public final class SwingShellAction {
    private final AtomicBoolean inFlight = new AtomicBoolean();

    public void run(
            String threadName,
            Action action,
            Consumer<Exception> reportFailure
    ) {
        if (!inFlight.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> reportFailure.accept(failure));
            } finally {
                inFlight.set(false);
            }
        }, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }
}
