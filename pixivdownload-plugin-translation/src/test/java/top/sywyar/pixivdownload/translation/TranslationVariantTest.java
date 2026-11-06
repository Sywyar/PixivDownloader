package top.sywyar.pixivdownload.translation;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.translation.api.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DisplayName("翻译会话固定引擎身份")
class TranslationVariantTest {
    @Test @DisplayName("读写、跳过检查和标题参考使用同一引擎")
    void bindsEngineToAllDocumentOperations() {
        var port = mock(NovelTranslationDocuments.class);
        UUID token = UUID.randomUUID();
        when(port.beginWrite(any(), anyLong(), anyString(), anyInt(), anyInt(), anyInt())).thenReturn(token);
        var documents = new TranslationDocuments(port);
        for (String engine : new String[]{"ai", "youdao", "libretranslate"}) {
            var session = documents.snapshot(engine);
            String key = "zh-CN@" + engine;
            session.translation(NovelTranslationDocuments.Kind.NOVEL, 7, "zh-CN");
            session.translated(7, "zh-CN", true, false, false);
            session.saveTranslation(7, "zh-CN", "body", null, null);
            session.titleReferences(9, "zh-CN", 10);
            verify(port).openTranslation(NovelTranslationDocuments.Kind.NOVEL, 7, key);
            verify(port).translated(NovelTranslationDocuments.Kind.NOVEL, 7, key, true, false, false);
            verify(port).beginWrite(NovelTranslationDocuments.Kind.NOVEL, 7, key, 4, -1, -1);
            verify(port).titleReferences(9, key, 0, 10);
        }
    }

    @Test @DisplayName("旧语言键保留未知来源，非规范或超限身份拒绝")
    void validatesVariantIdentity() {
        assertThat(TranslationVariant.parse("zh-CN").engine()).isEqualTo("unknown");
        assertThat(TranslationVariant.parse("zh-CN@youdao").language()).isEqualTo("zh-CN");
        assertThat(new TranslationVariant("zh-CN", "unknown").key()).isEqualTo("zh-CN");
        for (String invalid : new String[]{"", "en@unknown", "en@AI", "en@ai@other", "en\n@ai", "a".repeat(101)})
            assertThatThrownBy(() -> TranslationVariant.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
}
