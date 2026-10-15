package top.sywyar.pixivdownload.novel.narration.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.AiClientException;
import top.sywyar.pixivdownload.ai.model.AiChatResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("多角色朗读分析编排服务（合并单次按段）")
class NarrationScriptServiceTest {

    private static AiChatResult result(String content) {
        return new AiChatResult(content, "stop", null, null, null);
    }

    private static NarrationCharacter narrator(String instr) {
        return new NarrationCharacter(0, "Narrator", "unknown", "unknown", instr, true, false);
    }

    private static NarrationCharacter ch(int id, String name, String instr) {
        return new NarrationCharacter(id, name, "female", "elderly", instr, false, false);
    }

    @Test
    @DisplayName("analyzeSegment：一次返回逐句归属 + 新角色 + 补充；新角色临时 id 在合法集合内被保留")
    void analyzeSegmentReturnsMergedResult() throws Exception {
        AiChatClient ai = mock(AiChatClient.class);
        String json = "{\"lines\":[{\"i\":0,\"speaker\":1,\"delivery\":\"angry, faster\"},"
                + "{\"i\":1,\"speaker\":2,\"delivery\":\"\"}],"
                + "\"newCharacters\":[{\"id\":2,\"name\":\"少年\",\"gender\":\"male\",\"age\":\"teen\","
                + "\"instruction\":\"A bright teenage boy.\"}],"
                + "\"updatedCharacters\":[{\"id\":1,\"name\":\"皇太后\",\"instruction\":\"refined\"}],"
                + "\"conflicts\":[{\"id\":1,\"type\":\"incomplete\",\"reason\":\"missing age\",\"suggestion\":\"older\"}]}";
        when(ai.chat(eq(NarrationAnalysisRequest.CALL_TYPE), any(), any())).thenReturn(result(json));
        NarrationScriptService service = new NarrationScriptService(ai);

        NarrationSegmentAnalysis a = service.analyzeSegment(
                List.of(narrator("N"), ch(1, "哀家", "V1")), List.of("s0", "s1"), 2);

        assertEquals(2, a.lines().size());
        assertEquals(1, a.lines().get(0).speakerId());
        assertEquals("angry, faster", a.lines().get(0).delivery());
        assertEquals(2, a.lines().get(1).speakerId()); // 新角色临时 id 2 在合法集合内 → 保留
        assertEquals(1, a.newCharacters().size());
        assertEquals(2, a.newCharacters().get(0).id());
        assertEquals("少年", a.newCharacters().get(0).name());
        assertEquals("refined", a.updatedCharacters().get(1));
        assertEquals("皇太后", a.renamedCharacters().get(1));
        assertEquals(List.of(new NarrationConflict(1, "incomplete", "missing age", "older")), a.conflicts());
    }

    @Test
    @DisplayName("analyzeSegment：AI 关闭 / 失败 -> 该段整段归旁白，无新角色 / 补充 / 冲突")
    void analyzeSegmentFallsBackToNarrator() throws Exception {
        AiChatClient ai = mock(AiChatClient.class);
        when(ai.chat(eq(NarrationAnalysisRequest.CALL_TYPE), any(), any()))
                .thenThrow(new AiClientException("ai disabled"));
        NarrationScriptService service = new NarrationScriptService(ai);

        NarrationSegmentAnalysis a = service.analyzeSegment(
                List.of(narrator("N"), ch(1, "哀家", "V1")), List.of("a", "b"), 2);

        assertEquals(2, a.lines().size());
        assertEquals(0, a.lines().get(0).speakerId());
        assertEquals(0, a.lines().get(1).speakerId());
        assertTrue(a.newCharacters().isEmpty());
        assertTrue(a.updatedCharacters().isEmpty());
        assertTrue(a.conflicts().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"2,1,1", "5,1,3", "2,3,3"})
    @DisplayName("analyzeSegment：新角色临时 id 小于 nextId / 撞既有名册时整段归旁白")
    void analyzeSegmentFallsBackWhenNewCharacterIdConflictsWithRoster(int nextId, int existingId, int newId) throws Exception {
        AiChatClient ai = mock(AiChatClient.class);
        String json = "{\"lines\":[{\"i\":0,\"speaker\":1,\"delivery\":\"confused\"}],"
                + "\"newCharacters\":[{\"id\":" + newId + ",\"name\":\"少年\",\"gender\":\"male\",\"age\":\"teen\","
                + "\"instruction\":\"A bright teenage boy.\"}],"
                + "\"updatedCharacters\":[],\"conflicts\":[]}";
        when(ai.chat(eq(NarrationAnalysisRequest.CALL_TYPE), any(), any())).thenReturn(result(json));
        NarrationScriptService service = new NarrationScriptService(ai);

        NarrationSegmentAnalysis a = service.analyzeSegment(
                List.of(narrator("N"), ch(existingId, "哀家", "V1")), List.of("s0"), nextId);

        assertEquals(1, a.lines().size());
        assertEquals(0, a.lines().get(0).speakerId());
        assertTrue(a.newCharacters().isEmpty());
        assertTrue(a.updatedCharacters().isEmpty());
        assertTrue(a.conflicts().isEmpty());
    }

