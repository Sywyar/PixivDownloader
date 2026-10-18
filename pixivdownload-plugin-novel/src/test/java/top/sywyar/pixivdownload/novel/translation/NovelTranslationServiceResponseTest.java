package top.sywyar.pixivdownload.novel.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.testsupport.NovelTestMessages;
import top.sywyar.pixivdownload.novel.translation.ai.LangProbeRequest;
import top.sywyar.pixivdownload.novel.translation.ai.SourceLanguageProbeRequest;
import top.sywyar.pixivdownload.novel.translation.ai.TitleTranslationRequest;
import top.sywyar.pixivdownload.novel.translation.ai.TranslationRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("小说翻译空响应的失败与回退")
class NovelTranslationServiceResponseTest {
    private final AiChatClient ai = mock(AiChatClient.class);
    private final NovelDatabase novels = mock(NovelDatabase.class);
    private final NovelTranslationService service = new NovelTranslationService(
            ai, novels, mock(NovelGlossaryService.class), NovelTestMessages.messageResolver(), () -> true);

    @Test
    @DisplayName("语言代码探测返回 JSON 空值时保持无法识别")
    void nullLanguageProbeFallsBack() throws Exception {
        when(ai.chat(eq(LangProbeRequest.CALL_TYPE), any(), any())).thenReturn(response("null"));
        assertThat(service.resolveLangCode("English")).isEmpty();
        verifyNoInteractions(novels);
    }

    @Test
    @DisplayName("源语言探测返回 JSON 空值时继续翻译并保存正文")
    void nullSourceProbeDoesNotSkipTranslation() throws Exception {
        prepareNovel();
        when(ai.chat(eq(SourceLanguageProbeRequest.CALL_TYPE), any(), any())).thenReturn(response("null"));
        when(ai.chat(eq(TranslationRequest.CALL_TYPE), any(), any()))
                .thenReturn(response("{\"status\":\"ok\",\"lang\":\"en\",\"text\":\"Translated body\"}"));
        assertThat(translate(true).status()).isEqualTo(NovelTranslationService.Status.OK);
        verify(novels).saveTranslation(42, "en", "Translated body", null, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("正文和标题翻译的 JSON 空值返回受控错误且不写库")
    void nullTranslationDoesNotWrite(boolean body) throws Exception {
        prepareNovel();
        when(ai.chat(eq(SourceLanguageProbeRequest.CALL_TYPE), any(), any()))
                .thenReturn(response("{\"same\":false}"));
        when(ai.chat(eq(body ? TranslationRequest.CALL_TYPE : TitleTranslationRequest.CALL_TYPE), any(), any()))
                .thenReturn(response("null"));
        assertThat(translate(body).status()).isEqualTo(NovelTranslationService.Status.ERROR);
        verify(novels, never()).saveTranslation(anyLong(), any(), any(), any(), any());
        verify(novels, never()).saveTranslationMetadata(anyLong(), any(), any(), any());
    }

    private void prepareNovel() {
        NovelRecord record = mock(NovelRecord.class);
        when(record.novelId()).thenReturn(42L);
        when(record.title()).thenReturn("Original title");
        when(record.rawContent()).thenReturn("Original body");
        when(novels.getNovel(42)).thenReturn(record);
    }

    private NovelTranslationService.Result translate(boolean body) {
        return service.translateChapter(42, "English", 0, true, "en", null, body, !body, false);
    }

    private static AiChatResult response(String content) {
        return new AiChatResult(content, "stop", null, null, null);
    }
}
