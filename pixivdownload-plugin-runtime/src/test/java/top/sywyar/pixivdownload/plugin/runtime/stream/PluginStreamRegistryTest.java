package top.sywyar.pixivdownload.plugin.runtime.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.stream.PluginStream;
import top.sywyar.pixivdownload.plugin.api.stream.PluginStreamRegistrar;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 插件推流宿主注册中心测试：插件只经 owner-scoped registrar 注册 / 注销可关闭推流；宿主
 * {@code closeForPlugin} 关闭并清退且不残留引用，同时保留并发、失败重试与致命错误语义。
 */
@DisplayName("插件推流宿主注册中心")
class PluginStreamRegistryTest {

    /** 记录关闭次数的推流夹具；{@code fail=true} 时关闭抛异常（验证隔离）。 */
    private static final class RecordingStream implements PluginStream {
        int closedCount;
        boolean fail;

        @Override
        public void closeUnavailable() {
            closedCount++;
            if (fail) {
                throw new RuntimeException("boom-close");
            }
        }
    }

    @Test
    @DisplayName("owner-scoped register + closeForPlugin 关闭全部推流且不残留引用")
    void closeForPluginClosesAllAndClearsRefs() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar streams = registry.registrarForPlugin("ext-demo");
        RecordingStream first = new RecordingStream();
        RecordingStream second = new RecordingStream();
        streams.register("s1", first);
        streams.register("s2", second);
        assertThat(registry.activeStreamCount("ext-demo")).isEqualTo(2);

        int closed = registry.closeForPlugin("ext-demo");

