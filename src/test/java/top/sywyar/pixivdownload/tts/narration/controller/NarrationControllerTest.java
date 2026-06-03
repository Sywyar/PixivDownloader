package top.sywyar.pixivdownload.tts.narration.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.novel.NarrationConflictReport;
import top.sywyar.pixivdownload.novel.NovelNarrationCastService;
import top.sywyar.pixivdownload.novel.NovelNarrationScriptService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.tts.narration.NarrationAudioService;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("多角色朗读控制器（DTO 形状 / controlInstruction 不下发 / line 错误码）")
class NarrationControllerTest {

    private final NovelNarrationScriptService scriptService = mock(NovelNarrationScriptService.class);
    private final NovelNarrationCastService castService = mock(NovelNarrationCastService.class);
    private final NovelDatabase novelDatabase = mock(NovelDatabase.class);
    private final NarrationAudioService audioService = mock(NarrationAudioService.class);

    private final NarrationController controller =
            new NarrationController(scriptService, castService, audioService, novelDatabase, TestI18nBeans.appMessages());
    private final NarrationTtsController ttsController =
            new NarrationTtsController(audioService, scriptService, TestI18nBeans.appMessages());

    private NovelRecord novel(long id) {
        return new NovelRecord(id, "标题", "f", 1, "txt", 1L, null, null, null, null,
                1L, null, null, null, null, null, null, null, null, null, "正文。", null);
    }

    @Test
    @DisplayName("/script：逐句下发不含 controlInstruction；冲突携带当前 + 建议画像")
    void scriptResponseShape() {
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        List<NovelNarrationScriptService.ScriptLine> lines = List.of(
                new NovelNarrationScriptService.ScriptLine(0, 1, "哀家", "angry", 2, "住口！"));
        List<NarrationConflictReport> conflicts = List.of(
                new NarrationConflictReport(1, "哀家", "contradiction", "reason", "old voice", "new voice"));
        when(scriptService.getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt()))
                .thenReturn(new NovelNarrationScriptService.ChapterScript(lines, 5L, 88L, 0, 1234L, conflicts));
        when(castService.voices(5L)).thenReturn(List.of(
                new NarrationCharacter(0, "Narrator", "unknown", "unknown", "N.", true, false),
                new NarrationCharacter(1, "哀家", "female", "elderly", "An elderly woman.", false, true)));

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null));

        assertEquals(200, resp.getStatusCode().value());
        NarrationController.ScriptResponse body = (NarrationController.ScriptResponse) resp.getBody();
        assertEquals(1, body.lines().size());
        assertEquals(1, body.lines().get(0).speakerId());
        assertEquals(2, body.lines().get(0).paragraphIndex());
        assertEquals(88L, body.castUpdatedTime());
        assertEquals(1234L, body.analyzedTime());
        // cast 概要不含音色画像（CastBrief 无 controlInstruction 字段，结构性保证）
        assertEquals(2, body.cast().size());
        // 冲突携带当前 + 建议画像（admin-only 端点）
        assertEquals(1, body.conflicts().size());
        assertEquals("old voice", body.conflicts().get(0).currentInstruction());
        assertEquals("new voice", body.conflicts().get(0).suggestion());
    }

    @Test
    @DisplayName("/script：正文过长返回 400")
    void scriptTooLargeReturns400() {
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        when(scriptService.getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt()))
                .thenThrow(new NovelNarrationScriptService.ContentTooLargeException(200000));
        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null));
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("/availability：透出当前朗读引擎是否可用")
    void availabilityReflectsEngine() {
        when(audioService.isEngineAvailable()).thenReturn(true);
        ResponseEntity<?> on = controller.availability();
        assertEquals(200, on.getStatusCode().value());
        assertTrue(((NarrationController.AvailabilityResponse) on.getBody()).available());

        when(audioService.isEngineAvailable()).thenReturn(false);
        ResponseEntity<?> off = controller.availability();
        assertEquals(200, off.getStatusCode().value());
        assertTrue(!((NarrationController.AvailabilityResponse) off.getBody()).available());
    }

    @Test
    @DisplayName("/script：本地无此小说返回 404")
    void scriptNotFound() {
        when(novelDatabase.getNovel(9L)).thenReturn(null);
        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(9L, null, null, null));
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("/tts/line：成功返回音频字节；脚本缺失 404；引擎失败 502")
    void lineEndpointStatuses() {
        // 成功
        when(scriptService.synthesizeLine(7L, "", 0))
                .thenReturn(new NarrationAudio(new byte[]{1, 2, 3}, "audio/wav"));
        ResponseEntity<?> ok = ttsController.line(
                new NarrationTtsController.NarrationLineRequest(7L, 0, ""));
        assertEquals(200, ok.getStatusCode().value());
        assertTrue(ok.getBody() instanceof byte[]);

        // 缺脚本 → 404
        when(scriptService.synthesizeLine(8L, "", 0)).thenReturn(null);
        ResponseEntity<?> notFound = ttsController.line(
                new NarrationTtsController.NarrationLineRequest(8L, 0, ""));
        assertEquals(404, notFound.getStatusCode().value());

        // 引擎失败 → 502
        when(scriptService.synthesizeLine(9L, "", 0))
                .thenThrow(new NarrationVoiceException("boom", null));
        ResponseEntity<?> bad = ttsController.line(
                new NarrationTtsController.NarrationLineRequest(9L, 0, ""));
        assertEquals(502, bad.getStatusCode().value());
    }
}
