package top.sywyar.pixivdownload.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.ai.preset.AiPresetRegistry;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("模型目录的协议、分页与资源边界")
class AiModelDiscoveryTest {
    private final RestTemplate direct = new RestTemplate();
    private final RestTemplate proxy = new RestTemplate();
    private final OpenAiCompatibleAiClient client = new OpenAiCompatibleAiClient(
            new AiConfig(), mock(MessageResolver.class), direct, proxy);

    @Test
    @DisplayName("Anthropic 使用原生鉴权和游标逐页读取并去重")
    void readsAnthropicPagesThroughProxy() throws Exception {
        String base = new AiPresetRegistry().findById("anthropic").orElseThrow().baseUrl();
        var server = MockRestServiceServer.bindTo(proxy).build();
        server.expect(requestTo(base + "/models"))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("""
                        {"data":[{"id":"test/first+item"}],"has_more":true,"last_id":"test/first+item"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/models?after_id=test%2Ffirst%2Bitem"))
                .andExpect(header("x-api-key", "test-key"))
                .andRespond(withSuccess("""
                        {"data":[{"id":"test/first+item"},{"id":"test-last"}],"has_more":false}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.listModels(new AiClientSettings(base, "test-key", "", true)))
                .extracting(model -> model.id()).containsExactly("test-last", "test/first+item");
        server.verify();
    }

    @Test
    @DisplayName("已声明不支持聊天的模型不进入候选，能力未知的模型保留")
    void filtersOnlyExplicitNonChatModels() throws Exception {
        var server = MockRestServiceServer.bindTo(direct).build();
        server.expect(requestTo("https://example.test/v1/models")).andRespond(withSuccess("""
                {"data":[{"id":"test-embedding","capabilities":{"completion_chat":false}},
                {"id":"test-image","type":"text2image"},{"id":"test-chat","type":"chat"},
                {"id":"test-unknown"},{"id":"test-capable","capabilities":{"completion_chat":true}}]}
                """, MediaType.APPLICATION_JSON));
        assertThat(client.listModels(settings())).extracting(model -> model.id())
                .containsExactly("test-capable", "test-chat", "test-unknown");
        server.verify();
    }

    @Test
    @DisplayName("超过原有摘要数量仍完整返回，超过目录预算则明确失败")
    void neverSilentlyTruncatesCatalog() throws Exception {
        var server = MockRestServiceServer.bindTo(direct).build();
        for (int count : new int[]{501, 2001}) {
            String body = IntStream.range(0, count).mapToObj(i -> "{\"id\":\"test-" + i + "\"}")
                    .collect(Collectors.joining(",", "{\"data\":[", "]}"));
            server.expect(requestTo("https://example.test/v1/models"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        assertThat(client.listModels(settings())).hasSize(501);
        assertThatThrownBy(() -> client.listModels(settings())).isInstanceOf(AiClientException.class);
        server.verify();
    }

    @Test
    @DisplayName("重复分页游标失败且不返回已取得的部分目录")
    void rejectsRepeatedCursor() {
        String base = new AiPresetRegistry().findById("anthropic").orElseThrow().baseUrl();
        var server = MockRestServiceServer.bindTo(direct).build();
        String body = "{\"data\":[{\"id\":\"test-one\"}],\"has_more\":true,\"last_id\":\"test-one\"}";
        server.expect(requestTo(base + "/models")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/models?after_id=test-one"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.listModels(new AiClientSettings(base, "", "", false)))
                .isInstanceOf(AiClientException.class);
        server.verify();
    }

    @Test
    @DisplayName("未知分页合同不会被当作完整目录返回")
    void rejectsUnsupportedPagination() {
        var server = MockRestServiceServer.bindTo(direct).build();
        server.expect(requestTo("https://example.test/v1/models")).andRespond(withSuccess(
                "{\"data\":[{\"id\":\"test-one\"}],\"has_more\":true,\"last_id\":\"test-one\"}",
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.listModels(settings())).isInstanceOf(AiClientException.class);
        server.verify();
    }

    @Test
    @DisplayName("无长度响应只读到字节预算，错误响应不读取正文")
    void boundsStreamsBeforeBuffering() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.OK, HttpStatus.UNAUTHORIZED}) {
            var server = MockRestServiceServer.bindTo(direct).build();
            AtomicInteger reads = new AtomicInteger();
            server.expect(requestTo("https://example.test/v1/models")).andRespond(request ->
                    new MockClientHttpResponse(new InputStream() {
                        @Override public int read() { reads.incrementAndGet(); return ' '; }
                    }, status));
            assertThatThrownBy(() -> client.listModels(settings())).isInstanceOf(AiClientException.class);
            assertThat(reads.get()).isEqualTo(status == HttpStatus.OK ? 2 * 1024 * 1024 + 1 : 0);
            server.verify();
        }
    }

    private static AiClientSettings settings() {
        return new AiClientSettings("https://example.test/v1", "test-key", "", false);
    }

    @Test
    @DisplayName("后续页失败与页数耗尽都拒绝整轮目录")
    void rejectsFailedOrExcessivePages() {
        String base = new AiPresetRegistry().findById("anthropic").orElseThrow().baseUrl();
        for (boolean failure : new boolean[]{true, false}) {
            var server = MockRestServiceServer.bindTo(direct).build();
            for (int i = 0; i < (failure ? 2 : 20); i++) {
                var request = server.expect(requestTo(base + "/models" + (i == 0 ? "" : "?after_id=test-" + (i - 1))));
                if (failure && i == 1) request.andRespond(withStatus(HttpStatus.BAD_GATEWAY));
                else request.andRespond(withSuccess("{\"data\":[{\"id\":\"test-" + i
                        + "\"}],\"has_more\":true,\"last_id\":\"test-" + i + "\"}", MediaType.APPLICATION_JSON));
            }
            assertThatThrownBy(() -> client.listModels(new AiClientSettings(base, "", "", false)))
                    .isInstanceOf(AiClientException.class);
            server.verify();
        }
    }
}
