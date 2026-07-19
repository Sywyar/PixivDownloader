package top.sywyar.pixivdownload.core.pixiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("Pixiv JSON 稳定失败模型")
class PixivAjaxExceptionTest {

    @Test
    @DisplayName("HTTP 失败只暴露受控类别与状态码")
    void shouldExposeOnlyControlledHttpFailure() {
        PixivAjaxException failure = new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 429);

        assertThat(failure.failure()).isEqualTo(PixivAjaxFailure.HTTP_STATUS);
        assertThat(failure.statusCode()).isEqualTo(429);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).isEqualTo("Pixiv JSON request failed with HTTP status 429");
    }

    @Test
    @DisplayName("状态码必须与失败类别保持一致")
    void shouldRejectInconsistentStatusCode() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PixivAjaxException(PixivAjaxFailure.TRANSPORT, 500));
    }
}
