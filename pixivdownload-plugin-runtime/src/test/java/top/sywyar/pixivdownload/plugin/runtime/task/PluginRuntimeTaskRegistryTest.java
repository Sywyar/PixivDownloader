package top.sywyar.pixivdownload.plugin.runtime.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTask;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskDrain;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRejectedException;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("插件运行期后台任务宿主注册中心")
class PluginRuntimeTaskRegistryTest {

    @Test
    @DisplayName("一次性包装器只执行一次并在 finally 后归零")
    void oneShotRunsOnceAndReleasesInFinally() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        AtomicInteger calls = new AtomicInteger();
        PluginRuntimeTask task = tasks.registerOneShot(calls::incrementAndGet);

        assertThat(registry.activeTaskCount("ext-demo")).isOne();
        task.run();
        task.run();

        assertThat(calls).hasValue(1);
        assertThat(registry.activeTaskCount("ext-demo")).isZero();
    }

    @Test
    @DisplayName("周期包装器可重复执行并由主动 cancel 摘除")
    void periodicRunsUntilExplicitlyCancelled() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        AtomicInteger calls = new AtomicInteger();
        RecordingFuture future = new RecordingFuture();
        PluginRuntimeTask task = tasks.registerPeriodic(calls::incrementAndGet);
        task.bindCancellation(future);

        task.run();
        task.run();
        task.cancel();
        task.run();

        assertThat(calls).hasValue(2);
        assertThat(future.cancelCalls).hasValue(1);
        assertThat(registry.activeTaskCount("ext-demo")).isZero();
    }

    @Test
    @DisplayName("prepare 只关 admission 与清排队 delegate，不调用外部 Future")
    void prepareOnlyClosesAdmissionAndClearsQueuedDelegates() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        AtomicInteger oneShotCalls = new AtomicInteger();
        AtomicInteger periodicCalls = new AtomicInteger();
        RecordingFuture periodicFuture = new RecordingFuture();
        PluginRuntimeTask oneShot = tasks.registerOneShot(oneShotCalls::incrementAndGet);
        PluginRuntimeTask periodic = tasks.registerPeriodic(periodicCalls::incrementAndGet);
        periodic.bindCancellation(periodicFuture);

        PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-demo");

        assertThat(drain.ownerPluginId()).isEqualTo("ext-demo");
        assertThat(drain.generation()).isPositive();
        assertThat(tasks.acceptsNewTasks()).isFalse();
        assertThat(periodicFuture.cancelCalls).hasValue(0);
        assertThat(drain.activeCount()).isEqualTo(2);
        oneShot.run();
        assertThat(drain.activeCount()).isOne();
        periodic.run();
        assertThat(oneShotCalls).hasValue(0);
        assertThat(periodicCalls).hasValue(0);
        assertThatThrownBy(() -> tasks.registerOneShot(() -> {
        })).isInstanceOf(PluginRuntimeTaskRejectedException.class);

        registry.cancelQuiescedTasks("ext-demo", drain);
        assertThat(periodicFuture.cancelCalls).hasValue(1);
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("prepare 后包装器不再直接持插件 delegate 且 drain 不回指全局 registry")
    void preparedWrapperAndDrainDoNotRetainPluginObjects() throws Exception {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        Object pluginBean = new Object();
        PluginRuntimeTask task = registry.registrarForPlugin("ext-demo")
                .registerOneShot(pluginBean::hashCode);

        PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-demo");

        Field delegate = task.getClass().getDeclaredField("delegate");
        delegate.setAccessible(true);
        assertThat(delegate.get(task)).isNull();
        assertThat(Arrays.stream(drain.getClass().getDeclaredFields())
                .map(Field::getType))
                .doesNotContain(PluginRuntimeTaskRegistry.class);
        task.run();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("运行中的一次性任务必须等真实 finally 后 drain 才归零")
    void runningOneShotKeepsDrainActiveUntilFinally() throws Exception {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PluginRuntimeTask task = tasks.registerOneShot(() -> {
            entered.countDown();
            await(release);
        });
        Thread worker = new Thread(task, "plugin-runtime-task-running");
        worker.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-demo");
        registry.cancelQuiescedTasks("ext-demo", drain);

        assertThat(drain.activeCount()).isOne();
        assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20))).isFalse();
        release.countDown();
        worker.join(5_000L);
        assertThat(worker.isAlive()).isFalse();
        assertThat(drain.awaitDrained(System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
        assertThat(drain.activeCount()).isZero();
    }

    @Test
    @DisplayName("周期任务在 quiesce 早于 Future bind 时阻止 drain 并于迟到绑定后立即取消")
    void periodicLateBindCancelsAfterQuiesce() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        AtomicInteger calls = new AtomicInteger();
        PluginRuntimeTask task = tasks.registerPeriodic(calls::incrementAndGet);

        PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-demo");
        registry.cancelQuiescedTasks("ext-demo", drain);
        assertThat(drain.activeCount()).isOne();

        RecordingFuture future = new RecordingFuture();
        task.bindCancellation(future);

        assertThat(future.cancelCalls).hasValue(1);
        assertThat(drain.isDrained()).isTrue();
        task.run();
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("迟到绑定的取消失败保留精确任务供宿主重试")
    void lateBindCancellationFailureRemainsRetryable() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        PluginRuntimeTask task = tasks.registerPeriodic(() -> {
        });
        PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-demo");
        ToggleFuture future = new ToggleFuture();

        assertThatThrownBy(() -> task.bindCancellation(future))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused");
        assertThat(drain.activeCount()).isOne();
        assertThatThrownBy(() -> registry.resume("ext-demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");

        future.allowCancellation.set(true);
        registry.cancelQuiescedTasks("ext-demo", drain);
        assertThat(future.cancelCalls).hasValue(2);
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("一次性任务的迟到 Future 取消失败同样阻止 drain 并保留重试")
    void oneShotLateBindFailureAlsoBlocksDrain() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        PluginRuntimeTask task = tasks.registerOneShot(() -> {
        });
        PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-demo");
        ToggleFuture future = new ToggleFuture();

        assertThatThrownBy(() -> task.bindCancellation(future))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused");
        assertThat(drain.activeCount()).isOne();

        future.allowCancellation.set(true);
        registry.cancelQuiescedTasks("ext-demo", drain);
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("主动取消早于 Future 绑定时拒绝取消仍阻止 drain 并可重试")
    void cancelBeforeLateBindRefusalRemainsRetryable() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        PluginRuntimeTask task = tasks.registerPeriodic(() -> {
        });
        ToggleFuture future = new ToggleFuture();

        task.cancel();
        PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-demo");
        assertThat(drain.activeCount()).isOne();
        assertThatThrownBy(() -> task.bindCancellation(future))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused");
        assertThat(drain.activeCount()).isOne();
        assertThatThrownBy(() -> registry.resume("ext-demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");

        future.allowCancellation.set(true);
        registry.cancelQuiescedTasks("ext-demo", drain);
        assertThat(future.cancelCalls).hasValue(2);
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("明确未提交的任务可在主动取消后显式终结")
    void unsubmittedTaskCanBeDiscardedExplicitly() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        PluginRuntimeTask task = tasks.registerPeriodic(() -> {
        });

        task.cancel();
        assertThat(registry.activeTaskCount("ext-demo")).isOne();
        task.discardUnsubmitted();

        PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-demo");
        assertThat(drain.isDrained()).isTrue();
        registry.resume("ext-demo");
        assertThat(tasks.acceptsNewTasks()).isTrue();
    }

    @Test
    @DisplayName("Future cancel 返回失败会阻止 drain 与 resume，成功重试后换代开放")
    void refusedCancellationBlocksDrainUntilSuccessfulRetry() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        ToggleFuture future = new ToggleFuture();
        PluginRuntimeTask periodic = tasks.registerPeriodic(() -> {
        });
        periodic.bindCancellation(future);
        PluginRuntimeTaskDrain firstDrain = registry.prepareQuiesce("ext-demo");

        assertThatThrownBy(() -> registry.cancelQuiescedTasks("ext-demo", firstDrain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused");
        assertThat(firstDrain.activeCount()).isOne();
        assertThatThrownBy(() -> registry.resume("ext-demo"))
                .isInstanceOf(IllegalStateException.class);

        future.allowCancellation.set(true);
        registry.cancelQuiescedTasks("ext-demo", firstDrain);
        registry.resume("ext-demo");
        assertThat(tasks.acceptsNewTasks()).isTrue();

        PluginRuntimeTask replacement = tasks.registerOneShot(() -> {
        });
        PluginRuntimeTaskDrain secondDrain = registry.prepareQuiesce("ext-demo");
        assertThat(secondDrain.generation()).isEqualTo(firstDrain.generation() + 1L);
        assertThat(firstDrain.isDrained()).isTrue();
        assertThat(firstDrain.activeCount()).isZero();
        assertThat(secondDrain).isNotSameAs(firstDrain);
        replacement.run();
    }

    @Test
    @DisplayName("旧代际 drain 与错误 owner 均不能取消新代际任务")
    void staleOrForeignDrainFailsClosed() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar first = registry.registrarForPlugin("first");
        PluginRuntimeTask oldTask = first.registerOneShot(() -> {
        });
        PluginRuntimeTaskDrain oldDrain = registry.prepareQuiesce("first");
        oldTask.run();
        registry.resume("first");
        PluginRuntimeTask replacement = first.registerPeriodic(() -> {
        });
        RecordingFuture replacementFuture = new RecordingFuture();
        replacement.bindCancellation(replacementFuture);
        PluginRuntimeTaskDrain currentDrain = registry.prepareQuiesce("first");
        PluginRuntimeTaskDrain otherDrain = registry.prepareQuiesce("other");

        assertThatThrownBy(() -> registry.cancelQuiescedTasks("first", oldDrain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not current");
        assertThatThrownBy(() -> registry.cancelQuiescedTasks("first", otherDrain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not current");
        assertThat(replacementFuture.cancelCalls).hasValue(0);

        registry.cancelQuiescedTasks("first", currentDrain);
        assertThat(replacementFuture.cancelCalls).hasValue(1);
    }

    @Test
    @DisplayName("周期 delegate 抛错时保持原对象并摘除插件引用")
    void periodicFailureKeepsIdentityAndReleasesWrapper() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        IllegalStateException expected = new IllegalStateException("periodic-boom");
        PluginRuntimeTask task = registry.registrarForPlugin("ext-demo")
                .registerPeriodic(() -> {
                    throw expected;
                });

        assertThat(catchThrowable(task::run)).isSameAs(expected);
        assertThat(registry.activeTaskCount("ext-demo")).isZero();
        task.run();
    }

    @Test
    @DisplayName("register 与 prepare 竞态线性化后不会执行已清退 delegate")
    void registerLinearizesWithPrepare() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            int attemptIndex = attempt;
            PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
            PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-" + attemptIndex);
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<PluginRuntimeTask> registered = new AtomicReference<>();
            AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);
            Thread registering = new Thread(() -> {
                await(start);
                try {
                    registered.set(tasks.registerOneShot(calls::incrementAndGet));
                } catch (Throwable failure) {
                    registrationFailure.set(failure);
                }
            }, "plugin-runtime-task-register-race");
            Thread quiescing = new Thread(() -> {
                await(start);
                registry.prepareQuiesce("ext-" + attemptIndex);
            }, "plugin-runtime-task-prepare-race");
            registering.start();
            quiescing.start();
            start.countDown();
            registering.join(5_000L);
            quiescing.join(5_000L);
            assertThat(registering.isAlive()).isFalse();
            assertThat(quiescing.isAlive()).isFalse();

            PluginRuntimeTask task = registered.get();
            if (task != null) {
                task.run();
                assertThat(registrationFailure.get()).isNull();
            } else {
                assertThat(registrationFailure.get())
                        .isInstanceOf(PluginRuntimeTaskRejectedException.class);
            }
            PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-" + attemptIndex);
            assertThat(drain.isDrained()).isTrue();
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    @DisplayName("空白 owner 拒绝创建 registrar 且只读观测保持空状态")
    void ownerValidationAndEmptyObservationAreStable() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();

        assertThatThrownBy(() -> registry.registrarForPlugin(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.registrarForPlugin(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.activeTaskCount(null)).isZero();
        assertThat(registry.activeTaskCount(" ")).isZero();
        assertThat(registry.acceptsNewTasks(null)).isTrue();
        assertThat(registry.acceptsNewTasks(" ")).isTrue();
    }

    private static class RecordingFuture implements Future<Object> {
        final AtomicInteger cancelCalls = new AtomicInteger();
        volatile boolean cancelled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ToggleFuture extends RecordingFuture {
        final AtomicBoolean allowCancellation = new AtomicBoolean();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            boolean accepted = allowCancellation.get();
            cancelled = accepted;
            return accepted;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test latch");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test latch wait interrupted", failure);
        }
    }
}
