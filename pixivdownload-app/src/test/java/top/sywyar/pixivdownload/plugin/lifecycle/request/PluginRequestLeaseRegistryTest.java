package top.sywyar.pixivdownload.plugin.lifecycle.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("插件请求代际租约注册中心")
class PluginRequestLeaseRegistryTest {

    private final PluginRequestLeaseRegistry registry = new PluginRequestLeaseRegistry();

    @Test
    @DisplayName("取得计数后的 OOME 与 ThreadDeath 会补偿精确请求租约")
    void fatalAfterAcquireCompensatesExactRequestLease() {
        for (Error expected : new Error[]{new OutOfMemoryError("request-fatal"), new ThreadDeath()}) {
            PluginRequestLeaseRegistry failing = new PluginRequestLeaseRegistry(() -> {
                throw expected;
            });
            PluginRequestOwner owner = new PluginRequestOwner("stats", 9L, 10L);
            failing.publish(owner);

            assertThat(catchThrowable(() -> acquire(failing, owner))).isSameAs(expected);

            PluginRequestGenerationDrain drain = failing.withdraw(owner).orElseThrow();
            assertThat(drain.activeLeaseCount()).isZero();
            assertThat(drain.isDrained()).isTrue();
            assertThat(failing.retire(owner)).isTrue();
        }
    }

    @Test
    @DisplayName("撤回后拒绝新请求并等待已取得租约真实退出")
    void withdrawalRejectsNewRequestsAndWaitsForExistingLease() {
        PluginRequestOwner owner = new PluginRequestOwner("stats", 7L, 11L);
        registry.publish(owner);
        PluginRequestLease lease = acquire(registry, owner).orElseThrow();

        PluginRequestGenerationDrain drain = registry.withdraw(owner).orElseThrow();

        assertThat(acquire(registry, owner)).isEmpty();
        assertThat(drain.isDrained()).isFalse();
        assertThat(drain.activeLeaseCount()).isOne();
        assertThat(drain.awaitDrained(System.nanoTime() + Duration.ofMillis(5).toNanos())).isFalse();

        lease.close();

        assertThat(drain.isDrained()).isTrue();
        assertThat(drain.activeLeaseCount()).isZero();
        assertThat(drain.awaitDrained(System.nanoTime() + Duration.ofSeconds(1).toNanos())).isTrue();
    }

    @Test
    @DisplayName("重复撤回返回同一 drain 且租约关闭幂等")
    void withdrawalAndLeaseCloseAreIdempotent() {
        PluginRequestOwner owner = new PluginRequestOwner("stats", 3L, 4L);
        registry.publish(owner);
        PluginRequestLease lease = acquire(registry, owner).orElseThrow();

        PluginRequestGenerationDrain first = registry.withdraw(owner).orElseThrow();
        PluginRequestGenerationDrain second = registry.withdraw(owner).orElseThrow();
        lease.close();
        lease.close();

        assertThat(second).isSameAs(first);
        assertThat(first.activeLeaseCount()).isZero();
        assertThat(lease.isActive()).isFalse();
    }

    @Test
    @DisplayName("预分配租约在激活前关闭后永久拒绝激活且不进入 drain")
    void closingPreparedLeasePermanentlyRejectsActivation() {
        PluginRequestOwner owner = new PluginRequestOwner("stats", 12L, 13L);
        registry.publish(owner);
        PluginRequestLease lease = registry.prepareLease(owner).orElseThrow();

        lease.close();

        assertThatThrownBy(() -> registry.activate(lease))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already attempted");
        PluginRequestGenerationDrain drain = registry.withdraw(owner).orElseThrow();
        assertThat(drain.activeLeaseCount()).isZero();
        assertThat(drain.isDrained()).isTrue();
    }

