package top.sywyar.pixivdownload.ai.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.ai.OpenAiCompatibleAiClient;
import top.sywyar.pixivdownload.ai.model.AiModelInfo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AI GUI 模型查询端点")
class AiModelsControllerTest {

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
