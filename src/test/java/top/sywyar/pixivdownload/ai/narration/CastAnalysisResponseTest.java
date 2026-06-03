package top.sywyar.pixivdownload.ai.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("有声书选角响应解析与名册整理")
class CastAnalysisResponseTest {

    @Test
    @DisplayName("解析旁白 + 角色，名册旁白居首、角色随后")
    void parseRoster() {
        CastAnalysisResponse r = CastAnalysisResponse.parse(
                "{\"narrator\":{\"instruction\":\"A calm storyteller.\"},"
                        + "\"characters\":[{\"id\":1,\"name\":\"哀家\",\"gender\":\"female\",\"age\":\"elderly\","
                        + "\"instruction\":\"An elderly woman, low and cold voice.\"}]}");
        assertTrue(r.ok());
        List<NarrationCharacter> roster = r.roster();
        assertEquals(2, roster.size());
        assertEquals(0, roster.get(0).id());
        assertTrue(roster.get(0).narrator());
        assertEquals("A calm storyteller.", roster.get(0).controlInstruction());
        NarrationCharacter c = roster.get(1);
        assertEquals(1, c.id());
        assertEquals("哀家", c.name());
        assertEquals("female", c.gender());
        assertEquals("elderly", c.age());
        assertFalse(c.narrator());
    }

    @Test
    @DisplayName("容忍代码围栏；非法 gender/age 归一为 unknown")
    void parseWrappedAndNormalizeAttributes() {
        CastAnalysisResponse r = CastAnalysisResponse.parse(
                "```json\n{\"narrator\":{\"instruction\":\"N\"},\"characters\":["
                        + "{\"id\":1,\"name\":\"X\",\"gender\":\"robot\",\"age\":\"???\",\"instruction\":\"V\"}]}\n```");
        NarrationCharacter c = r.roster().get(1);
        assertEquals("unknown", c.gender());
        assertEquals("unknown", c.age());
    }

    @Test
    @DisplayName("旁白缺失时用默认旁白音色兜底，仍至少含一名旁白")
    void missingNarratorFallsBack() {
        CastAnalysisResponse r = CastAnalysisResponse.parse("{\"characters\":[]}");
        assertFalse(r.ok());
        List<NarrationCharacter> roster = r.roster();
        assertEquals(1, roster.size());
        assertEquals(NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION, roster.get(0).controlInstruction());
    }

    @Test
    @DisplayName("忽略 id≤0 / 重复 id / 缺 instruction 的角色")
    void dropsInvalidCharacters() {
        CastAnalysisResponse r = CastAnalysisResponse.parse(
                "{\"narrator\":{\"instruction\":\"N\"},\"characters\":["
                        + "{\"id\":0,\"name\":\"假旁白\",\"instruction\":\"x\"},"
                        + "{\"id\":1,\"name\":\"A\",\"instruction\":\"a\"},"
                        + "{\"id\":1,\"name\":\"A-别名\",\"instruction\":\"dup\"},"
                        + "{\"id\":2,\"name\":\"B\"}]}");
        List<NarrationCharacter> roster = r.roster();
        // 旁白 + 仅 id=1 一个有效角色（id=0 顶替旁白被弃、id=1 重复保留首个、id=2 缺 instruction 被弃）
        assertEquals(2, roster.size());
        assertEquals(1, roster.get(1).id());
        assertEquals("A", roster.get(1).name());
    }

    @Test
    @DisplayName("空内容或无法解析时抛出 IllegalArgumentException")
    void parseEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> CastAnalysisResponse.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CastAnalysisResponse.parse("not json"));
    }
}
