package top.sywyar.pixivdownload.ai.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("合并单次分析响应解析与对齐")
class NarrationAnalysisResponseTest {

    private static final Set<Integer> CAST = Set.of(0, 1, 2);

    @Test
    @DisplayName("解析标准 JSON：逐句下标 / 说话人 / 情绪微调")
    void parseLines() {
        NarrationAnalysisResponse r = NarrationAnalysisResponse.parse(
                "{\"lines\":[{\"i\":0,\"speaker\":0,\"delivery\":\"\"},"
                        + "{\"i\":1,\"speaker\":1,\"delivery\":\"cold, slow\"}],"
                        + "\"newCharacters\":[],\"updatedCharacters\":[],\"conflicts\":[]}");
        List<NarrationLineVoice> lines = r.normalizedTo(2, CAST);
        assertEquals(0, lines.get(0).speakerId());
        assertEquals("", lines.get(0).delivery());
        assertEquals(1, lines.get(1).speakerId());
        assertEquals("cold, slow", lines.get(1).delivery());
    }

    @Test
    @DisplayName("normalizedTo：名册外 speaker 归旁白、缺失句子归旁白、对齐到输入句数")
    void normalizeOutOfRangeAndMissing() {
        NarrationAnalysisResponse r = NarrationAnalysisResponse.parse(
                "{\"lines\":[{\"i\":0,\"speaker\":2,\"delivery\":\"\"},"
                        + "{\"i\":1,\"speaker\":99,\"delivery\":\"x\"}]}");
        List<NarrationLineVoice> lines = r.normalizedTo(3, CAST);
        assertEquals(3, lines.size());
        assertEquals(2, lines.get(0).speakerId());   // 合法
        assertEquals(0, lines.get(1).speakerId());   // 名册外 99 → 旁白
        assertEquals(0, lines.get(2).speakerId());   // 模型未给第 3 句 → 旁白
    }

    @Test
    @DisplayName("normalizedTo：新角色临时 id 在合法集合内时按其归属保留")
    void normalizeKeepsNewCharacterTempId() {
        NarrationAnalysisResponse r = NarrationAnalysisResponse.parse(
                "{\"lines\":[{\"i\":0,\"speaker\":3,\"delivery\":\"\"}],"
                        + "\"newCharacters\":[{\"id\":3,\"name\":\"少年\",\"gender\":\"male\",\"age\":\"teen\","
                        + "\"instruction\":\"A bright teenage boy.\"}]}");
        // 合法集合 = 名册 id ∪ 新角色临时 id
        List<NarrationLineVoice> lines = r.normalizedTo(1, Set.of(0, 1, 2, 3));
        assertEquals(3, lines.get(0).speakerId());
    }

    @Test
    @DisplayName("newCharacters：去重 / 丢弃非法（id≤0、缺 name / instruction）、归一 gender/age")
    void parseNewCharacters() {
        NarrationAnalysisResponse r = NarrationAnalysisResponse.parse(
                "{\"lines\":[],\"newCharacters\":["
                        + "{\"id\":3,\"name\":\"哀家\",\"gender\":\"robot\",\"age\":\"???\",\"instruction\":\"An old woman.\"},"
                        + "{\"id\":3,\"name\":\"哀家别名\",\"instruction\":\"dup id\"},"
                        + "{\"id\":0,\"name\":\"假旁白\",\"instruction\":\"x\"},"
                        + "{\"id\":4,\"name\":\"无音色\"},"
                        + "{\"id\":5,\"instruction\":\"no name\"}]}");
        List<NarrationCharacter> news = r.newCharacters();
        assertEquals(1, news.size());
        NarrationCharacter c = news.get(0);
        assertEquals(3, c.id());
        assertEquals("哀家", c.name());
        assertEquals("unknown", c.gender());
        assertEquals("unknown", c.age());
        assertEquals("An old woman.", c.controlInstruction());
        assertTrue(!c.narrator() && !c.editedByUser());
    }

    @Test
    @DisplayName("updatedCharacters：解析为「id → 画像」，丢弃空 instruction")
    void parseUpdatedCharacters() {
        NarrationAnalysisResponse r = NarrationAnalysisResponse.parse(
                "{\"lines\":[],\"updatedCharacters\":["
                        + "{\"id\":1,\"instruction\":\"A richer, warmer voice.\"},"
                        + "{\"id\":2,\"instruction\":\"\"}]}");
        Map<Integer, String> updates = r.updatedCharacters();
        assertEquals(1, updates.size());
        assertEquals("A richer, warmer voice.", updates.get(1));
    }

    @Test
    @DisplayName("renamedCharacters：解析 updatedCharacters 里可选 name，按 id 给出改名；缺 name 不计、instruction 仍独立解析")
    void parseRenamedCharacters() {
        NarrationAnalysisResponse r = NarrationAnalysisResponse.parse(
                "{\"lines\":[],\"updatedCharacters\":["
                        + "{\"id\":1,\"name\":\"莱蒂西亚\",\"instruction\":\"A young noblewoman.\"},"
                        + "{\"id\":2,\"instruction\":\"only refine\"},"
                        + "{\"id\":3,\"name\":\"  \"}]}");
        Map<Integer, String> renamed = r.renamedCharacters();
        assertEquals(1, renamed.size());
        assertEquals("莱蒂西亚", renamed.get(1));
        // 同条目的 instruction 仍走 updatedCharacters（改名与补充互不影响）
        Map<Integer, String> updated = r.updatedCharacters();
        assertEquals("A young noblewoman.", updated.get(1));
        assertEquals("only refine", updated.get(2));
    }

    @Test
    @DisplayName("conflicts：保留合法 type + 非空 suggestion，丢弃非法 type / 空 suggestion")
    void parseConflicts() {
        NarrationAnalysisResponse r = NarrationAnalysisResponse.parse(
                "{\"lines\":[],\"conflicts\":["
                        + "{\"id\":1,\"type\":\"contradiction\",\"reason\":\"stored male, text is female\","
                        + "\"suggestion\":\"An elderly woman, low and cold.\"},"
                        + "{\"id\":2,\"type\":\"incomplete\",\"reason\":\"missing age\",\"suggestion\":\"A child voice.\"},"
                        + "{\"id\":3,\"type\":\"bogus\",\"reason\":\"r\",\"suggestion\":\"s\"},"
                        + "{\"id\":4,\"type\":\"contradiction\",\"reason\":\"r\",\"suggestion\":\"\"}]}");
        List<NarrationConflict> conflicts = r.conflicts();
        assertEquals(2, conflicts.size());
        assertEquals(1, conflicts.get(0).characterId());
        assertEquals(NarrationConflict.TYPE_CONTRADICTION, conflicts.get(0).type());
        assertEquals("An elderly woman, low and cold.", conflicts.get(0).suggestion());
        assertEquals(2, conflicts.get(1).characterId());
        assertEquals(NarrationConflict.TYPE_INCOMPLETE, conflicts.get(1).type());
    }

    @Test
    @DisplayName("容忍代码围栏包裹的 JSON")
    void parseWrapped() {
        NarrationAnalysisResponse r = NarrationAnalysisResponse.parse(
                "```json\n{\"lines\":[{\"i\":0,\"speaker\":1,\"delivery\":\"\"}]}\n```");
        assertEquals(1, r.normalizedTo(1, CAST).get(0).speakerId());
    }

    @Test
    @DisplayName("空内容或无法解析时抛出 IllegalArgumentException")
    void parseEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> NarrationAnalysisResponse.parse(""));
        assertThrows(IllegalArgumentException.class, () -> NarrationAnalysisResponse.parse("not json"));
    }
}
