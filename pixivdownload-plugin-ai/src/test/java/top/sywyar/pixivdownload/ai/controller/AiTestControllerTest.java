package top.sywyar.pixivdownload.ai.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.model.AiChatResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AI 测试的远程入口与本机边界")
class AiTestControllerTest {
    @Test
    @DisplayName("临时设置用于管理员探测且模型回显不泄漏密钥，旧 GUI 仍拒绝远程")
    void adminUsesTemporarySettingsAndRedactsReply() throws Exception {
        var client = mock(AiChatClient.class);
        var controller = new AiTestController(client);
        var body = new AiTestRequest("https://example.test/v1", "fixture-secret", "fixture-model", true);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.2"); request.addHeader("Host", "example.test");
        assertThat(controller.test(body, request).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(client);
        when(client.chatTest(anyString(), any(), anyList(), any()))
                .thenReturn(new AiChatResult("reply fixture-secret", "stop", 1, 1, 2));
        var result = controller.testAdmin(body);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().toString()).contains("[redacted]").doesNotContain("fixture-secret");
        verify(client).chatTest(anyString(), eq(body.toClientSettings()), anyList(), any());
    }
}
