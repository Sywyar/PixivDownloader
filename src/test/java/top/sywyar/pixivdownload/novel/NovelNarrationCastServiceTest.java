package top.sywyar.pixivdownload.novel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.ai.narration.NarrationConflict;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.tts.narration.NarrationScriptService;
import top.sywyar.pixivdownload.tts.narration.NarrationSegmentAnalysis;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("朗读花名册入册与冲突路由")
class NovelNarrationCastServiceTest {

    private static final TransactionOperations TX = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };

    private static NarrationCharacter narrator(String instr) {
        return new NarrationCharacter(0, "Narrator", "unknown", "unknown", instr, true, false);
    }

    /** AI 生成（未锁定）的已有角色。 */
    private static NarrationCharacter aiChar(int id, String name, String instr) {
        return new NarrationCharacter(id, name, "female", "elderly", instr, false, false);
    }

    /** 用户手改锁定的角色。 */
    private static NarrationCharacter lockedChar(int id, String name, String instr) {
        return new NarrationCharacter(id, name, "female", "elderly", instr, false, true);
    }

    private static NarrationSegmentAnalysis analysis(List<NarrationCharacter> newChars,
                                                     Map<Integer, String> updated,
                                                     List<NarrationConflict> conflicts) {
        return new NarrationSegmentAnalysis(List.of(), newChars, updated, conflicts);
    }

    private NovelNarrationCastService service(NovelMapper mapper) {
        return new NovelNarrationCastService(mapper, mock(NovelDatabase.class), mock(NarrationScriptService.class), TX);
    }

    @Test
    @DisplayName("新角色入册：仅新名按 max+1 分配 id 写入；已在册名复用其 id，全部建立临时 id→真实 id 映射")
    void enrollNewCharactersAndRemap() {
        NovelMapper mapper = mock(NovelMapper.class);
        List<NarrationCharacter> roster = List.of(narrator("N"), aiChar(1, "哀家", "V1"));

        NovelNarrationCastService.SegmentRosterResult res = service(mapper).processSegmentRoster(7L, roster,
                analysis(
                        List.of(aiChar(9, "太后", "An old woman."), aiChar(8, "哀家", "改写")),
                        Map.of(), List.of()));

        // 新角色「太后」按 max(1)+1 = 2 入册（AI 生成 -> edited_by_user=false）
        verify(mapper).insertNarrationVoiceIfAbsent(eq(7L), eq(2), eq("太后"),
                any(), any(), eq("An old woman."), eq(false), anyLong());
        // 已在册的「哀家」不被重复写入（音色不被 AI 覆盖）
        verify(mapper, never()).insertNarrationVoiceIfAbsent(eq(7L), anyInt(), eq("哀家"),
                any(), any(), any(), anyBoolean(), anyLong());
        // 临时 id 重映射：9 → 新真实 id 2；8 → 复用已有「哀家」的真实 id 1
        assertEquals(Integer.valueOf(2), res.tempToReal().get(9));
        assertEquals(Integer.valueOf(1), res.tempToReal().get(8));
        verify(mapper).touchNarrationCast(eq(7L), anyLong());
    }

    @Test
    @DisplayName("兼容性补充：AI 生成角色刷新画像；用户锁定角色忽略")
    void updatedCharactersRoutedByEditedFlag() {
        NovelMapper mapper = mock(NovelMapper.class);
        List<NarrationCharacter> roster = List.of(narrator("N"), aiChar(1, "甲", "V1"), lockedChar(2, "乙", "LOCKED"));

        service(mapper).processSegmentRoster(5L, roster,
                analysis(List.of(), Map.of(1, "refined-1", 2, "refined-2"), List.of()));

        // id 1（AI 生成）画像被刷新，仍记为 AI 生成
        verify(mapper).updateNarrationVoiceInstruction(eq(5L), eq(1), eq("refined-1"), eq(false));
        // id 2（用户锁定）忽略，绝不写入
        verify(mapper, never()).updateNarrationVoiceInstruction(eq(5L), eq(2), any(), anyBoolean());
    }

    @Test
    @DisplayName("冲突路由：AI 生成角色自动采纳建议覆盖；用户锁定角色保留原值、收集为待处理冲突")
    void conflictsRoutedByEditedFlag() {
        NovelMapper mapper = mock(NovelMapper.class);
        List<NarrationCharacter> roster = List.of(
                narrator("N"), aiChar(1, "甲", "V1"), lockedChar(2, "乙", "LOCKED-VOICE"));

        NovelNarrationCastService.SegmentRosterResult res = service(mapper).processSegmentRoster(3L, roster,
                analysis(List.of(), Map.of(), List.of(
                        new NarrationConflict(1, NarrationConflict.TYPE_CONTRADICTION, "r1", "auto-applied"),
                        new NarrationConflict(2, NarrationConflict.TYPE_INCOMPLETE, "r2", "suggested-2"))));

        // id 1（AI 生成）自动采纳建议覆盖画像
        verify(mapper).updateNarrationVoiceInstruction(eq(3L), eq(1), eq("auto-applied"), eq(false));
        // id 2（用户锁定）绝不覆盖
        verify(mapper, never()).updateNarrationVoiceInstruction(eq(3L), eq(2), any(), anyBoolean());
        // 用户锁定角色的冲突收集为待处理项，携带 name / 当前画像 / 建议
        assertEquals(1, res.unresolvedConflicts().size());
        NarrationConflictReport report = res.unresolvedConflicts().get(0);
        assertEquals(2, report.characterId());
        assertEquals("乙", report.name());
        assertEquals(NarrationConflict.TYPE_INCOMPLETE, report.type());
        assertEquals("LOCKED-VOICE", report.currentInstruction());
        assertEquals("suggested-2", report.suggestion());
    }

    @Test
    @DisplayName("loadRoster：持久化缺旁白时内存兜底补默认旁白居首")
    void loadRosterPrependsDefaultNarrator() {
        NovelMapper mapper = mock(NovelMapper.class);
        when(mapper.findNarrationVoices(2L)).thenReturn(List.of(aiChar(1, "哀家", "V1")));
        List<NarrationCharacter> roster = service(mapper).loadRoster(2L);
        assertEquals(2, roster.size());
        assertTrue(roster.get(0).narrator());
        assertEquals(NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION, roster.get(0).controlInstruction());
        assertEquals("哀家", roster.get(1).name());
    }
}
