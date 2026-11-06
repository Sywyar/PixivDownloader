package top.sywyar.pixivdownload.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.translation.TranslationTestMessages;
import top.sywyar.pixivdownload.translation.ai.LangProbeRequest;
import top.sywyar.pixivdownload.translation.ai.SourceLanguageProbeRequest;
import top.sywyar.pixivdownload.translation.ai.TitleTranslationRequest;
import top.sywyar.pixivdownload.translation.ai.TranslationRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("小说翻译空响应的失败与回退")
class AiNovelTranslationServiceResponseTest {
    private final AiChatClient ai = mock(AiChatClient.class);
    private final TranslationDocuments novels = mock(TranslationDocuments.class);
    private final AiNovelTranslationService service = new AiNovelTranslationService(
            ai, novels, TranslationTestMessages.messageResolver(), () -> true);

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
        assertThat(translate(true).status()).isEqualTo(AiNovelTranslationService.Status.OK);
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
        assertThat(translate(body).status()).isEqualTo(AiNovelTranslationService.Status.ERROR);
        verify(novels, never()).saveTranslation(anyLong(), any(), any(), any(), any());
        verify(novels, never()).saveTranslationMetadata(anyLong(), any(), any(), any());
    }

    private void prepareNovel() {
        TranslationDocuments.Content record = mock(TranslationDocuments.Content.class);
        when(record.novelId()).thenReturn(42L);
        when(record.title()).thenReturn("Original title");
        when(record.rawContent()).thenReturn("Original body");
        when(novels.getNovel(42)).thenReturn(record);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @ValueSource(strings = {" \t"})
    @DisplayName("仅选择简介时忽略未选择的原标题，空简介不调用 AI 或写入译文")
    void emptySelectedDescriptionDoesNotCallAi(String description) throws Exception {
        var source = new TranslationDocuments.Content(42, null, "body", "Original title", description);
        when(novels.getNovel(42)).thenReturn(source);
        when(novels.getSeries(42)).thenReturn(source);
        when(ai.chat(eq(TitleTranslationRequest.CALL_TYPE), any(), any()))
                .thenReturn(response("{\"status\":\"ok\",\"lang\":\"en\",\"title\":\"Translated title\"}"));
        assertThat(service.translateChapter(42, "English", 0, true, "en", null, false, false, true).status())
                .isEqualTo(AiNovelTranslationService.Status.EMPTY);
        assertThat(service.translateSeriesTitle(42, "English", "en", null, false, true)).isEmpty();
        verifyNoInteractions(ai);
        verify(novels, never()).saveTranslationMetadata(anyLong(), any(), any(), any());
        verify(novels, never()).saveSeriesTitleTranslation(anyLong(), any(), any(), any());
    }

    private AiNovelTranslationService.Result translate(boolean body) {
        return service.translateChapter(42, "English", 0, true, "en", null, body, !body, false);
    }

    private static AiChatResult response(String content) {
        return new AiChatResult(content, "stop", null, null, null);
    }
}
