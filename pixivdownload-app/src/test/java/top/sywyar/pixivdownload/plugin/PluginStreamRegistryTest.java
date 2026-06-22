package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("closeForPlugin 隔离单个回调异常：抛异常的流不影响其它流关闭")
    void closeForPluginIsolatesFailingStream() {
        PluginStreamRegistry registry = new PluginStreamRegistry();
        RecordingStream failing = new RecordingStream();
        failing.fail = true;
        RecordingStream healthy = new RecordingStream();
        registry.register("ext-demo", "bad", failing);
        registry.register("ext-demo", "good", healthy);

        int closed = registry.closeForPlugin("ext-demo");

        assertThat(failing.closedCount).isEqualTo(1); // 被调用过（随即抛异常被隔离）
        assertThat(healthy.closedCount).isEqualTo(1); // 不受影响
        assertThat(closed).isEqualTo(1);              // 仅成功关闭的计数
        assertThat(registry.activeStreamCount("ext-demo")).isZero();
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
}
