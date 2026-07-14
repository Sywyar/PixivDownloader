package top.sywyar.pixivdownload.douyin.schedule.failure;

import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;

/** 把抖音客户端错误统一收敛为调度安全分类及 Guard 动作。 */
public final class DouyinScheduledFailureMapper {

    public static final long RATE_LIMIT_RETRY_MILLIS = 15L * 60L * 1_000L;

    private DouyinScheduledFailureMapper() {
    }

    public static ScheduledExecutionException fromClient(DouyinClientException failure) {
        DouyinClientErrorCode code = failure == null ? null : failure.code();
        if (code == null) {
            return internal("douyin.schedule.client-failed");
        }
        return switch (code) {
            case COOKIE_REQUIRED, COOKIE_MISSING_FIELDS, COOKIE_EXPIRED ->
                    failure(ScheduledFailure.Category.CREDENTIAL_INVALID,
                            "douyin.schedule.credential-invalid");
            case LOGIN_OR_VERIFY_PAGE, SIGNATURE_REQUIRED ->
                    failure(ScheduledFailure.Category.CHALLENGE,
                            "douyin.schedule.challenge");
            case HTTP_RATE_LIMITED, RATE_LIMITED ->
                    failure(ScheduledFailure.Category.RATE_LIMITED,
                            "douyin.schedule.rate-limited", RATE_LIMIT_RETRY_MILLIS);
            case HTTP_FORBIDDEN, REGION_RESTRICTED, PERMISSION_DENIED ->
                    failure(ScheduledFailure.Category.ACCESS_UNAVAILABLE,
                            "douyin.schedule.access-unavailable");
            case CANCELLED -> ScheduledExecutionException.cancelled();
            case INVALID_URL, INVALID_SHORT_URL, NON_DOUYIN_TARGET,
                    UNSUPPORTED_FINAL_URL, UNSUPPORTED_CONTENT,
                    MEDIA_URL_MISSING ->
                    failure(ScheduledFailure.Category.NOT_FOUND,
                            "douyin.schedule.work-unavailable");
            case NETWORK_TIMEOUT, SHORT_LINK_UNRESOLVED, REDIRECT_LOOP,
                    PAGINATION_STALLED, NETWORK_ERROR, DOWNLOAD_SIZE_MISMATCH ->
                    failure(ScheduledFailure.Category.RETRYABLE_NETWORK,
                            "douyin.schedule.network-failed");
        };
    }

    public static ScheduledExecutionException networkFailure() {
        return failure(ScheduledFailure.Category.RETRYABLE_NETWORK,
                "douyin.schedule.network-failed");
    }

    public static ScheduledExecutionException internal(String code) {
        return failure(ScheduledFailure.Category.INTERNAL, code);
    }

    public static ScheduledGuardDecision guardDecision(ScheduledFailure failure) {
        if (failure == null) {
            return ScheduledGuardDecision.proceed();
        }
        return switch (failure.category()) {
            case CREDENTIAL_INVALID -> new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.SUSPEND_CREDENTIAL,
                    "DOUYIN_CREDENTIAL_INVALID", 0L);
            case CHALLENGE -> new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT,
                    "DOUYIN_CHALLENGE", 0L);
            case RATE_LIMITED -> new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.RETRY_LATER,
                    "DOUYIN_RATE_LIMITED",
                    failure.retryAfterMillis() > 0
                            ? failure.retryAfterMillis() : RATE_LIMIT_RETRY_MILLIS);
            case ACCESS_UNAVAILABLE -> new ScheduledGuardDecision(
                    ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK,
                    "DOUYIN_ACCESS_UNAVAILABLE", 0L);
            default -> ScheduledGuardDecision.proceed();
        };
    }

    private static ScheduledExecutionException failure(
            ScheduledFailure.Category category,
            String code) {
        return new ScheduledExecutionException(category, code);
    }

    private static ScheduledExecutionException failure(
            ScheduledFailure.Category category,
            String code,
            long retryAfterMillis) {
        return new ScheduledExecutionException(category, code, retryAfterMillis);
    }
}
