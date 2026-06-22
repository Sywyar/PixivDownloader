package top.sywyar.pixivdownload.core.download.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
            @Override
            public String queueType() {
                return queueType;
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
}
