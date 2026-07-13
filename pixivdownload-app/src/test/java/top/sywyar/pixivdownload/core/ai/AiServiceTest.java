package top.sywyar.pixivdownload.core.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AI 可选插件门面")
class AiServiceTest {

    @Test
    @DisplayName("能力撤回后配置探测按未配置降级")
    void withdrawnClientIsNotConfigured() {
        AiChatClient client = mock(AiChatClient.class);
        when(client.isConfigured()).thenThrow(new ExternalCapabilityUnavailableException("withdrawn"));
        AiService service = new AiService(new AiChatClientRegistry(List.of(client)));

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("能力撤回后的聊天调用投影为受控插件不可用异常")
    void withdrawnClientChatIsControlled() throws Exception {
        AiChatClient client = mock(AiChatClient.class);
        when(client.chat(anyString(), anyList(), any()))
                .thenThrow(new ExternalCapabilityUnavailableException("withdrawn"));
        AiService service = new AiService(new AiChatClientRegistry(List.of(client)));

        assertThatThrownBy(() -> service.chat("test", List.of(), null))
                .isInstanceOf(AiService.AiException.class)
                .hasMessage("AI plugin unavailable")
                .hasNoCause();
    }
}
