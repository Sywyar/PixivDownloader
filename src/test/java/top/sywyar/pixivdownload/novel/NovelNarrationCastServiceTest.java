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
import top.sywyar.pixivdownload.tts.narration.NarrationScript;
import top.sywyar.pixivdownload.tts.narration.NarrationScriptService;
import top.sywyar.pixivdownload.tts.narration.NarrationSegmentAnalysis;
import top.sywyar.pixivdownload.tts.narration.NarrationSentence;

import java.util.ArrayList;
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
        return new NarrationSegmentAnalysis(List.of(), newChars, updated, Map.of(), conflicts);
    }

    private final NarrationReferenceVoiceStore fileStore = mock(NarrationReferenceVoiceStore.class);

    private NovelNarrationCastService service(NovelMapper mapper) {
        return new NovelNarrationCastService(mapper, mock(NovelDatabase.class), mock(NarrationScriptService.class),
                fileStore, TX);
    }

    @Test
    @DisplayName("整册替换：保留角色走 upsert（不抹参考音）、被移除角色删行 + 删参考音文件，绝不整册清空")
    void replaceVoicesPreservesKeptAndCleansRemoved() {
        NovelMapper mapper = mock(NovelMapper.class);
        // 替换前在册：旁白 0、角色 1、角色 2
        when(mapper.findNarrationVoices(5L)).thenReturn(List.of(
                narrator("N"), aiChar(1, "甲", "V1"), aiChar(2, "乙", "V2")));

        // 新名册只保留旁白 0 + 角色 1（角色 2 被移除）
        service(mapper).replaceVoices(5L, List.of(narrator("N"), aiChar(1, "甲", "V1b")));

        // 保留角色走 upsert（ON CONFLICT 只改画像、保留 ref_audio_*）
        verify(mapper).upsertNarrationVoice(eq(5L), eq(NarrationCharacter.NARRATOR_ID),
                any(), any(), any(), any(), anyBoolean(), anyLong());
        verify(mapper).upsertNarrationVoice(eq(5L), eq(1), eq("甲"), any(), any(), eq("V1b"), eq(true), anyLong());
        // 被移除角色：删行 + 删盘上参考音文件
        verify(mapper).deleteNarrationVoice(5L, 2);
        verify(fileStore).deleteCharacterFiles(5L, 2);
        // 绝不整册清空（否则会连保留角色的参考音绑定一起抹掉）
        verify(mapper, never()).deleteNarrationVoices(anyLong());
        // 保留角色不被删
        verify(mapper, never()).deleteNarrationVoice(5L, 1);
        verify(mapper, never()).deleteNarrationVoice(5L, NarrationCharacter.NARRATOR_ID);
        verify(mapper).touchNarrationCast(eq(5L), anyLong());
    }

    @Test
    @DisplayName("删除花名册：删音色行 + 删册记录 + 删磁盘参考音目录")
    void deleteCastRemovesVoicesAndCastDirectory() {
        NovelMapper mapper = mock(NovelMapper.class);
        service(mapper).delete(9L);
        verify(mapper).deleteNarrationVoices(9L);
        verify(mapper).deleteNarrationCast(9L);
        verify(fileStore).deleteCastDirectory(9L);
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
    @DisplayName("受控改名：AI 生成角色按 id 改名（第一人称主角真实姓名后段揭晓并入同一音色）；用户锁定 / 旁白 / 重名跳过")
    void renameRoutedByIdGuardsLockedAndCollisions() {
        NovelMapper mapper = mock(NovelMapper.class);
        // 在册：旁白 0、AI 生成的「我」(id 1)、用户锁定的「乙」(id 2)、AI 生成的「丙」(id 3)
        List<NarrationCharacter> roster = List.of(
                narrator("N"), aiChar(1, "我", "V1"), lockedChar(2, "乙", "LOCKED"), aiChar(3, "丙", "V3"));

        Map<Integer, String> renamed = new java.util.LinkedHashMap<>();
        renamed.put(1, "莱蒂西亚");  // AI 生成 → 真实姓名揭晓，按 id 改名（音色 id 不变）
        renamed.put(2, "新乙");      // 用户锁定 → 跳过
        renamed.put(3, "乙");        // 与另一个已有角色「乙」(id 2) 重名 → 跳过，避免塌成一个
        renamed.put(0, "旁白别名");  // 旁白 → 跳过

        NovelNarrationCastService.SegmentRosterResult res = service(mapper).processSegmentRoster(7L, roster,
                new NarrationSegmentAnalysis(List.of(), List.of(), Map.of(), renamed, List.of()));

        // 仅「我」(id 1) 被改名
        verify(mapper).updateNarrationVoiceName(7L, 1, "莱蒂西亚");
        verify(mapper, never()).updateNarrationVoiceName(eq(7L), eq(2), any());
        verify(mapper, never()).updateNarrationVoiceName(eq(7L), eq(3), any());
        verify(mapper, never()).updateNarrationVoiceName(eq(7L), eq(NarrationCharacter.NARRATOR_ID), any());
        verify(mapper).touchNarrationCast(eq(7L), anyLong());
        assertTrue(res.unresolvedConflicts().isEmpty());
    }

    private static NarrationSentence sent(String text, int paragraphIndex) {
        return new NarrationSentence(text, paragraphIndex);
    }

    /** 构造若干句、每句长度固定、分布在指定段落下标上。 */
    private static List<NarrationSentence> sentences(int len, int... paragraphIndices) {
        String text = "x".repeat(len);
        List<NarrationSentence> list = new ArrayList<>();
        for (int p : paragraphIndices) {
            list.add(new NarrationSentence(text, p));
        }
        return list;
    }

    @Test
    @DisplayName("分段：segmentSize<=0 整章一批；与翻译默认（0=整章）一致")
    void splitIntoBatchesWholeChapterWhenSizeNonPositive() {
        List<NarrationSentence> all = sentences(10, 0, 0, 1, 2, 2);
        List<List<NarrationSentence>> batches = NovelNarrationCastService.splitIntoBatches(all, 0);
        assertEquals(1, batches.size());
        assertEquals(5, batches.get(0).size());
        // 负数同样整章一批
        assertEquals(1, NovelNarrationCastService.splitIntoBatches(all, -1).size());
    }

    @Test
    @DisplayName("分段：segmentSize>0 按段落累积切批，批边界落在段落处、整段不被拆开")
    void splitIntoBatchesAccumulatesWholeParagraphs() {
        // 段落 0：两句共 20 字；段落 1：三句共 30 字；段落 2：一句 10 字。阈值 15。
        List<NarrationSentence> all = new ArrayList<>();
        all.add(sent("0123456789", 0));
        all.add(sent("0123456789", 0));
        all.add(sent("0123456789", 1));
        all.add(sent("0123456789", 1));
        all.add(sent("0123456789", 1));
        all.add(sent("0123456789", 2));

        List<List<NarrationSentence>> batches = NovelNarrationCastService.splitIntoBatches(all, 15);

        assertEquals(3, batches.size());
        // 批1=段落0 两句（20>=15 切）；批2=段落1 三句（30>=15 切）；批3=段落2 余下一句
        assertEquals(2, batches.get(0).size());
        assertEquals(0, batches.get(0).get(0).paragraphIndex());
        assertEquals(3, batches.get(1).size());
        assertEquals(1, batches.get(1).get(0).paragraphIndex());
        assertEquals(1, batches.get(2).size());
        assertEquals(2, batches.get(2).get(0).paragraphIndex());
        // 每批内不混段落（段落是不可分割单元）
        for (List<NarrationSentence> batch : batches) {
            int first = batch.get(0).paragraphIndex();
            // 同一批可跨多个段落，但单个段落不会被拆到两批：批边界总在段落结束处
            assertTrue(batch.get(0).paragraphIndex() <= batch.get(batch.size() - 1).paragraphIndex());
            assertTrue(first >= 0);
        }
    }

    @Test
    @DisplayName("分段：单个段落本身超过阈值时独立成批，不被拆开")
    void splitIntoBatchesKeepsOversizedParagraphWhole() {
        List<NarrationSentence> all = new ArrayList<>();
        all.add(sent("0123456789", 0));
        all.add(sent("0123456789", 0));
        all.add(sent("0123456789", 0)); // 段落 0 共 30 字
        all.add(sent("01234", 1));       // 段落 1 共 5 字
        List<List<NarrationSentence>> batches = NovelNarrationCastService.splitIntoBatches(all, 8);
        assertEquals(2, batches.size());
        assertEquals(3, batches.get(0).size()); // 整个段落 0（30 字）不被拆
        assertEquals(1, batches.get(1).size());
    }

    @Test
    @DisplayName("analyzeChapter：显式指定花名册存在时用它做基底，不解析本作默认册")
    void analyzeChapterUsesCastOverride() {
        NovelMapper mapper = mock(NovelMapper.class);
        NovelDatabase db = mock(NovelDatabase.class);
        NarrationScriptService scriptSvc = mock(NarrationScriptService.class);
        NovelNarrationCastService svc = new NovelNarrationCastService(mapper, db, scriptSvc,
                mock(NarrationReferenceVoiceStore.class), TX);

        when(mapper.countNarrationCastById(9L)).thenReturn(1);
        when(mapper.findNarrationVoices(9L)).thenReturn(List.of(narrator("N")));
        when(scriptSvc.analyzeSegment(any(), any(), anyInt()))
                .thenReturn(new NarrationSegmentAnalysis(List.of(), List.of(), Map.of(), Map.of(), List.of()));
        NarrationScript built = new NarrationScript(List.of(narrator("N")),
                List.of(new NarrationScript.Line(0, "句。", 0, "Narrator", "", "N")), true);
        when(scriptSvc.buildScript(any(), any(), any())).thenReturn(built);

        ChapterNarration out = svc.analyzeChapter(11L, List.of(new NarrationSentence("句。", 0)), 0, 9L);

        assertEquals(9L, out.castId());
        // 指定册存在 → 走 exists 分支、不解析本作默认册（不读 novel 记录）
        verify(db, never()).getNovel(anyLong());
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

    @Test
    @DisplayName("手动编辑旁白：新花名册尚无旁白行时插入并锁定，避免保存静默丢失")
    void updateVoiceInstructionInsertsMissingNarrator() {
        NovelMapper mapper = mock(NovelMapper.class);
        when(mapper.updateNarrationVoiceInstruction(5L, NarrationCharacter.NARRATOR_ID, "custom narrator", true))
                .thenReturn(0);

        service(mapper).updateVoiceInstruction(5L, NarrationCharacter.NARRATOR_ID, " custom narrator ");

        verify(mapper).upsertNarrationVoice(eq(5L), eq(NarrationCharacter.NARRATOR_ID), eq("Narrator"),
                eq("unknown"), eq("unknown"), eq("custom narrator"), eq(true), anyLong());
        verify(mapper).touchNarrationCast(eq(5L), anyLong());
    }
}
