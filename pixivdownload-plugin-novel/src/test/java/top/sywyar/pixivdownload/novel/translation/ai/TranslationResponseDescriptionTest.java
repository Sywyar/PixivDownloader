package top.sywyar.pixivdownload.novel.translation.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TranslationResponse 随段同译简介 description 字段解析")
class TranslationResponseDescriptionTest {

    @Test
    @DisplayName("含 description 字段：translatedDescription() 返回 trim 后的非空字符串")
    void parsesTranslatedDescription() {
        TranslationResponse resp = TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"zh-CN\","
                        + "\"text\":\"译文正文\",\"title\":\"译后标题\","
                        + "\"description\":\"  译后简介 <br> 第二行  \",\"glossary\":[]}");
        assertThat(resp.ok()).isTrue();
        assertThat(resp.translatedDescription()).isEqualTo("译后简介 <br> 第二行");
    }

    @Test
    @DisplayName("description 字段缺失 / 显式 null / 空白 / 字面 \"null\" 一律视为未译简介，translatedDescription()=null")
    void treatsMissingOrPlaceholderAsNull() {
        assertThat(TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"text\":\"body\",\"glossary\":[]}").translatedDescription())
                .isNull();
        assertThat(TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"text\":\"body\",\"description\":null,\"glossary\":[]}")
                .translatedDescription())
                .isNull();
        assertThat(TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"text\":\"body\",\"description\":\"   \",\"glossary\":[]}")
                .translatedDescription())
                .isNull();
        assertThat(TranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"text\":\"body\",\"description\":\"null\",\"glossary\":[]}")
                .translatedDescription())
                .isNull();
    }

    @Test
    @DisplayName("无效语言响应中的空简介规范化为 null")
    void invalidLanguageHasNoDescription() {
        TranslationResponse resp = TranslationResponse.parse(
                "{\"status\":\"invalid_language\",\"lang\":\"\",\"text\":\"\","
                        + "\"title\":\"\",\"description\":\"\"}");
        assertThat(resp.invalidLanguage()).isTrue();
        assertThat(resp.translatedDescription()).isNull();
    }
}
