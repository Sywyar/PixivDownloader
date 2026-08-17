package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("API 错误响应稳定契约")
class ApiErrorResponseTest {

    @Test
    @DisplayName("机器码去除首尾空白且保留本地化说明")
    void exposesStableCodeAndLocalizedError() {
        ApiErrorResponse response = ApiErrorResponse.of(" auth.unauthorized ", "Unauthorized");

        assertThat(response.code()).isEqualTo("auth.unauthorized");
        assertThat(response.error()).isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("拒绝缺失的必填字段")
    void rejectsMissingRequiredFields() {
        assertThatIllegalArgumentException().isThrownBy(() -> ApiErrorResponse.of(" ", "error"));
        assertThatNullPointerException().isThrownBy(() -> ApiErrorResponse.of("request.invalid", null));
    }
}
