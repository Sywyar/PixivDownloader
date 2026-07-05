package top.sywyar.pixivdownload.novel.translation.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TranslationResponse 随段同译标题 title 字段解析")
class TranslationResponseTitleTest {

    @Test
    @DisplayName("含 title 字段：translatedTitle() 返回 trim 后的非空字符串")
    void parsesTranslatedTitle() {
        TranslationResponse resp = TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"zh-CN\","
                        + "\"text\":\"译文正文\",\"title\":\"  译后标题  \",\"glossary\":[]}");
        assertThat(resp.ok()).isTrue();
        assertThat(resp.translatedTitle()).isEqualTo("译后标题");
    }

    @Test
    @DisplayName("title 字段缺失 / 显式 null / 空白 / 字面 \"null\" 一律视为未译标题，translatedTitle()=null")
    void treatsMissingOrPlaceholderAsNull() {
        assertThat(TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"text\":\"body\",\"glossary\":[]}").translatedTitle())
                .isNull();
        assertThat(TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"text\":\"body\",\"title\":null,\"glossary\":[]}").translatedTitle())
                .isNull();
        assertThat(TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"text\":\"body\",\"title\":\"   \",\"glossary\":[]}").translatedTitle())
                .isNull();
        assertThat(TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"text\":\"body\",\"title\":\"null\",\"glossary\":[]}").translatedTitle())
                .isNull();
    }

    @Test
    @DisplayName("invalid_language 状态：translatedTitle 永远为 null（与 ok=false 一致，不会被误写入 DB）")
    void invalidLanguageHasNoTitle() {
        TranslationResponse resp = TranslationResponse.parse(
                "{\"status\":\"invalid_language\",\"lang\":\"\",\"text\":\"\",\"title\":\"\"}");
        assertThat(resp.invalidLanguage()).isTrue();
        assertThat(resp.translatedTitle()).isNull();
    }
}
