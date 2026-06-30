package top.sywyar.pixivdownload.gui;

import java.util.Objects;

/**
 * 进程退出协调器：把 GUI 进程最终退出的清退步骤串成一条确定顺序，不依赖 JVM 多 shutdown hook 的未定义执行顺序。
 *
 * <p>固定顺序（{@link #shutdown()} 内顺序执行、同一线程）：
 * <ol>
 *   <li><b>禁止新的 backend start / restart</b>（{@code forbidStep}）——杜绝退出过程中又拉起新后端，并使在途 start 失效；</li>
 *   <li><b>清理 starter 注册</b>（{@code registrationStep.close()}）——把 {@link BackendLifecycleManager} 对 PROCESS
 *       bootstrap 会话的静态引用恢复为默认，释放对会话的静态捕获（<b>不</b>撤销退出闩锁）；</li>
 *   <li><b>同步关闭 Spring backend context</b>（{@code contextCloseStep}，有限超时，返回 {@link BackendLifecycleManager.CloseAttempt}）
 *       ——等其 Bean / controller 拆卸完成；</li>
 *   <li><b>仅当 context 已确认关闭</b>（{@link BackendLifecycleManager.CloseResult#isContextConfirmedClosed()}）才
 *       {@code sessionCloseStep.close()}——停止 / 卸载全部插件、释放 classloader / 句柄。</li>
 * </ol>
 *
 * <p>关键不变量：Spring context 清退<b>总是先于</b> PF4J classloader 卸载。即时确认关闭时同步关闭 session；
 * {@link BackendLifecycleManager.CloseResult#TIMED_OUT} 时把 session 关闭挂到同一 operation 的 completion，待 Spring
 * 真正关闭后再顺序执行；最终 {@link BackendLifecycleManager.CloseResult#FAILED} 时不卸载 PF4J，由 OS 在进程退出时释放。
 * 每个步骤隔离，{@link #shutdown()} 与 session 关闭均幂等；不新增线程池，迟到完成回调由既有 lifecycle worker 执行。
 */
public final class BackendShutdownCoordinator {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BackendShutdownCoordinator.class);

    /** 步骤 3：同步、有限超时关闭 Spring backend context，返回可判断的关闭结果。 */
    @FunctionalInterface
    public interface ContextCloseStep {
        BackendLifecycleManager.CloseAttempt closeContext(long timeoutMillis);
    }

    private final Runnable forbidStep;
    private final AutoCloseable registrationStep;
    private final ContextCloseStep contextCloseStep;
    private final long contextCloseTimeoutMillis;
    private final AutoCloseable sessionCloseStep;

    private volatile boolean done;
    private volatile boolean sessionClosed;

    /**
     * @param forbidStep                 步骤 1：禁止后续 backend start / restart（使在途 start 失效）
     * @param registrationStep           步骤 2：清理 starter 注册（释放对 bootstrap 会话的静态引用；不撤销退出闩锁）
     * @param contextCloseStep           步骤 3：同步、有限超时关闭 Spring backend context（必须先于步骤 4 完成或确认未关闭）
     * @param contextCloseTimeoutMillis  步骤 3 的有限超时（必须为正）
     * @param sessionCloseStep           步骤 4：仅当步骤 3 确认关闭后才执行——关闭 PROCESS bootstrap 会话（PF4J 停 / 卸载、释放 classloader）
     */
    public BackendShutdownCoordinator(Runnable forbidStep, AutoCloseable registrationStep,
                                      ContextCloseStep contextCloseStep, long contextCloseTimeoutMillis,
                                      AutoCloseable sessionCloseStep) {
        this.forbidStep = Objects.requireNonNull(forbidStep, "forbidStep");
        this.registrationStep = Objects.requireNonNull(registrationStep, "registrationStep");
        this.contextCloseStep = Objects.requireNonNull(contextCloseStep, "contextCloseStep");
        this.sessionCloseStep = Objects.requireNonNull(sessionCloseStep, "sessionCloseStep");
        if (contextCloseTimeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "contextCloseTimeoutMillis must be positive: " + contextCloseTimeoutMillis);
        }
        this.contextCloseTimeoutMillis = contextCloseTimeoutMillis;
    }

    /**
     * 按固定顺序执行清退。幂等——重复调用只执行一次。任一步抛错隔离、不阻断后续。
     *
     * <p>步骤 4（PF4J session 关闭）仅在步骤 3 已确认关闭时执行。TIMED_OUT 时注册 completion 回调延后执行；
     * FAILED 时跳过，保证任何情况下都不与 Spring teardown 并发卸载 classloader。
     */
    public synchronized void shutdown() {
        if (done) {
            return;
        }
        done = true;
        runQuietly(forbidStep, "forbid backend lifecycle");
        closeQuietly(registrationStep, "backend starter registration");

        BackendLifecycleManager.CloseAttempt attempt;
        try {
            attempt = Objects.requireNonNull(
                    contextCloseStep.closeContext(contextCloseTimeoutMillis), "context close attempt");
        } catch (RuntimeException e) {
            log.warn("Error during process shutdown step (backend context): {}", e.getMessage(), e);
            attempt = BackendLifecycleManager.CloseAttempt.completed(BackendLifecycleManager.CloseResult.FAILED);
        }

        BackendLifecycleManager.CloseResult current = attempt.result();
        if (current.isContextConfirmedClosed()) {
            closeSessionOnce();
            return;
        }
        if (current != BackendLifecycleManager.CloseResult.TIMED_OUT) {
            logUnconfirmedClose(current, false);
            return;
        }

        log.warn("Deferring PF4J session close: backend context did not finish within {} ms. "
                        + "The same lifecycle worker will close the session only after Spring teardown is confirmed.",
                contextCloseTimeoutMillis);
        attempt.completion().whenComplete((terminal, failure) -> {
            if (failure != null) {
                log.warn("Backend context close completion failed; PF4J session remains open for OS cleanup: {}",
                        failure.getMessage(), failure);
            } else if (terminal != null && terminal.isContextConfirmedClosed()) {
                closeSessionOnce();
            } else {
                logUnconfirmedClose(terminal, true);
            }
        });
    }

    private synchronized void closeSessionOnce() {
        if (sessionClosed) {
            return;
        }
        sessionClosed = true;
        closeQuietly(sessionCloseStep, "plugin bootstrap session");
    }

    private void logUnconfirmedClose(BackendLifecycleManager.CloseResult result, boolean deferred) {
        log.warn("Skipping PF4J session close: backend context was not confirmed closed "
                        + "(result={}, deferred={}, timeoutMs={}). Residual plugin resources remain loaded "
                        + "and will be released by OS on process exit.",
                result, deferred, contextCloseTimeoutMillis);
    }

    private static void runQuietly(Runnable step, String description) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("Error during process shutdown step ({}): {}", description, e.getMessage(), e);
        }
    }

    private static void closeQuietly(AutoCloseable step, String description) {
        try {
            step.close();
        } catch (Exception e) {
            log.warn("Error during process shutdown step ({}): {}", description, e.getMessage(), e);
        }
    }
}
