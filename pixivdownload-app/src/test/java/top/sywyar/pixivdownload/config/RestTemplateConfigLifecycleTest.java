package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("宿主 HTTP 客户端的上下文生命周期")
class RestTemplateConfigLifecycleTest {

    @ParameterizedTest
    @ValueSource(strings = {"restTemplate", "pixivCredentialRestTemplate",
            "downloadRestTemplate", "pixivImageRestTemplate"})
    @DisplayName("关闭上下文释放空闲连接且不影响另一上下文")
    void closesOwnedConnectionsWithoutClosingAnotherContext(String beanName) throws Exception {
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var first = context();
             var second = context()) {
            server.setSoTimeout(5_000);
            try (Socket firstConnection = exchange(first.getBean(beanName, RestTemplate.class), server, requests)) {
                first.close();
                assertThat(firstConnection.getInputStream().read()).isEqualTo(-1);
                try (Socket secondConnection = exchange(second.getBean(beanName, RestTemplate.class), server, requests)) {
                    second.close();
                    assertThat(secondConnection.getInputStream().read()).isEqualTo(-1);
                }
            }
        } finally {
            requests.shutdownNow();
            assertThat(requests.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(ProxyConfig.class, () -> {
            var config = new ProxyConfig();
            config.setEnabled(false);
            return config;
        });
        context.register(RestTemplateConfig.class);
        context.refresh();
        return context;
    }

    private static Socket exchange(RestTemplate client, ServerSocket server, ExecutorService requests) throws Exception {
        var response = requests.submit(() -> client.getForObject(
                "http://127.0.0.1:" + server.getLocalPort() + "/", byte[].class));
        Socket connection = server.accept();
        try {
            connection.setSoTimeout(5_000);
            var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            while (!reader.readLine().isEmpty()) {
                // 读完真实请求头后再返回可复用连接的响应。
            }
            connection.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
                    + "Connection: keep-alive\r\n\r\nOK").getBytes(StandardCharsets.US_ASCII));
            connection.getOutputStream().flush();
            assertThat(response.get(5, TimeUnit.SECONDS)).isEqualTo("OK".getBytes(StandardCharsets.US_ASCII));
            return connection;
        } catch (Exception | AssertionError failure) {
            connection.close();
            throw failure;
        }
    }
}