    @Test
    @DisplayName("预分配租约的 close 与 activate 竞态最终只留下 CLOSED 且 drain 为零")
    void preparedLeaseCloseActivateRaceAlwaysDrains() throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            PluginRequestLeaseRegistry racing = new PluginRequestLeaseRegistry();
            PluginRequestOwner owner = new PluginRequestOwner("stats-" + attempt, 1L, attempt + 1L);
            racing.publish(owner);
            PluginRequestLease lease = racing.prepareLease(owner).orElseThrow();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> activation = executor.submit(() -> {
                    start.await();
                    try {
                        racing.activate(lease);
                    } catch (IllegalStateException ignored) {
                        // close won the exact synchronized state transition.
                    }
                    return null;
                });
                Future<?> close = executor.submit(() -> {
                    start.await();
                    lease.close();
                    return null;
                });
                start.countDown();
                activation.get(5, TimeUnit.SECONDS);
                close.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            PluginRequestGenerationDrain drain = racing.withdraw(owner).orElseThrow();
            assertThat(lease.isActive()).isFalse();
            assertThat(drain.activeLeaseCount()).isZero();
            assertThat(drain.isDrained()).isTrue();
        }
    }

    @Test
    @DisplayName("旧 serving 的迟到撤回与退休不会影响同 generation 的新 serving")
    void staleServingCannotAffectReplacement() {
        PluginRequestOwner oldOwner = new PluginRequestOwner("stats", 9L, 21L);
        PluginRequestOwner newOwner = new PluginRequestOwner("stats", 9L, 22L);
        registry.publish(oldOwner);
        PluginRequestGenerationDrain oldDrain = registry.withdraw(oldOwner).orElseThrow();
        registry.publish(newOwner);

        assertThat(registry.withdraw(oldOwner)).containsSame(oldDrain);
        assertThat(registry.currentOwner("stats")).contains(newOwner);
        try (PluginRequestLease lease = acquire(registry, newOwner).orElseThrow()) {
            assertThat(lease.owner()).isEqualTo(newOwner);
            assertThat(oldDrain.isDrained()).isTrue();
            assertThat(registry.retire(oldOwner)).isTrue();
            assertThat(registry.currentOwner("stats")).contains(newOwner);
        }
    }

    @Test
    @DisplayName("仍接收请求或仍有在途租约时拒绝退休")
    void retireRequiresWithdrawalAndDrain() {
        PluginRequestOwner owner = new PluginRequestOwner("stats", 1L, 1L);
        registry.publish(owner);

        assertThatThrownBy(() -> registry.retire(owner))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accepting");

        PluginRequestLease lease = acquire(registry, owner).orElseThrow();
        registry.withdraw(owner).orElseThrow();
        assertThatThrownBy(() -> registry.retire(owner))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active leases");

        lease.close();
        assertThat(registry.retire(owner)).isTrue();
        assertThat(registry.retire(owner)).isFalse();
        assertThat(registry.currentOwner("stats")).isEmpty();
    }

    @Test
    @DisplayName("并发 acquire 与 withdraw 以同一状态锁线性化且不会假排空")
    void acquireAndWithdrawLinearize() throws Exception {
        PluginRequestOwner owner = new PluginRequestOwner("stats", 5L, 8L);
        registry.publish(owner);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<PluginRequestLease>> acquisition = executor.submit(() -> {
                start.await();
                return acquire(registry, owner);
            });
            Future<PluginRequestGenerationDrain> withdrawal = executor.submit(() -> {
                start.await();
                return registry.withdraw(owner).orElseThrow();
            });

            start.countDown();
            Optional<PluginRequestLease> lease = acquisition.get(5, TimeUnit.SECONDS);
            PluginRequestGenerationDrain drain = withdrawal.get(5, TimeUnit.SECONDS);

            if (lease.isPresent()) {
                assertThat(drain.isDrained()).isFalse();
                lease.orElseThrow().close();
            }
            assertThat(drain.awaitDrained(System.nanoTime() + Duration.ofSeconds(1).toNanos())).isTrue();
            assertThat(acquire(registry, owner)).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("等待被中断时保留中断标记且不把活跃请求误报为归零")
    void interruptedWaitPreservesInterrupt() throws Exception {
        PluginRequestOwner owner = new PluginRequestOwner("stats", 2L, 2L);
        registry.publish(owner);
        PluginRequestLease lease = acquire(registry, owner).orElseThrow();
        PluginRequestGenerationDrain drain = registry.withdraw(owner).orElseThrow();
        CountDownLatch waiting = new CountDownLatch(1);
        boolean[] result = new boolean[1];
        boolean[] interrupted = new boolean[1];
        Thread thread = new Thread(() -> {
            waiting.countDown();
            result[0] = drain.awaitDrained();
            interrupted[0] = Thread.currentThread().isInterrupted();
        }, "plugin-request-drain-test");

        thread.start();
        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        thread.interrupt();
        thread.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thread.isAlive()).isFalse();
        assertThat(result[0]).isFalse();
        assertThat(interrupted[0]).isTrue();
        assertThat(drain.isDrained()).isFalse();
        lease.close();
    }

    private static Optional<PluginRequestLease> acquire(
            PluginRequestLeaseRegistry registry,
            PluginRequestOwner owner) {
        Optional<PluginRequestLease> prepared = registry.prepareLease(owner);
        if (prepared.isEmpty()) {
            return Optional.empty();
        }
        PluginRequestLease lease = prepared.orElseThrow();
        boolean active = false;
        try {
            active = registry.activate(lease);
            return active ? Optional.of(lease) : Optional.empty();
        } finally {
            if (!active) {
                lease.close();
            }
        }
    }
}
