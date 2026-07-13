package top.sywyar.pixivdownload.core.download.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QueueOperationRegistry} 测试：按 queueType 解析、重复 queueType fail-fast、缺操作返回空、
 * 快照不可变、register / unregister 可逆（注册 → 注销 → 再注册后快照一致，为运行期装卸载下的队列操作回收复用）。
 */
@DisplayName("跨类型队列宿主操作注册中心")
class QueueOperationRegistryTest {

    /** 仅承载 queueType 的测试桩操作（清空恒返回 0，不参与本测试断言）。 */
    private static QueueOperations ops(String queueType) {
        return new QueueOperations() {
            private final QueueTaskTracker tracker = new QueueTaskTracker(
                    queueType == null || queueType.isBlank() ? "test-probe" : queueType);

            @Override
            public String queueType() {
                return queueType;
            }

            @Override
            public QueueGenerationDrain prepareQuiesce() {
                return tracker.prepareQuiesce();
            }

            @Override
            public void cancelQuiescedTasks() {
                tracker.cancelQuiescedTasks();
            }

            @Override
            public int clearAll() {
                return 0;
            }

            @Override
            public int clearForOwner(String ownerUuid) {
                return 0;
            }
        };
    }

    @Test
    @DisplayName("按 queueType 解析操作；未知 / null / 空白 queueType 返回空")
    void resolvesByQueueType() {
        QueueOperations illust = ops("illust");
        QueueOperations novel = ops("novel");
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of(illust, novel));

