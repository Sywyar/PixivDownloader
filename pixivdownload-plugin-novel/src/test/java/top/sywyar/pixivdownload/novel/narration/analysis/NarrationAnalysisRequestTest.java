package top.sywyar.pixivdownload.novel.narration.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("朗读分析请求（提示词 / 花名册 / nextId）")
class NarrationAnalysisRequestTest {

    @Test
    @DisplayName("toMessages：system 规范 + user（携带花名册画像、nextId 与本段句子）")
    void toMessagesShape() {
        NarrationAnalysisRequest req = new NarrationAnalysisRequest(
                List.of(NarrationCharacter.defaultNarrator()), List.of("第一句。", "第二句。"), 1);
        List<AiChatMessage> messages = req.toMessages();
        assertEquals(2, messages.size());
        assertEquals(AiChatMessage.ROLE_SYSTEM, messages.get(0).role());
        String user = messages.get(1).content();
        assertTrue(user.contains(NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION), "花名册应带旁白画像");
        assertTrue(user.contains("ids >= 1"), "应给出新角色起始 id");
        assertTrue(user.contains("第一句。"), "应带本段句子");
    }

    @Test
    @DisplayName("系统提示词要求新角色音色画像详细多维度且彼此区分，且仍输出严格 JSON")
    void systemPromptDemandsDetailedDistinctVoices() {
        String system = new NarrationAnalysisRequest(
                List.of(NarrationCharacter.defaultNarrator()), List.of("x"), 1)
                .toMessages().get(0).content();
        assertTrue(system.contains("DETAILED"), "应要求详细画像");
        assertTrue(system.contains("pitch register"), "应逐条限定音高等维度");
        assertTrue(system.contains("DISTINGUISHABLE"), "应要求与旁白 / 其它角色区分");
        assertTrue(system.contains("STRICT JSON"), "JSON 契约不变");
    }
}
