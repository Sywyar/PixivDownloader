package top.sywyar.pixivdownload.plugin.runtime.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTask;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskDrain;
import top.sywyar.pixivdownload.plugin.api.task.PluginRuntimeTaskRegistrar;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("插件运行期任务取消致命错误")
class PluginRuntimeTaskRegistryFatalTest {

    @Test
    @DisplayName("致命取消失败保持原对象身份且其它任务仍尝试取消")
    void fatalCancellationKeepsIdentityAfterTryingOtherTasks() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        IllegalStateException ordinary = new IllegalStateException("ordinary-cancel");
        OutOfMemoryError fatal = new OutOfMemoryError("fatal-cancel");
        ThrowingFuture ordinaryFuture = new ThrowingFuture(ordinary);
        ThrowingFuture fatalFuture = new ThrowingFuture(fatal);
        RecordingFuture healthyFuture = new RecordingFuture();
        bindPeriodic(tasks, ordinaryFuture);
        bindPeriodic(tasks, fatalFuture);
        bindPeriodic(tasks, healthyFuture);
        PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-demo");

        assertThat(catchThrowable(() -> registry.cancelQuiescedTasks("ext-demo", drain)))
                .isSameAs(fatal);
        assertThat(fatal.getSuppressed()).contains(ordinary);
        assertThat(ordinaryFuture.cancelCalls).hasValue(1);
        assertThat(fatalFuture.cancelCalls).hasValue(1);
        assertThat(healthyFuture.cancelCalls).hasValue(1);
        assertThat(drain.activeCount()).isEqualTo(2);

        ordinaryFuture.failure.set(null);
        fatalFuture.failure.set(null);
        registry.cancelQuiescedTasks("ext-demo", drain);
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("ThreadDeath 取消失败同样保持原对象并可在下一次调用重试")
    void threadDeathRemainsRetryable() {
        PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
        PluginRuntimeTaskRegistrar tasks = registry.registrarForPlugin("ext-demo");
        ThreadDeath expected = new ThreadDeath();
        ThrowingFuture future = new ThrowingFuture(expected);
        bindPeriodic(tasks, future);
        PluginRuntimeTaskDrain drain = registry.prepareQuiesce("ext-demo");

        assertThat(catchThrowable(() -> registry.cancelQuiescedTasks("ext-demo", drain)))
                .isSameAs(expected);
        assertThat(drain.activeCount()).isOne();

        future.failure.set(null);
        registry.cancelQuiescedTasks("ext-demo", drain);
        assertThat(drain.isDrained()).isTrue();
    }

    private static void bindPeriodic(PluginRuntimeTaskRegistrar tasks, Future<?> future) {
        PluginRuntimeTask task = tasks.registerPeriodic(() -> {
        });
        task.bindCancellation(future);
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

    private static final class ThrowingFuture extends RecordingFuture {
        private final AtomicReference<Throwable> failure;

        private ThrowingFuture(Throwable failure) {
            this.failure = new AtomicReference<>(failure);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            Throwable current = failure.get();
            if (current instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (current instanceof Error error) {
                throw error;
            }
            cancelled = true;
            return true;
        }
    }
}
