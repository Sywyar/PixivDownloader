package top.sywyar.pixivdownload.tts.narration.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import top.sywyar.pixivdownload.core.ai.AiService;
import top.sywyar.pixivdownload.novel.narration.analysis.NarrationCharacter;
import top.sywyar.pixivdownload.novel.narration.analysis.NarratorVoicePreset;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.config.DebugConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.novel.narration.NarrationConflictReport;
import top.sywyar.pixivdownload.novel.narration.NarrationReferenceVoiceService;
import top.sywyar.pixivdownload.novel.narration.NovelNarrationCastService;
import top.sywyar.pixivdownload.novel.narration.NovelNarrationScriptService;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.core.metadata.novel.NovelRecord;
import top.sywyar.pixivdownload.novel.narration.audio.NarrationAudioService;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException;

import java.util.List;

import top.sywyar.pixivdownload.novel.db.NovelNarrationCast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("多角色朗读控制器（DTO 形状 / controlInstruction 不下发 / line 错误码）")
class NarrationControllerTest {

    private final NovelNarrationScriptService scriptService = mock(NovelNarrationScriptService.class);
    private final NovelNarrationCastService castService = mock(NovelNarrationCastService.class);
    private final NarrationReferenceVoiceService referenceVoiceService = mock(NarrationReferenceVoiceService.class);
    private final NovelDatabase novelDatabase = mock(NovelDatabase.class);
    private final NarrationAudioService audioService = mock(NarrationAudioService.class);
    private final DebugConfig debugConfig = new DebugConfig();
    private final AiService aiService = mock(AiService.class);

