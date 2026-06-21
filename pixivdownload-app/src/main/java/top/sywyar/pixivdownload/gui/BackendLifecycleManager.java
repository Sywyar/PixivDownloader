package top.sywyar.pixivdownload.gui;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import top.sywyar.pixivdownload.PixivDownloadApplication;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages the embedded Spring Boot backend for GUI actions that need
 * exclusive access to SQLite.
 */
public final class BackendLifecycleManager {

    public enum State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        FAILED
    }

    public record Snapshot(State state, Throwable error) {}

    @FunctionalInterface
    public interface Listener {
        void onStateChanged(Snapshot snapshot);
    }

    private static final Object LOCK = new Object();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile ConfigurableApplicationContext context;
    private static volatile State state = State.STOPPED;
    private static volatile Throwable lastError;
    private static volatile String[] applicationArgs = new String[0];
    private static volatile Consumer<Throwable> startupFailureHandler = error -> {};

    private BackendLifecycleManager() {}

    public static void configure(String[] args, Consumer<Throwable> failureHandler) {
        applicationArgs = args == null ? new String[0] : Arrays.copyOf(args, args.length);
        startupFailureHandler = failureHandler != null ? failureHandler : error -> {};
    }

    public static Snapshot snapshot() {
        return new Snapshot(state, lastError);
    }

    public static State state() {
        return state;
    }

    public static boolean isRunning() {
        return state == State.RUNNING;
    }

    public static void addListener(Listener listener) {
        Listener safeListener = Objects.requireNonNull(listener, "listener");
        LISTENERS.add(safeListener);
        notifyListener(safeListener, snapshot());
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean startAsync() {
        return startAsync(null);
    }

    public static boolean startAsync(Runnable afterStart) {
        synchronized (LOCK) {
            if (state == State.STARTING || state == State.RUNNING) {
                return false;
            }
            updateState(State.STARTING, null);
        }

        Thread starter = new Thread(() -> {
            try {
                context = PixivDownloadApplication.start(applicationArgs);
                updateState(State.RUNNING, null);
                runOnEdt(afterStart);
            } catch (Throwable error) {
                context = null;
                updateState(State.FAILED, error);
                startupFailureHandler.accept(error);
            }
        }, "spring-main");
        starter.setDaemon(false);
        starter.start();
        return true;
    }

    public static boolean stopAsync(Runnable afterStop) {
        synchronized (LOCK) {
            if (state == State.STOPPING || state == State.STARTING) {
                return false;
            }
            if (state == State.STOPPED || (state == State.FAILED && context == null)) {
                runOnEdt(afterStop);
                return true;
            }
            updateState(State.STOPPING, null);
        }

        Thread stopper = new Thread(() -> {
            try {
                ConfigurableApplicationContext current = context;
                context = null;
                if (current != null) {
                    SpringApplication.exit(current);
                    current.close();
                }
                updateState(State.STOPPED, null);
            } catch (Throwable error) {
                updateState(State.FAILED, error);
            } finally {
                runOnEdt(afterStop);
            }
        }, "spring-stop");
        stopper.setDaemon(false);
        stopper.start();
        return true;
    }

    public static boolean restartAsync() {
        return restartAsync(null);
    }

    public static boolean restartAsync(Runnable afterRestart) {
        Snapshot current = snapshot();
        if (current.state == State.STARTING || current.state == State.STOPPING) {
            return false;
        }
        return stopAsync(() -> startAsync(afterRestart));
    }

    private static void updateState(State newState, Throwable error) {
        state = newState;
        lastError = error;
        Snapshot snapshot = new Snapshot(newState, error);
        for (Listener listener : LISTENERS) {
            notifyListener(listener, snapshot);
        }
    }

    private static void notifyListener(Listener listener, Snapshot snapshot) {
        runOnEdt(() -> listener.onStateChanged(snapshot));
    }

    private static void runOnEdt(Runnable action) {
        if (action == null) {
            return;
        }
        if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
