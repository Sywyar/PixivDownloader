package top.sywyar.pixivdownload.plugin.runtime.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResourceException;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResourceScope;
import top.sywyar.pixivdownload.plugin.api.resource.PluginResources;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件资源作用域与任务真实排空")
class PluginRuntimeResourcesTest {
    private final PluginRuntimeTaskRegistry registry = new PluginRuntimeTaskRegistry();
    private final PluginResources resources = registry.resourcesForPlugin("owner");

    @Test
    @DisplayName("局部作用域逆序幂等清理且不影响相邻作用域")
    void localCloseIsReverseOrderedAndIndependent() {
        var first = resources.openScope();
        var second = resources.openScope();
        List<String> closed = new ArrayList<>();
        first.acquire(() -> () -> closed.add("first"));
        first.acquire(() -> () -> closed.add("last"));
        second.acquire(() -> () -> closed.add("second"));
        first.close();
        first.close();
        assertThat(closed).containsExactly("last", "first");

        var drain = registry.prepareQuiesce("owner");
        registry.closeResources("owner", drain);
        assertThat(closed).containsExactly("last", "first", "second");
        registry.closeResources("owner", drain);
    }

    @Test
    @DisplayName("关闭失败继续清理其它资源并只重试失败项")
    void failedResourceRemainsOwnedAndBlocksReplacement() {
        var scope = resources.openScope();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        scope.acquire(() -> () -> {
            if (attempts.incrementAndGet() == 1) throw new Exception("private-detail");
        });
        scope.acquire(() -> closed::incrementAndGet);
        var drain = registry.prepareQuiesce("owner");
        assertThatThrownBy(() -> registry.closeResources("owner", drain))
                .isInstanceOf(PluginResourceException.class).hasMessage("PLUGIN_RESOURCE_CLOSE_FAILED")
                .hasNoCause();
        assertThat(closed).hasValue(1);
        assertThatThrownBy(() -> registry.resume("owner")).isInstanceOf(IllegalStateException.class);
        registry.closeResources("owner", drain);
        registry.resume("owner");
        assertThat(attempts).hasValue(2);
        assertThat(closed).hasValue(1);
    }

    @Test
    @DisplayName("旧入口与迟到回调不触达替换代际")
    void lateCallbacksAndFactoriesCannotReachReplacement() {
        var scope = resources.openScope();
        AtomicInteger calls = new AtomicInteger();
        Runnable callback = scope.guard(calls::incrementAndGet);
        callback.run();
        var drain = registry.prepareQuiesce("owner");
        callback.run();
        assertThatThrownBy(() -> scope.acquire(() -> {
            calls.incrementAndGet();
            return () -> {};
        })).hasMessage("PLUGIN_RESOURCE_CLOSED");
        registry.closeResources("owner", drain);
        registry.resume("owner");
        var replacement = registry.resourcesForPlugin("owner").openScope();
        Runnable newCallback = replacement.guard(calls::incrementAndGet);
        assertThatThrownBy(resources::openScope).hasMessage("PLUGIN_RESOURCE_CLOSED");
        assertThatThrownBy(() -> registry.closeResources("owner", drain)).isInstanceOf(IllegalStateException.class);
        scope.close();
        callback.run();
        newCallback.run();
        assertThat(calls).hasValue(2);
        replacement.close();
    }

