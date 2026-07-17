package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务级出站代理覆盖 ThreadLocal 的<b>清理契约</b>测试：覆盖 set/clear 往返、{@link OutboundProxyOverride#runScoped}
 * 结束（含异常）后必定清除、跨任务不串用，以及覆盖只承载纯 JDK 代理端点（不持插件类型引用）。
 *
 * <p>钉死「{@code OutboundProxyOverride} 等任务级 ThreadLocal 不持有插件类型引用、任务结束后清理」这一不变量：
 * 调度 / 下载池线程是共享池，覆盖必须在任务结束后清除，避免跨插件 / 跨任务上下文污染。
 */
@DisplayName("出站代理覆盖 ThreadLocal 清理契约")
class OutboundProxyOverrideContractTest {

    @AfterEach
    void tearDown() {
        // 防御：即便断言失败也清掉可能残留的覆盖，避免污染同线程后续测试。
        OutboundProxyOverride.clear();
    }

    @Test
    @DisplayName("set 设置覆盖、clear 清除：clear 后 current() 为 null")
    void setThenClearLeavesNoOverride() {
        OutboundProxyOverride.set("10.0.0.1:8080");
        OutboundProxyEndpoint host = OutboundProxyOverride.current();
        assertThat(host).isNotNull();
        assertThat(host.getHostName()).isEqualTo("10.0.0.1");
        assertThat(host.getPort()).isEqualTo(8080);
        // 覆盖只承载 core-api 的纯 JDK 值类型，不持有任何插件或 HTTP 客户端类型引用
        assertThat(host).isInstanceOf(OutboundProxyEndpoint.class);

        OutboundProxyOverride.clear();
        assertThat(OutboundProxyOverride.current()).isNull();
    }

    @Test
    @DisplayName("runScoped：任务期间设置覆盖、结束后必定清除")
    void runScopedSetsDuringAndClearsAfter() {
        AtomicReference<OutboundProxyEndpoint> during = new AtomicReference<>();

        OutboundProxyOverride.runScoped("10.0.0.2:1080", () -> during.set(OutboundProxyOverride.current()));

        assertThat(during.get()).isNotNull();
        assertThat(during.get().getHostName()).isEqualTo("10.0.0.2");
        assertThat(during.get().getPort()).isEqualTo(1080);
        // 任务结束后覆盖必定清除
        assertThat(OutboundProxyOverride.current()).isNull();
    }

    @Test
    @DisplayName("runScoped：任务抛异常时仍清除覆盖（finally 清理契约）")
    void runScopedClearsEvenWhenTaskThrows() {
        assertThatThrownBy(() -> OutboundProxyOverride.runScoped("10.0.0.3:3128", () -> {
            assertThat(OutboundProxyOverride.current()).isNotNull();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        // 异常 unwind 后覆盖仍被清除，不残留
        assertThat(OutboundProxyOverride.current()).isNull();
    }

    @Test
    @DisplayName("runScoped：null / 非法 host:port 等同不设置，仍保证结束后清除")
    void runScopedWithNullProxyClearsToo() {
        // 预置一个覆盖，模拟同线程上一任务遗留（若未清理）的残留
        OutboundProxyOverride.set("10.0.0.9:9999");

        AtomicReference<OutboundProxyEndpoint> during = new AtomicReference<>(
                new OutboundProxyEndpoint("placeholder", 80));
        OutboundProxyOverride.runScoped(null, () -> during.set(OutboundProxyOverride.current()));

        // null 代理：作用域内无覆盖（不串用上一任务的残留）
        assertThat(during.get()).isNull();
        // 结束后仍清除
        assertThat(OutboundProxyOverride.current()).isNull();
    }

    @Test
    @DisplayName("跨任务不串用：连续两个 runScoped 各自独立、互不污染")
    void sequentialScopesDoNotBleed() {
        AtomicReference<OutboundProxyEndpoint> first = new AtomicReference<>();
        AtomicReference<OutboundProxyEndpoint> second = new AtomicReference<>();

        OutboundProxyOverride.runScoped("10.0.0.4:8000", () -> first.set(OutboundProxyOverride.current()));
        OutboundProxyOverride.runScoped("10.0.0.5:8001", () -> second.set(OutboundProxyOverride.current()));

        assertThat(first.get().getPort()).isEqualTo(8000);
        assertThat(second.get().getPort()).isEqualTo(8001);
        assertThat(OutboundProxyOverride.current()).isNull();
    }

    @Test
    @DisplayName("显式直连作用域在异常后也会清理")
    void directScopeClearsAfterFailure() {
        assertThatThrownBy(() -> OutboundProxyOverride.runDirectScoped(() -> {
            assertThat(OutboundProxyOverride.isActive()).isTrue();
            assertThat(OutboundProxyOverride.current()).isNull();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(OutboundProxyOverride.isActive()).isFalse();
    }
}