    @Test
    @DisplayName("新角色先丢弃空项、非法项并按临时编号去重，再参与逐句归属")
    void analyzesNormalizedCharacters() throws Exception {
        AiChatClient ai = mock(AiChatClient.class);
        when(ai.chat(eq(NarrationAnalysisRequest.CALL_TYPE), any(), any())).thenReturn(result("""
                {"lines":[{"i":0,"speaker":2}],"newCharacters":[null,{},
                 {"id":2,"name":"少年","instruction":"first"},
                 {"id":2,"name":"重复","instruction":"second"},
                 {"id":3,"name":"无画像"}]}
                """));
        NarrationSegmentAnalysis analysis = new NarrationScriptService(ai)
                .analyzeSegment(List.of(narrator("N")), List.of("a", "b"), 2);
        assertEquals(List.of(2, 0), analysis.lines().stream().map(NarrationLineVoice::speakerId).toList());
        assertEquals(List.of(new NarrationCharacter(2, "少年", "unknown", "unknown", "first", false, false)),
                analysis.newCharacters());
        assertTrue(analysis.updatedCharacters().isEmpty());
        assertTrue(analysis.renamedCharacters().isEmpty());
        assertTrue(analysis.conflicts().isEmpty());
    }

    @Test
    @DisplayName("buildScript：用最终名册 + 逐句归属合成 Control Instruction（基底 + 逐句微调），每行带 delivery")
    void buildScriptAssemblesLines() {
        NarrationScriptService service = new NarrationScriptService(mock(AiChatClient.class));
        List<NarrationCharacter> roster = List.of(
                narrator("A calm storyteller."), ch(1, "哀家", "An elderly woman, low and cold voice."));
        List<NarrationLineVoice> voices = List.of(
                new NarrationLineVoice(0, 1, "angry, faster"),
                new NarrationLineVoice(1, 0, ""));

        NarrationScript script = service.buildScript(roster, List.of("l0", "l1"), voices);

        assertTrue(script.multiVoice());
        NarrationScript.Line l0 = script.lines().get(0);
        assertEquals(1, l0.speakerId());
        assertEquals("哀家", l0.speakerName());
        assertEquals("angry, faster", l0.delivery());
        assertEquals("An elderly woman, low and cold voice, angry, faster", l0.controlInstruction());
        NarrationScript.Line l1 = script.lines().get(1);
        assertEquals(0, l1.speakerId());
        assertEquals("", l1.delivery());
        assertEquals("A calm storyteller.", l1.controlInstruction());
    }

    @Test
    @DisplayName("buildScript：缺失逐句归属的句子归旁白；结果与输入句子等长")
    void buildScriptFillsMissingWithNarrator() {
        NarrationScriptService service = new NarrationScriptService(mock(AiChatClient.class));
        List<NarrationCharacter> roster = List.of(narrator("A calm storyteller."));
        NarrationScript script = service.buildScript(roster, List.of("a", "b"), List.of());
        assertFalse(script.multiVoice());
        assertEquals(2, script.lines().size());
        for (NarrationScript.Line line : script.lines()) {
            assertEquals(0, line.speakerId());
            assertEquals("A calm storyteller.", line.controlInstruction());
        }
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
