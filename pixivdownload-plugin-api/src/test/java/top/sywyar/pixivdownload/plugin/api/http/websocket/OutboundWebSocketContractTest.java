package top.sywyar.pixivdownload.plugin.api.http.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpProxyProvider;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("出站 WebSocket 稳定契约")
class OutboundWebSocketContractTest {

    @Test
    @DisplayName("公开表面只暴露 profile、请求、连接与关闭语义")
    void publicSurfaceHasExactTransportNeutralShape() throws Exception {
        assertThat(OutboundWebSocketClientProfile.class.isRecord()).isTrue();
        assertThat(Arrays.stream(OutboundWebSocketClientProfile.class.getRecordComponents())
                .map(component -> component.getName()).toList())
                .containsExactly("connectTimeout", "route");
        assertThat(Arrays.stream(OutboundWebSocketClientProfile.class.getRecordComponents())
                .map(component -> component.getType().getName()).toList())
                .containsExactly(Duration.class.getName(), OutboundHttpRoute.class.getName());

        assertThat(OutboundWebSocketRequest.class.isRecord()).isTrue();
        assertThat(Arrays.stream(OutboundWebSocketRequest.class.getRecordComponents())
                .map(component -> component.getName()).toList())
                .containsExactly("uri", "headers");
        assertThat(Arrays.stream(OutboundWebSocketRequest.class.getRecordComponents())
                .map(component -> component.getType().getName()).toList())
                .containsExactly(URI.class.getName(), Map.class.getName());

        assertThat(OutboundWebSocketClient.class.getInterfaces())
                .containsExactly(AutoCloseable.class);
        Method connect = OutboundWebSocketClient.class.getDeclaredMethod(
                "connect", OutboundWebSocketRequest.class, WebSocket.Listener.class);
        assertThat(connect.getReturnType()).isEqualTo(CompletableFuture.class);
        assertThat(connect.getGenericReturnType().getTypeName())
                .isEqualTo(CompletableFuture.class.getName() + "<" + WebSocket.class.getName() + ">");
        assertThat(connect.getExceptionTypes()).isEmpty();

        Method close = OutboundWebSocketClient.class.getDeclaredMethod("close");
        assertThat(close.getReturnType()).isEqualTo(void.class);
        assertThat(close.getExceptionTypes()).isEmpty();

        Method open = OutboundWebSocketClientFactory.class.getDeclaredMethod(
                "open", OutboundWebSocketClientProfile.class);
        assertThat(open.getReturnType()).isEqualTo(OutboundWebSocketClient.class);
        assertThat(open.getExceptionTypes()).isEmpty();
    }

