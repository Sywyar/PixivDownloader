package top.sywyar.pixivdownload.ai.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("翻译请求消息构造")
class TranslationRequestTest {

    @Test
    @DisplayName("无映射表时用户消息不含 Glossary 段")
    void noGlossarySection() {
        List<AiChatMessage> messages = new TranslationRequest("简体中文", "本文").toMessages();
        String user = messages.get(messages.size() - 1).content();
        assertFalse(user.contains("Glossary"));
        assertTrue(user.contains("Target language: 简体中文"));
        assertTrue(user.contains("本文"));
    }

    @Test
    @DisplayName("含映射表时把条目以 JSON 注入用户消息")
    void glossarySectionInjected() {
        TranslationRequest req = new TranslationRequest("简体中文", "ハルヒが来た",
                List.of(new GlossaryTerm("ハルヒ", "zh-CN", "春日")));
        String user = req.toMessages().get(req.toMessages().size() - 1).content();
        assertTrue(user.contains("Glossary"));
        assertTrue(user.contains("ハルヒ"));
        assertTrue(user.contains("春日"));
        assertTrue(user.contains("zh-CN"));
    }

    @Test
    @DisplayName("系统提示词固定为英文且要求回报新名词")
    void systemPromptDescribesGlossary() {
        List<AiChatMessage> messages = new TranslationRequest("english", "text").toMessages();
        String system = messages.get(0).content();
        assertEquals(AiChatMessage.ROLE_SYSTEM, messages.get(0).role());
        assertTrue(system.contains("glossary"));
    }
}
