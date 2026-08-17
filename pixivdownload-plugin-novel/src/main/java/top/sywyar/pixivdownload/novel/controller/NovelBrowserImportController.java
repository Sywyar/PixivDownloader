package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.browser.NovelBrowserFetchTicketStore;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequestFactory;
import top.sywyar.pixivdownload.novel.response.NovelErrorResponse;
import top.sywyar.pixivdownload.novel.schedule.PixivNovelMetadata;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.web.LocalRequestTrust;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** 接收本机油猴脚本已通过浏览器登录态取得的 Pixiv 小说响应。 */
@RestController
@RequestMapping("/api/novel/browser-import")
@PluginManagedBean
@RequiredArgsConstructor
public class NovelBrowserImportController {

    public static final String IMPORT_TOKEN_HEADER = "X-Novel-Import-Token";
    public static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private static final int MAX_TITLE_LENGTH = 1_000;
    private static final int MAX_DESCRIPTION_LENGTH = 262_144;
    private static final int MAX_CONTENT_LENGTH = 3_000_000;
    private static final int MAX_TAGS = 256;
    private static final int MAX_SHORT_FIELD_LENGTH = 256;
    private static final int MAX_URL_LENGTH = 4_096;
    private static final int MAX_COUNT = 10_000_000;

    private final ObjectMapper objectMapper;
    private final NovelBrowserFetchTicketStore ticketStore;
    private final ApplicationModeProvider applicationModeProvider;
    private final MessageResolver messages;

    @GetMapping("/token")
    public ResponseEntity<?> issueImportToken(HttpServletRequest request) {
        if (!availableTo(request)) {
            return error(403, "novel.browser-import.unavailable");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ImportTokenResponse(
                        ticketStore.issueImportToken(), NovelBrowserFetchTicketStore.TICKET_TTL_SECONDS));
    }

    @PostMapping("/{novelId}")
    public ResponseEntity<?> importNovel(@PathVariable long novelId, HttpServletRequest request) {
        if (!availableTo(request)) {
            return error(403, "novel.browser-import.unavailable");
        }
        if (novelId <= 0L || !ticketStore.claimImportToken(request.getHeader(IMPORT_TOKEN_HEADER))) {
            return error(400, "novel.browser-import.token-invalid");
        }
        if (request.getContentLengthLong() > MAX_RESPONSE_BYTES) {
            return error(413, "novel.browser-import.payload-too-large");
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            return error(400, "novel.browser-import.response-invalid");
        }

        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                return error(413, "novel.browser-import.payload-too-large");
            }
            JsonNode body = requirePixivBody(decodeUtf8(bytes), novelId);
            PixivNovelMetadata metadata = PixivNovelMetadata.parse(novelId, body);
            if (!validMetadata(metadata)) {
                return error(400, "novel.browser-import.response-invalid");
            }
            String rawMetaJson = NovelDownloadRequestFactory.boundedRawMetadata(objectMapper, body);
            String fetchToken = ticketStore.issueBrowserFetchTicket(novelId, metadata, rawMetaJson);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new FetchTicketResponse(
                            fetchToken, NovelBrowserFetchTicketStore.TICKET_TTL_SECONDS));
        } catch (PixivAjaxException oversized) {
            return oversized.failure() == PixivAjaxFailure.RESPONSE_TOO_LARGE
                    ? error(413, "novel.browser-import.payload-too-large")
                    : error(400, "novel.browser-import.response-invalid");
        } catch (IOException | RuntimeException invalid) {
            return error(400, "novel.browser-import.response-invalid");
        }
    }

    private JsonNode requirePixivBody(String response, long novelId) throws IOException {
        JsonNode root;
        try (JsonParser parser = objectMapper.createParser(response)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("trailing JSON content");
            }
        }
        if (root == null || !root.isObject()
                || !root.path("error").isBoolean() || root.path("error").asBoolean()) {
            throw new IllegalArgumentException("invalid Pixiv response envelope");
        }
        JsonNode body = root.path("body");
        if (!body.isObject() || !matchesNovelId(body.path("id"), novelId)) {
            throw new IllegalArgumentException("Pixiv response novel id mismatch");
        }
        return body;
    }

    private static boolean matchesNovelId(JsonNode value, long expected) {
        if (value.isIntegralNumber()) {
            return value.canConvertToLong() && value.longValue() == expected;
        }
        if (!value.isTextual()) {
            return false;
        }
        return Long.toString(expected).equals(value.textValue());
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static boolean validMetadata(PixivNovelMetadata value) {
        return value.novelId() > 0L
                && text(value.title(), 1, MAX_TITLE_LENGTH)
                && text(value.content(), 0, MAX_CONTENT_LENGTH)
                && value.xRestrict() >= 0 && value.xRestrict() <= 2
                && value.bookmarkCount() >= -1
                && value.authorId() != null && value.authorId() > 0L
                && text(value.authorName(), 0, MAX_TITLE_LENGTH)
                && text(value.description(), 0, MAX_DESCRIPTION_LENGTH)
                && text(value.language(), 0, 64)
                && (value.seriesId() == null || value.seriesId() > 0L)
                && (value.seriesOrder() == null
                || value.seriesOrder() >= 0L && value.seriesOrder() <= MAX_COUNT)
                && nullableText(value.seriesTitle(), MAX_TITLE_LENGTH)
                && validCount(value.wordCount())
                && validCount(value.textLength())
                && validCount(value.readingTimeSeconds())
                && validCount(value.pageCount())
                && validTags(value)
                && validImageUrl(value.coverUrl(), true)
                && (value.uploadTimestamp() == null || value.uploadTimestamp() > 0L)
                && validEmbeddedImages(value.embeddedImages());
    }

    private static boolean validTags(PixivNovelMetadata value) {
        if (value.tags() == null || value.tags().size() > MAX_TAGS) {
            return false;
        }
        for (WorkTag tag : value.tags()) {
            if (tag == null || !text(tag.name(), 1, MAX_SHORT_FIELD_LENGTH)
                    || !nullableText(tag.translatedName(), MAX_SHORT_FIELD_LENGTH)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validEmbeddedImages(Map<String, String> images) {
        if (images == null || images.size() > PixivNovelMetadata.MAX_EMBEDDED_IMAGES) {
            return false;
        }
        for (Map.Entry<String, String> entry : images.entrySet()) {
            if (!text(entry.getKey(), 1, 128) || !validImageUrl(entry.getValue(), false)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validImageUrl(String value, boolean allowBlank) {
        if (value == null || value.isBlank()) {
            return allowBlank;
        }
        if (value.length() > MAX_URL_LENGTH) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && host.toLowerCase(Locale.ROOT).endsWith(".pximg.net")
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean validCount(Integer value) {
        return value == null || (value >= 0 && value <= MAX_COUNT);
    }

    private static boolean nullableText(String value, int maxLength) {
        return value == null || text(value, 0, maxLength);
    }

    private static boolean text(String value, int minLength, int maxLength) {
        return value != null && value.length() >= minLength && value.length() <= maxLength;
    }

    private boolean availableTo(HttpServletRequest request) {
        return "solo".equals(applicationModeProvider.getMode())
                && LocalRequestTrust.isLocalRequest(
                request.getRemoteAddr(),
                request.getHeader("Host"),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getHeader("Forwarded"));
    }

    private ResponseEntity<NovelErrorResponse> error(int status, String key) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new NovelErrorResponse(key, messages.get(key)));
    }

    public record ImportTokenResponse(String token, long expiresInSeconds) {
    }

    public record FetchTicketResponse(String fetchToken, long expiresInSeconds) {
    }
}