    @Test
    @DisplayName("profile 拒绝空值与非正连接超时")
    void profileRejectsInvalidConnectionSettings() {
        assertThatThrownBy(() -> new OutboundWebSocketClientProfile(
                Duration.ZERO, OutboundHttpRoute.inherit()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectTimeout");
        assertThatThrownBy(() -> new OutboundWebSocketClientProfile(
                Duration.ofSeconds(-1), OutboundHttpRoute.inherit()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectTimeout");
        assertThatThrownBy(() -> new OutboundWebSocketClientProfile(
                null, OutboundHttpRoute.inherit()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("connectTimeout");
        assertThatThrownBy(() -> new OutboundWebSocketClientProfile(
                Duration.ofSeconds(1), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("route");

        OutboundWebSocketClientProfile profile = new OutboundWebSocketClientProfile(
                Duration.ofSeconds(15), OutboundHttpRoute.direct());

        assertThat(profile.connectTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(profile.route()).isEqualTo(OutboundHttpRoute.direct());
    }

    @Test
    @DisplayName("请求只接受绝对 WS URI 且保留原始编码")
    void requestAcceptsOnlyAbsoluteWebSocketTargets() {
        OutboundWebSocketRequest request = request(
                URI.create("wss://example.test/path?signature=%2B"));

        assertThat(request.uri().toASCIIString())
                .isEqualTo("wss://example.test/path?signature=%2B");

        for (String invalid : List.of(
                "/relative",
                "https://example.test/socket",
                "ws:/missing-authority",
                "wss://user@example.test/socket",
                "wss://example.test/socket#fragment")) {
            assertThatThrownBy(() -> request(URI.create(invalid)))
                    .as("非法 WebSocket URI: %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WS(S)");
        }
    }

    @Test
    @DisplayName("请求防御性复制头并按大小写不敏感语义合并同名值")
    void requestDefensivelyCopiesAndMergesHeaderCasings() {
        List<String> firstValues = new ArrayList<>(List.of("one"));
        List<String> secondValues = new ArrayList<>(List.of("two", "three"));
        Map<String, List<String>> source = new LinkedHashMap<>();
        source.put("X-Trace", firstValues);
        source.put("x-trace", secondValues);

        OutboundWebSocketRequest request =
                new OutboundWebSocketRequest(URI.create("wss://example.test/socket"), source);

        firstValues.add("late");
        secondValues.clear();
        source.put("X-Late", List.of("ignored"));

        assertThat(request.headers()).hasSize(1);
        assertThat(request.headers()).containsKey("x-TrAcE");
        assertThat(request.headers().get("X-TRACE")).containsExactly("one", "two", "three");
        assertThatThrownBy(() -> request.headers().put("X-Test", List.of("value")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.headers().get("x-trace").add("value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new OutboundWebSocketRequest(
                URI.create("ws://example.test/socket"), null).headers()).isEmpty();
    }

    @Test
    @DisplayName("请求与连接 profile 的字符串表示不泄露握手材料")
    void requestAndProfileHaveRedactedStringRepresentations() {
        String uriSecret = "uri-secret-canary";
        String headerSecret = "header-secret-canary";
        OutboundWebSocketRequest request = new OutboundWebSocketRequest(
                URI.create("wss://example.test/socket?TrustedClientToken=" + uriSecret),
                Map.of(
                        "Authorization", List.of("Bearer " + headerSecret),
                        "Cookie", List.of("session=" + headerSecret)));

        assertThat(request.toString())
                .contains("headerCount=2")
                .doesNotContain(
                        "example.test", uriSecret, headerSecret,
                        "TrustedClientToken", "Authorization", "Cookie");

        AtomicInteger providerToStringCalls = new AtomicInteger();
        OutboundHttpProxyProvider provider = new OutboundHttpProxyProvider() {
            @Override
            public URI resolveProxyUri() {
                return URI.create("http://127.0.0.1:7890");
            }

            @Override
            public String toString() {
                providerToStringCalls.incrementAndGet();
                return "provider-secret-canary";
            }
        };
        OutboundWebSocketClientProfile profile = new OutboundWebSocketClientProfile(
                Duration.ofSeconds(5),
                OutboundHttpRoute.requiredExplicitProxy(provider));

        assertThat(profile.toString()).doesNotContain("provider-secret-canary");
        assertThat(providerToStringCalls).hasValue(0);
    }

    @Test
    @DisplayName("请求拒绝非法 header token、空值与 CR LF 注入")
    void requestRejectsInvalidHeadersAndLineBreakInjection() {
        URI uri = URI.create("wss://example.test/socket");

        assertThatThrownBy(() -> new OutboundWebSocketRequest(
                uri, Map.of("X-Test\r\nInjected", List.of("value"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CR or LF");
        assertThatThrownBy(() -> new OutboundWebSocketRequest(
                uri, Map.of("X-Test", List.of("value\nInjected: yes"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CR or LF");
        for (String invalidName : List.of(
                "X Test",
                "X:Test",
                "X\tTest",
                "X-测试")) {
            assertThatThrownBy(() -> new OutboundWebSocketRequest(
                    uri, Map.of(invalidName, List.of("value"))))
                    .as("非法 header name: %s", invalidName)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTP token");
        }

        Map<String, List<String>> nullValues = new LinkedHashMap<>();
        nullValues.put("X-Test", null);
        assertThatThrownBy(() -> new OutboundWebSocketRequest(uri, nullValues))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("values");

        List<String> valueWithNull = new ArrayList<>();
        valueWithNull.add(null);
        assertThatThrownBy(() -> new OutboundWebSocketRequest(
                uri, Map.of("X-Test", valueWithNull)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");
    }

    private static OutboundWebSocketRequest request(URI uri) {
        return new OutboundWebSocketRequest(uri, Map.of());
    }
}
