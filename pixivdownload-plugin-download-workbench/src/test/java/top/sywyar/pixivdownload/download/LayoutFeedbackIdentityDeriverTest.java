package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("布局调查作用域匿名身份派生")
class LayoutFeedbackIdentityDeriverTest {

    private static final String INSTALL_ID = "11111111-2222-4333-8444-555555555555";
    private static final String OTHER_INSTALL_ID = "22222222-3333-4444-8555-666666666666";
    private static final String SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OTHER_SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff";

    @Test
    @DisplayName("相同 installIdentity + 相同 surveyId 输出稳定一致")
    void sameInputsProduceSameOutput() {
        String first = LayoutFeedbackIdentityDeriver.deriveScopedIdentity(SURVEY_ID, INSTALL_ID);
        String second = LayoutFeedbackIdentityDeriver.deriveScopedIdentity(SURVEY_ID, INSTALL_ID);
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("不同 surveyId 输出不同（调查作用域隔离）")
    void differentSurveyIdsProduceDifferentOutput() {
        assertThat(LayoutFeedbackIdentityDeriver.deriveScopedIdentity(SURVEY_ID, INSTALL_ID))
                .isNotEqualTo(LayoutFeedbackIdentityDeriver.deriveScopedIdentity(OTHER_SURVEY_ID, INSTALL_ID));
    }

    @Test
    @DisplayName("不同 installIdentity 输出不同")
    void differentInstallIdentitiesProduceDifferentOutput() {
        assertThat(LayoutFeedbackIdentityDeriver.deriveScopedIdentity(SURVEY_ID, INSTALL_ID))
                .isNotEqualTo(LayoutFeedbackIdentityDeriver.deriveScopedIdentity(SURVEY_ID, OTHER_INSTALL_ID));
    }

    @Test
    @DisplayName("输出固定为 plf_ + 64 位小写 hex")
    void outputMatchesScopedIdFormat() {
        String scoped = LayoutFeedbackIdentityDeriver.deriveScopedIdentity(SURVEY_ID, INSTALL_ID);
        assertThat(scoped).matches("^plf_[0-9a-f]{64}$");
        assertThat(LayoutFeedbackIdentityDeriver.SCOPED_ID_PATTERN.matcher(scoped).matches()).isTrue();
    }

    @Test
    @DisplayName("输出不含原始安装 UUID 文本")
    void outputDoesNotContainRawUuid() {
        String scoped = LayoutFeedbackIdentityDeriver.deriveScopedIdentity(SURVEY_ID, INSTALL_ID);
        assertThat(scoped).doesNotContain("11111111").doesNotContain("2222").doesNotContain("-");
        assertThat(scoped).isNotEqualTo(INSTALL_ID);
    }

    @Test
    @DisplayName("不同 installIdentity 得到互不相同的输出集合")
    void distinctInputsNeverCollide() {
        Set<String> outputs = new LinkedHashSet<>();
        String[] installs = {INSTALL_ID, OTHER_INSTALL_ID,
                "33333333-4444-4555-8666-777777777777", "44444444-5555-4666-8777-888888888888"};
        for (String install : installs) {
            outputs.add(LayoutFeedbackIdentityDeriver.deriveScopedIdentity(SURVEY_ID, install));
        }
        assertThat(outputs).hasSize(installs.length);
    }

    @Test
    @DisplayName("Survey ID 形状校验：非法形状被拒绝")
    void invalidSurveyIdsAreRejected() {
        assertThat(LayoutFeedbackIdentityDeriver.isValidSurveyId(null)).isFalse();
        assertThat(LayoutFeedbackIdentityDeriver.isValidSurveyId("")).isFalse();
        assertThat(LayoutFeedbackIdentityDeriver.isValidSurveyId("short")).isFalse();
        assertThat(LayoutFeedbackIdentityDeriver.isValidSurveyId("x".repeat(129))).isFalse();
        assertThat(LayoutFeedbackIdentityDeriver.isValidSurveyId("contains space here")).isFalse();
        assertThatThrownBy(() -> LayoutFeedbackIdentityDeriver.deriveScopedIdentity("bad id", INSTALL_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Survey ID 形状校验：UUID 外形与安全令牌均合法")
    void validSurveyIdsAreAccepted() {
        assertThat(LayoutFeedbackIdentityDeriver.isValidSurveyId(SURVEY_ID)).isTrue();
        assertThat(LayoutFeedbackIdentityDeriver.isValidSurveyId("survey-token-123456")).isTrue();
        assertThat(LayoutFeedbackIdentityDeriver.isValidSurveyId("SURVEY_ID_ABC_123456")).isTrue();
    }

    @Test
    @DisplayName("非 v4 或非 RFC 4122 variant 的安装身份被拒绝")
    void nonV4InstallIdentityIsRejected() {
        assertThatThrownBy(() -> LayoutFeedbackIdentityDeriver.deriveScopedIdentity(
                SURVEY_ID, "11111111-2222-1333-8444-555555555555"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LayoutFeedbackIdentityDeriver.deriveScopedIdentity(
                SURVEY_ID, "11111111-2222-4333-ccc4-555555555555"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LayoutFeedbackIdentityDeriver.deriveScopedIdentity(
                SURVEY_ID, "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
