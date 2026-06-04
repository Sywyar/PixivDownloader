package top.sywyar.pixivdownload.novel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.db.NovelNarrationCast;
import top.sywyar.pixivdownload.novel.db.NovelNarrationScriptRow;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.tts.narration.NarrationAudioService;
import top.sywyar.pixivdownload.tts.narration.NarrationScript;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AI 听小说脚本编排 / 持久化服务")
class NovelNarrationScriptServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NovelRecord novel(long id, String raw) {
        return new NovelRecord(id, "标题", "f", 1, "txt", 1L, null, null, null, null,
                1L, null, null, null, null, null, null, null, null, null, raw, null);
    }

    private NovelNarrationCast cast(long id, long updatedTime) {
        return new NovelNarrationCast(id, "册", null, id, 1L, updatedTime, 2);
    }

    @Test
    @DisplayName("getOrAnalyze：命中持久化缓存直接返回、绝不调用 LLM 分析")
    void cacheHitSkipsAnalysis() {
        NovelNarrationCastService castService = mock(NovelNarrationCastService.class);
        NovelDatabase db = mock(NovelDatabase.class);
        NovelMapper mapper = mock(NovelMapper.class);
        NarrationAudioService audio = mock(NarrationAudioService.class);

        String json = "[{\"i\":0,\"speaker\":1,\"speakerName\":\"哀家\",\"delivery\":\"angry\",\"paragraphIndex\":2,\"text\":\"住口！\"}]";
        when(mapper.findNarrationScript(7L, "")).thenReturn(
                new NovelNarrationScriptRow(7L, "", 5L, 0, 1234L, json));
        when(castService.find(5L)).thenReturn(cast(5L, 99L));

        NovelNarrationScriptService service = new NovelNarrationScriptService(castService, db, mapper, audio, objectMapper);
        NovelNarrationScriptService.ChapterScript result = service.getOrAnalyze(7L, "", 0, false, 0);

        assertEquals(1, result.lines().size());
        assertEquals(1, result.lines().get(0).speakerId());
        assertEquals(2, result.lines().get(0).paragraphIndex());
        assertEquals(99L, result.castUpdatedTime());
        assertEquals(1234L, result.analyzedTime());
        verify(castService, never()).analyzeChapter(anyLong(), any(), anyInt(), nullable(Long.class));
        verify(mapper, never()).upsertNarrationScript(anyLong(), any(), anyLong(), anyInt(), anyLong(), any());
    }

    @Test
    @DisplayName("getOrAnalyze：force 重新分析并落库（含 segment_size），逐句 paragraphIndex 由断句补齐")
    void forceReanalyzesAndPersists() {
        NovelNarrationCastService castService = mock(NovelNarrationCastService.class);
        NovelDatabase db = mock(NovelDatabase.class);
        NovelMapper mapper = mock(NovelMapper.class);
        NarrationAudioService audio = mock(NarrationAudioService.class);

        when(db.getNovel(7L)).thenReturn(novel(7L, "句子一。句子二。"));
        List<NarrationCharacter> roster = List.of(
                new NarrationCharacter(0, "Narrator", "unknown", "unknown", "N.", true, false),
                new NarrationCharacter(1, "甲", "male", "young", "A young man.", false, false));
        NarrationScript script = new NarrationScript(roster, List.of(
                new NarrationScript.Line(0, "句子一。", 1, "甲", "calm", "A young man, calm"),
                new NarrationScript.Line(1, "句子二。", 0, "Narrator", "", "N.")), true);
        when(castService.analyzeChapter(eq(7L), any(), eq(0), nullable(Long.class)))
                .thenReturn(new ChapterNarration(script, List.of(), 5L));
        when(castService.find(5L)).thenReturn(cast(5L, 100L));

        NovelNarrationScriptService service = new NovelNarrationScriptService(castService, db, mapper, audio, objectMapper);
        NovelNarrationScriptService.ChapterScript result = service.getOrAnalyze(7L, "", 0, true, 0);

        assertEquals(2, result.lines().size());
        org.assertj.core.api.Assertions.assertThat(result.analyzedTime()).isGreaterThan(0L);
        verify(castService).analyzeChapter(eq(7L), any(), eq(0), nullable(Long.class));
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(mapper).upsertNarrationScript(eq(7L), eq(""), eq(5L), eq(0), anyLong(), jsonCaptor.capture());
        // script_json 存 speaker / paragraphIndex / text，不存 controlInstruction
        String saved = jsonCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved)
                .contains("\"speaker\"").contains("\"paragraphIndex\"").contains("\"text\"")
                .doesNotContain("controlInstruction");
    }

    @Test
    @DisplayName("getOrAnalyze：castId<=0 走纯旁白（不调 LLM、逐句归旁白、落库 cast_id=0）")
    void narratorOnlySkipsAnalysis() {
        NovelNarrationCastService castService = mock(NovelNarrationCastService.class);
        NovelDatabase db = mock(NovelDatabase.class);
        NovelMapper mapper = mock(NovelMapper.class);
        NarrationAudioService audio = mock(NarrationAudioService.class);

        when(db.getNovel(7L)).thenReturn(novel(7L, "句子一。句子二。"));

        NovelNarrationScriptService service = new NovelNarrationScriptService(castService, db, mapper, audio, objectMapper);
        NovelNarrationScriptService.ChapterScript result = service.getOrAnalyze(7L, "", 0, true, 0, 0L);

        assertEquals(0L, result.castId());
        assertEquals(2, result.lines().size());
        result.lines().forEach(l -> assertEquals(0, l.speakerId()));
        verify(castService, never()).analyzeChapter(anyLong(), any(), anyInt(), nullable(Long.class));
        verify(mapper).upsertNarrationScript(eq(7L), eq(""), eq(0L), eq(0), anyLong(), any());
    }

    @Test
    @DisplayName("getOrAnalyze：显式 castId 透传给 analyzeChapter，落库脚本用其返回的 castId")
    void castIdOverrideThreadedToAnalysis() {
        NovelNarrationCastService castService = mock(NovelNarrationCastService.class);
        NovelDatabase db = mock(NovelDatabase.class);
        NovelMapper mapper = mock(NovelMapper.class);
        NarrationAudioService audio = mock(NarrationAudioService.class);

        when(db.getNovel(7L)).thenReturn(novel(7L, "句子一。"));
        NarrationScript script = new NarrationScript(List.of(
                new NarrationCharacter(0, "Narrator", "unknown", "unknown", "N.", true, false)),
                List.of(new NarrationScript.Line(0, "句子一。", 0, "Narrator", "", "N.")), true);
        when(castService.analyzeChapter(eq(7L), any(), eq(0), eq(9L)))
                .thenReturn(new ChapterNarration(script, List.of(), 9L));
        when(castService.find(9L)).thenReturn(cast(9L, 50L));

        NovelNarrationScriptService service = new NovelNarrationScriptService(castService, db, mapper, audio, objectMapper);
        NovelNarrationScriptService.ChapterScript result = service.getOrAnalyze(7L, "", 0, true, 0, 9L);

        assertEquals(9L, result.castId());
        verify(castService).analyzeChapter(eq(7L), any(), eq(0), eq(9L));
        verify(mapper).upsertNarrationScript(eq(7L), eq(""), eq(9L), eq(0), anyLong(), any());
    }

    @Test
    @DisplayName("synthesizeLine：按持久化行 speaker 从活花名册取基底、合并 delivery 后合成（音色编辑即时生效）")
    void synthesizeLineUsesActiveRosterBase() {
        NovelNarrationCastService castService = mock(NovelNarrationCastService.class);
        NovelDatabase db = mock(NovelDatabase.class);
        NovelMapper mapper = mock(NovelMapper.class);
        NarrationAudioService audio = mock(NarrationAudioService.class);

        String json = "[{\"i\":0,\"speaker\":1,\"speakerName\":\"哀家\",\"delivery\":\"angry\",\"paragraphIndex\":0,\"text\":\"住口！\"}]";
        when(mapper.findNarrationScript(7L, "")).thenReturn(
                new NovelNarrationScriptRow(7L, "", 5L, 0, 1L, json));
        when(castService.voices(5L)).thenReturn(List.of(
                new NarrationCharacter(0, "Narrator", "unknown", "unknown", "N.", true, false),
                new NarrationCharacter(1, "哀家", "female", "elderly", "An elderly woman, low and cold voice.", false, true)));
        when(audio.synthesizeLine(any(), any())).thenReturn(new NarrationAudio(new byte[]{1, 2}, "audio/wav"));

        NovelNarrationScriptService service = new NovelNarrationScriptService(castService, db, mapper, audio, objectMapper);
        NarrationAudio out = service.synthesizeLine(7L, "", 0);

        assertEquals("audio/wav", out.contentType());
        ArgumentCaptor<NarrationScript.Line> lineCaptor = ArgumentCaptor.forClass(NarrationScript.Line.class);
        verify(audio).synthesizeLine(lineCaptor.capture(), any());
        NarrationScript.Line line = lineCaptor.getValue();
        assertEquals(1, line.speakerId());
        assertEquals("住口！", line.text());
        // 基底来自活花名册 + 逐句 delivery 合并（combine 去基底末尾句点）
        assertEquals("An elderly woman, low and cold voice, angry", line.controlInstruction());
    }

    @Test
    @DisplayName("synthesizeLine：脚本不存在 / 行越界返回 null（控制器转 404）")
    void synthesizeLineReturnsNullWhenMissing() {
        NovelNarrationCastService castService = mock(NovelNarrationCastService.class);
        NovelDatabase db = mock(NovelDatabase.class);
        NovelMapper mapper = mock(NovelMapper.class);
        NarrationAudioService audio = mock(NarrationAudioService.class);
        NovelNarrationScriptService service = new NovelNarrationScriptService(castService, db, mapper, audio, objectMapper);

        when(mapper.findNarrationScript(7L, "")).thenReturn(null);
        assertNull(service.synthesizeLine(7L, "", 0));

        String json = "[{\"i\":0,\"speaker\":0,\"speakerName\":\"Narrator\",\"delivery\":\"\",\"paragraphIndex\":0,\"text\":\"a\"}]";
        when(mapper.findNarrationScript(8L, "")).thenReturn(new NovelNarrationScriptRow(8L, "", 0L, 0, 1L, json));
        assertNull(service.synthesizeLine(8L, "", 5));
    }
}