        assertThat(closed).isEqualTo(2);
        assertThat(first.closedCount).isEqualTo(1);
        assertThat(second.closedCount).isEqualTo(1);
        assertThat(registry.activeStreamCount("ext-demo")).isZero();
    }

    @Test
    @DisplayName("owner-scoped unregister 摘除推流且不触发关闭回调")
    void unregisterDetachesStream() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar streams = registry.registrarForPlugin("ext-demo");
        RecordingStream stream = new RecordingStream();
        streams.register("s1", stream);

        streams.unregister("s1");
        assertThat(registry.activeStreamCount("ext-demo")).isZero();

        assertThat(registry.closeForPlugin("ext-demo")).isZero();
        assertThat(stream.closedCount).isZero();
    }

    @Test
    @DisplayName("普通失败不妨碍其它流关闭且失败项保留到重试成功")
    void closeForPluginRetainsOnlyFailingStreamForRetry() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar streams = registry.registrarForPlugin("ext-demo");
        RecordingStream failing = new RecordingStream();
        failing.fail = true;
        RecordingStream healthy = new RecordingStream();
        streams.register("bad", failing);
        streams.register("good", healthy);

        assertThatThrownBy(() -> registry.closeForPlugin("ext-demo"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom-close");

        assertThat(failing.closedCount).isEqualTo(1);
        assertThat(healthy.closedCount).isEqualTo(1);
        assertThat(registry.activeStreamCount("ext-demo")).isEqualTo(1);

        failing.fail = false;
        int closed = registry.closeForPlugin("ext-demo");

        assertThat(closed).isEqualTo(1);
        assertThat(failing.closedCount).isEqualTo(2);
        assertThat(healthy.closedCount).isEqualTo(1);
        assertThat(registry.activeStreamCount("ext-demo")).isZero();
    }

    @Test
    @DisplayName("致命失败按原对象延后重抛且其它流仍会尝试关闭")
    void fatalFailureKeepsIdentityAfterClosingOtherStreams() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar streams = registry.registrarForPlugin("ext-demo");
        IllegalStateException ordinary = new IllegalStateException("ordinary-first");
        OutOfMemoryError fatal = new OutOfMemoryError("stream-fatal");
        AtomicInteger ordinaryCalls = new AtomicInteger();
        AtomicInteger fatalCalls = new AtomicInteger();
        RecordingStream healthy = new RecordingStream();
        streams.register("ordinary", () -> {
            ordinaryCalls.incrementAndGet();
            throw ordinary;
        });
        streams.register("fatal", () -> {
            fatalCalls.incrementAndGet();
            throw fatal;
        });
        streams.register("healthy", healthy);

        assertThatThrownBy(() -> registry.closeForPlugin("ext-demo")).isSameAs(fatal);

        assertThat(ordinaryCalls).hasValue(1);
        assertThat(fatalCalls).hasValue(1);
        assertThat(healthy.closedCount).isEqualTo(1);
        assertThat(fatal.getSuppressed()).contains(ordinary);
        assertThat(registry.activeStreamCount("ext-demo")).isEqualTo(2);
    }

    @Test
    @DisplayName("closeForPlugin 只作用于目标 owner 的推流")
    void closeForPluginAffectsOnlyTargetPlugin() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar mine = registry.registrarForPlugin("ext-demo");
        PluginStreamRegistrar otherOwner = registry.registrarForPlugin("ext-other");
        RecordingStream mineStream = new RecordingStream();
        RecordingStream otherStream = new RecordingStream();
        mine.register("same-token", mineStream);
        otherOwner.register("same-token", otherStream);

        registry.closeForPlugin("ext-demo");

        assertThat(mineStream.closedCount).isEqualTo(1);
        assertThat(otherStream.closedCount).isZero();
        assertThat(registry.activeStreamCount("ext-other")).isEqualTo(1);
    }

    @Test
    @DisplayName("关闭回调反向 unregister 自身时安全且不重复关闭")
    void reentrantUnregisterDuringCloseIsSafe() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar streams = registry.registrarForPlugin("ext-demo");
        AtomicInteger closes = new AtomicInteger();
        streams.register("s1", () -> {
            closes.incrementAndGet();
            streams.unregister("s1");
        });

        int closed = registry.closeForPlugin("ext-demo");

        assertThat(closed).isEqualTo(1);
        assertThat(closes.get()).isEqualTo(1);
        assertThat(registry.activeStreamCount("ext-demo")).isZero();
    }

    @Test
    @DisplayName("关闭 admission 后迟到注册立即关闭，失败项阻断 resume 并可重试")
    void lateRegistrationClosesImmediatelyAndPendingFailureBlocksResume() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar streams = registry.registrarForPlugin("ext-demo");
        registry.closeForPlugin("ext-demo");
        assertThat(streams.acceptsNewStreams()).isFalse();
        RecordingStream healthyLate = new RecordingStream();

        streams.register("late-ok", healthyLate);

        assertThat(healthyLate.closedCount).isEqualTo(1);
        assertThat(registry.activeStreamCount("ext-demo")).isZero();

        RecordingStream failingLate = new RecordingStream();
        failingLate.fail = true;
        assertThatThrownBy(() -> streams.register("late-failed", failingLate))
                .isInstanceOf(RuntimeException.class);
        assertThat(registry.activeStreamCount("ext-demo")).isEqualTo(1);
        assertThatThrownBy(() -> registry.resume("ext-demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending callbacks");

        failingLate.fail = false;
        assertThat(registry.closeForPlugin("ext-demo")).isEqualTo(1);
        registry.resume("ext-demo");
        assertThat(streams.acceptsNewStreams()).isTrue();
    }

    @Test
    @DisplayName("关闭 callback 重入 unregister 后抛错仍恢复精确 token 供重试")
    void reentrantUnregisterThenFailureIsRestoredForRetry() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar streams = registry.registrarForPlugin("ext-demo");
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger closes = new AtomicInteger();
        PluginStream stream = () -> {
            closes.incrementAndGet();
            streams.unregister("exact-token");
            if (fail.get()) {
                throw new IllegalStateException("close-after-unregister");
            }
        };
        streams.register("exact-token", stream);

        assertThatThrownBy(() -> registry.closeForPlugin("ext-demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("close-after-unregister");
        assertThat(registry.activeStreamCount("ext-demo")).isEqualTo(1);
        assertThatThrownBy(() -> registry.resume("ext-demo"))
                .isInstanceOf(IllegalStateException.class);

        fail.set(false);
        assertThat(registry.closeForPlugin("ext-demo")).isEqualTo(1);
        assertThat(closes).hasValue(2);
        assertThat(registry.activeStreamCount("ext-demo")).isZero();
    }

    @Test
    @DisplayName("迟到注册与 quiesce 线性化后不会留下未关闭流")
    void lateRegisterLinearizesWithQuiesce() throws Exception {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar streams = registry.registrarForPlugin("ext-demo");
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        streams.register("existing", () -> {
            closeEntered.countDown();
            await(releaseClose);
        });
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                registry.closeForPlugin("ext-demo");
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        }, "plugin-stream-close");
        closer.start();
        assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();

        RecordingStream late = new RecordingStream();
        Thread register = new Thread(() -> streams.register("late", late),
                "plugin-stream-late-register");
        register.start();
        releaseClose.countDown();
        closer.join(5_000L);
        register.join(5_000L);

        assertThat(closer.isAlive()).isFalse();
        assertThat(register.isAlive()).isFalse();
        assertThat(closeFailure.get()).isNull();
        assertThat(late.closedCount).isEqualTo(1);
        assertThat(registry.activeStreamCount("ext-demo")).isZero();
        assertThat(streams.acceptsNewStreams()).isFalse();
    }

    @Test
    @DisplayName("关闭会等待并观察并发迟到注册的失败回调")
    void closeWaitsForAndObservesConcurrentLateRegistrationFailure() throws Exception {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar streams = registry.registrarForPlugin("ext-demo");
        CountDownLatch existingCloseEntered = new CountDownLatch(1);
        CountDownLatch existingCloseExited = new CountDownLatch(1);
        CountDownLatch releaseExistingClose = new CountDownLatch(1);
        streams.register("existing", () -> {
            existingCloseEntered.countDown();
            await(releaseExistingClose);
            existingCloseExited.countDown();
        });

        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                registry.closeForPlugin("ext-demo");
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        }, "plugin-stream-close-with-late-failure");
        closer.start();
        assertThat(existingCloseEntered.await(5, TimeUnit.SECONDS)).isTrue();

        IllegalStateException lateFailure = new IllegalStateException("late-close-failed");
        CountDownLatch lateCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseLateClose = new CountDownLatch(1);
        AtomicReference<Throwable> registerFailure = new AtomicReference<>();
        Thread register = new Thread(() -> {
            try {
                streams.register("late", () -> {
                    lateCloseEntered.countDown();
                    await(releaseLateClose);
                    throw lateFailure;
                });
            } catch (Throwable failure) {
                registerFailure.set(failure);
            }
        }, "plugin-stream-late-failure");
        register.start();
        assertThat(lateCloseEntered.await(5, TimeUnit.SECONDS)).isTrue();

        releaseExistingClose.countDown();
        assertThat(existingCloseExited.await(5, TimeUnit.SECONDS)).isTrue();
        awaitWaiting(closer);
        releaseLateClose.countDown();
        closer.join(5_000L);
        register.join(5_000L);

        assertThat(closer.isAlive()).isFalse();
        assertThat(register.isAlive()).isFalse();
        assertThat(closeFailure.get()).isSameAs(lateFailure);
        assertThat(registerFailure.get()).isSameAs(lateFailure);
        assertThat(registry.activeStreamCount("ext-demo")).isEqualTo(1);
    }

    @Test
    @DisplayName("空白 token 与 null 回调静默忽略，空白 owner 不能创建登记入口")
    void blankTokensAreIgnoredAndBlankOwnerIsRejected() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        PluginStreamRegistrar streams = registry.registrarForPlugin("ext-demo");
        streams.register(" ", () -> {
        });
        streams.register("s1", null);

        assertThat(registry.activeStreamCount("ext-demo")).isZero();
        assertThat(registry.closeForPlugin("ghost")).isZero();
        streams.unregister("unknown");
        assertThatThrownBy(() -> registry.registrarForPlugin(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.registrarForPlugin(" "))
                .isInstanceOf(IllegalArgumentException.class);
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

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("stream closer did not wait for the concurrent close callback");
    }
}