    private final NarrationController controller =
            new NarrationController(scriptService, castService, referenceVoiceService, audioService, novelDatabase,
                    appMessages(), debugConfig, aiService);
    private final NarrationTtsController ttsController =
            new NarrationTtsController(audioService, scriptService, appMessages());

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 默认朗读引擎可用（多数 /script 用例的 happy path）；不可用 / 调试模式的用例各自覆盖。
        when(audioService.isEngineAvailable()).thenReturn(true);
        when(aiService.isConfigured()).thenReturn(true);
    }

    private NovelRecord novel(long id) {
        return new NovelRecord(id, "标题", "f", 1, "txt", 1L, null, null, null, null,
                1L, null, null, null, null, null, null, null, null, null, "正文。", null);
    }

    private static AppMessages appMessages() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames(
                "classpath:i18n/messages",
                "classpath:i18n/ValidationMessages",
                "classpath:i18n/tts/messages",
                "classpath:i18n/web/tts");
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(true);
        return new AppMessages(source);
    }

    @Test
    @DisplayName("/script：逐句下发不含 controlInstruction；冲突携带当前 + 建议画像")
    void scriptResponseShape() {
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        List<NovelNarrationScriptService.ScriptLine> lines = List.of(
                new NovelNarrationScriptService.ScriptLine(0, 1, "哀家", "angry", 2, "住口！"));
        List<NarrationConflictReport> conflicts = List.of(
                new NarrationConflictReport(1, "哀家", "contradiction", "reason", "old voice", "new voice"));
        when(scriptService.getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class)))
                .thenReturn(new NovelNarrationScriptService.ChapterScript(lines, 5L, 88L, 0, 1234L, conflicts));
        when(castService.voices(5L)).thenReturn(List.of(
                new NarrationCharacter(0, "Narrator", "unknown", "unknown", "N.", true, false),
                new NarrationCharacter(1, "哀家", "female", "elderly", "An elderly woman.", false, true)));

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null, null, null, null));

        assertEquals(200, resp.getStatusCode().value());
        NarrationController.ScriptResponse body = (NarrationController.ScriptResponse) resp.getBody();
        assertEquals(1, body.lines().size());
        assertEquals(1, body.lines().get(0).speakerId());
        assertEquals(2, body.lines().get(0).paragraphIndex());
        assertEquals(5L, body.castId());
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
        when(scriptService.getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class)))
                .thenThrow(new NovelNarrationScriptService.ContentTooLargeException(200000));
        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null, null, null, null));
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("/script：显式 castId 透传给分析；castId 随脚本下发")
    void scriptPassesCastIdOverride() {
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        List<NovelNarrationScriptService.ScriptLine> lines = List.of(
                new NovelNarrationScriptService.ScriptLine(0, 0, "Narrator", "", 0, "正文。"));
        when(scriptService.getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt(), eq(9L), nullable(String.class)))
                .thenReturn(new NovelNarrationScriptService.ChapterScript(lines, 9L, 5L, 0, 6L, List.of()));

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null, 9L, null, null));

        assertEquals(200, resp.getStatusCode().value());
        NarrationController.ScriptResponse body = (NarrationController.ScriptResponse) resp.getBody();
        assertEquals(9L, body.castId());
        verify(scriptService).getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt(), eq(9L), nullable(String.class));
    }

    @Test
    @DisplayName("/script：analyzeIfMissing=false 且无缓存返回 204（绝不分析）")
    void scriptPeekNoCacheReturns204() {
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        when(scriptService.peekScript(7L, "")).thenReturn(null);

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null, null, null, false));

        assertEquals(204, resp.getStatusCode().value());
        verify(scriptService, org.mockito.Mockito.never())
                .getOrAnalyze(anyLong(), any(), anyInt(), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class));
    }

    @Test
    @DisplayName("/script：引擎不可用且非调试模式时 force 重分析被拒（503），绝不调用分析")
    void scriptRejectsForceWhenEngineUnavailable() {
        when(audioService.isEngineAvailable()).thenReturn(false);
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, true, null, null, null));

        assertEquals(503, resp.getStatusCode().value());
        verify(scriptService, org.mockito.Mockito.never())
                .getOrAnalyze(anyLong(), any(), anyInt(), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class));
    }

    @Test
    @DisplayName("/script：AI 未配置时 force 重分析被拒（503），绝不调用分析")
    void scriptRejectsForceWhenAiUnavailable() {
        when(aiService.isConfigured()).thenReturn(false);
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, true, null, null, null));

        assertEquals(503, resp.getStatusCode().value());
        assertTrue(resp.getBody() instanceof ErrorResponse);
        assertEquals("AI 文本模型当前不可用，无法生成新的朗读脚本；请稍后再试或检查 AI 插件配置",
                ((ErrorResponse) resp.getBody()).getError());
        verify(scriptService, org.mockito.Mockito.never())
                .getOrAnalyze(anyLong(), any(), anyInt(), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class));
    }

    @Test
    @DisplayName("/script：AI 未配置且无缓存脚本时新分析被拒（503），绝不调用分析")
    void scriptRejectsNewAnalysisWhenAiUnavailableNoCache() {
        when(aiService.isConfigured()).thenReturn(false);
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        when(scriptService.peekScript(7L, "")).thenReturn(null);

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null, null, null, null));

        assertEquals(503, resp.getStatusCode().value());
        verify(scriptService, org.mockito.Mockito.never())
                .getOrAnalyze(anyLong(), any(), anyInt(), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class));
    }

    @Test
    @DisplayName("/script：调试模式不能绕过 AI 未配置的新分析门禁")
    void scriptRejectsAiUnavailableEvenInDebugMode() {
        when(aiService.isConfigured()).thenReturn(false);
        debugConfig.setEnabled(true);
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, true, null, null, null));

        assertEquals(503, resp.getStatusCode().value());
        verify(scriptService, org.mockito.Mockito.never())
                .getOrAnalyze(anyLong(), any(), anyInt(), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class));
    }

    @Test
    @DisplayName("/script：引擎不可用且非调试模式、无缓存脚本时新分析被拒（503），绝不调用分析")
    void scriptRejectsNewAnalysisWhenUnavailableNoCache() {
        when(audioService.isEngineAvailable()).thenReturn(false);
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        when(scriptService.peekScript(7L, "")).thenReturn(null);

        // analyzeIfMissing 默认 true、force 默认 false：缓存缺失即触发新分析 → 被守卫拦下
        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null, null, null, null));

        assertEquals(503, resp.getStatusCode().value());
        verify(scriptService, org.mockito.Mockito.never())
                .getOrAnalyze(anyLong(), any(), anyInt(), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class));
    }

    @Test
    @DisplayName("/script：引擎不可用但命中缓存脚本时仍正常返回（不触发新分析）")
    void scriptAllowsCacheHitWhenUnavailable() {
        when(audioService.isEngineAvailable()).thenReturn(false);
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        List<NovelNarrationScriptService.ScriptLine> lines = List.of(
                new NovelNarrationScriptService.ScriptLine(0, 0, "Narrator", "", 0, "正文。"));
        NovelNarrationScriptService.ChapterScript cached =
                new NovelNarrationScriptService.ChapterScript(lines, 5L, 1L, 0, 2L, List.of());
        when(scriptService.peekScript(7L, "")).thenReturn(cached);
        when(scriptService.getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class)))
                .thenReturn(cached);

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null, null, null, null));

        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("/script：AI 未配置但命中缓存脚本时仍正常返回（不触发新分析）")
    void scriptAllowsCacheHitWhenAiUnavailable() {
        when(aiService.isConfigured()).thenReturn(false);
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        List<NovelNarrationScriptService.ScriptLine> lines = List.of(
                new NovelNarrationScriptService.ScriptLine(0, 0, "Narrator", "", 0, "正文。"));
        NovelNarrationScriptService.ChapterScript cached =
                new NovelNarrationScriptService.ChapterScript(lines, 5L, 1L, 0, 2L, List.of());
        when(scriptService.peekScript(7L, "")).thenReturn(cached);
        when(scriptService.getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class)))
                .thenReturn(cached);

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null, null, null, null));

        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("/script：调试模式开启时即便引擎不可用也允许分析（仅运行 LLM 查看说话人 / 冲突）")
    void scriptAllowsAnalysisInDebugModeWhenUnavailable() {
        when(audioService.isEngineAvailable()).thenReturn(false);
        debugConfig.setEnabled(true);
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        List<NovelNarrationScriptService.ScriptLine> lines = List.of(
                new NovelNarrationScriptService.ScriptLine(0, 0, "Narrator", "", 0, "正文。"));
        when(scriptService.getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class)))
                .thenReturn(new NovelNarrationScriptService.ChapterScript(lines, 5L, 1L, 0, 2L, List.of()));

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, true, null, null, null));

        assertEquals(200, resp.getStatusCode().value());
        verify(scriptService).getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt(), nullable(Long.class), nullable(String.class));
    }

    @Test
    @DisplayName("/script：narratorPreset 经枚举解析为画像传给分析（不信任客户端原文）")
    void scriptResolvesNarratorPreset() {
        when(novelDatabase.getNovel(7L)).thenReturn(novel(7L));
        List<NovelNarrationScriptService.ScriptLine> lines = List.of(
                new NovelNarrationScriptService.ScriptLine(0, 0, "Narrator", "", 0, "正文。"));
        when(scriptService.getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt(), nullable(Long.class),
                eq(NarratorVoicePreset.CALM_MALE.instruction())))
                .thenReturn(new NovelNarrationScriptService.ChapterScript(lines, 5L, 1L, 0, 2L, List.of()));

        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(7L, null, null, null, null, "calm-male", null));

        assertEquals(200, resp.getStatusCode().value());
        verify(scriptService).getOrAnalyze(eq(7L), eq(""), eq(0), anyBoolean(), anyInt(), nullable(Long.class),
                eq(NarratorVoicePreset.CALM_MALE.instruction()));
    }

    @Test
    @DisplayName("/narrator-presets：列出全部旁白音色预设，首项为默认（温暖女声）")
    void narratorPresetsEndpoint() {
        NarrationController.NarratorPresetsResponse resp = controller.narratorPresets();
        assertEquals(NarratorVoicePreset.all().size(), resp.presets().size());
        assertEquals(NarratorVoicePreset.DEFAULT.id(), resp.presets().get(0).id());
        assertEquals(NarratorVoicePreset.WARM_FEMALE.instruction(), resp.presets().get(0).instruction());
    }

    @Test
    @DisplayName("/availability：透出朗读引擎是否可用 + 调试模式开关 + 文本模型是否已配置")
    void availabilityReflectsEngine() {
        when(audioService.isEngineAvailable()).thenReturn(true);
        when(aiService.isConfigured()).thenReturn(true);
        ResponseEntity<?> on = controller.availability();
        assertEquals(200, on.getStatusCode().value());
        assertTrue(((NarrationController.AvailabilityResponse) on.getBody()).available());
        assertTrue(((NarrationController.AvailabilityResponse) on.getBody()).textModelConfigured());

        when(audioService.isEngineAvailable()).thenReturn(false);
        when(aiService.isConfigured()).thenReturn(false);
        ResponseEntity<?> off = controller.availability();
        assertEquals(200, off.getStatusCode().value());
        assertTrue(!((NarrationController.AvailabilityResponse) off.getBody()).available());
        // 调试模式默认关闭
        assertTrue(!((NarrationController.AvailabilityResponse) off.getBody()).debug());
        // 文本模型未配置时透出 textModelConfigured=false（前端据此隐藏「富感情朗读」入口）
        assertTrue(!((NarrationController.AvailabilityResponse) off.getBody()).textModelConfigured());

        // 调试模式开启时透出 debug=true（即便引擎不可用）
        debugConfig.setEnabled(true);
        ResponseEntity<?> dbg = controller.availability();
        assertEquals(200, dbg.getStatusCode().value());
        NarrationController.AvailabilityResponse body = (NarrationController.AvailabilityResponse) dbg.getBody();
        assertTrue(!body.available());
        assertTrue(body.debug());
    }

    @Test
    @DisplayName("/script：本地无此小说返回 404")
    void scriptNotFound() {
        when(novelDatabase.getNovel(9L)).thenReturn(null);
        ResponseEntity<?> resp = controller.script(
                new NarrationController.ScriptRequest(9L, null, null, null, null, null, null));
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("/casts：列表 / 新建 / 本作默认 / 取某册 voices")
    void castEndpoints() {
        // 列表
        when(castService.listAll()).thenReturn(List.of(
                new NovelNarrationCast(3L, "甲册", null, 7L, 1L, 2L, 5),
                new NovelNarrationCast(4L, "共享册", null, null, 1L, 2L, 0)));
        NarrationController.CastListResponse list = controller.listCasts();
        assertEquals(2, list.casts().size());
        assertEquals(3L, list.casts().get(0).id());

        // 新建（无绑定的共享册）
        when(castService.create(eq("共享册"), nullable(Long.class), nullable(Long.class)))
                .thenReturn(new NovelNarrationCast(8L, "共享册", null, null, 1L, 2L, 0));
        ResponseEntity<NarrationController.CastSummary> created = controller.createCast(
                new NarrationController.CreateCastRequest("共享册", null, null));
        assertEquals(200, created.getStatusCode().value());
        assertEquals(8L, created.getBody().id());

        // 本作默认（已创建）
        when(castService.resolveNovelDefaultCast(7L)).thenReturn(new NovelNarrationCastService.DefaultCast(
                new NovelNarrationCast(3L, "甲册", null, 7L, 1L, 2L, 5), "甲册", null, 7L));
        ResponseEntity<NarrationController.DefaultCastResponse> def = controller.novelDefaultCast(7L);
        assertEquals(200, def.getStatusCode().value());
        assertEquals(3L, def.getBody().castId());

        // 本作默认（尚未创建 → castId null）
        when(castService.resolveNovelDefaultCast(8L)).thenReturn(
                new NovelNarrationCastService.DefaultCast(null, "novel-8", null, 8L));
        assertNull(controller.novelDefaultCast(8L).getBody().castId());

        // 取某册 voices（含音色画像）
        when(castService.find(3L)).thenReturn(new NovelNarrationCast(3L, "甲册", null, 7L, 1L, 2L, 5));
        when(castService.voices(3L)).thenReturn(List.of(
                new NarrationCharacter(0, "Narrator", "unknown", "unknown", "N.", true, false),
                new NarrationCharacter(1, "哀家", "female", "elderly", "An elderly woman.", false, true)));
        when(referenceVoiceService.sources(3L)).thenReturn(java.util.Map.of(1, "upload"));
        ResponseEntity<NarrationController.CastResponse> voices = controller.castVoices(3L);
        assertEquals(200, voices.getStatusCode().value());
        assertEquals(2, voices.getBody().voices().size());
        assertEquals("An elderly woman.", voices.getBody().voices().get(1).controlInstruction());
        assertEquals("upload", voices.getBody().voices().get(1).refAudioSource());
    }

    @Test
    @DisplayName("/cast/voice：按 castId 编辑指定花名册角色音色")
    void updateVoiceByCastId() {
        when(castService.exists(3L)).thenReturn(true);
        ResponseEntity<?> resp = controller.updateVoice(
                new NarrationController.VoiceUpdateRequest(3L, null, 1, "A calm young man."));
        assertEquals(200, resp.getStatusCode().value());
        verify(castService).updateVoiceInstruction(3L, 1, "A calm young man.");
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
