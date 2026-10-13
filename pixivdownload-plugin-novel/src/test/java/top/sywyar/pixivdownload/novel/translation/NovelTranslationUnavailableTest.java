package top.sywyar.pixivdownload.novel.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.AiClientException;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.translation.ai.SourceLanguageProbeRequest;
import top.sywyar.pixivdownload.novel.translation.ai.TranslationRequest;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("小说翻译在数据库失效时停止副作用")
class NovelTranslationUnavailableTest {
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final AiChatClient ai = mock(AiChatClient.class);
    private final NovelDatabase novels = mock(NovelDatabase.class);
    private final NovelGlossaryService glossary = mock(NovelGlossaryService.class);
    private final NovelTranslationService service = new NovelTranslationService(
            ai, novels, glossary, mock(MessageResolver.class), available::get);

    @Test
    @DisplayName("无库时语言探测和章节翻译均不调用 AI 或数据库")
    void refusesBeforeAnyExternalCall() {
        available.set(false);

        assertThatThrownBy(() -> service.resolveLangCode("English"))
                .isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(this::translate)
                .isInstanceOf(DatabaseUnavailableException.class);

        verifyNoInteractions(ai, novels, glossary);
    }

    @Test
    @DisplayName("第一段 AI 返回前数据库失效，不请求第二段也不保存不完整译文")
    void stopsBetweenSegments() throws Exception {
        prepareNovel();
        when(ai.chat(eq(TranslationRequest.CALL_TYPE), any(), any())).thenAnswer(call -> {
            available.set(false);
            return response("{\"status\":\"ok\",\"lang\":\"en\",\"text\":\"first\"}");
        });

        assertThatThrownBy(this::translate).isInstanceOf(DatabaseUnavailableException.class);

        verify(ai, times(1)).chat(eq(TranslationRequest.CALL_TYPE), any(), any());
        verify(novels, never()).saveTranslation(anyLong(), any(), any(), any(), any());
        verifyNoInteractions(glossary);
    }

    @Test
    @DisplayName("语言探测失败同时数据库失效，不按普通探测失败继续翻译")
    void unavailableDoesNotBecomeProbeFallback() throws Exception {
        prepareNovel();
        when(ai.chat(eq(SourceLanguageProbeRequest.CALL_TYPE), any(), any())).thenAnswer(call -> {
            available.set(false);
            throw new AiClientException("probe failed");
        });

        assertThatThrownBy(this::translate).isInstanceOf(DatabaseUnavailableException.class);

        verify(ai, never()).chat(eq(TranslationRequest.CALL_TYPE), any(), any());
        verify(novels, never()).saveTranslation(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("数据库可用时继续按段翻译并一次保存完整正文")
    void preservesAvailableTranslationBehavior() throws Exception {
        prepareNovel();
        AtomicInteger segment = new AtomicInteger();
        when(ai.chat(eq(TranslationRequest.CALL_TYPE), any(), any())).thenAnswer(call ->
                response("{\"status\":\"ok\",\"lang\":\"en\",\"text\":\"translated-"
                        + segment.incrementAndGet() + "\"}"));

        assertThat(translate().status()).isEqualTo(NovelTranslationService.Status.OK);

        verify(ai, times(2)).chat(eq(TranslationRequest.CALL_TYPE), any(), any());
        verify(novels).saveTranslation(eq(42L), eq("en"), eq("translated-1\ntranslated-2"), isNull(), isNull());
    }

    private NovelTranslationService.Result translate() {
        return service.translateChapter(42L, "English", 10, true, "en", null, true, false, false);
    }

    private void prepareNovel() throws Exception {
        NovelRecord record = mock(NovelRecord.class);
        when(record.rawContent()).thenReturn("first paragraph\nsecond paragraph");
        when(novels.getNovel(42L)).thenReturn(record);
        when(ai.chat(eq(SourceLanguageProbeRequest.CALL_TYPE), any(), any()))
                .thenReturn(response("{\"same\":false}"));
    }

    private static AiChatResult response(String content) {
        return new AiChatResult(content, "stop", null, null, null);
    }
}
