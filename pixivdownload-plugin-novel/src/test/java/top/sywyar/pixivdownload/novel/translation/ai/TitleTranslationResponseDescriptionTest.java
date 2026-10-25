package top.sywyar.pixivdownload.novel.translation.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TitleTranslationResponse 系列简介 description 字段解析")
class TitleTranslationResponseDescriptionTest {

    @Test
    @DisplayName("含 description 字段：translatedDescription() 返回 trim 后的非空字符串；不影响 ok()")
    void parsesTranslatedDescription() {
        TitleTranslationResponse resp = TitleTranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"zh-CN\","
                        + "\"title\":\"译后系列名\","
                        + "\"description\":\"  译后系列简介 <br> 第二行  \"}");
        assertThat(resp.ok()).isTrue();
        assertThat(resp.translatedDescription()).isEqualTo("译后系列简介 <br> 第二行");
    }

    @Test
    @DisplayName("description 字段缺失 / 显式 null / 空白 / 字面 \"null\" 一律视为未译简介，translatedDescription()=null")
    void treatsMissingOrPlaceholderAsNull() {
        assertThat(TitleTranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"title\":\"Series Name\"}")
                .translatedDescription())
                .isNull();
        assertThat(TitleTranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"title\":\"Series Name\",\"description\":null}")
                .translatedDescription())
                .isNull();
        assertThat(TitleTranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"title\":\"Series Name\",\"description\":\"   \"}")
                .translatedDescription())
                .isNull();
        assertThat(TitleTranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"title\":\"Series Name\",\"description\":\"null\"}")
                .translatedDescription())
                .isNull();
    }

    @Test
    @DisplayName("无效语言响应中的空简介规范化为 null")
    void invalidLanguageHasNoDescription() {
        TitleTranslationResponse resp = TitleTranslationResponse.parse(
                "{\"status\":\"invalid_language\",\"lang\":\"\","
                        + "\"title\":\"\",\"description\":\"\"}");
        assertThat(resp.invalidLanguage()).isTrue();
        assertThat(resp.translatedDescription()).isNull();
    }
}
