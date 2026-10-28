package top.sywyar.pixivdownload.core.pixiv.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import top.sywyar.pixivdownload.core.pixiv.*;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.*;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/** 跨作品类型共用的 Pixiv 用户、当前账号和缩略图查询。 */
@RestController
@RequestMapping("/api/pixiv")
@RequiredArgsConstructor
@Slf4j
public final class PixivSharedProxyController {
    private final ObjectMapper objectMapper;
    private final PixivAjaxClient pixivAjaxClient;
    private final PixivThumbnailFetcher pixivThumbnailFetcher;
    private final PixivProxyAccessPolicy pixivProxyAccessPolicy;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    private final MessageResolver messages;

    @GetMapping("/user/{userId}/meta")
    public ResponseEntity<?> getUserMeta(
            @PathVariable String userId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        URI uri = UriComponentsBuilder.fromUriString("https://www.pixiv.net/ajax/user/{userId}")
                .queryParam("lang", "zh").buildAndExpand(userId).encode().toUri();
        String body = pixivAjaxClient.get(uri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asText()));
        }
        String name = root.path("body").path("name").asText();
        return ResponseEntity.ok(new UserMetaResponse(name, userId));
    }

    @GetMapping("/thumbnail-proxy")
    public ResponseEntity<byte[]> proxyThumbnail(@RequestParam String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SecurityException(messages.get("pixiv.proxy.thumbnail-url.invalid"));
        }
        byte[] bytes = pixivThumbnailFetcher.fetch(uri);
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.setContentType(thumbnailMediaType(bytes));
        responseHeaders.setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic());
        return ResponseEntity.ok().headers(responseHeaders).body(bytes);
    }

    private static MediaType thumbnailMediaType(byte[] bytes) {
        // JDK 的文件头探测不包含 WebP；只读头部，不解码或重编码图片。
        if (bytes.length >= 12 && ByteBuffer.wrap(bytes).getInt(0) == 0x52494646
                && ByteBuffer.wrap(bytes).getInt(8) == 0x57454250) {
            return MediaType.parseMediaType("image/webp");
        }
        try {
            String type = URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(bytes));
            if ("image/jpeg".equals(type) || "image/png".equals(type) || "image/gif".equals(type)) {
                return MediaType.parseMediaType(type);
            }
        } catch (IOException ignored) {
            // 未识别的字节保持二进制响应，不能把上游 HTML 当作本机页面提供。
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    @GetMapping("/me/uid")
    public ResponseEntity<?> getMeUid(
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie,
            HttpServletRequest request) {
        cookie = acquisitionCredential(request, cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String uid = PixivCookieUserResolver.extractUidFromCookie(cookie);
        if (uid == null) {
            return ResponseEntity.status(401).body(new ErrorResponse(messages.get("pixiv.proxy.me.cookie.missing")));
        }
        return ResponseEntity.ok(new MeUidResponse(uid));
    }

    @ExceptionHandler(PixivThumbnailFetchException.class)
    public ResponseEntity<ErrorResponse> handleThumbnailFetch(PixivThumbnailFetchException failure) {
        return switch (failure.failure()) {
            case INVALID_TARGET -> thumbnailFailureResponse(
                    HttpStatus.BAD_REQUEST,
                    messages.get("pixiv.proxy.thumbnail-url.host.invalid"));
            case HTTP_STATUS -> thumbnailFailureResponse(
                    HttpStatus.BAD_GATEWAY,
                    failure.statusCode() == 401 || failure.statusCode() == 403
                            ? messages.get("pixiv.proxy.thumbnail.upstream.unauthorized")
                            : messages.get("pixiv.proxy.thumbnail.upstream.failed", failure.statusCode()));
            case TRANSPORT -> thumbnailFailureResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    messages.get("pixiv.proxy.thumbnail.transport.failed"));
        };
    }

    private ResponseEntity<ErrorResponse> thumbnailFailureResponse(HttpStatus responseStatus, String detail) {
        log.warn(messages.getForLog("error.log.request.failed", detail));
        return ResponseEntity.status(responseStatus).body(new ErrorResponse(detail));
    }

    @ExceptionHandler({IllegalArgumentException.class, SecurityException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException failure) {
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = messages.get("error.request.param.invalid");
        log.warn(messages.getForLog("error.log.request.failed", detail));
        return ResponseEntity.badRequest().body(new ErrorResponse(detail));
    }

    private ResponseEntity<?> checkMultiModeAccess(HttpServletRequest request) {
        PixivProxyAccessDecision decision = pixivProxyAccessPolicy.evaluate(
                requestOwnerIdentityResolver.resolveExistingOwnerUuid(request).orElse(null),
                requestOwnerIdentityResolver.isAdminAuthenticated(request));
        return switch (decision.outcome()) {
            case ALLOWED -> null;
            case OWNER_REQUIRED -> ResponseEntity.status(401)
                    .body(new ErrorResponse(decision.errorMessage()));
            case RATE_LIMITED -> ResponseEntity.status(429)
                    .body(new ProxyRateLimitResponse(
                            decision.errorMessage(), decision.maxRequests(), decision.windowHours()));
        };
    }

    private static String acquisitionCredential(HttpServletRequest request, String legacyCredential) {
        return AcquisitionCredentialResolver.resolve(
                request == null ? null : request.getHeader(AcquisitionCredentialResolver.HEADER_NAME),
                legacyCredential);
    }

    public record UserMetaResponse(String name, String userId) {}
    public record MeUidResponse(String uid) {}
    public record ErrorResponse(String code, String error) implements ApiErrorResponse {
        public ErrorResponse(String error) { this("pixiv.proxy.request-failed", error); }
    }
    public record ProxyRateLimitResponse(String code, String error, int maxRequests, int windowHours)
            implements ApiErrorResponse {
        public ProxyRateLimitResponse(String error, int maxRequests, int windowHours) {
            this("pixiv.proxy.rate-limited", error, maxRequests, windowHours);
        }
    }
}
