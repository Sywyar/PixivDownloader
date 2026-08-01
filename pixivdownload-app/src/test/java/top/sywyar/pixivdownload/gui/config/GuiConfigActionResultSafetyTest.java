package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 配置动作结果安全策略")
class GuiConfigActionResultSafetyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "result.sessionId",
            "result.PHPSESSID",
            "auth.bearer",
            "credentials.access-key",
            "credentials.access_key_id",
            "keys.signing-key",
            "keys.encryptionKey",
            "keys.decryption_key"
    })
    @DisplayName("会话和通用密钥字段不得投影到界面")
    void genericCredentialPathsAreRejected(String path) {
        assertThat(GuiConfigActionResultSafety.isSafeJsonPath(path, false)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "reply",
            "result.status",
            "results.channel",
            "diagnostics.reachable",
            "metrics.elapsed_ms"
    })
    @DisplayName("普通结构化结果字段保持可投影")
    void ordinaryStructuredResultPathsRemainAllowed(String path) {
        assertThat(GuiConfigActionResultSafety.isSafeJsonPath(path, false)).isTrue();
    }

    @Test
    @DisplayName("显示文本移除控制字符并中和 HTML 角括号")
    void displayTextIsBoundedPlainText() {
        String sanitized = GuiConfigActionResultSafety.sanitizeDisplayText(
                "  <html><script>alert\u0000(1)</script>  ");

        assertThat(sanitized)
                .isEqualTo("‹html›‹script›alert(1)‹/script›")
                .doesNotContain("<", ">", "\u0000");
    }

    @Test
    @DisplayName("超长显示文本按 Unicode 码点截断")
    void displayTextIsTruncatedByCodePoint() {
        String sanitized = GuiConfigActionResultSafety.sanitizeDisplayText(
                "😀".repeat(GuiConfigActionResultSafety.MAX_DISPLAY_TEXT_CODE_POINTS + 10));

        assertThat(sanitized.codePointCount(0, sanitized.length()))
                .isEqualTo(GuiConfigActionResultSafety.MAX_DISPLAY_TEXT_CODE_POINTS + 1);
        assertThat(sanitized).endsWith("…");
    }
}
