package top.sywyar.pixivdownload.novel.translation.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TitleTranslationResponse 解析与状态判定")
class TitleTranslationResponseTest {

    @Test
    @DisplayName("严格 JSON 直接解析：ok 状态 + 译后标题 + BCP-47 lang")
    void parsesStrictJson() {
        TitleTranslationResponse resp = TitleTranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"zh-CN\",\"title\":\"中文标题\"}");
        assertThat(resp.ok()).isTrue();
        assertThat(resp.invalidLanguage()).isFalse();
        assertThat(resp.title()).isEqualTo("中文标题");
        assertThat(resp.lang()).isEqualTo("zh-CN");
    }

    @Test
    @DisplayName("invalid_language 状态：ok() 为 false，invalidLanguage() 为 true，title 可空")
    void parsesInvalidLanguage() {
        TitleTranslationResponse resp = TitleTranslationResponse.parse(
                "{\"status\":\"invalid_language\",\"lang\":\"\",\"title\":\"\"}");
        assertThat(resp.ok()).isFalse();
        assertThat(resp.invalidLanguage()).isTrue();
    }

    @Test
    @DisplayName("ok 状态但 title 空：ok() 为 false（防止把空译当成功落库覆盖原标题）")
    void okWithEmptyTitleIsNotOk() {
        TitleTranslationResponse resp = TitleTranslationResponse.parse(
                "{\"status\":\"ok\",\"lang\":\"en-US\",\"title\":\"   \"}");
        assertThat(resp.ok()).isFalse();
    }

    @Test
    @DisplayName("容忍 ``` json 围栏与前后说明：截取首个 { 到末个 } 再次解析")
    void parsesJsonWrappedInCodeFence() {
        TitleTranslationResponse resp = TitleTranslationResponse.parse(
                "Here is the JSON:\n```json\n{\"status\":\"ok\",\"lang\":\"ja-JP\",\"title\":\"タイトル\"}\n```");
        assertThat(resp.ok()).isTrue();
        assertThat(resp.title()).isEqualTo("タイトル");
        assertThat(resp.lang()).isEqualTo("ja-JP");
    }

    @Test
    @DisplayName("内容空白时直接抛 IllegalArgumentException（不走兜底）")
    void rejectsBlankContent() {
        assertThatThrownBy(() -> TitleTranslationResponse.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TitleTranslationResponse.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
