package top.sywyar.pixivdownload.multimodesurvey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("多人模式去留调查匿名身份")
class MultiModeDecisionSurveyIdentityControllerTest {

    private static final String INSTALL_ID = "123e4567-e89b-42d3-a456-426614174000";

    @Test
    @DisplayName("同一安装身份稳定派生且不暴露原始 UUID")
    void derivesStableSurveyScopedIdentity() {
        String first = MultiModeDecisionSurveyIdentityController.deriveScopedIdentity(INSTALL_ID);
        String second = MultiModeDecisionSurveyIdentityController.deriveScopedIdentity(INSTALL_ID);

        assertThat(first).isEqualTo(second).matches("pmds_[0-9a-f]{64}");
        assertThat(first).doesNotContain(INSTALL_ID);
        var response = new MultiModeDecisionSurveyIdentityController(() -> INSTALL_ID).identity();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().distinctId()).isEqualTo(first);
    }

    @Test
    @DisplayName("非法安装身份拒绝派生，端点按 503 且 no-store 降级")
    void rejectsInvalidIdentityWithoutExposingIt() {
        assertThatThrownBy(() -> MultiModeDecisionSurveyIdentityController.deriveScopedIdentity("bad"))
                .isInstanceOf(IllegalArgumentException.class);

        var response = new MultiModeDecisionSurveyIdentityController(() -> "bad").identity();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).isNull();
    }
}
