package top.sywyar.pixivdownload.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("OpenAI 兼容模型列表")
class OpenAiCompatibleAiClientTest {

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
