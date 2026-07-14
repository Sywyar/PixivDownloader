package top.sywyar.pixivdownload.douyin.schedule.failure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("抖音计划失败归一")
class DouyinScheduledFailureMapperTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(DouyinClientErrorCode.class)
    @DisplayName("每个客户端错误都映射到稳定且无上游正文的失败")
    void everyClientErrorHasSafeMapping(DouyinClientErrorCode code) {
        String unsafeBody = "cookie: private-value";

        ScheduledExecutionException result = DouyinScheduledFailureMapper.fromClient(
                new DouyinClientException(code, unsafeBody));

        assertThat(result.category()).isEqualTo(expectedCategory(code));
        assertThat(result.code()).doesNotContain("private-value");
        assertThat(result.getMessage()).doesNotContain(unsafeBody);
        if (code == DouyinClientErrorCode.HTTP_RATE_LIMITED
                || code == DouyinClientErrorCode.RATE_LIMITED) {
            assertThat(result.retryAfterMillis())
                    .isEqualTo(DouyinScheduledFailureMapper.RATE_LIMIT_RETRY_MILLIS);
        } else {
            assertThat(result.retryAfterMillis()).isZero();
        }
    }

    private static ScheduledFailure.Category expectedCategory(DouyinClientErrorCode code) {
        return switch (code) {
            case COOKIE_REQUIRED, COOKIE_MISSING_FIELDS, COOKIE_EXPIRED ->
                    ScheduledFailure.Category.CREDENTIAL_INVALID;
            case LOGIN_OR_VERIFY_PAGE, SIGNATURE_REQUIRED ->
                    ScheduledFailure.Category.CHALLENGE;
            case HTTP_RATE_LIMITED, RATE_LIMITED -> ScheduledFailure.Category.RATE_LIMITED;
            case HTTP_FORBIDDEN, REGION_RESTRICTED, PERMISSION_DENIED ->
                    ScheduledFailure.Category.ACCESS_UNAVAILABLE;
            case CANCELLED -> ScheduledFailure.Category.CANCELLED;
            case INVALID_URL, INVALID_SHORT_URL, NON_DOUYIN_TARGET,
                    UNSUPPORTED_FINAL_URL, UNSUPPORTED_CONTENT, MEDIA_URL_MISSING,
                    UPSTREAM_NOT_FOUND ->
                    ScheduledFailure.Category.NOT_FOUND;
            case NETWORK_TIMEOUT, SHORT_LINK_UNRESOLVED, REDIRECT_LOOP,
                    PAGINATION_STALLED, NETWORK_ERROR, DOWNLOAD_SIZE_MISMATCH,
                    UPSTREAM_SERVER_ERROR ->
                    ScheduledFailure.Category.RETRYABLE_NETWORK;
            case UPSTREAM_CLIENT_ERROR, RESPONSE_STRUCTURE_UNRECOGNIZED -> ScheduledFailure.Category.INTERNAL;
        };
    }
}