    @Test
    @DisplayName("在途回调占用任务 drain，局部关闭不能提前释放资源")
    void runningCallbackUsesExistingTaskDrain() throws Exception {
        var scope = resources.openScope();
        AtomicInteger closed = new AtomicInteger();
        scope.acquire(() -> closed::incrementAndGet);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread worker = start(scope.guard(() -> {
            entered.countDown();
            await(release);
        }), failure);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = registry.prepareQuiesce("owner");
            registry.cancelQuiescedTasks("owner", drain);
            assertThat(drain.activeCount()).isEqualTo(1);
            assertThatThrownBy(scope::close).hasMessage("PLUGIN_RESOURCE_BUSY");
            assertThatThrownBy(() -> registry.closeResources("owner", drain)).isInstanceOf(IllegalStateException.class);
            assertThat(closed).hasValue(0);
            release.countDown();
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(drain.isDrained()).isTrue();
            registry.closeResources("owner", drain);
            assertThat(closed).hasValue(1);
        } finally {
            release.countDown();
            worker.join(5000);
        }
    }

    @Test
    @DisplayName("关闭期间迟到返回的工厂仍由原作用域接管并释放")
    void lateFactoryResultRemainsOwnedUntilDrain() throws Exception {
        var scope = resources.openScope();
        AtomicInteger closed = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread worker = start(() -> scope.acquire(() -> {
            entered.countDown();
            await(release);
            return closed::incrementAndGet;
        }), failure);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = registry.prepareQuiesce("owner");
            assertThat(drain.isDrained()).isFalse();
            assertThatThrownBy(scope::close).hasMessage("PLUGIN_RESOURCE_BUSY");
            release.countDown();
            worker.join(5000);
            assertThat(failure.get()).isNull();
            assertThat(closed).hasValue(0);
            registry.closeResources("owner", drain);
            assertThat(closed).hasValue(1);
        } finally {
            release.countDown();
            worker.join(5000);
        }
    }

    @Test
    @DisplayName("回调与工厂异常不泄漏任务租约，致命关闭错误保持身份")
    void exceptionsReleaseLeasesAndFatalCloseIsNotHidden() {
        PluginResourceScope scope = resources.openScope();
        var failure = new IllegalArgumentException("callback-failure");
        assertThatThrownBy(() -> scope.guard(() -> { throw failure; }).run()).isSameAs(failure);
        assertThatThrownBy(() -> scope.acquire(() -> { throw failure; })).isSameAs(failure);
        assertThat(registry.activeTaskCount("owner")).isZero();
        var fatal = new OutOfMemoryError("fixture");
        AtomicInteger attempts = new AtomicInteger();
        scope.acquire(() -> () -> {
            if (attempts.incrementAndGet() == 1) throw fatal;
        });
        var drain = registry.prepareQuiesce("owner");
        assertThatThrownBy(() -> registry.closeResources("owner", drain)).isSameAs(fatal);
        registry.closeResources("owner", drain);
        assertThat(attempts).hasValue(2);
    }

    private static Thread start(Runnable task, AtomicReference<Throwable> failure) {
        Thread worker = new Thread(() -> {
            try { task.run(); } catch (Throwable thrown) { failure.set(thrown); }
        });
        worker.start();
        return worker;
    }

    @Test
    @DisplayName("资源关闭重入返回忙碌，原关闭可重试且新工厂不执行")
    void reentrantCloseRetainsOnlyPendingResource() {
        var scope = resources.openScope();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger forbidden = new AtomicInteger();
        scope.acquire(() -> () -> {
            assertThatThrownBy(() -> scope.acquire(() -> {
                forbidden.incrementAndGet();
                return () -> {};
            })).hasMessage("PLUGIN_RESOURCE_CLOSED");
            if (attempts.incrementAndGet() == 1) scope.close();
        });
        assertThatThrownBy(scope::close).hasMessage("PLUGIN_RESOURCE_BUSY");
        scope.close();
        scope.close();
        assertThat(attempts).hasValue(2);
        assertThat(forbidden).hasValue(0);
    }

    @Test
    @DisplayName("并发关闭不会同时执行 disposer，宿主保留资源直至关闭返回")
    void concurrentCloseKeepsHostOwnershipUntilDisposerReturns() throws Exception {
        var scope = resources.openScope();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        scope.acquire(() -> () -> {
            closes.incrementAndGet();
            entered.countDown();
            await(release);
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = start(scope::close, failure);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var drain = registry.prepareQuiesce("owner");
            assertThatThrownBy(() -> registry.closeResources("owner", drain)).hasMessage("PLUGIN_RESOURCE_BUSY");
            assertThatThrownBy(() -> registry.resume("owner")).isInstanceOf(IllegalStateException.class);
            release.countDown();
            worker.join(5000);
            assertThat(failure.get()).isNull();
            registry.closeResources("owner", drain);
            assertThat(closes).hasValue(1);
        } finally {
            release.countDown();
            worker.join(5000);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback did not finish");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
