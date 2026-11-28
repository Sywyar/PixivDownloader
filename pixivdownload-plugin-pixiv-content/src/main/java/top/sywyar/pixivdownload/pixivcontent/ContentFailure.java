package top.sywyar.pixivdownload.pixivcontent;

import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;

/** Sanitized upstream failure. No request headers, URLs or Pixiv response bodies reach clients or logs. */
final class ContentFailure extends RuntimeException {
    final int status;
    final String code;
    final String messageKey;
    /** 触发本失败的上游 HTTP 状态；非上游 HTTP 失败为 0，仅用于诊断日志。 */
    final int upstreamStatus;

    private ContentFailure(int status, String code, String messageKey) {
        this(status, code, messageKey, 0);
    }

    private ContentFailure(int status, String code, String messageKey, int upstreamStatus) {
        super(code);
        this.status = status;
        this.code = code;
        this.messageKey = messageKey;
        this.upstreamStatus = upstreamStatus;
    }

    /**
     * 抛出位置：最多四层插件内的调用帧（类.方法:行），跨过通用 JSON 读取帮助方法后仍能定位到具体查询或校验。
     * 只含源码位置，不含上游数据。
     */
    String origin() {
        // 映射自宿主端口失败时，原始异常的调用帧才指向发起请求的查询。
        return origin(getCause() == null ? this : getCause());
    }

    static String origin(Throwable failure) {
        var frames = new StringBuilder();
        int count = 0;
        for (StackTraceElement frame : failure.getStackTrace()) {
            String type = frame.getClassName();
            if (type.equals(ContentFailure.class.getName()) || !type.startsWith(ContentFailure.class.getPackageName() + ".")) {
                continue;
            }
            if (count++ > 0) frames.append(" < ");
            frames.append(type.substring(type.lastIndexOf('.') + 1)).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (count == 4) break;
        }
        return count == 0 ? "unknown" : frames.toString();
    }

    /** 上游拒绝、限流、不可用或响应异常需要排查；其余是调用方可直接处理的业务结果。 */
    boolean upstreamFault() {
        return switch (code) {
            case "PIXIV_ACCESS_DENIED", "PIXIV_RATE_LIMITED", "PIXIV_UNAVAILABLE",
                 "PIXIV_RESPONSE_INVALID", "PIXIV_RESPONSE_TOO_LARGE" -> true;
            default -> false;
        };
    }

    static ContentFailure notFound() {
        return new ContentFailure(404, "PIXIV_CONTENT_NOT_FOUND", "error.notFound");
    }

    static ContentFailure credentialRequired() {
        return new ContentFailure(400, AcquisitionCredentialResolver.REQUIRED_CODE, "error.credentialRequired");
    }

    static ContentFailure tagSearchTooBroad() {
        return new ContentFailure(422, "PIXIV_TAG_SEARCH_TOO_BROAD", "error.tagSearchTooBroad");
    }

    static ContentFailure premiumRequired() {
        return new ContentFailure(403, "PIXIV_PREMIUM_REQUIRED", "error.premiumRequired");
    }

    static ContentFailure animationBusy() {
        return new ContentFailure(503, "PIXIV_ANIMATION_BUSY", "error.animationBusy");
    }

    static ContentFailure invalidResponse() {
        return new ContentFailure(502, "PIXIV_RESPONSE_INVALID", "error.invalidResponse");
    }

    static ContentFailure expiredCursor() {
        return new ContentFailure(410, "PIXIV_RECOMMENDATION_EXPIRED", "error.recommendationExpired");
    }

    static ContentFailure tooLarge() {
        return new ContentFailure(502, "PIXIV_RESPONSE_TOO_LARGE", "error.tooLarge");
    }

    static ContentFailure transport() {
        return new ContentFailure(502, "PIXIV_UNAVAILABLE", "error.transport");
    }

    static ContentFailure upstream(int status) {
        return switch (status) {
            case 401, 403 -> new ContentFailure(403, "PIXIV_ACCESS_DENIED", "error.access", status);
            case 404, 410 -> new ContentFailure(404, "PIXIV_CONTENT_NOT_FOUND", "error.notFound", status);
            case 429 -> new ContentFailure(429, "PIXIV_RATE_LIMITED", "error.rate", status);
            default -> new ContentFailure(502, "PIXIV_UNAVAILABLE", "error.transport", status);
        };
    }

    static ContentFailure from(PixivAjaxException failure) {
        ContentFailure mapped = switch (failure.failure()) {
            case HTTP_STATUS -> upstream(failure.statusCode());
            case RESPONSE_TOO_LARGE -> tooLarge();
            case INVALID_TARGET -> invalidResponse();
            case TRANSPORT -> transport();
        };
        // 宿主失败不含凭据与正文；保留它只为让诊断位置指向发起请求的查询。
        mapped.initCause(failure);
        return mapped;
    }
}
