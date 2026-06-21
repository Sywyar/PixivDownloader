package top.sywyar.pixivdownload.core.schedule.work;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScheduledWorkRunnerRegistry} 测试：按 kind 解析、重复 kind fail-fast、缺执行器返回空、
 * 快照不可变、register / unregister 可逆。
 */
@DisplayName("作品类型执行器注册中心")
class ScheduledWorkRunnerRegistryTest {

    /** 仅承载 kind 的测试桩执行器（download 永真，不参与本测试断言）。 */
    private static ScheduledWorkRunner runner(String kind) {
        return new ScheduledWorkRunner() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public boolean download(ScheduledWork work, ScheduledWorkSettings settings, String cookie) {
                return true;
            }
        };
    }

    @Test
    @DisplayName("按 kind 解析执行器；未知 / null / 空白 kind 返回空")
    void resolvesByKind() {
        ScheduledWorkRunner illust = runner(ScheduledWorkKind.ILLUST);
        ScheduledWorkRunner novel = runner(ScheduledWorkKind.NOVEL);
        ScheduledWorkRunnerRegistry registry = new ScheduledWorkRunnerRegistry(List.of(illust, novel));

        assertThat(registry.resolve(ScheduledWorkKind.ILLUST)).containsSame(illust);
        assertThat(registry.resolve(ScheduledWorkKind.NOVEL)).containsSame(novel);
        assertThat(registry.resolve("manga")).isEmpty();
        assertThat(registry.resolve(null)).isEmpty();
        assertThat(registry.resolve("  ")).isEmpty();
    }

    @Test
    @DisplayName("空注册中心：任意 kind 解析返回空（缺执行器即不可用），不抛异常")
    void emptyRegistryResolvesEmpty() {
        ScheduledWorkRunnerRegistry registry = new ScheduledWorkRunnerRegistry(List.of());
        assertThat(registry.resolve(ScheduledWorkKind.NOVEL)).isEmpty();
        assertThat(registry.runners()).isEmpty();
    }

    @Test
    @DisplayName("重复 kind：构造期 fail-fast")
    void duplicateKindFailsFast() {
        assertThatThrownBy(() -> new ScheduledWorkRunnerRegistry(
                List.of(runner(ScheduledWorkKind.ILLUST), runner(ScheduledWorkKind.ILLUST))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate scheduled work runner kind");
    }

    @Test
    @DisplayName("kind 为空白的执行器：注册期 fail-fast")
    void blankKindFailsFast() {
        assertThatThrownBy(() -> new ScheduledWorkRunnerRegistry(List.of(runner(" "))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without kind");
    }

    @Test
    @DisplayName("runners() 快照不可变：返回的列表不可修改")
    void runnersSnapshotImmutable() {
        ScheduledWorkRunnerRegistry registry = new ScheduledWorkRunnerRegistry(
                List.of(runner(ScheduledWorkKind.ILLUST)));
        List<ScheduledWorkRunner> snapshot = registry.runners();
        assertThat(snapshot).hasSize(1);
        assertThatThrownBy(() -> snapshot.add(runner(ScheduledWorkKind.NOVEL)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("register / unregister 可逆：注册后可解析，注销后返回空、其它 kind 不受影响")
    void registerUnregisterIsReversible() {
        ScheduledWorkRunner illust = runner(ScheduledWorkKind.ILLUST);
        ScheduledWorkRunnerRegistry registry = new ScheduledWorkRunnerRegistry(List.of(illust));

        ScheduledWorkRunner novel = runner(ScheduledWorkKind.NOVEL);
        registry.register(List.of(novel));
        assertThat(registry.resolve(ScheduledWorkKind.NOVEL)).containsSame(novel);

        registry.unregister(ScheduledWorkKind.NOVEL);
        assertThat(registry.resolve(ScheduledWorkKind.NOVEL)).isEmpty();
        // 注销 novel 不影响 illust
        assertThat(registry.resolve(ScheduledWorkKind.ILLUST)).containsSame(illust);
        // 注销未注册的 kind 静默返回
        registry.unregister("manga");
        assertThat(registry.runners()).containsExactly(illust);
    }

    @Test
    @DisplayName("register 与既有 kind 冲突：fail-fast 且既有快照不被污染")
    void registerConflictKeepsExistingSnapshot() {
        ScheduledWorkRunner illust = runner(ScheduledWorkKind.ILLUST);
        ScheduledWorkRunnerRegistry registry = new ScheduledWorkRunnerRegistry(List.of(illust));

        assertThatThrownBy(() -> registry.register(List.of(runner(ScheduledWorkKind.ILLUST))))
                .isInstanceOf(IllegalStateException.class);
        // 失败注册不污染既有快照
        assertThat(registry.resolve(ScheduledWorkKind.ILLUST)).containsSame(illust);
        assertThat(registry.runners()).containsExactly(illust);
    }
}
