package top.sywyar.pixivdownload.config;

import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutboundProxyOverride 线程级出站代理覆盖")
class OutboundProxyOverrideTest {

    @AfterEach
    void cleanup() {
        OutboundProxyOverride.clear();
    }

    @Test
    @DisplayName("parse：合法 host:port 解析为 HTTP 代理")
    void parsesValidHostPort() {
        HttpHost host = OutboundProxyOverride.parse("127.0.0.1:7890");
        assertThat(host).isNotNull();
        assertThat(host.getHostName()).isEqualTo("127.0.0.1");
        assertThat(host.getPort()).isEqualTo(7890);
        assertThat(host.getSchemeName()).isEqualTo("http");
    }

    @Test
    @DisplayName("parse：空白 / 缺端口 / 端口非法一律返回 null")
    void rejectsInvalidValues() {
        assertThat(OutboundProxyOverride.parse(null)).isNull();
        assertThat(OutboundProxyOverride.parse("  ")).isNull();
        assertThat(OutboundProxyOverride.parse("127.0.0.1")).isNull();
        assertThat(OutboundProxyOverride.parse("127.0.0.1:")).isNull();
        assertThat(OutboundProxyOverride.parse("127.0.0.1:abc")).isNull();
        assertThat(OutboundProxyOverride.parse("127.0.0.1:0")).isNull();
        assertThat(OutboundProxyOverride.parse("127.0.0.1:70000")).isNull();
        assertThat(OutboundProxyOverride.parse(":7890")).isNull();
    }

    @Test
    @DisplayName("set/clear：覆盖只对当前线程可见，clear 后消失")
    void setAndClearArePerThread() throws Exception {
        OutboundProxyOverride.set("10.0.0.1:8080");
        assertThat(OutboundProxyOverride.current()).isNotNull();

        AtomicReference<HttpHost> seenByOtherThread = new AtomicReference<>();
        Thread other = new Thread(() -> seenByOtherThread.set(OutboundProxyOverride.current()));
        other.start();
        other.join();
        assertThat(seenByOtherThread.get()).isNull();

        OutboundProxyOverride.clear();
        assertThat(OutboundProxyOverride.current()).isNull();
    }

    @Test
    @DisplayName("set：传入空白或非法值等同于不设置覆盖")
    void blankOrInvalidSetMeansNoOverride() {
        OutboundProxyOverride.set(null);
        assertThat(OutboundProxyOverride.current()).isNull();
        OutboundProxyOverride.set("   ");
        assertThat(OutboundProxyOverride.current()).isNull();
        OutboundProxyOverride.set("not-a-proxy");
        assertThat(OutboundProxyOverride.current()).isNull();
    }
}
