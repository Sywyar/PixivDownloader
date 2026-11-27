package top.sywyar.pixivdownload.core.pixiv.web;

import top.sywyar.pixivdownload.core.web.HttpParameterError;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import top.sywyar.pixivdownload.schedule.ScheduleRequestScope;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ScheduleManagementException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/** 跨作品类型共用的 Pixiv 用户、当前账号和缩略图查询。 */
@HttpApiGroup("core-pixiv")
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
    private final ScheduleRequestScope scheduleRequests;

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = UserMetaResponse.class)))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ApiErrorResponse.Basic.class, ErrorResponse.class})))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProxyRateLimitResponse.class)))
    @Parameter(name = "X-User-UUID", in = ParameterIn.HEADER, schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ApiErrorResponse.Basic.class, ErrorResponse.class})))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @PostMapping("/user/{userId}/meta/schedule")
    public ResponseEntity<?> getScheduleUserMeta(
            @PathVariable String userId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = "application/json", schema = @Schema(implementation = ScheduleRequestScope.Request.class),
                    examples = @ExampleObject(value = """
                            {"sourceType":"<sourceType>","activationToken":"<activationToken>"}
                            """)))
            @jakarta.validation.Valid @RequestBody ScheduleRequestScope.Request context,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String credential,
            HttpServletRequest request) throws IOException {
        if (!top.sywyar.pixivdownload.setup.ProgramAccess.identity(request)
                .map(identity -> identity.permits("core", "authors.refresh"))
                .orElseGet(() -> requestOwnerIdentityResolver.isAdminAuthenticated(request))) {
            return ResponseEntity.status(403).body(ApiErrorResponse.of("CREDENTIAL_FORBIDDEN", messages.get("auth.unauthorized")));
        }
        return scheduleRequests.read(request, context, AcquisitionCredentialResolver.resolve(credential), ScheduledNetworkRoute.inherit(),
                cookie -> getUserMeta(userId, cookie, request));
    }

    @ExceptionHandler(ScheduleManagementException.class)
    public ResponseEntity<ApiErrorResponse> handleScheduleContext(ScheduleManagementException failure) {
        return ResponseEntity.status(failure.code() == ScheduleManagementException.Code.CONFLICT ? 409 : 400)
                .body(ApiErrorResponse.of(failure.messageCode(), messages.get(failure.messageCode(), failure.messageArgs())));
    }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = UserMetaResponse.class)))
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProxyRateLimitResponse.class)))
    @Parameter(name = "X-User-UUID", in = ParameterIn.HEADER, schema = @Schema(type = "string", format = "uuid"))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiErrorResponse.Basic.class)))
    @GetMapping("/user/{userId}/meta")
    public ResponseEntity<?> getUserMeta(
            @PathVariable String userId,
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) throws IOException {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        URI uri = UriComponentsBuilder.fromUriString("https://www.pixiv.net/ajax/user/{userId}")
                .queryParam("lang", "zh").buildAndExpand(userId).encode().toUri();
        String body = pixivAjaxClient.get(uri, cookie);
        JsonNode root = objectMapper.readTree(body);
        if (root.path("error").asBoolean(false)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(root.path("message").asString()));
        }
        String name = root.path("body").path("name").asString();
        return ResponseEntity.ok(new UserMetaResponse(name, userId));
    }

    @ApiResponse(responseCode = "200", content = {
            @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "502", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @KnownErrorCodes(status = "400", value = "error.request.param.invalid")
    @GetMapping("/thumbnail-proxy")
    public ResponseEntity<?> proxyThumbnail(@RequestParam String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return invalidThumbnail("pixiv.proxy.thumbnail-url.invalid", "invalid_format");
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

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = MeUidResponse.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "429", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProxyRateLimitResponse.class)))
    @Parameter(name = "X-User-UUID", in = ParameterIn.HEADER, schema = @Schema(type = "string", format = "uuid"))
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    @ApiResponse(responseCode = "400", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/me/uid")
    public ResponseEntity<?> getMeUid(
            @RequestHeader(value = AcquisitionCredentialResolver.HEADER_NAME, required = false) String cookie,
            HttpServletRequest request) {
        cookie = AcquisitionCredentialResolver.resolve(cookie);
        ResponseEntity<?> deny = checkMultiModeAccess(request);
        if (deny != null) return deny;
        String uid = PixivCookieUserResolver.extractUidFromCookie(cookie);
        if (uid == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse(AcquisitionCredentialResolver.REQUIRED_CODE,
                    messages.get("pixiv.proxy.me.cookie.missing")));
        }
        return ResponseEntity.ok(new MeUidResponse(uid));
    }

    @ExceptionHandler(PixivThumbnailFetchException.class)
    public ResponseEntity<?> handleThumbnailFetch(PixivThumbnailFetchException failure) {
        return switch (failure.failure()) {
            case INVALID_TARGET -> invalidThumbnail("pixiv.proxy.thumbnail-url.host.invalid", "invalid_value");
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

    private ResponseEntity<HttpParameterError> invalidThumbnail(String messageKey, String reason) {
        log.warn(messages.getForLog(messageKey));
        return ResponseEntity.badRequest().body(new HttpParameterError("error.request.param.invalid",
                messages.get(messageKey), "query", "url", reason));
    }

    private ResponseEntity<ErrorResponse> thumbnailFailureResponse(HttpStatus responseStatus, String detail) {
        log.warn(messages.getForLog("error.log.request.failed", detail));
        return ResponseEntity.status(responseStatus).body(new ErrorResponse(detail));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> invalidParameter() {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of("error.request.param.invalid",
                messages.get("error.request.param.invalid")));
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
                    .header("Retry-After", Long.toString(java.time.Duration.ofHours(decision.windowHours()).toSeconds()))
                    .body(new ProxyRateLimitResponse(
                            decision.errorMessage(), decision.maxRequests(), decision.windowHours()));
        };
    }

    public record UserMetaResponse(String name, String userId) {}
    public record MeUidResponse(String uid) {}
    public record ErrorResponse(String code, String error) implements ApiErrorResponse {
        public ErrorResponse(String error) { this("pixiv.proxy.request-failed", error); }
    }
    public record ProxyRateLimitResponse(@Schema(allowableValues = "pixiv.proxy.rate-limited") String code, String error, int maxRequests, int windowHours)
            implements ApiErrorResponse {
        public ProxyRateLimitResponse(String error, int maxRequests, int windowHours) {
            this("pixiv.proxy.rate-limited", error, maxRequests, windowHours);
        }
    }
}
