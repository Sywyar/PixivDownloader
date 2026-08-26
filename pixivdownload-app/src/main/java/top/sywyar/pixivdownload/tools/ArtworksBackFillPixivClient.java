package top.sywyar.pixivdownload.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 查询 Pixiv 插画详情，并把 HTTP 与 JSON 响应归一为回填结果。
 */
final class ArtworksBackFillPixivClient implements AutoCloseable {

    private static final String PIXIV_AJAX = "https://www.pixiv.net/ajax/illust/";
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
                .disableCookieManagement();
        if (options.useProxy()) {
            clientBuilder.setProxy(new HttpHost("http", options.proxyHost(), options.proxyPort()));
        }
        return new ArtworksBackFillPixivClient(clientBuilder.build(), mapper);
    }

    LookupResult query(long artworkId) {
        HttpGet request = new HttpGet(PIXIV_AJAX + artworkId);
        PixivRequestHeaders.applyAjax(request, null);

        try {
            return http.execute(request, response -> parseResponse(
                    response.getCode(),
                    EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8),
                    mapper
            ));
        } catch (Exception e) {
            return LookupResult.skip(message("artworks-backfill.lookup.request-error", e.getMessage()));
        }
    }

    static LookupResult parseResponse(int status, String body, ObjectMapper mapper) throws IOException {
        if (status == 429) {
            return LookupResult.rateLimited();
        }
        if (status == 404) {
            return LookupResult.deleted("HTTP 404");
        }

        JsonNode root = mapper.readTree(body);
        if (root == null) {
            return LookupResult.skip(message("artworks-backfill.lookup.empty-response"));
        }
        if (root.path("error").asBoolean(false)) {
            String responseMessage = root.path("message").asText("pixiv ajax error");
            String lower = responseMessage.toLowerCase();
            for (String keyword : R18_KEYWORDS) {
                if (lower.contains(keyword.toLowerCase())) {
                    return LookupResult.r18Only(responseMessage);
                }
            }
            for (String keyword : DELETED_KEYWORDS) {
                if (lower.contains(keyword.toLowerCase())) {
                    return LookupResult.deleted(responseMessage);
                }
            }
            return LookupResult.skip(responseMessage);
        }

        JsonNode payload = root.path("body");
        long authorId = payload.path("userId").asLong(0);
        String authorName = payload.path("userName").asText("").trim();
        if (authorId > 0 && authorName.isEmpty()) {
            authorName = String.valueOf(authorId);
        }
        int xRestrict = payload.path("xRestrict").asInt(0);
        boolean isAi = payload.path("aiType").asInt(0) >= 2;
        String description = payload.path("description").asText("");
        List<TagEntry> tags = extractTags(payload);
        long seriesId = 0;
        long seriesOrder = 0;
        String seriesTitle = null;
        JsonNode navigation = payload.path("seriesNavData");
        if (navigation.isObject()) {
            long candidateSeriesId = navigation.path("seriesId").asLong(0);
            if (candidateSeriesId > 0) {
                seriesId = candidateSeriesId;
                seriesOrder = navigation.path("order").asLong(0);
                seriesTitle = navigation.path("title").asText("").trim();
                if (seriesTitle.isEmpty()) {
                    seriesTitle = String.valueOf(seriesId);
                }
            }
        }
        return LookupResult.found(
                authorId,
                authorName,
                xRestrict,
                isAi,
                description,
                tags,
                seriesId,
                seriesOrder,
                seriesTitle
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
        final int xRestrict;
        final boolean isAi;
        final String description;
        final List<TagEntry> tags;
        final long seriesId;
        final long seriesOrder;
        final String seriesTitle;
        final String message;

        private LookupResult(
                ResultType type,
                long authorId,
                String authorName,
                int xRestrict,
                boolean isAi,
                String description,
                List<TagEntry> tags,
                long seriesId,
                long seriesOrder,
                String seriesTitle,
                String message
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
                    null
            );
        }

        static LookupResult r18Only(String message) {
            return new LookupResult(ResultType.R18_ONLY, 0, null, 1, false, null, null, 0, 0, null, message);
        }

        static LookupResult deleted(String message) {
            return new LookupResult(ResultType.DELETED, 0, null, 0, false, null, null, 0, 0, null, message);
        }

        static LookupResult skip(String message) {
            return new LookupResult(ResultType.SKIP, 0, null, 0, false, null, null, 0, 0, null, message);
        }

        static LookupResult rateLimited() {
            return new LookupResult(ResultType.RATE_LIMITED, 0, null, 0, false, null, null, 0, 0, null, "HTTP 429");
        }
    }

    record TagEntry(String name, String translatedName) {}
}
