package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 进程退出协调器、Registration 清理协议与后端并发状态管理的测试：
 * <ul>
 *   <li>{@link BackendShutdownCoordinator}：固定清退顺序、幂等、步骤隔离，以及「context 确认关闭才关 session」的超时语义
 *      （{@link BackendLifecycleManager.CloseResult#TIMED_OUT} / {@link BackendLifecycleManager.CloseResult#FAILED} 跳过 session 关闭）；</li>
 *   <li>{@link BackendLifecycleManager.Registration}：close 恢复默认 starter / 参数 / 失败处理器、代际安全、幂等；
 *       <b>不</b>撤销进程退出闩锁（{@code shutdownInitiated}）。</li>
 *   <li>{@link BackendLifecycleManager#forbidLifecycle()}：置位后 start / restart / configure 全拒绝（configure 抛
 *       {@link IllegalStateException}）、且不被 Registration.close 撤销。</li>
 *   <li>{@link BackendLifecycleManager#closeBackendContext(long)}：STARTING（迟到 context 由启动线程自行关闭）、
 *       STOPPING（有限等待既有 stop、不并发拆第二个 close）、超时（TIMED_OUT、保持 STOPPING 不伪装 STOPPED）三条退出路径，
 *       以及 start operation 用启动时捕获的 starter / args（启动途中 configure 不切换）。</li>
 * </ul>
 * 位于 gui 包以访问 {@link BackendLifecycleManager} 的 package-private 测试观测（{@code resetForTests} /
 * {@code usesDefaultStarter} / {@code isShutdownInitiated}）。每个用例经 {@code resetForTests} 在 @AfterEach 复位，
 * 确保跨用例零静态残留；并发用例在 finally 无条件释放 latch / 关闭 context / 关闭 executor。
 */
@DisplayName("进程退出协调器 / Registration / 并发状态管理（forbid 不撤销、STARTING/STOPPING/timeout、捕获）")
class BackendProcessShutdownTest {

    @BeforeEach
    void forceHeadless() {
        // 让 runOnEdt 在测试线程内联执行（确定性），避免 EDT 未泵送致回调链路挂起。
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void resetStaticState() {
        BackendLifecycleManager.resetForTests();
    }

    // ── BackendShutdownCoordinator ───────────────────────────────────────────

    @Test
    @DisplayName("进程退出顺序：禁止启动 → 清理注册 → 关 context → 关 session（严格序，context 确认关闭才关 session）")
    void shutdownOrderIsForbidThenRegistrationThenContextThenSession() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        BackendShutdownCoordinator coordinator = new BackendShutdownCoordinator(
                () -> order.add("forbid"),
                () -> order.add("registration"),
                timeout -> { order.add("context"); return BackendLifecycleManager.CloseAttempt.completed(BackendLifecycleManager.CloseResult.CLOSED); },
                1_000L,
                () -> order.add("session"));

        coordinator.shutdown();

        assertThat(order).containsExactly("forbid", "registration", "context", "session");
    }

    @Test
    @DisplayName("shutdown 幂等：重复调用只执行一次四步")
    void shutdownIsIdempotent() {
        AtomicInteger count = new AtomicInteger();
        BackendShutdownCoordinator coordinator = new BackendShutdownCoordinator(
                count::incrementAndGet,
                () -> count.incrementAndGet(),
                timeout -> { count.incrementAndGet(); return BackendLifecycleManager.CloseAttempt.completed(BackendLifecycleManager.CloseResult.CLOSED); },
                1_000L,
                () -> count.incrementAndGet());

        coordinator.shutdown();
        coordinator.shutdown();
        coordinator.shutdown();

        assertThat(count.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("步骤隔离：任一步抛错不阻断后续；contextClose 抛错按 FAILED 处理、跳过 session")
    void stepsAreIsolatedAndContinueAfterFailure() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        BackendShutdownCoordinator coordinator = new BackendShutdownCoordinator(
                () -> { order.add("forbid"); throw new IllegalStateException("boom"); },
                () -> { throw new RuntimeException("registration boom"); },
                timeout -> { order.add("context"); throw new RuntimeException("context boom"); },
                1_000L,
                () -> order.add("session"));

        coordinator.shutdown();

        // forbid / registration / context 各自尝试；context 抛错 → FAILED → session 跳过
        assertThat(order).containsExactly("forbid", "context");
    }

    @Test
    @DisplayName("contextCloseStep 返回 CLOSED → 关闭 session（context 已确认关闭）")
    void contextClosedRunsSessionClose() {
        AtomicInteger sessionCloses = new AtomicInteger();
        BackendShutdownCoordinator coordinator = new BackendShutdownCoordinator(
                () -> {}, () -> {},
                timeout -> BackendLifecycleManager.CloseAttempt.completed(BackendLifecycleManager.CloseResult.CLOSED),
                1_000L,
                () -> sessionCloses.incrementAndGet());

        coordinator.shutdown();

        assertThat(sessionCloses.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("contextCloseStep 返回 ALREADY_STOPPED → 关闭 session（无 context 需关闭，下游安全）")
    void contextAlreadyStoppedRunsSessionClose() {
        AtomicInteger sessionCloses = new AtomicInteger();
        BackendShutdownCoordinator coordinator = new BackendShutdownCoordinator(
                () -> {}, () -> {},
                timeout -> BackendLifecycleManager.CloseAttempt.completed(BackendLifecycleManager.CloseResult.ALREADY_STOPPED),
                1_000L,
                () -> sessionCloses.incrementAndGet());

        coordinator.shutdown();

        assertThat(sessionCloses.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("contextCloseStep 返回 TIMED_OUT → 跳过 session 关闭（不并发卸载仍在拆 context 的 PF4J）")
    void contextCloseTimedOutSkipsSessionClose() {
        AtomicInteger sessionCloses = new AtomicInteger();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        BackendShutdownCoordinator coordinator = new BackendShutdownCoordinator(
                () -> order.add("forbid"),
                () -> order.add("registration"),
                timeout -> { order.add("context"); return BackendLifecycleManager.CloseAttempt.completed(BackendLifecycleManager.CloseResult.TIMED_OUT); },
                1_000L,
                () -> { order.add("session"); sessionCloses.incrementAndGet(); });

        coordinator.shutdown();

        assertThat(order).containsExactly("forbid", "registration", "context");
        assertThat(sessionCloses.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("contextCloseStep 返回 FAILED → 跳过 session 关闭（context 状态未知，保守不卸载 PF4J）")
    void contextCloseFailedSkipsSessionClose() {
        AtomicInteger sessionCloses = new AtomicInteger();
        BackendShutdownCoordinator coordinator = new BackendShutdownCoordinator(
                () -> {}, () -> {},
                timeout -> BackendLifecycleManager.CloseAttempt.completed(BackendLifecycleManager.CloseResult.FAILED),
                1_000L,
                () -> sessionCloses.incrementAndGet());

        coordinator.shutdown();

        assertThat(sessionCloses.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("协调器 contextCloseTimeoutMillis <= 0 拒绝（避免无限等待）")
    void coordinatorRejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> new BackendShutdownCoordinator(
                () -> {}, () -> {}, timeout -> BackendLifecycleManager.CloseAttempt.completed(BackendLifecycleManager.CloseResult.CLOSED), 0L, () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackendShutdownCoordinator(
                () -> {}, () -> {}, timeout -> BackendLifecycleManager.CloseAttempt.completed(BackendLifecycleManager.CloseResult.CLOSED), -1L, () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Registration 清理协议（代际安全、不撤销退出闩锁）──────────────────────

    @Test
    @DisplayName("Registration.close() 在仍是当前配置时恢复默认 starter / 参数 / 失败处理器（不触碰退出闩锁）")
    void registrationCloseRestoresDefaultsWhenCurrent() {
        AtomicBoolean customCalled = new AtomicBoolean();
        BackendLifecycleManager.BackendStarter custom = args -> {
            customCalled.set(true);
            return null;
        };
        BackendLifecycleManager.Registration registration =
                BackendLifecycleManager.configure(new String[]{"--x"}, t -> {}, custom);

        assertThat(BackendLifecycleManager.usesDefaultStarter()).isFalse();
        assertThat(registration.isActive()).isTrue();

        registration.close();

        assertThat(BackendLifecycleManager.usesDefaultStarter()).isTrue();
        assertThat(registration.isActive()).isFalse();
        // 退出闩锁从未置位，close 也不触碰它
        assertThat(BackendLifecycleManager.isShutdownInitiated()).isFalse();
    }

    @Test
    @DisplayName("代际安全：旧 Registration.close() 不误清后注册的新配置（新 configure 取代后旧句柄关闭为 no-op）")
    void oldRegistrationCloseDoesNotClearNewerConfiguration() {
        BackendLifecycleManager.BackendStarter custom = args -> null;
        BackendLifecycleManager.Registration first =
                BackendLifecycleManager.configure(null, null, custom);
        BackendLifecycleManager.Registration second =
                BackendLifecycleManager.configure(null, null, custom);
        long secondGeneration = BackendLifecycleManager.currentStarterGeneration();

        first.close(); // 旧句柄——已被 second 取代，不应恢复默认

        assertThat(first.isActive()).isFalse();
        assertThat(second.isActive()).isTrue();
        assertThat(BackendLifecycleManager.usesDefaultStarter())
                .as("旧句柄关闭不应清掉新配置的 starter")
                .isFalse();

        second.close(); // 当前句柄——恢复默认
        assertThat(BackendLifecycleManager.usesDefaultStarter()).isTrue();
        // 第二次关闭后代际已前进（stale 句柄不可再匹配）
        assertThat(BackendLifecycleManager.currentStarterGeneration()).isGreaterThan(secondGeneration);
    }

    @Test
    @DisplayName("null starter 不静默保留旧回调——显式回落默认 starter")
    void nullStarterFallsBackToDefault() {
        BackendLifecycleManager.configure(null, null, args -> null); // 先装一个自定义 starter
        assertThat(BackendLifecycleManager.usesDefaultStarter()).isFalse();

        BackendLifecycleManager.Registration registration =
                BackendLifecycleManager.configure(null, null, null); // null → 显式默认，不保留上一个

        assertThat(BackendLifecycleManager.usesDefaultStarter()).isTrue();
        registration.close();
    }

    @Test
    @DisplayName("多次关闭 Registration 安全（幂等）")
    void registrationCloseIsIdempotent() {
        BackendLifecycleManager.Registration registration =
                BackendLifecycleManager.configure(null, null, args -> null);
        registration.close();
        registration.close();
        registration.close();
        assertThat(BackendLifecycleManager.usesDefaultStarter()).isTrue();
    }

    @Test
    @DisplayName("正常非 shutdown 场景：Registration.close 恢复默认 starter，但不改变进程退出状态")
    void registrationCloseDoesNotChangeShutdownState() {
        assertThat(BackendLifecycleManager.isShutdownInitiated()).isFalse();
        BackendLifecycleManager.Registration registration =
                BackendLifecycleManager.configure(new String[]{"--a"}, null, args -> null);
        registration.close();
        assertThat(BackendLifecycleManager.usesDefaultStarter()).isTrue();
        // 从未 forbid，close 后退出闩锁仍为 false（进程退出状态未被改变）
        assertThat(BackendLifecycleManager.isShutdownInitiated()).isFalse();
    }

    // ── forbidLifecycle 闩锁（不可被 Registration.close 撤销）─────────────────

    @Test
    @DisplayName("forbidLifecycle 后 startAsync / restartAsync 一律拒绝（退出过程中不拉起新后端）")
    void forbidLifecycleBlocksStartAndRestart() {
        assertThat(BackendLifecycleManager.isShutdownInitiated()).isFalse();
        BackendLifecycleManager.forbidLifecycle();
        assertThat(BackendLifecycleManager.isShutdownInitiated()).isTrue();

        assertThat(BackendLifecycleManager.startAsync()).isFalse();
        assertThat(BackendLifecycleManager.restartAsync()).isFalse();
    }

    @Test
    @DisplayName("forbidLifecycle 后当前 Registration.close() 不撤销退出闩锁（shutdownInitiated 仍为 true）")
    void forbidSurvivesRegistrationClose() {
        BackendLifecycleManager.Registration registration =
                BackendLifecycleManager.configure(null, null, args -> null);
        BackendLifecycleManager.forbidLifecycle();
        assertThat(BackendLifecycleManager.isShutdownInitiated()).isTrue();

        registration.close(); // 不得撤销退出闩锁

        assertThat(BackendLifecycleManager.isShutdownInitiated())
                .as("Registration.close 不得撤销进程退出闩锁")
                .isTrue();
        // close 仍恢复了默认 starter（释放对回调的静态引用）
        assertThat(BackendLifecycleManager.usesDefaultStarter()).isTrue();
    }

    @Test
    @DisplayName("forbid 后 configure 抛 IllegalStateException（不静默重新开放生命周期）")
    void afterForbidConfigureRejects() {
        BackendLifecycleManager.forbidLifecycle();
        assertThatThrownBy(() -> BackendLifecycleManager.configure(null, null, args -> null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("forbid 后无论 Registration 是否关闭，start / restart 持续拒绝")
    void forbidPersistsAcrossRegistrationClose() {
        BackendLifecycleManager.Registration registration =
                BackendLifecycleManager.configure(null, null, args -> null);
        BackendLifecycleManager.forbidLifecycle();
        registration.close();

        assertThat(BackendLifecycleManager.startAsync()).isFalse();
        assertThat(BackendLifecycleManager.restartAsync()).isFalse();
        assertThat(BackendLifecycleManager.isShutdownInitiated()).isTrue();
    }

    // ── closeBackendContext 基本路径 ─────────────────────────────────────────

    @Test
    @DisplayName("closeBackendContext：backend 已停 / 无 context 时返回 ALREADY_STOPPED（进程退出安全）")
    void closeBackendContextNoopWhenAlreadyStopped() {
        assertThat(closeBackendResult(1_000L))
                .isEqualTo(BackendLifecycleManager.CloseResult.ALREADY_STOPPED);
        assertThat(BackendLifecycleManager.state()).isEqualTo(BackendLifecycleManager.State.STOPPED);
    }

    @Test
    @DisplayName("closeBackendContext timeoutMillis <= 0 拒绝（避免 Thread.join(0) 无限等待）")
    void closeBackendContextRejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> closeBackendResult(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> closeBackendResult(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("closeBackendContext 同步关闭真实 context 并落 STOPPED，返回 CLOSED（验证 context 清退先于 session 关闭的同步点）")
    void closeBackendContextClosesRealContextSynchronously() {
        ConfigurableApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.refresh();
        BackendLifecycleManager.configure(null, null, args -> ctx);

        try {
            assertThat(BackendLifecycleManager.startAsync(null)).isTrue();
            awaitState(BackendLifecycleManager.State.RUNNING);
            assertThat(ctx.isActive()).isTrue();

            assertThat(closeBackendResult(10_000L))
                    .isEqualTo(BackendLifecycleManager.CloseResult.CLOSED);

            assertThat(ctx.isActive()).isFalse();
            assertThat(BackendLifecycleManager.state()).isEqualTo(BackendLifecycleManager.State.STOPPED);
        } finally {
            if (ctx.isActive()) {
                ctx.close();
            }
        }
    }

    @Test
    @DisplayName("restartAsync：context 关闭未确认时不启动下一轮后端")
    void restartDoesNotStartNextBackendWhenStopCloseIsUnconfirmed() {
        FailingCloseContext firstContext = new FailingCloseContext();
        AtomicInteger starterCalls = new AtomicInteger();
        AtomicBoolean afterRestart = new AtomicBoolean();
        AtomicReference<ConfigurableApplicationContext> secondContext = new AtomicReference<>();
        BackendLifecycleManager.configure(null, null, args -> {
            if (starterCalls.incrementAndGet() == 1) {
                return firstContext;
            }
            AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
            ctx.refresh();
            secondContext.set(ctx);
            return ctx;
        });

        try {
            assertThat(BackendLifecycleManager.startAsync(null)).isTrue();
            awaitState(BackendLifecycleManager.State.RUNNING);

            firstContext.failCloseKeepingActive();

            assertThat(BackendLifecycleManager.restartAsync(() -> afterRestart.set(true))).isTrue();
            awaitState(BackendLifecycleManager.State.FAILED);

            assertThat(starterCalls.get())
                    .as("stop 未确认关闭时不得调用第二次 starter")
                    .isEqualTo(1);
            assertThat(secondContext.get())
                    .as("stop 未确认关闭时不得创建第二个 context")
                    .isNull();
            assertThat(afterRestart).isFalse();
            assertThat(BackendLifecycleManager.state())
                    .as("stop 未确认关闭时不得进入 STARTING/RUNNING")
                    .isEqualTo(BackendLifecycleManager.State.FAILED);
            assertThat(firstContext.isActive()).isTrue();
        } finally {
            firstContext.allowClose();
            closeIfLifecycleDoesNotOwn(firstContext);
            closeIfActive(secondContext.get());
        }
    }

    @Test
    @DisplayName("stopAsync：context 关闭未确认时不执行 afterStop")
    void stopAsyncDoesNotRunAfterStopWhenCloseIsUnconfirmed() {
        FailingCloseContext ctx = new FailingCloseContext();
        AtomicBoolean afterStop = new AtomicBoolean();
        BackendLifecycleManager.configure(null, null, args -> ctx);

        try {
            assertThat(BackendLifecycleManager.startAsync(null)).isTrue();
            awaitState(BackendLifecycleManager.State.RUNNING);

            ctx.failCloseKeepingActive();

            assertThat(BackendLifecycleManager.stopAsync(() -> afterStop.set(true))).isTrue();
            awaitState(BackendLifecycleManager.State.FAILED);

            assertThat(afterStop)
                    .as("关闭未确认成功时 afterStop 不得执行")
                    .isFalse();
            assertThat(ctx.isActive()).isTrue();
        } finally {
            ctx.allowClose();
            closeIfLifecycleDoesNotOwn(ctx);
        }
    }

    @Test
    @DisplayName("startAsync：FAILED 且仍持有 context 时拒绝直接启动新后端")
    void startAsyncRejectsFailedStateWithOwnedContext() {
        FailingCloseContext failedContext = new FailingCloseContext();
        AtomicReference<ConfigurableApplicationContext> unexpectedContext = new AtomicReference<>();
        AtomicInteger unexpectedStarterCalls = new AtomicInteger();
        BackendLifecycleManager.configure(null, null, args -> failedContext);

        try {
            assertThat(BackendLifecycleManager.startAsync(null)).isTrue();
            awaitState(BackendLifecycleManager.State.RUNNING);

            failedContext.failCloseKeepingActive();
            assertThat(BackendLifecycleManager.stopAsync(null)).isTrue();
            awaitState(BackendLifecycleManager.State.FAILED);

            BackendLifecycleManager.configure(null, null, args -> {
                unexpectedStarterCalls.incrementAndGet();
                AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
                ctx.refresh();
                unexpectedContext.set(ctx);
                return ctx;
            });

            assertThat(BackendLifecycleManager.startAsync(null)).isFalse();

            assertThat(unexpectedStarterCalls.get())
                    .as("FAILED 且旧 context 未确认关闭时不得调用 starter")
                    .isZero();
            assertThat(unexpectedContext.get()).isNull();
            assertThat(BackendLifecycleManager.state()).isEqualTo(BackendLifecycleManager.State.FAILED);
            assertThat(failedContext.isActive()).isTrue();
        } finally {
            failedContext.allowClose();
            closeIfLifecycleDoesNotOwn(failedContext);
            closeIfActive(unexpectedContext.get());
        }
    }

    // ── 并发退出路径：STARTING / STOPPING / timeout / 捕获 ─────────────────────

    @Test
    @DisplayName("退出期 STARTING：迟到 context 由启动线程自行关闭、落 STOPPED，closeBackendContext 等 ALREADY_STOPPED")
    void shutdownDuringStartingLateContextClosedByStarter() throws Exception {
        CountDownLatch blockStart = new CountDownLatch(1);
        ConfigurableApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.refresh();
        AtomicReference<ConfigurableApplicationContext> produced = new AtomicReference<>();
        BackendLifecycleManager.configure(null, null, args -> {
            produced.set(ctx);
            awaitUninterruptibly(blockStart); // 阻塞至测试释放
            return ctx;
        });

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            assertThat(BackendLifecycleManager.startAsync(null)).isTrue();
            awaitState(BackendLifecycleManager.State.STARTING);

            BackendLifecycleManager.forbidLifecycle(); // 退出闩锁：使在途 start 失效
            Future<BackendLifecycleManager.CloseResult> closeFuture =
                    exec.submit(() -> closeBackendResult(10_000L));

            blockStart.countDown(); // 释放被阻塞的 starter → 迟到 context 到达

            BackendLifecycleManager.CloseResult result = closeFuture.get(30, TimeUnit.SECONDS);
            assertThat(result.isContextConfirmedClosed())
                    .as("无论调用观察到 CLOSED 或已落定的 ALREADY_STOPPED，context 都必须确认关闭")
                    .isTrue();
            assertThat(BackendLifecycleManager.state()).isEqualTo(BackendLifecycleManager.State.STOPPED);
            assertThat(produced.get()).isSameAs(ctx);
            assertThat(ctx.isActive())
                    .as("迟到 context 必须由启动线程自行关闭")
                    .isFalse();
        } finally {
            blockStart.countDown();
            exec.shutdownNow();
            if (ctx.isActive()) {
                ctx.close();
            }
        }
    }

    @Test
    @DisplayName("退出期 STOPPING：closeBackendContext 有限等待既有 stop operation，不提前落 STOPPED、不并发拆第二个 close")
    void shutdownDuringStoppingWaitsForInFlightStop() throws Exception {
        BlockingCloseContext ctx = new BlockingCloseContext();
        BackendLifecycleManager.configure(null, null, args -> ctx);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            assertThat(BackendLifecycleManager.startAsync(null)).isTrue();
            awaitState(BackendLifecycleManager.State.RUNNING);

            assertThat(BackendLifecycleManager.stopAsync(null)).isTrue();
            awaitState(BackendLifecycleManager.State.STOPPING); // stop worker 阻塞在 ctx.close()

            Future<BackendLifecycleManager.CloseResult> closeFuture =
                    exec.submit(() -> closeBackendResult(10_000L));

            // closeBackendContext 应在等待既有 stop，不应提前返回、不应提前落 STOPPED
            assertThatThrownBy(() -> closeFuture.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(BackendLifecycleManager.state())
                    .as("等待既有 stop 期间不得提前落 STOPPED")
                    .isEqualTo(BackendLifecycleManager.State.STOPPING);

            ctx.allowClose(); // 放行 stop worker 的阻塞关闭

            BackendLifecycleManager.CloseResult result = closeFuture.get(30, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(BackendLifecycleManager.CloseResult.CLOSED);
            awaitState(BackendLifecycleManager.State.STOPPED);
        } finally {
            ctx.allowClose();
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("context 关闭超时：返回 TIMED_OUT、状态保持 STOPPING（不伪装 STOPPED），session 不应被关")
    void contextCloseTimeoutReturnsTimedOutKeepingStopping() throws Exception {
        BlockingCloseContext ctx = new BlockingCloseContext();
        BackendLifecycleManager.configure(null, null, args -> ctx);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            assertThat(BackendLifecycleManager.startAsync(null)).isTrue();
            awaitState(BackendLifecycleManager.State.RUNNING);

            // 不放行阻塞关闭 → 短超时必然超时
            Future<BackendLifecycleManager.CloseResult> closeFuture =
                    exec.submit(() -> closeBackendResult(300L));

            BackendLifecycleManager.CloseResult result = closeFuture.get(30, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(BackendLifecycleManager.CloseResult.TIMED_OUT);
            assertThat(BackendLifecycleManager.state())
                    .as("超时后不得伪装 STOPPED，保持明确的关闭中状态")
                    .isEqualTo(BackendLifecycleManager.State.STOPPING);
            assertThat(ctx.isActive()).isTrue(); // context 仍未关闭（helper 仍在拆）
        } finally {
            ctx.allowClose(); // 放行 daemon helper，避免泄漏到后续用例
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("context 关闭超时后迟到完成：状态最终 STOPPED，并严格按 context→session 顺序清退")
    void contextCloseTimeoutThenCompletionClosesSessionInOrder() throws Exception {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        BlockingCloseContext ctx = new BlockingCloseContext(order);
        BackendLifecycleManager.Registration registration =
                BackendLifecycleManager.configure(null, null, args -> ctx);
        CountDownLatch sessionClosed = new CountDownLatch(1);
        AtomicInteger sessionCloses = new AtomicInteger();

        try {
            assertThat(BackendLifecycleManager.startAsync(null)).isTrue();
            awaitState(BackendLifecycleManager.State.RUNNING);

            BackendShutdownCoordinator coordinator = new BackendShutdownCoordinator(
                    BackendLifecycleManager::forbidLifecycle,
                    registration,
                    BackendLifecycleManager::closeBackendContext,
                    200L,
                    () -> {
                        order.add("session");
                        sessionCloses.incrementAndGet();
                        sessionClosed.countDown();
                    });

            coordinator.shutdown();

            assertThat(BackendLifecycleManager.state()).isEqualTo(BackendLifecycleManager.State.STOPPING);
            assertThat(sessionCloses.get())
                    .as("timeout 返回时 context 仍在拆卸，不得提前关闭 session")
                    .isZero();

            ctx.allowClose();

            assertThat(sessionClosed.await(10, TimeUnit.SECONDS))
                    .as("context 迟到关闭后应由同一 completion 链顺序关闭 session")
                    .isTrue();
            assertThat(order).containsExactly("context", "session");
            assertThat(sessionCloses.get()).isEqualTo(1);
            awaitState(BackendLifecycleManager.State.STOPPED);
        } finally {
            ctx.allowClose();
        }
    }
    @Test
    @DisplayName("start operation 用启动时捕获的 starter/args：启动途中 configure 不切换到新回调")
    void startOperationUsesCapturedStarterAndArgs() throws Exception {
        CountDownLatch blockStart = new CountDownLatch(1);
        ConfigurableApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.refresh();
        List<String> firstArgs = new CopyOnWriteArrayList<>();
        AtomicInteger secondCalled = new AtomicInteger();

        BackendLifecycleManager.BackendStarter first = args -> {
            firstArgs.addAll(Arrays.asList(args));
            awaitUninterruptibly(blockStart);
            return ctx;
        };
        BackendLifecycleManager.BackendStarter second = args -> {
            secondCalled.incrementAndGet();
            return null;
        };

        BackendLifecycleManager.configure(new String[]{"--captured"}, null, first);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            assertThat(BackendLifecycleManager.startAsync(null)).isTrue(); // 捕获 first + ["--captured"]
            awaitState(BackendLifecycleManager.State.STARTING);

            // 启动途中重新 configure（shutdownInitiated 仍为 false，允许）——静态 starter 切到 second
            BackendLifecycleManager.Registration secondReg =
                    BackendLifecycleManager.configure(new String[]{"--other"}, null, second);

            blockStart.countDown(); // 放行 first → 用捕获的 first / ["--captured"] 发布
            awaitState(BackendLifecycleManager.State.RUNNING);

            assertThat(firstArgs).containsExactly("--captured"); // 用了启动时捕获的回调与参数
            assertThat(secondCalled.get())
                    .as("启动途中 configure 不得切换正在执行的 operation 到新回调")
                    .isZero();
            secondReg.close();
        } finally {
            blockStart.countDown();
            exec.shutdownNow();
        }
    }

    private static BackendLifecycleManager.CloseResult closeBackendResult(long timeoutMillis) {
        return BackendLifecycleManager.closeBackendContext(timeoutMillis).result();
    }
    // --- helpers ---

    private static void awaitState(BackendLifecycleManager.State target) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (BackendLifecycleManager.state() != target && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for backend state " + target, e);
            }
        }
        assertThat(BackendLifecycleManager.state())
                .as("expected backend to reach " + target)
                .isEqualTo(target);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeIfActive(ConfigurableApplicationContext ctx) {
        if (ctx != null && ctx.isActive()) {
            ctx.close();
        }
    }

    private static void closeIfLifecycleDoesNotOwn(ConfigurableApplicationContext ctx) {
        if (BackendLifecycleManager.state() == BackendLifecycleManager.State.STOPPED) {
            closeIfActive(ctx);
        }
    }

    /**
     * 首次关闭时可保持 active，用于模拟 Spring context 关闭未确认成功；测试结束前解除后可正常关闭。
     */
    private static final class FailingCloseContext extends AnnotationConfigApplicationContext {
        private final AtomicBoolean keepActiveOnClose = new AtomicBoolean();

        private FailingCloseContext() {
            refresh();
        }

        void failCloseKeepingActive() {
            keepActiveOnClose.set(true);
        }

        void allowClose() {
            keepActiveOnClose.set(false);
        }

        @Override
        public void close() {
            if (keepActiveOnClose.get()) {
                return;
            }
            super.close();
        }
    }

    /**
     * {@link AnnotationConfigApplicationContext} 子类：首次 {@code close()} 阻塞至 {@link #allowClose()} 放行，
     * 用于让 stop operation / 关闭 helper 停在「正在拆 context」状态，以稳定复现 STOPPING / 超时路径。
     * 仅首次进入阻塞；后续 close（{@code SpringApplication.exit} 后的显式 close）直接转发基类（幂等 no-op）。
     */
    private static final class BlockingCloseContext extends AnnotationConfigApplicationContext {
        private final CountDownLatch allowClose = new CountDownLatch(1);
        private final AtomicInteger entries = new AtomicInteger();
        private final List<String> closeOrder;

        private BlockingCloseContext() {
            this(null);
        }

        private BlockingCloseContext(List<String> closeOrder) {
            this.closeOrder = closeOrder;
            refresh();
        }
        @Override
        public void close() {
            if (entries.getAndIncrement() == 0) {
                awaitUninterruptibly(allowClose); // 仅首次进入阻塞
            }
            super.close();
            if (closeOrder != null) {
                closeOrder.add("context");
            }
        }

        void allowClose() {
            allowClose.countDown();
        }
    }
}
