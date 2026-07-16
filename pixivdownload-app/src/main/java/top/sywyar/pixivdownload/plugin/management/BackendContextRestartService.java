package top.sywyar.pixivdownload.plugin.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.gui.BackendLifecycleManager;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 接受由管理员发起的后端 Spring 上下文重启请求。只有桌面生命周期管理器明确处于
 * {@link BackendLifecycleManager.State#RUNNING} 时才接受；headless 启动不由该管理器持有上下文，
 * 因而保持 STOPPED 并被拒绝，绝不会从管理 API 拉起第二个上下文。
 */
@Service
public class BackendContextRestartService {

    private static final long RESPONSE_GRACE_MILLIS = 500L;
    private static final Logger log = LoggerFactory.getLogger(BackendContextRestartService.class);

    private final Supplier<BackendLifecycleManager.State> stateSupplier;
    private final BooleanSupplier restartAction;
    private final DelayedExecutor delayedExecutor;
    private final RestartFailureReporter failureReporter;
    private final long delayMillis;
    private final AtomicBoolean pending = new AtomicBoolean();

    @Autowired
    public BackendContextRestartService(AppMessages messages) {
        this(BackendLifecycleManager::state, BackendLifecycleManager::restartAsync,
                BackendContextRestartService::startDaemonAfterDelay,
                failure -> {
                    String message = messages.getForLog("plugin.manage.log.backend-restart-rejected");
                    if (failure == null) {
                        log.warn(message);
                    } else {
                        log.warn(message, failure);
                    }
                },
                RESPONSE_GRACE_MILLIS);
    }

    BackendContextRestartService(Supplier<BackendLifecycleManager.State> stateSupplier,
                                 BooleanSupplier restartAction,
                                 DelayedExecutor delayedExecutor,
                                 RestartFailureReporter failureReporter,
                                 long delayMillis) {
        this.stateSupplier = Objects.requireNonNull(stateSupplier, "stateSupplier");
        this.restartAction = Objects.requireNonNull(restartAction, "restartAction");
        this.delayedExecutor = Objects.requireNonNull(delayedExecutor, "delayedExecutor");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        if (delayMillis < 0L) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }
        this.delayMillis = delayMillis;
    }

    /**
     * 接受重启请求并在短暂宽限后调用生命周期管理器，使 HTTP 成功响应有机会先返回给浏览器。
     */
    public BackendRestartResult requestRestart() {
        BackendLifecycleManager.State state = stateSupplier.get();
        if (state != BackendLifecycleManager.State.RUNNING) {
            throw new PluginManagementException(PluginManagementErrorCode.BACKEND_RESTART_UNAVAILABLE,
                    null, "restart-backend", null,
                    "Backend context restart is unavailable while lifecycle state is " + state);
        }
        if (!pending.compareAndSet(false, true)) {
            throw new PluginManagementException(PluginManagementErrorCode.BACKEND_RESTART_PENDING,
                    null, "restart-backend", null, "Backend context restart is already pending");
        }
        try {
            delayedExecutor.execute(() -> {
                try {
                    if (!restartAction.getAsBoolean()) {
                        failureReporter.report(null);
                    }
                } catch (RuntimeException e) {
                    failureReporter.report(e);
                } finally {
                    pending.set(false);
                }
            }, delayMillis);
        } catch (RuntimeException e) {
            pending.set(false);
            throw new PluginManagementException(PluginManagementErrorCode.BACKEND_RESTART_UNAVAILABLE,
                    null, "restart-backend", null, "Backend context restart could not be scheduled");
        }
        return new BackendRestartResult(true);
    }

    private static void startDaemonAfterDelay(Runnable action, long delayMillis) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
                action.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "backend-context-restart-request");
        thread.setDaemon(true);
        thread.start();
    }

    @FunctionalInterface
    interface DelayedExecutor {
        void execute(Runnable action, long delayMillis);
    }

    @FunctionalInterface
    interface RestartFailureReporter {
        void report(Throwable failure);
    }

    /** 已接受的后端重启请求。 */
    public record BackendRestartResult(boolean accepted) {
    }
}
