package top.sywyar.pixivdownload.core.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.AiClientException;
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
    @DisplayName("恢复校验转发配置身份，已绑定调用不转接替代能力")
    void bindsExactClientAndChecksConfiguration() throws Exception {
        AiChatClient previous = mock(AiChatClient.class), replacement = mock(AiChatClient.class);
        when(previous.configurationFingerprint()).thenReturn(java.util.Optional.of("original"));
        var registry = new AiChatClientRegistry();
        registry.registerPrepared("fixture", 1, List.of(previous));
        var service = new AiService(registry);
        var bound = service.bind().orElseThrow();
        assertThat(service.configurationFingerprint()).contains("original");
        registry.unregisterPrepared("fixture", 1);
        when(previous.chatBound(anyString(), anyString(), anyList(), any()))
                .thenThrow(new ExternalCapabilityUnavailableException("withdrawn"));
        registry.registerPrepared("fixture", 2, List.of(replacement));
        assertThatThrownBy(() -> bound.chatBound("original", "translation", List.of(), null))
                .isInstanceOf(ExternalCapabilityUnavailableException.class);
        org.mockito.Mockito.verifyNoInteractions(replacement);
        registry.unregisterPrepared("fixture", 2);
        assertThat(service.bind()).isEmpty();
        assertThat(service.configurationFingerprint()).isEmpty();
    }

    @Test
    @DisplayName("能力撤回后配置探测按未配置降级")
    void withdrawnClientIsNotConfigured() {
        AiChatClient client = mock(AiChatClient.class);
        when(client.isConfigured()).thenThrow(new ExternalCapabilityUnavailableException("withdrawn"));
        AiChatClientRegistry registry = new AiChatClientRegistry();
        registry.register("test", List.of(client));
        AiService service = new AiService(registry);

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("能力撤回后的聊天调用投影为受控插件不可用异常")
    void withdrawnClientChatIsControlled() throws Exception {
        AiChatClient client = mock(AiChatClient.class);
        when(client.chat(anyString(), anyList(), any()))
                .thenThrow(new ExternalCapabilityUnavailableException("withdrawn"));
        AiChatClientRegistry registry = new AiChatClientRegistry();
        registry.register("test", List.of(client));
        AiService service = new AiService(registry);

        assertThatThrownBy(() -> service.chat("test", List.of(), null))
                .isInstanceOf(AiClientException.class)
                .hasMessage("AI plugin unavailable")
                .hasNoCause();
    }
}
