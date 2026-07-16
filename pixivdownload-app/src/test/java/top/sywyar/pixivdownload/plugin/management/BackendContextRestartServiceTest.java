package top.sywyar.pixivdownload.plugin.management;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.BackendLifecycleManager;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("后端 Spring 上下文重启请求服务")
class BackendContextRestartServiceTest {

    @Test
    @DisplayName("非 RUNNING 状态拒绝请求且不会调度重启，headless 不会拉起第二上下文")
    void nonRunningStateDoesNotScheduleRestart() {
        AtomicBoolean scheduled = new AtomicBoolean();
        AtomicBoolean restarted = new AtomicBoolean();
        BackendContextRestartService service = new BackendContextRestartService(
                () -> BackendLifecycleManager.State.STOPPED,
                () -> {
                    restarted.set(true);
                    return true;
                },
                (action, delay) -> scheduled.set(true),
                failure -> { },
                0L);

        assertThatThrownBy(service::requestRestart)
                .isInstanceOf(PluginManagementException.class)
                .extracting(thrown -> ((PluginManagementException) thrown).code())
                .isEqualTo(PluginManagementErrorCode.BACKEND_RESTART_UNAVAILABLE);
        assertThat(scheduled).isFalse();
        assertThat(restarted).isFalse();
    }

    @Test
    @DisplayName("RUNNING 请求先返回 accepted，再由延迟任务触发生命周期重启")
    void runningStateSchedulesRestartAfterResponse() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicLong delay = new AtomicLong(-1L);
        AtomicInteger restartCalls = new AtomicInteger();
        AtomicInteger failureReports = new AtomicInteger();
        BackendContextRestartService service = new BackendContextRestartService(
                () -> BackendLifecycleManager.State.RUNNING,
                () -> {
                    restartCalls.incrementAndGet();
                    return true;
                },
                (action, delayMillis) -> {
                    queued.set(action);
                    delay.set(delayMillis);
                },
                failure -> failureReports.incrementAndGet(),
                125L);

        BackendContextRestartService.BackendRestartResult result = service.requestRestart();

        assertThat(result.accepted()).isTrue();
        assertThat(restartCalls).hasValue(0);
        assertThat(delay).hasValue(125L);
        queued.get().run();
        assertThat(restartCalls).hasValue(1);
        assertThat(failureReports).hasValue(0);
    }

    @Test
    @DisplayName("延迟触发时生命周期管理器拒绝重启会记录可诊断失败")
    void rejectedDelayedRestartIsReported() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger failureReports = new AtomicInteger();
        BackendContextRestartService service = new BackendContextRestartService(
                () -> BackendLifecycleManager.State.RUNNING,
                () -> false,
                (action, delayMillis) -> queued.set(action),
                failure -> failureReports.incrementAndGet(),
                0L);

        service.requestRestart();
        queued.get().run();

        assertThat(failureReports).hasValue(1);
        assertThat(service.requestRestart().accepted()).isTrue();
    }

    @Test
    @DisplayName("延迟重启动作抛异常时上报原因并清除 pending")
    void failedDelayedRestartIsReportedAndPendingIsCleared() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException("restart failed");
        BackendContextRestartService service = new BackendContextRestartService(
                () -> BackendLifecycleManager.State.RUNNING,
                () -> {
                    throw failure;
                },
                (action, delayMillis) -> queued.set(action),
                reported::set,
                0L);

        service.requestRestart();
        queued.get().run();

        assertThat(reported).hasValue(failure);
        assertThat(service.requestRestart().accepted()).isTrue();
    }

    @Test
    @DisplayName("首个延迟请求未触发前拒绝重复重启请求")
    void duplicatePendingRequestIsRejected() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        BackendContextRestartService service = new BackendContextRestartService(
                () -> BackendLifecycleManager.State.RUNNING,
                () -> true,
                (action, delayMillis) -> queued.set(action),
                failure -> { },
                0L);

        service.requestRestart();

        assertThatThrownBy(service::requestRestart)
                .isInstanceOf(PluginManagementException.class)
                .extracting(thrown -> ((PluginManagementException) thrown).code())
                .isEqualTo(PluginManagementErrorCode.BACKEND_RESTART_PENDING);

        queued.get().run();
        assertThat(service.requestRestart().accepted()).isTrue();
    }
}
