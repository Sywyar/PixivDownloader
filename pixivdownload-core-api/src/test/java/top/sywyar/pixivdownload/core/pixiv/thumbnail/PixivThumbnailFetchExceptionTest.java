package top.sywyar.pixivdownload.core.pixiv.thumbnail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("Pixiv 缩略图稳定失败")
class PixivThumbnailFetchExceptionTest {

    @Test
    @DisplayName("HTTP 失败应保留受控状态码")
    void httpFailurePreservesStatusCode() {
        PixivThumbnailFetchException failure = new PixivThumbnailFetchException(
                PixivThumbnailFailure.HTTP_STATUS,
                404
        );

        assertThat(failure.failure()).isEqualTo(PixivThumbnailFailure.HTTP_STATUS);
        assertThat(failure.statusCode()).isEqualTo(404);
        assertThat(failure.getMessage()).doesNotContain("http://", "https://", "Cookie");
    }

    @Test
    @DisplayName("非 HTTP 失败只允许零状态码")
    void nonHttpFailureRequiresZeroStatusCode() {
        for (PixivThumbnailFailure failure : new PixivThumbnailFailure[]{
                PixivThumbnailFailure.INVALID_TARGET,
                PixivThumbnailFailure.TRANSPORT
        }) {
            PixivThumbnailFetchException exception = new PixivThumbnailFetchException(failure, 0);
            assertThat(exception.failure()).isEqualTo(failure);
            assertThat(exception.statusCode()).isZero();
        }

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PixivThumbnailFetchException(
                        PixivThumbnailFailure.TRANSPORT, 500));
    }

    @Test
    @DisplayName("失败类别与 HTTP 状态码必须有效")
    void failureAndHttpStatusMustBeValid() {
        assertThatNullPointerException()
                .isThrownBy(() -> new PixivThumbnailFetchException(null, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PixivThumbnailFetchException(
                        PixivThumbnailFailure.HTTP_STATUS, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PixivThumbnailFetchException(
                        PixivThumbnailFailure.HTTP_STATUS, 600));
    }
}
