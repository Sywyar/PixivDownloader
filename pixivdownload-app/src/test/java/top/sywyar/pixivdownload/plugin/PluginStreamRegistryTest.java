package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStream;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginStreamRegistry;

/**
 * 插件推流宿主注册中心测试：按 pluginId 注册 / 注销可关闭推流、{@code closeForPlugin} 关闭并清退（不残留引用、
 * 隔离单个回调异常、只作用于目标插件），以及关闭回调反向触发 {@code unregister} 的安全 no-op。
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
    @DisplayName("register + closeForPlugin：关闭该插件全部推流，返回关闭数，且不再残留引用")
    void closeForPluginClosesAllAndClearsRefs() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        RecordingStream a = new RecordingStream();
        RecordingStream b = new RecordingStream();
        registry.register("ext-demo", "s1", a);
        registry.register("ext-demo", "s2", b);
        assertThat(registry.activeStreamCount("ext-demo")).isEqualTo(2);

        int closed = registry.closeForPlugin("ext-demo");

        assertThat(closed).isEqualTo(2);
        assertThat(a.closedCount).isEqualTo(1);
        assertThat(b.closedCount).isEqualTo(1);
        // 关闭后不残留任何回调引用（连接不成为泄漏点）
        assertThat(registry.activeStreamCount("ext-demo")).isZero();
    }

    @Test
    @DisplayName("unregister 摘除推流：closeForPlugin 不再触达它，且不调用其关闭回调")
    void unregisterDetachesStream() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        RecordingStream a = new RecordingStream();
        registry.register("ext-demo", "s1", a);

        registry.unregister("ext-demo", "s1");
        assertThat(registry.activeStreamCount("ext-demo")).isZero();

        assertThat(registry.closeForPlugin("ext-demo")).isZero();
        assertThat(a.closedCount).isZero(); // 注销不触发关闭回调
    }

    @Test
    @DisplayName("普通失败不妨碍其它流关闭且失败项保留到重试成功")
    void closeForPluginRetainsOnlyFailingStreamForRetry() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        RecordingStream failing = new RecordingStream();
        failing.fail = true;
        RecordingStream healthy = new RecordingStream();
        registry.register("ext-demo", "bad", failing);
        registry.register("ext-demo", "good", healthy);

        assertThatThrownBy(() -> registry.closeForPlugin("ext-demo"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom-close");

        assertThat(failing.closedCount).isEqualTo(1); // 被调用过并保留
        assertThat(healthy.closedCount).isEqualTo(1); // 不受影响
        assertThat(registry.activeStreamCount("ext-demo")).isEqualTo(1);

        failing.fail = false;
        int closed = registry.closeForPlugin("ext-demo");

        assertThat(closed).isEqualTo(1);
        assertThat(failing.closedCount).isEqualTo(2);
        assertThat(healthy.closedCount).isEqualTo(1); // 成功项不重复关闭
        assertThat(registry.activeStreamCount("ext-demo")).isZero();
    }

    @Test
    @DisplayName("致命失败按原对象延后重抛且其它流仍会尝试关闭")
    void fatalFailureKeepsIdentityAfterClosingOtherStreams() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        IllegalStateException ordinary = new IllegalStateException("ordinary-first");
        OutOfMemoryError fatal = new OutOfMemoryError("stream-fatal");
        AtomicInteger ordinaryCalls = new AtomicInteger();
        AtomicInteger fatalCalls = new AtomicInteger();
        RecordingStream healthy = new RecordingStream();
        registry.register("ext-demo", "ordinary", () -> {
            ordinaryCalls.incrementAndGet();
            throw ordinary;
        });
        registry.register("ext-demo", "fatal", () -> {
            fatalCalls.incrementAndGet();
            throw fatal;
        });
        registry.register("ext-demo", "healthy", healthy);

        assertThatThrownBy(() -> registry.closeForPlugin("ext-demo")).isSameAs(fatal);

        assertThat(ordinaryCalls).hasValue(1);
        assertThat(fatalCalls).hasValue(1);
        assertThat(healthy.closedCount).isEqualTo(1);
        assertThat(fatal.getSuppressed()).contains(ordinary);
        assertThat(registry.activeStreamCount("ext-demo")).isEqualTo(2);
    }

    @Test
    @DisplayName("closeForPlugin 只作用于目标插件：其它插件的推流不受影响")
    void closeForPluginAffectsOnlyTargetPlugin() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        RecordingStream mine = new RecordingStream();
        RecordingStream other = new RecordingStream();
        registry.register("ext-demo", "s1", mine);
        registry.register("ext-other", "s1", other);

        registry.closeForPlugin("ext-demo");

        assertThat(mine.closedCount).isEqualTo(1);
        assertThat(other.closedCount).isZero();
        assertThat(registry.activeStreamCount("ext-other")).isEqualTo(1);
    }

    @Test
    @DisplayName("关闭回调反向 unregister 自身：作用于已摘下的快照，安全 no-op、不重复关闭")
    void reentrantUnregisterDuringCloseIsSafe() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        AtomicInteger closes = new AtomicInteger();
        // 模拟 SSEController：关闭回调里反过来调 unregister（真实链路是 emitter.complete → onCompletion → unregister）
        registry.register("ext-demo", "s1", () -> {
            closes.incrementAndGet();
            registry.unregister("ext-demo", "s1");
        });

        int closed = registry.closeForPlugin("ext-demo");

        assertThat(closed).isEqualTo(1);
        assertThat(closes.get()).isEqualTo(1); // 只关闭一次
        assertThat(registry.activeStreamCount("ext-demo")).isZero();
    }

    @Test
    @DisplayName("关闭 admission 后迟到注册立即关闭，失败项阻断 resume 并可重试")
    void lateRegistrationClosesImmediatelyAndPendingFailureBlocksResume() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        registry.closeForPlugin("ext-demo");
        assertThat(registry.acceptsNewStreams("ext-demo")).isFalse();
        RecordingStream healthyLate = new RecordingStream();

        registry.register("ext-demo", "late-ok", healthyLate);

        assertThat(healthyLate.closedCount).isEqualTo(1);
        assertThat(registry.activeStreamCount("ext-demo")).isZero();

        RecordingStream failingLate = new RecordingStream();
        failingLate.fail = true;
        assertThatThrownBy(() -> registry.register("ext-demo", "late-failed", failingLate))
                .isInstanceOf(RuntimeException.class);
        assertThat(registry.activeStreamCount("ext-demo")).isEqualTo(1);
        assertThatThrownBy(() -> registry.resume("ext-demo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending callbacks");

        failingLate.fail = false;
        assertThat(registry.closeForPlugin("ext-demo")).isEqualTo(1);
        registry.resume("ext-demo");
        assertThat(registry.acceptsNewStreams("ext-demo")).isTrue();
    }

    @Test
    @DisplayName("关闭 callback 重入 unregister 后抛错仍恢复精确 token 供重试")
    void reentrantUnregisterThenFailureIsRestoredForRetry() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        AtomicBoolean fail = new AtomicBoolean(true);
        AtomicInteger closes = new AtomicInteger();
        PluginStream stream = () -> {
            closes.incrementAndGet();
            registry.unregister("ext-demo", "exact-token");
            if (fail.get()) {
                throw new IllegalStateException("close-after-unregister");
            }
        };
        registry.register("ext-demo", "exact-token", stream);

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
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        registry.register("ext-demo", "existing", () -> {
            closeEntered.countDown();
            try {
                if (!releaseClose.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release close");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("stream close interrupted");
            }
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
        Thread register = new Thread(() -> registry.register("ext-demo", "late", late),
                "plugin-stream-late-register");
        register.start();
        releaseClose.countDown();
        closer.join(5000);
        register.join(5000);

        assertThat(closer.isAlive()).isFalse();
        assertThat(register.isAlive()).isFalse();
        assertThat(closeFailure.get()).isNull();
        assertThat(late.closedCount).isEqualTo(1);
        assertThat(registry.activeStreamCount("ext-demo")).isZero();
        assertThat(registry.acceptsNewStreams("ext-demo")).isFalse();
    }

    @Test
    @DisplayName("关闭会等待并观察并发迟到注册的失败回调")
    void closeWaitsForAndObservesConcurrentLateRegistrationFailure() throws Exception {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        CountDownLatch existingCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseExistingClose = new CountDownLatch(1);
        registry.register("ext-demo", "existing", () -> {
            existingCloseEntered.countDown();
            await(releaseExistingClose);
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
                registry.register("ext-demo", "late", () -> {
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
        Thread.sleep(50L);
        assertThat(closer.isAlive()).isTrue();
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
    @DisplayName("空白 / null 入参与未注册插件：静默忽略，closeForPlugin 返回 0")
    void blankAndUnknownInputsAreIgnored() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        registry.register(null, "s1", () -> { });
        registry.register("ext-demo", " ", () -> { });
        registry.register("ext-demo", "s1", null);

        assertThat(registry.activeStreamCount("ext-demo")).isZero();
        assertThat(registry.closeForPlugin("ghost")).isZero();
        registry.unregister("ghost", "s1"); // 不抛
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
