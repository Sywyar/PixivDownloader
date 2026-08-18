package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.SwingUtilities;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Swing-side compatibility facade over the toolkit-neutral host lifecycle contract. */
public final class SwingBackendLifecycle {
    public enum State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
    public record Snapshot(State state, Throwable lastError) {}
    @FunctionalInterface public interface Listener { void onStateChanged(Snapshot snapshot); }

    private static final Map<Listener, AutoCloseable> SUBSCRIPTIONS = new ConcurrentHashMap<>();

    private SwingBackendLifecycle() {}

    public static Snapshot snapshot() { return map(SwingHost.host().backendSnapshot()); }
    public static State state() { return snapshot().state(); }
    public static boolean startAsync() { return startAsync(null); }
    public static boolean startAsync(Runnable afterStart) { return SwingHost.host().startBackend(onEdt(afterStart)); }
    public static boolean stopAsync(Runnable afterStop) { return SwingHost.host().stopBackend(onEdt(afterStop)); }
    public static boolean restartAsync() { return restartAsync(null); }
    public static boolean restartAsync(Runnable afterRestart) { return SwingHost.host().restartBackend(onEdt(afterRestart)); }

    public static void addListener(Listener listener) {
        AutoCloseable subscription = SwingHost.host().subscribeBackend(value -> onEdt(() -> listener.onStateChanged(map(value))).run());
        AutoCloseable previous = SUBSCRIPTIONS.put(listener, subscription);
        close(previous);
    }

    public static void removeListener(Listener listener) { close(SUBSCRIPTIONS.remove(listener)); }

    private static Snapshot map(DesktopUiHost.BackendSnapshot value) {
        return new Snapshot(State.valueOf(value.state().name()), value.lastError());
    }

    private static Runnable onEdt(Runnable action) {
        if (action == null) return null;
        return () -> {
            if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
        };
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (Exception ignored) {}
    }
}
