package top.sywyar.pixivdownload.tts.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.AiService;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.ai.narration.CastAnalysisRequest;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.ai.narration.SpeakerAttributionRequest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("多角色朗读脚本编排服务")
class NarrationScriptServiceTest {

    private static AiChatResult result(String content) {
        return new AiChatResult(content, "stop", null, null, null);
    }

    private static final String CAST_JSON =
            "{\"narrator\":{\"instruction\":\"A calm storyteller.\"},"
                    + "\"characters\":[{\"id\":1,\"name\":\"哀家\",\"gender\":\"female\",\"age\":\"elderly\","
                    + "\"instruction\":\"An elderly woman, low and cold voice.\"}]}";

    private static final String ATTR_SPEAKER1 =
            "{\"segments\":[{\"i\":0,\"speaker\":1,\"delivery\":\"angry, faster\"}]}";

    @Test
    @DisplayName("两段式：选角 + 逐句归属，合成基底音色 + 逐句微调的 Control Instruction")
    void analyzeBuildsMultiVoiceScript() throws Exception {
        AiService ai = mock(AiService.class);
        when(ai.chat(eq(CastAnalysisRequest.CALL_TYPE), any(), any())).thenReturn(result(CAST_JSON));
        when(ai.chat(eq(SpeakerAttributionRequest.CALL_TYPE), any(), any())).thenReturn(result(ATTR_SPEAKER1));
        NarrationScriptService service = new NarrationScriptService(ai);

        NarrationScript script = service.analyze("正文……", List.of("哀家在这深宫四十年。", "（旁白描写。）"));

        assertTrue(script.multiVoice());
        assertEquals(2, script.cast().size());
        assertEquals(2, script.lines().size());
        // 第 0 句归角色 1，Control Instruction = 角色基底画像 + 逐句微调（基底末尾句点被合并）
        NarrationScript.Line l0 = script.lines().get(0);
        assertEquals(1, l0.speakerId());
        assertEquals("哀家", l0.speakerName());
        assertEquals("An elderly woman, low and cold voice, angry, faster", l0.controlInstruction());
        // 第 1 句模型没给 -> 旁白，画像为旁白基底
        NarrationScript.Line l1 = script.lines().get(1);
        assertEquals(0, l1.speakerId());
        assertEquals("A calm storyteller.", l1.controlInstruction());
    }

    @Test
    @DisplayName("选角失败 -> 全篇单一旁白，multiVoice 为 false")
    void castFailureFallsBackToSingleNarrator() throws Exception {
        AiService ai = mock(AiService.class);
        when(ai.chat(eq(CastAnalysisRequest.CALL_TYPE), any(), any()))
                .thenThrow(new AiService.AiException("ai disabled"));
        when(ai.chat(eq(SpeakerAttributionRequest.CALL_TYPE), any(), any()))
                .thenReturn(result("{\"segments\":[{\"i\":0,\"speaker\":0,\"delivery\":\"\"}]}"));
        NarrationScriptService service = new NarrationScriptService(ai);

        NarrationScript script = service.analyze("正文……", List.of("一句话。"));

        assertFalse(script.multiVoice());
        assertEquals(1, script.cast().size());
        assertEquals(0, script.lines().get(0).speakerId());
        assertEquals(NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION, script.lines().get(0).controlInstruction());
    }

    @Test
    @DisplayName("归属批次失败 -> 该批整批归旁白，但仍用真实名册的旁白音色")
    void attributionFailureFallsBackToNarrator() throws Exception {
        AiService ai = mock(AiService.class);
        when(ai.chat(eq(CastAnalysisRequest.CALL_TYPE), any(), any())).thenReturn(result(CAST_JSON));
        when(ai.chat(eq(SpeakerAttributionRequest.CALL_TYPE), any(), any()))
                .thenThrow(new AiService.AiException("boom"));
        NarrationScriptService service = new NarrationScriptService(ai);

        NarrationScript script = service.analyze("正文……", List.of("a", "b"));

        assertTrue(script.multiVoice()); // 名册仍含角色
        for (NarrationScript.Line line : script.lines()) {
            assertEquals(0, line.speakerId());
            assertEquals("A calm storyteller.", line.controlInstruction());
        }
    }

    @Test
    @DisplayName("超过单批上限时分批，并按全局偏移重排下标")
    void batchesAndOffsetsIndices() throws Exception {
        AiService ai = mock(AiService.class);
        when(ai.chat(eq(CastAnalysisRequest.CALL_TYPE), any(), any())).thenReturn(result(CAST_JSON));
        when(ai.chat(eq(SpeakerAttributionRequest.CALL_TYPE), any(), any())).thenReturn(result(ATTR_SPEAKER1));
        NarrationScriptService service = new NarrationScriptService(ai);

        int total = NarrationScriptService.MAX_SENTENCES_PER_REQUEST + 3;
        List<String> sentences = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            sentences.add("s" + i);
        }

        NarrationScript script = service.analyze("正文……", sentences);
        assertEquals(total, script.lines().size());
        for (int i = 0; i < total; i++) {
            assertEquals(i, script.lines().get(i).index());
        }
        // 每批首句（全局 0 与 MAX）归角色 1，其余旁白
        assertEquals(1, script.lines().get(0).speakerId());
        assertEquals(1, script.lines().get(NarrationScriptService.MAX_SENTENCES_PER_REQUEST).speakerId());
        assertEquals(0, script.lines().get(1).speakerId());
    }

    @Test
    @DisplayName("combine：空微调返回基底；非空微调合并并去基底末尾句点")
    void combineMergesDeliveryNote() {
        assertEquals("Base voice.", NarrationScriptService.combine("Base voice.", ""));
        assertEquals("Base voice.", NarrationScriptService.combine("Base voice.", null));
        assertEquals("Base voice, whispering", NarrationScriptService.combine("Base voice.", "whispering"));
        assertEquals("Base voice, sad", NarrationScriptService.combine("Base voice", "sad"));
    }
}
