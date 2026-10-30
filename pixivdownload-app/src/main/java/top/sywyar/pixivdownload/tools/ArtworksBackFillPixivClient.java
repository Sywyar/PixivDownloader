package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpEntity;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import org.apache.hc.core5.util.Timeout;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 查询 Pixiv 插画和小说详情，并把 HTTP 与 JSON 响应归一为回填结果。
 */
final class ArtworksBackFillPixivClient implements AutoCloseable {

    private static final String PIXIV_AJAX = "https://www.pixiv.net/ajax/";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final String[] R18_KEYWORDS = {
            "R-18", "R18", "年齢制限", "年龄限制", "閲覧制限", "18歳未満",
            "成人向け", "成人向", "restricted", "age"
    };
    private static final String[] DELETED_KEYWORDS = {
            "削除", "存在しない", "not found", "该作品", "不存在", "已删除"
    };

    private final CloseableHttpClient http;
    private final ObjectMapper mapper;

    private ArtworksBackFillPixivClient(CloseableHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    static ArtworksBackFillPixivClient open(ArtworksBackFill.Options options, ObjectMapper mapper) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(15))
                .setResponseTimeout(Timeout.ofSeconds(15))
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .build();

        var clientBuilder = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableCookieManagement();
        if (options.useProxy()) {
            clientBuilder.setProxy(new HttpHost("http", options.proxyHost(), options.proxyPort()));
        }
        return new ArtworksBackFillPixivClient(clientBuilder.build(), mapper);
    }

    LookupResult query(long artworkId) throws IOException {
        return query(WorkType.ARTWORK, artworkId);
    }

    LookupResult queryNovel(long id) throws IOException {
        return query(WorkType.NOVEL, id);
    }

    private LookupResult query(WorkType type, long artworkId) throws IOException {
        HttpGet request = new HttpGet(PIXIV_AJAX + (type == WorkType.NOVEL ? "novel/" : "illust/") + artworkId);
        PixivRequestHeaders.applyAjax(request, null);

        try {
            return http.execute(request, response -> parseResponse(
                    response.getCode(),
                    readResponse(response.getEntity()),
                    mapper, type
            ));
        } catch (IOException e) {
            throw new IOException(type.name() + "=" + artworkId + " "
                    + message("artworks-backfill.lookup.request-error", e.getMessage()), e);
        }
    }

    static String readResponse(HttpEntity entity) throws IOException {
        if (entity == null) return "";
        if (entity.getContentLength() > MAX_RESPONSE_BYTES) throw new IOException("PIXIV_RESPONSE_TOO_LARGE");
        try (var input = entity.getContent()) {
            byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("PIXIV_RESPONSE_TOO_LARGE");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static LookupResult parseResponse(int status, String body, ObjectMapper mapper) throws IOException {
        return parseResponse(status, body, mapper, WorkType.ARTWORK);
    }

    static LookupResult parseResponse(int status, String body, ObjectMapper mapper, WorkType type) throws IOException {
        if (status == 429) {
            return LookupResult.rateLimited();
        }
        if (status == 404) {
            return LookupResult.deleted("HTTP 404");
        }
        if (status == 401 || status == 403 || status == 407 || status >= 500) {
            throw new IOException(message("artworks-backfill.lookup.request-error", "HTTP " + status));
        }
        if (status < 200 || status >= 300) {
            return LookupResult.skip("HTTP " + status);
        }

        JsonNode root = mapper.readTree(body);
        if (root == null) {
            return LookupResult.skip(message("artworks-backfill.lookup.empty-response"));
        }
        if (root.path("error").asBoolean(false)) {
            String responseMessage = root.path("message").asText("pixiv ajax error");
            String lower = responseMessage.toLowerCase(Locale.ROOT);
            for (String keyword : R18_KEYWORDS) {
                if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return LookupResult.r18Only(responseMessage);
                }
            }
            for (String keyword : DELETED_KEYWORDS) {
                if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return LookupResult.deleted(responseMessage);
                }
            }
            return LookupResult.skip(responseMessage);
        }

        JsonNode payload = root.path("body");
        if (!payload.isObject() || payload.isEmpty()) {
            return LookupResult.skip(message("artworks-backfill.lookup.empty-response"));
        }
        long authorId = payload.path("userId").asLong(0);
        if (authorId <= 0) {
            return LookupResult.skip(message("artworks-backfill.lookup.empty-response"));
        }
        Long uploadTime = type == WorkType.NOVEL
                ? new top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator(mapper)
                        .curateNovel(1, payload, "backfill").uploadTime() : null;
        return parsePayload(payload, uploadTime, true);
    }

    static LookupResult parsePayload(JsonNode payload, Long uploadTime, boolean completeResponse) {
        long authorId = payload.path("userId").asLong(0);
        String authorName = payload.path("userName").asText("").trim();
        if (authorName.isEmpty()) {
            authorName = null;
        }
        Integer xRestrict = payload.path("xRestrict").canConvertToInt()
                ? payload.path("xRestrict").intValue() : null;
        Boolean isAi = payload.path("aiType").canConvertToInt()
                ? payload.path("aiType").intValue() >= 2 : null;
        String description = payload.path("description").isTextual() ? payload.path("description").textValue() : null;
        List<TagEntry> tags = payload.path("tags").path("tags").isArray() ? extractTags(payload) : null;
        long seriesId = 0;
        Long seriesOrder = null;
        String seriesTitle = null;
        JsonNode navigation = payload.path("seriesNavData");
        if (navigation.isObject()) {
            long candidateSeriesId = navigation.path("seriesId").asLong(0);
            if (candidateSeriesId > 0) {
                seriesId = candidateSeriesId;
                if (navigation.path("order").canConvertToLong()) seriesOrder = navigation.path("order").longValue();
                seriesTitle = navigation.path("title").asText("").trim();
                if (seriesTitle.isEmpty()) {
                    seriesTitle = null;
                }
            }
        }
        if (uploadTime == null) uploadTime = new top.sywyar.pixivdownload.core.metadata.sidecar.WorkMetaCurator(new ObjectMapper())
                .curateArtwork(1, payload, null, "backfill").uploadTime();
        return new LookupResult(
                ResultType.FOUND,
                authorId,
                authorName,
                xRestrict,
                isAi,
                description,
                tags,
                seriesId,
                seriesOrder,
                seriesTitle,
                null,
                uploadTime,
                navigation.isNull() || seriesId > 0 || (completeResponse && navigation.isMissingNode())
        );
    }

    private static List<TagEntry> extractTags(JsonNode payload) {
        JsonNode tags = payload.path("tags").path("tags");
        if (!tags.isArray() || tags.isEmpty()) {
            return List.of();
        }

        List<TagEntry> result = new ArrayList<>();
        for (JsonNode value : tags) {
            String name = value.path("tag").asText("");
            if (name.isEmpty()) {
                continue;
            }
            String translatedName = null;
            JsonNode translation = value.path("translation");
            if (translation.isObject()) {
                String english = translation.path("en").asText("");
                if (!english.isEmpty()) {
                    translatedName = english;
                }
            }
            result.add(new TagEntry(name, translatedName));
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        http.close();
    }

    private static String message(String code, Object... args) {
        return MessageBundles.getForLog(code, args);
    }

    enum ResultType {
        FOUND,
        R18_ONLY,
        DELETED,
        SKIP,
        RATE_LIMITED
    }

    static final class LookupResult {
        final ResultType type;
        final long authorId;
        final String authorName;
        final Integer xRestrict;
        final Boolean isAi;
        final String description;
        final List<TagEntry> tags;
        final long seriesId;
        final Long seriesOrder;
        final String seriesTitle;
        final String message;
        final Long uploadTime;
        final boolean seriesKnown;

        private LookupResult(
                ResultType type,
                long authorId,
                String authorName,
                Integer xRestrict,
                Boolean isAi,
                String description,
                List<TagEntry> tags,
                long seriesId,
                Long seriesOrder,
                String seriesTitle,
                String message,
                Long uploadTime,
                boolean seriesKnown
        ) {
            this.type = type;
            this.authorId = authorId;
            this.authorName = authorName;
            this.xRestrict = xRestrict;
            this.isAi = isAi;
            this.description = description;
            this.tags = tags;
            this.seriesId = seriesId;
            this.seriesOrder = seriesOrder;
            this.seriesTitle = seriesTitle;
            this.message = message;
            this.uploadTime = uploadTime;
            this.seriesKnown = seriesKnown;
        }

        static LookupResult found(
                long authorId,
                String authorName,
                int xRestrict,
                boolean isAi,
                String description,
                List<TagEntry> tags,
                long seriesId,
                long seriesOrder,
                String seriesTitle
        ) {
            return new LookupResult(
                    ResultType.FOUND,
                    authorId,
                    authorName,
                    xRestrict,
                    isAi,
                    description,
                    tags,
                    seriesId,
                    seriesOrder,
                    seriesTitle,
                    null, null, true
            );
        }

        static LookupResult r18Only(String message) {
            return new LookupResult(ResultType.R18_ONLY, 0, null, 1, null, null, null, 0, null, null, message, null, false);
        }

        static LookupResult deleted(String message) {
            return new LookupResult(ResultType.DELETED, 0, null, 0, false, null, null, 0, null, null, message, null, false);
        }

        static LookupResult skip(String message) {
            return new LookupResult(ResultType.SKIP, 0, null, 0, false, null, null, 0, null, null, message, null, false);
        }

        static LookupResult rateLimited() {
            return new LookupResult(ResultType.RATE_LIMITED, 0, null, 0, false, null, null, 0, null, null, "HTTP 429", null, false);
        }
    }

    record TagEntry(String name, String translatedName) {}
}
