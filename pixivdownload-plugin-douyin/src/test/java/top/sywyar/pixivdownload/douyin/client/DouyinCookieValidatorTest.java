package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DouyinCookieValidator 抖音 Cookie 字段校验")
class DouyinCookieValidatorTest {

    @Test
    @DisplayName("包含登录态必需字段时可用，msToken 与 odin_tt 只作为提示")
    void acceptsRequiredFieldsAndReportsSuggestedMissing() {
        var validation = DouyinCookieValidator.validate(
                "ttwid=tt; passport_csrf_token=csrf; sessionid=sid");

        assertThat(validation.usable()).isTrue();
        assertThat(validation.missingRequired()).isEmpty();
        assertThat(validation.missingSuggested()).contains("msToken", "odin_tt", "sid_guard", "sid_tt");
    }

    @Test
    @DisplayName("缺少 Cookie 与缺少必需字段给出不同错误码")
    void rejectsEmptyAndMissingRequiredFields() {
        assertThatThrownBy(() -> DouyinCookieValidator.ensureUsable(" "))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.COOKIE_REQUIRED);

        assertThatThrownBy(() -> DouyinCookieValidator.ensureUsable("ttwid=tt; passport_csrf_token=csrf"))
                .isInstanceOf(DouyinClientException.class)
                .hasMessageContaining("sessionid / sessionid_ss / sid_tt / sid_guard")
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.COOKIE_MISSING_FIELDS);

        assertThatThrownBy(() -> DouyinCookieValidator.ensureUsable("ttwid=tt; sessionid=sid"))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.COOKIE_MISSING_FIELDS);
    }
}
