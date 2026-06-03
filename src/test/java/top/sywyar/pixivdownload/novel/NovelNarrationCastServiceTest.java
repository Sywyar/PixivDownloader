package top.sywyar.pixivdownload.novel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.tts.narration.NarrationScriptService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("朗读花名册入册（与名词记录机制一致）")
class NovelNarrationCastServiceTest {

    private static NarrationCharacter narrator(String instr) {
        return new NarrationCharacter(0, "Narrator", "unknown", "unknown", instr, true);
    }

    private static NarrationCharacter ch(int id, String name, String instr) {
        return new NarrationCharacter(id, name, "female", "elderly", instr, false);
    }

    private NovelNarrationCastService service(NovelMapper mapper) {
        return new NovelNarrationCastService(mapper, mock(NovelDatabase.class), mock(NarrationScriptService.class));
    }

    @Test
    @DisplayName("已有角色不重复入册，仅新角色按 max+1 分配新 id 并写入")
    void enrollOnlyAddsNewByName() {
        NovelMapper mapper = mock(NovelMapper.class);
        when(mapper.findNarrationVoices(7L)).thenReturn(List.of(narrator("N"), ch(1, "哀家", "V1")));
        service(mapper).enrollCharacters(7L,
                List.of(narrator("N2"), ch(9, "哀家", "改写"), ch(9, "太后", "An old woman.")));

        // 新角色「太后」按 max(1)+1 = 2 入册
        verify(mapper).insertNarrationVoiceIfAbsent(eq(7L), eq(2), eq("太后"),
                any(), any(), eq("An old woman."), anyLong());
        // 已在册的「哀家」不被重复写入（音色不被 AI 覆盖）
        verify(mapper, never()).insertNarrationVoiceIfAbsent(eq(7L), anyInt(), eq("哀家"),
                any(), any(), any(), anyLong());
        // 旁白已存在，不再插入
        verify(mapper, never()).insertNarrationVoiceIfAbsent(eq(7L), eq(0), any(),
                any(), any(), any(), anyLong());
        verify(mapper).touchNarrationCast(eq(7L), anyLong());
    }

    @Test
    @DisplayName("空名册首次入册：写入旁白(取 AI 音色)与新角色(id 从 1 起)")
    void enrollSeedsNarratorAndCharactersWhenEmpty() {
        NovelMapper mapper = mock(NovelMapper.class);
        when(mapper.findNarrationVoices(3L)).thenReturn(List.of());
        service(mapper).enrollCharacters(3L, List.of(narrator("旁白音色"), ch(5, "哀家", "V1")));

        verify(mapper).insertNarrationVoiceIfAbsent(eq(3L), eq(0), eq("Narrator"),
                eq("unknown"), eq("unknown"), eq("旁白音色"), anyLong());
        verify(mapper).insertNarrationVoiceIfAbsent(eq(3L), eq(1), eq("哀家"),
                any(), any(), eq("V1"), anyLong());
        verify(mapper).touchNarrationCast(eq(3L), anyLong());
    }

    @Test
    @DisplayName("无新角色时不写入、不 touch")
    void enrollNoopWhenNothingNew() {
        NovelMapper mapper = mock(NovelMapper.class);
        when(mapper.findNarrationVoices(1L)).thenReturn(List.of(narrator("N"), ch(1, "哀家", "V1")));
        service(mapper).enrollCharacters(1L, List.of(narrator("N2"), ch(2, "哀家", "改写")));

        verify(mapper, never()).insertNarrationVoiceIfAbsent(anyLong(), anyInt(), any(),
                any(), any(), any(), anyLong());
        verify(mapper, never()).touchNarrationCast(anyLong(), anyLong());
    }

    @Test
    @DisplayName("loadRoster：持久化缺旁白时内存兜底补默认旁白居首")
    void loadRosterPrependsDefaultNarrator() {
        NovelMapper mapper = mock(NovelMapper.class);
        when(mapper.findNarrationVoices(2L)).thenReturn(List.of(ch(1, "哀家", "V1")));
        List<NarrationCharacter> roster = service(mapper).loadRoster(2L);
        assertEquals(2, roster.size());
        assertTrue(roster.get(0).narrator());
        assertEquals(NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION, roster.get(0).controlInstruction());
        assertEquals("哀家", roster.get(1).name());
    }
}
