package top.sywyar.pixivdownload.ai.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.ai.AiConfig;
import top.sywyar.pixivdownload.ai.OpenAiCompatibleAiClient;
import top.sywyar.pixivdownload.ai.model.AiModelInfo;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("AI GUI 模型查询端点")
class AiModelsControllerTest {

    @ParameterizedTest
    @CsvSource({
            "401,,api-key-required",
            "401,'',api-key-required",
            "401,'  ',api-key-required",
            "401,test-secret,authentication-failed",
            "403,,models-forbidden",
            "403,test-secret,models-forbidden",
            "500,test-secret,models-query-failed"
    })
    @DisplayName("模型查询区分缺少密钥、鉴权失败和访问被拒绝，且不回传服务错误正文")
    void modelQueryReportsAuthenticationStatus(int status, String apiKey, String expectedCode) {
        RestTemplate direct = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(direct).build();
        server.expect(requestTo("https://example.test/v1/models"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("upstream-secret"));
        var controller = new AiModelsController(new OpenAiCompatibleAiClient(
                new AiConfig(), mock(MessageResolver.class), direct, new RestTemplate()));

        var response = controller.models(
                new AiTestRequest("https://example.test/v1", apiKey, "", false), localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOfSatisfying(AiModelsResponse.class, body -> {
            assertThat(body.success()).isFalse();
            assertThat(body.code()).isEqualTo(expectedCode);
            assertThat(body.error()).isEqualTo("HTTP " + status);
            assertThat(body.models()).isEmpty();
            assertThat(body.count()).isZero();
        });
        server.verify();
    }

    @Test
    @DisplayName("公开模型目录允许不携带密钥查询")
    void publicModelDirectoryNeedsNoKey() {
        RestTemplate direct = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(direct).build();
        server.expect(requestTo("https://example.test/v1/models"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"public-model\"}]}", MediaType.APPLICATION_JSON));
        var controller = new AiModelsController(new OpenAiCompatibleAiClient(
                new AiConfig(), mock(MessageResolver.class), direct, new RestTemplate()));

        var response = controller.models(
                new AiTestRequest("https://example.test/v1", "", "", false), localRequest());

        assertThat(response.getBody()).isInstanceOfSatisfying(AiModelsResponse.class, body -> {
            assertThat(body.success()).isTrue();
            assertThat(body.code()).isNull();
            assertThat(body.models()).extracting(AiModelInfo::id).containsExactly("public-model");
        });
        server.verify();
    }

    @Test
    @DisplayName("本机请求返回当前服务模型")
    void localRequestReturnsModels() throws Exception {
        OpenAiCompatibleAiClient client = mock(OpenAiCompatibleAiClient.class);
        when(client.listModels(any())).thenReturn(List.of(new AiModelInfo("model-a", "vendor")));
        AiModelsController controller = new AiModelsController(client);

        var response = controller.models(
                new AiTestRequest("https://example.test/v1", "secret", "", false), localRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOfSatisfying(AiModelsResponse.class, body -> {
            assertThat(body.success()).isTrue();
            assertThat(body.count()).isEqualTo(1);
            assertThat(body.models()).extracting(AiModelInfo::id).containsExactly("model-a");
        });
    }

    @Test
    @DisplayName("非本机请求在调用外部服务前被拒绝")
    void remoteRequestIsRejectedBeforeClientCall() {
        OpenAiCompatibleAiClient client = mock(OpenAiCompatibleAiClient.class);
        AiModelsController controller = new AiModelsController(client);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.8");
        when(request.getHeader("Host")).thenReturn("localhost:6999");

        var response = controller.models(
                new AiTestRequest("https://example.test/v1", "secret", "", false), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(client);
    }

    private static HttpServletRequest localRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("Host")).thenReturn("127.0.0.1:6999");
        return request;
    }
}
