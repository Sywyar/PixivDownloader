package top.sywyar.pixivdownload.pixivcontent;

import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;

/** Sanitized upstream failure. No request headers, URLs or Pixiv response bodies reach clients or logs. */
final class ContentFailure extends RuntimeException {
    final int status;
    final String code;
    final String messageKey;

    private ContentFailure(int status, String code, String messageKey) {
        super(code);
        this.status = status;
        this.code = code;
        this.messageKey = messageKey;
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
            case 401, 403 -> new ContentFailure(403, "PIXIV_ACCESS_DENIED", "error.access");
            case 404, 410 -> notFound();
            case 429 -> new ContentFailure(429, "PIXIV_RATE_LIMITED", "error.rate");
            default -> transport();
        };
    }

    static ContentFailure from(PixivAjaxException failure) {
        return switch (failure.failure()) {
            case HTTP_STATUS -> upstream(failure.statusCode());
            case RESPONSE_TOO_LARGE -> tooLarge();
            case INVALID_TARGET -> invalidResponse();
            case TRANSPORT -> transport();
        };
    }
}