        assertThat(registry.resolve("illust")).containsSame(illust);
        assertThat(registry.resolve("novel")).containsSame(novel);
        assertThat(registry.resolve("manga")).isEmpty();
        assertThat(registry.resolve(null)).isEmpty();
        assertThat(registry.resolve("  ")).isEmpty();
    }

    @Test
    @DisplayName("按 owner 精确返回本代操作且不混入父上下文或其它插件")
    void resolvesExactOwnerOperations() {
        QueueOperations parent = ops("parent");
        QueueOperations first = ops("first");
        QueueOperations second = ops("second");
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of(parent));
        registry.register("owner-a", List.of(first));
        registry.register("owner-b", List.of(second));

        List<QueueOperationRegistry.OwnedQueueOperations> ownerA = registry.operationsForOwner("owner-a");

        assertThat(ownerA).singleElement().satisfies(owned -> {
            assertThat(owned.queueType()).isEqualTo("first");
            assertThat(owned.operations()).isSameAs(first);
        });
        assertThat(ownerA).noneMatch(owned -> owned.operations() == parent || owned.operations() == second);
        assertThatThrownBy(() -> ownerA.add(
                new QueueOperationRegistry.OwnedQueueOperations("second", second)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(registry.operationsForOwner("missing")).isEmpty();
    }

    @Test
    @DisplayName("空注册中心：任意 queueType 解析返回空（缺操作即不可用），all() 为空、不抛异常")
    void emptyRegistryResolvesEmpty() {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        assertThat(registry.resolve("novel")).isEmpty();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    @DisplayName("重复 queueType：构造期 fail-fast")
    void duplicateQueueTypeFailsFast() {
        assertThatThrownBy(() -> new QueueOperationRegistry(List.of(ops("illust"), ops("illust"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate queue operations type");
    }

    @Test
    @DisplayName("queueType 为空白的操作：注册期 fail-fast")
    void blankQueueTypeFailsFast() {
        assertThatThrownBy(() -> new QueueOperationRegistry(List.of(ops(" "))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without queueType");
    }

    @Test
    @DisplayName("all() 快照不可变：返回的列表不可修改")
    void allSnapshotImmutable() {
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of(ops("illust")));
        List<QueueOperations> snapshot = registry.all();
        assertThat(snapshot).hasSize(1);
        assertThatThrownBy(() -> snapshot.add(ops("novel")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("register / unregister 可逆：注册后可解析，注销后返回空、其它 queueType 不受影响")
    void registerUnregisterIsReversible() {
        QueueOperations illust = ops("illust");
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of(illust));

        QueueOperations novel = ops("novel");
        registry.register(List.of(novel));
        assertThat(registry.resolve("novel")).containsSame(novel);

        registry.unregister("novel");
        assertThat(registry.resolve("novel")).isEmpty();
        // 注销 novel 不影响 illust
        assertThat(registry.resolve("illust")).containsSame(illust);
        // 注销未注册的 queueType 静默返回
        registry.unregister("manga");
        assertThat(registry.all()).containsExactly(illust);

        // 再注册：快照恢复到与首次注册等价
        registry.register(List.of(novel));
        assertThat(registry.resolve("novel")).containsSame(novel);
        assertThat(registry.all()).containsExactly(illust, novel);
    }

    @Test
    @DisplayName("register 与既有 queueType 冲突：fail-fast 且既有快照不被污染")
    void registerConflictKeepsExistingSnapshot() {
        QueueOperations illust = ops("illust");
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of(illust));

        assertThatThrownBy(() -> registry.register(List.of(ops("illust"))))
                .isInstanceOf(IllegalStateException.class);
        // 失败注册不污染既有快照
        assertThat(registry.resolve("illust")).containsSame(illust);
        assertThat(registry.all()).containsExactly(illust);
    }

    @Test
    @DisplayName("注册恰好读取一次 queueType 并把捕获键用于后续 owner 快照")
    void registrationCapturesQueueTypeExactlyOnce() {
        AtomicInteger getterCalls = new AtomicInteger();
        QueueOperations flaky = forwardingOperations(() -> {
            if (getterCalls.incrementAndGet() > 1) {
                throw new AssertionError("queueType read more than once");
            }
            return "captured";
        });
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());

        registry.register("owner-a", List.of(flaky));

        assertThat(getterCalls).hasValue(1);
        assertThat(registry.operationsForOwner("owner-a")).singleElement().satisfies(owned -> {
            assertThat(owned.queueType()).isEqualTo("captured");
            assertThat(owned.operations()).isSameAs(flaky);
        });
    }

    @Test
    @DisplayName("queueType 捕获失败时旧快照保持原子不变")
    void captureFailureKeepsOldSnapshot() {
        QueueOperations stable = ops("stable");
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of(stable));
        QueueOperations invalid = forwardingOperations(() -> null);

        assertThatThrownBy(() -> registry.register("owner-a", List.of(invalid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without queueType");

        assertThat(registry.resolve("stable")).containsSame(stable);
        assertThat(registry.all()).containsExactly(stable);
    }

    @Test
    @DisplayName("阻塞的插件 queueType getter 不占 registry lock，resolve 与 unregister 可继续")
    void blockingGetterDoesNotHoldRegistryLock() throws Exception {
        QueueOperations existing = ops("existing");
        QueueOperationRegistry registry = new QueueOperationRegistry(List.of());
        registry.register("owner-existing", List.of(existing));
        CountDownLatch getterEntered = new CountDownLatch(1);
        CountDownLatch releaseGetter = new CountDownLatch(1);
        QueueOperations blocking = forwardingOperations(() -> {
            getterEntered.countDown();
            try {
                if (!releaseGetter.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release queueType getter");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("queueType getter interrupted");
            }
            return "blocking";
        });
        AtomicReference<Throwable> registerFailure = new AtomicReference<>();
        Thread register = new Thread(() -> {
            try {
                registry.register("owner-blocking", List.of(blocking));
            } catch (Throwable failure) {
                registerFailure.set(failure);
            }
        }, "blocking-queue-registration");
        register.start();
        assertThat(getterEntered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(registry.resolve("existing")).containsSame(existing);
        Thread unregister = new Thread(() -> registry.unregisterOwner("owner-existing"),
                "concurrent-queue-unregister");
        unregister.start();
        unregister.join(1000);
        assertThat(unregister.isAlive()).isFalse();
        assertThat(registry.resolve("existing")).isEmpty();

        releaseGetter.countDown();
        register.join(5000);
        assertThat(register.isAlive()).isFalse();
        assertThat(registerFailure.get()).isNull();
        assertThat(registry.resolve("blocking")).containsSame(blocking);
    }

    private static QueueOperations forwardingOperations(java.util.function.Supplier<String> queueType) {
        QueueTaskTracker tracker = new QueueTaskTracker("test-forwarding");
        return new QueueOperations() {
            @Override public String queueType() { return queueType.get(); }
            @Override public QueueGenerationDrain prepareQuiesce() { return tracker.prepareQuiesce(); }
            @Override public void cancelQuiescedTasks() { tracker.cancelQuiescedTasks(); }
            @Override public int clearAll() { return 0; }
            @Override public int clearForOwner(String ownerUuid) { return 0; }
        };
    }
}
