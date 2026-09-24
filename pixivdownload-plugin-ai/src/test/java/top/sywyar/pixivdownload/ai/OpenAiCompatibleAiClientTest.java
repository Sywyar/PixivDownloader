package top.sywyar.pixivdownload.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.preset.AiPresetRegistry;
import top.sywyar.pixivdownload.ai.probe.ConnectivityProbeRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("OpenAI 兼容聊天与模型列表")
class OpenAiCompatibleAiClientTest {

    @ParameterizedTest
    @CsvSource({"openai,max_completion_tokens,max_tokens", "openrouter,max_tokens,max_completion_tokens"})
    @DisplayName("内置 OpenAI 与路由预设保留温度和 JSON 输出，并传递兼容的输出上限")
    void presetsUseCompatibleParameters(String presetId, String limitField, String absentLimit) throws Exception {
        String model = new AiPresetRegistry().findById(presetId).orElseThrow().defaultModel();
        RestTemplate direct = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(direct).build();
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value(model))
                .andExpect(jsonPath("$.temperature").value(0.3))
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andExpect(jsonPath("$." + limitField).value(256))
                .andExpect(jsonPath("$." + absentLimit).doesNotExist())
                .andExpect(jsonPath("$.reasoning_split").doesNotExist())
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}",
                        MediaType.APPLICATION_JSON));
        var result = client(direct).chatTest("test", new AiClientSettings(
                "https://example.test/v1", "", model, false), new ConnectivityProbeRequest().toMessages(),
                AiChatOptions.json().withTemperature(0.3).withMaxTokens(256));
        assertThat(result.content()).isEqualTo("{}");
        server.verify();
    }

    @Test
    @DisplayName("MiniMax 分离思考字段，业务层仅收到回答正文")
    void minimaxSeparatesReasoning() throws Exception {
        String model = new AiPresetRegistry().findById("minimax").orElseThrow().defaultModel();
        RestTemplate direct = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(direct).build();
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.reasoning_split").value(true))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"OK",
                        "reasoning_content":"internal reasoning","reasoning_details":[]}}]}
                        """, MediaType.APPLICATION_JSON));
        var result = client(direct).chatTest("test", new AiClientSettings(
                "https://example.test/v1", "", model, false),
                new ConnectivityProbeRequest().toMessages(), AiChatOptions.defaults());
        assertThat(result.content()).isEqualTo("OK");
        server.verify();
    }

    @Test
    @DisplayName("自定义模型继续使用原有协议字段且不附加模型专用参数")
    void customModelKeepsOriginalParameters() throws Exception {
        RestTemplate direct = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(direct).build();
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        config.setBaseUrl("https://example.test/v1");
        config.setModel("user-selected-model");
        var client = new OpenAiCompatibleAiClient(config, mock(MessageResolver.class), direct, new RestTemplate());
        server.expect(requestTo("https://example.test/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value(config.getModel()))
                .andExpect(jsonPath("$.max_tokens").value(256))
                .andExpect(jsonPath("$.temperature").value(0.3))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andExpect(jsonPath("$.reasoning_split").doesNotExist())
                .andExpect(jsonPath("$.max_completion_tokens").doesNotExist())
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}",
                        MediaType.APPLICATION_JSON));
        assertThat(client.chat("test", new ConnectivityProbeRequest().toMessages(),
                AiChatOptions.defaults().withTemperature(0.3).withMaxTokens(256)).content()).isEqualTo("OK");
        server.verify();
    }

    @Test
    @DisplayName("GET models 使用当前鉴权并返回排序去重后的有界模型")
    void listsModelsWithCurrentSettings() throws Exception {
        RestTemplate direct = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(direct).build();
        OpenAiCompatibleAiClient client = client(direct);
        server.expect(requestTo("https://example.test/v1/models"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test-secret"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"id":"zeta","owned_by":"vendor"},
                          {"id":"alpha","owned_by":"owner"},
                          {"id":"alpha","owned_by":"duplicate"},
                          {"id":"   ","owned_by":"ignored"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        var models = client.listModels(new AiClientSettings(
                "https://example.test/v1/", "sk-test-secret", "", false));

        assertThat(models).extracting(model -> model.id())
                .containsExactly("alpha", "zeta");
        assertThat(models.get(0).ownedBy()).isEqualTo("owner");
        server.verify();
    }

    @Test
    @DisplayName("模型接口错误不会回显 API Key")
    void redactsApiKeyFromModelListFailure() {
        RestTemplate direct = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(direct).build();
        OpenAiCompatibleAiClient client = client(direct);
        String apiKey = "sk-test-secret-value";
        server.expect(requestTo("https://example.test/v1/models"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"api_key\":\"" + apiKey + "\"}"));

        assertThatThrownBy(() -> client.listModels(new AiClientSettings(
                "https://example.test/v1", apiKey, "", false)))
                .isInstanceOf(AiClientException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining(apiKey);
        server.verify();
    }

    private static OpenAiCompatibleAiClient client(RestTemplate direct) {
        return new OpenAiCompatibleAiClient(
                new AiConfig(), mock(MessageResolver.class), direct, new RestTemplate());
    }
}
