package top.sywyar.pixivdownload.novel.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import top.sywyar.pixivdownload.ai.AiService;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;

@DisplayName("下载即自动翻译服务端队列")
class NovelAutoTranslateServiceTest {

    private final NovelTranslationService translationService = mock(NovelTranslationService.class);
    private final NovelGlossaryService glossaryService = mock(NovelGlossaryService.class);
    private final NovelMergeService mergeService = mock(NovelMergeService.class);
    private final AiService aiService = mock(AiService.class);
    // 同步执行器：让 submit 内联完成，测试可对终态做确定性断言（无需等待异步线程）。
    private final TaskExecutor directExecutor = Runnable::run;

    private NovelAutoTranslateService service() {
        return new NovelAutoTranslateService(
                translationService, glossaryService, mergeService, aiService, directExecutor);
    }

    private NovelTranslationService.Result ok(String lang) {
        return new NovelTranslationService.Result(NovelTranslationService.Status.OK, lang, "ok", false);
    }

    @Test
    @DisplayName("AI 未启用时标记为 FAILED 且不调用翻译")
    void aiDisabledMarksFailed() {
        when(aiService.isConfigured()).thenReturn(false);
        NovelAutoTranslateService s = service();

        s.submit(100L, null, "english", 0, false, "epub");

        NovelAutoTranslateService.StatusView v = s.getStatus(100L);
        assertNotNull(v);
        assertEquals("FAILED", v.phase());
        assertTrue(v.failed());
        verify(translationService, never()).translateChapter(anyLong(), anyString(), anyInt(),
                anyBoolean(), nullable(String.class), nullable(Long.class),
                anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("单本成功翻译后状态为 DONE 且不触发系列合订")
    void standaloneSuccessNoMerge() throws Exception {
        when(aiService.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(100L)).thenReturn(7L);
        when(translationService.translateChapter(eq(100L), eq("english"), eq(0), eq(false),
                eq("en-US"), eq(7L), eq(true), eq(true), eq(true))).thenReturn(ok("en-US"));
        NovelAutoTranslateService s = service();

        s.submit(100L, null, "english", 0, true, "epub");

        NovelAutoTranslateService.StatusView v = s.getStatus(100L);
        assertEquals("DONE", v.phase());
        assertTrue(v.done());
        assertEquals("en-US", v.langCode());
        verify(mergeService, never()).merge(anyLong(), any());
        verify(translationService, never()).translateSeriesTitle(anyLong(), anyString(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("系列成功翻译后翻译系列名并按设置重生译文合订")
    void seriesSuccessTriggersMerge() throws Exception {
        when(aiService.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(7L);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(ok("en-US"));
        NovelAutoTranslateService s = service();

        s.submit(100L, 500L, "english", 0, true, "epub");

        assertEquals("DONE", s.getStatus(100L).phase());
        verify(translationService).translateSeriesTitle(
                eq(500L), eq("english"), eq("en-US"), eq(7L), eq(true), eq(true));
        verify(mergeService).merge(eq(500L), eq(NovelDownloadService.NovelFormat.EPUB));
    }

    @Test
    @DisplayName("同系列两章都完成翻译（串行链不丢任务）")
    void seriesSerialCompletesAll() throws Exception {
        when(aiService.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode(anyString())).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(7L);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(ok("en-US"));
        NovelAutoTranslateService s = service();

        s.submit(100L, 500L, "english", 0, false, "epub");
        s.submit(101L, 500L, "english", 0, false, "epub");

        assertEquals("DONE", s.getStatus(100L).phase());
        assertEquals("DONE", s.getStatus(101L).phase());
        verify(translationService, times(2)).translateChapter(anyLong(), anyString(), anyInt(),
                anyBoolean(), nullable(String.class), nullable(Long.class),
                anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("同一目标语言只探测一次语言代码")
    void langCodeProbedOnce() throws Exception {
        when(aiService.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode("english")).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(null);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(ok("en-US"));
        NovelAutoTranslateService s = service();

        s.submit(100L, null, "english", 0, false, "epub");
        s.submit(101L, null, "English", 0, false, "epub"); // 大小写归一化后命中缓存

        verify(translationService, times(1)).resolveLangCode(anyString());
    }

    @Test
    @DisplayName("终态状态超过保留期后被惰性清理（返回 null 并移除条目）")
    void terminalStatusExpires() throws Exception {
        when(aiService.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode(anyString())).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(null);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(ok("en-US"));
        NovelAutoTranslateService s = service();

        s.submit(100L, null, "english", 0, false, "epub");

        long now = System.currentTimeMillis();
        assertEquals("DONE", s.getStatus(100L, now).phase());
        // 进入终态超过保留期：惰性清理，视为无翻译记录
        assertNull(s.getStatus(100L, now + Duration.ofMinutes(31).toMillis()));
        // 条目已被移除：后续读取仍为 null
        assertNull(s.getStatus(100L));
    }

    @Test
    @DisplayName("源语言与目标一致时标记为 SAME_LANGUAGE 终态并按设置合订，但不翻译系列名")
    void sameLanguageRefreshesMerge() throws Exception {
        when(aiService.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode(anyString())).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(7L);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(new NovelTranslationService.Result(
                        NovelTranslationService.Status.SAME_LANGUAGE, "en-US", "same", false));
        NovelAutoTranslateService s = service();

        s.submit(100L, 500L, "english", 0, true, "epub");

        NovelAutoTranslateService.StatusView v = s.getStatus(100L);
        assertEquals("SAME_LANGUAGE", v.phase());
        assertTrue(v.done());
        assertEquals("en-US", v.langCode());
        verify(mergeService).merge(eq(500L), eq(NovelDownloadService.NovelFormat.EPUB));
        verify(translationService, never()).translateSeriesTitle(anyLong(), anyString(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("翻译返回错误状态时标记为 FAILED 且不合订")
    void translateErrorMarksFailed() throws Exception {
        when(aiService.isConfigured()).thenReturn(true);
        when(translationService.resolveLangCode(anyString())).thenReturn("en-US");
        when(glossaryService.getOrCreateNovelDefaultId(anyLong())).thenReturn(null);
        when(translationService.translateChapter(anyLong(), anyString(), anyInt(), anyBoolean(),
                nullable(String.class), nullable(Long.class), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(new NovelTranslationService.Result(
                        NovelTranslationService.Status.ERROR, null, "err", false));
        NovelAutoTranslateService s = service();

        s.submit(100L, 500L, "english", 0, true, "epub");

        assertEquals("FAILED", s.getStatus(100L).phase());
        assertTrue(s.getStatus(100L).failed());
        verify(mergeService, never()).merge(anyLong(), any());
    }
}
