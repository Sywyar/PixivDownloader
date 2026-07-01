package top.sywyar.pixivdownload.ai.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("AI 连接测试探测请求实体")
class ConnectivityProbeRequestTest {

    @Test
    @DisplayName("toMessages 产出 system + user 两条固定探测消息")
    void toMessagesProducesProbe() {
        List<AiChatMessage> messages = new ConnectivityProbeRequest().toMessages();
        assertEquals(2, messages.size());
        assertEquals(AiChatMessage.ROLE_SYSTEM, messages.get(0).role());
        assertEquals(AiChatMessage.ROLE_USER, messages.get(1).role());
    }
}
