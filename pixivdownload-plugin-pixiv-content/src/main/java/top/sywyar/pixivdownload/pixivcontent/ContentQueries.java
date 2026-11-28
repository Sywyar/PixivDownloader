package top.sywyar.pixivdownload.pixivcontent;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentialsUnavailableException;
import top.sywyar.pixivdownload.core.web.HttpParameterException;
import top.sywyar.pixivdownload.pixivcontent.ContentModels.*;

/** Maps Pixiv web responses to the stable content API. */
final class ContentQueries {
    private static final Logger log = LoggerFactory.getLogger(ContentQueries.class);
    static final String WEB = "https://www.pixiv.net";
    private static final int USER_PAGE_SIZE = 48;
    private static final int SERIES_PAGE_SIZE = 12;

    private final PixivAjaxClient ajax;
    private final ObjectMapper json;
    private final PageManifestCache pageCache = new PageManifestCache(System::nanoTime);

    ContentQueries(PixivAjaxClient ajax, ObjectMapper json) {
        this.ajax = ajax;
        this.json = json;
    }

    static URI searchUri(UriComponentsBuilder endpoint, String word, String order, String mode, String sMode,
                         LocalDate startDate, LocalDate endDate, int page) {
        checkDate(startDate, "startDate");
        checkDate(endDate, "endDate");
        if (startDate != null && endDate != null && endDate.isBefore(startDate))
            throw new HttpParameterException("query", "endDate", "out_of_range");
        if (startDate != null) endpoint.queryParam("scd", startDate);
        if (endDate != null) endpoint.queryParam("ecd", endDate);
        if (word.indexOf('/') >= 0 || word.indexOf('\\') >= 0 || ".".equals(word) || "..".equals(word)) {
            throw new HttpParameterException("query", "word", "invalid_value");
        }
        return endpoint
                .pathSegment("{word}").queryParam("word", "{word}").queryParam("order", order)
                .queryParam("mode", mode).queryParam("s_mode", sMode)
                .queryParam("p", page).encode().buildAndExpand(Map.of("word", word)).toUri();
    }

    static LocalDate checkDate(LocalDate date, String field) {
        if (date != null && (date.getYear() < 1 || date.getYear() > 9999))
            throw new HttpParameterException("query", field, "out_of_range");
        return date;
    }

    ArtworkList search(String word, String order, String mode, String sMode, ArtworkSearchType type,
                       LocalDate startDate, LocalDate endDate, int page, String cookie) {
        return search(word, order, mode, sMode, type, startDate, endDate, page, cookie, SearchFilters.NONE);
    }

    ArtworkList search(String word, String order, String mode, String sMode, ArtworkSearchType type,
                       LocalDate startDate, LocalDate endDate, int page, String cookie, SearchFilters filters) {
        var endpoint = UriComponentsBuilder.fromUriString(WEB + "/ajax/search/" + type.path)
                .queryParam("type", type.pixivType);
        filters.apply(endpoint);
        URI uri = searchUri(endpoint, word, order, mode, sMode, startDate, endDate, page);
        JsonNode results = body(uri, cookie).path(type.results);
        List<Card> cards = new ArrayList<>();
        for (JsonNode item : array(results.path("data"))) {
            if (!item.path("isAdContainer").asBoolean(false) && !item.path("isMasked").asBoolean(false)) {
                var card = card(item, false);
                if (type != ArtworkSearchType.all && !card.type().equals(type.name())) throw ContentFailure.invalidResponse();
                cards.add(card);
            }
        }
        int total = nonnegative(results.path("total"));
        return new ArtworkList(page, total, page < nonnegative(results.path("lastPage")), cards);
    }

    Ranking rankings(String mode, RankingContent content, LocalDate date, int page, String cookie) {
        var request = UriComponentsBuilder.fromUriString(WEB + "/ranking.php")
                .queryParam("mode", mode).queryParam("format", "json").queryParam("p", page);
        if (content != RankingContent.all) request.queryParam("content", content.name());
        if (date != null) request.queryParam("date", date.format(DateTimeFormatter.BASIC_ISO_DATE));
        // 排行榜是旧版 PHP 页面，format=json 让宿主 JSON 出口按 JSON 文档放行。
        JsonNode result = document(request.build().toUri(), cookie);
        if (!mode.equals(result.path("mode").asString("")) || result.path("page").asInt(-1) != page
                || !content.name().equals(result.path("content").asString("all"))) {
            throw ContentFailure.invalidResponse();
        }
        LocalDate published;
        try {
            published = LocalDate.parse(result.path("date").asString(""), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException invalid) {
            throw ContentFailure.invalidResponse();
        }
        if (date != null && !date.equals(published)) throw ContentFailure.invalidResponse();
        List<RankedArtwork> entries = new ArrayList<>();
        for (JsonNode item : array(result.path("contents"))) {
            if (!item.path("is_masked").asBoolean(false)) {
                entries.add(new RankedArtwork(positive(item.path("rank")), card(item, true)));
            }
        }
        JsonNode next = result.path("next");
        if (!next.isIntegralNumber() && !(next.isBoolean() && !next.asBoolean())) {
            throw ContentFailure.invalidResponse();
        }
        return new Ranking(mode, content, published, page, next.asLong(0) > page, entries);
    }

    Artwork artwork(long id, String cookie) {
        JsonNode info;
        try {
            info = body(URI.create(WEB + "/ajax/illust/" + id), cookie);
            if (id(info.path("id")) != id) throw ContentFailure.invalidResponse();
            if (info.path("isMasked").asBoolean(false)) throw ContentFailure.upstream(403);
        } catch (PixivAjaxException rejection) {
            if (List.of(401, 403, 404, 410).contains(rejection.statusCode())) pageCache.denied(id, cookie, rejection);
            throw rejection;
        } catch (ContentFailure | AcquisitionCredentialsUnavailableException unavailable) {
            pageCache.denied(id, cookie, unavailable);
            throw unavailable;
        }
        int count = positive(info.path("pageCount"));
        var manifest = pages(id, cookie, count);
        if (manifest.manifest().pages().size() != count && manifest.cached()) {
            log.info("Pixiv page list was stale; refetching: artworkId={} cachedPages={} expectedPages={}",
                    id, manifest.manifest().pages().size(), count);
            pageCache.invalidate(manifest);
            manifest = pages(id, cookie, count);
        }
        if (manifest.manifest().pages().size() != count) {
            pageCache.invalidate(manifest);
            throw ContentFailure.invalidResponse();
        }
        List<ImagePage> images = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String preview = imagePath(id, i);
            var page = manifest.manifest().pages().get(i);
            images.add(new ImagePage(i, preview, preview + "?size=original", page.width(), page.height()));
        }
        String workType = type(info.path("illustType"));
        return new Artwork(id, text(info, "title"), id(info.path("userId")), text(info, "userName"),
                workType, count, detailTags(info), images, seriesMembership(info),
                "ugoira".equals(workType) ? PixivContentPlugin.API + "/artworks/" + id + "/animation" : null,
                optionalLevel(info.path("xRestrict")), optionalLevel(info.path("aiType")),
                optionalInstant(info.path("createDate")), optionalInstant(info.path("uploadDate")),
                info.path("description").asString(null), optionalCount(info.path("bookmarkCount")));
    }

    static List<String> detailTags(JsonNode info) {
        List<String> tags = new ArrayList<>();
        for (JsonNode entry : array(info.path("tags").path("tags"))) tags.add(text(entry, "tag"));
        return tags;
    }

    static SeriesMembership seriesMembership(JsonNode info) {
        JsonNode nav = info.path("seriesNavData");
        return nav.isObject()
                ? new SeriesMembership(id(nav.path("seriesId")), text(nav, "title"), positive(nav.path("order"))) : null;
    }

    <T> T readImage(long id, int pageIndex, String size, String cookie, Function<URI, T> read) {
        if (pageIndex < 0) throw new HttpParameterException("path", "pageIndex", "out_of_range");
        boolean original = switch (size) {
            case "preview" -> false;
            case "original" -> true;
            default -> throw new HttpParameterException("query", "size", "invalid_value");
        };
        var manifest = pages(id, cookie, 0);
        if (pageIndex >= manifest.manifest().pages().size() && manifest.cached()) {
            log.info("Pixiv page list may have changed; refetching: artworkId={} cachedPages={} pageIndex={}",
                    id, manifest.manifest().pages().size(), pageIndex);
            pageCache.invalidate(manifest);
            manifest = pages(id, cookie, 0);
        }
        URI uri = imageUri(manifest, pageIndex, original);
        try {
            return transfer(manifest, uri, read);
        } catch (ContentFailure failure) {
            if (!manifest.cached() || !staleUrl(failure)) throw failure;
            var fresh = pages(id, cookie, 0);
            URI updated = imageUri(fresh, pageIndex, original);
            boolean unchanged = uri.equals(updated);
            // 只重试一次：刷新后的地址相同即放弃，最终失败由控制器记录。
            log.info("Pixiv image link rejected; refreshed the page list: artworkId={} pageIndex={} upstreamStatus={} action={}",
                    id, pageIndex, failure.upstreamStatus, unchanged ? "give_up" : "retry");
            if (unchanged) {
                pageCache.invalidate(fresh);
                throw failure;
            }
            return transfer(fresh, updated, read);
        }
    }

    private <T> T transfer(PageManifestCache.Read manifest, URI uri, Function<URI, T> read) {
        try {
            return read.apply(uri);
        } catch (ContentFailure failure) {
            if (staleUrl(failure)) pageCache.invalidate(manifest);
            throw failure;
        }
    }

    private static boolean staleUrl(ContentFailure failure) {
        return failure.status == 403 || failure.status == 404;
    }

    private static URI imageUri(PageManifestCache.Read read, int index, boolean original) {
        var pages = read.manifest().pages();
        if (index >= pages.size()) throw ContentFailure.notFound();
        var page = pages.get(index);
        return URI.create(original ? page.original() : page.preview());
    }

    User user(long id, String cookie) {
        JsonNode profile = body(URI.create(WEB + "/ajax/user/" + id + "?full=1"), cookie);
        if (id(profile.path("userId")) != id) throw ContentFailure.invalidResponse();
        String avatar = profile.path("imageBig").asString(profile.path("image").asString(""));
        return new User(id, text(profile, "name"), profile.path("comment").asString(""),
                avatar.isBlank() ? null : cdnUri(avatar).toString());
    }

    ArtworkList userArtworks(long id, int page, String cookie, String... tags) {
        if (tags != null && tags.length > 0) {
            var matches = AuthorTagSearch.search(this, id, "illustmanga", tags, page, USER_PAGE_SIZE, cookie);
            return new ArtworkList(page, matches.total(), matches.hasNext(),
                    matches.works().stream().map(item -> card(item, false)).toList());
        }
        String user = WEB + "/ajax/user/" + id;
        JsonNode profile = body(URI.create(user + "/profile/all"), cookie);
        var ids = new TreeSet<Long>(Comparator.reverseOrder());
        for (String category : List.of("illusts", "manga")) {
            JsonNode index = profile.path(category);
            // Pixiv serializes an empty PHP dictionary as an empty array.
            if (index.isArray() && index.isEmpty()) continue;
            if (!index.isObject()) throw ContentFailure.invalidResponse();
            for (String key : index.propertyNames()) ids.add(positiveId(key));
        }
        long offset = (page - 1L) * USER_PAGE_SIZE;
        if (offset >= ids.size()) return new ArtworkList(page, ids.size(), false, List.of());
        List<Long> selection = ids.stream().skip(offset).limit(USER_PAGE_SIZE).toList();
        var request = UriComponentsBuilder.fromUriString(user + "/illusts");
        for (long key : selection) request.queryParam("ids[]", key);
        JsonNode works = body(request.build().encode().toUri(), cookie);
        if (!works.isObject() && !(works.isArray() && works.isEmpty())) throw ContentFailure.invalidResponse();
        List<Card> cards = new ArrayList<>();
        for (long key : selection) {
            JsonNode item = works.path(Long.toString(key));
            if (item.isMissingNode() || item.isNull() || item.path("isMasked").asBoolean(false)) continue;
            Card card = card(item, false);
            if (card.id() != key || card.userId() != id) throw ContentFailure.invalidResponse();
            cards.add(card);
        }
        return new ArtworkList(page, ids.size(), offset + USER_PAGE_SIZE < ids.size(), cards);
    }

    Series series(long id, int page, String cookie) {
        JsonNode data = body(URI.create(WEB + "/ajax/series/" + id + "?p=" + page), cookie);
        JsonNode info = null;
        for (JsonNode candidate : array(data.path("illustSeries"))) {
            if (id(candidate.path("id")) == id) { info = candidate; break; }
        }
        if (info == null || id(data.path("page").path("seriesId")) != id) throw ContentFailure.invalidResponse();
        int total = nonnegative(info.path("total"));
        Map<Long, JsonNode> thumbnails = new HashMap<>();
        for (JsonNode item : array(data.path("thumbnails").path("illust"))) {
            thumbnails.put(id(item.path("id")), item);
        }
        List<SeriesEntry> entries = new ArrayList<>();
        for (JsonNode entry : array(data.path("page").path("series"))) {
            JsonNode item = thumbnails.get(id(entry.path("workId")));
            if (item != null && !item.path("isMasked").asBoolean(false)) {
                entries.add(new SeriesEntry(positive(entry.path("order")), card(item, false)));
            }
        }
        return new Series(id, text(info, "title"), id(info.path("userId")), info.path("caption").asString(""),
                page, total, (long) page * SERIES_PAGE_SIZE < total, entries);
    }

    private PageManifestCache.Read pages(long id, String cookie, int expectedCount) {
        return pageCache.read(id, cookie, () -> {
            JsonNode list = array(body(URI.create(WEB + "/ajax/illust/" + id + "/pages"), cookie));
            if (list.isEmpty() || expectedCount > 0 && list.size() != expectedCount) throw ContentFailure.invalidResponse();
            var result = new ArrayList<PageManifestCache.Page>(list.size());
            for (var item : list) {
                var urls = item.path("urls");
                result.add(new PageManifestCache.Page(cdnUri(text(urls, "regular")).toString(),
                        cdnUri(text(urls, "original")).toString(), optionalCount(item.path("width")),
                        optionalCount(item.path("height"))));
            }
            return new PageManifestCache.Manifest(result);
        });
    }

    JsonNode browseBody(URI uri, String cookie) {
        try {
            return body(uri, cookie);
        } catch (PixivAjaxException failure) {
            if (cookie == null && failure.statusCode() == 400) throw ContentFailure.credentialRequired();
            throw failure;
        }
    }

    JsonNode body(URI uri, String cookie) {
        JsonNode envelope = document(uri, cookie);
        if (!envelope.path("error").isBoolean() || envelope.path("error").asBoolean()) {
            throw ContentFailure.invalidResponse();
        }
        JsonNode value = envelope.path("body");
        if (value.isMissingNode() || value.isNull()) throw ContentFailure.invalidResponse();
        return value;
    }

    JsonNode document(URI uri, String cookie) {
        return parse(ajax.get(uri, cookie));
    }

    JsonNode parse(String raw) {
        if (raw == null) throw ContentFailure.invalidResponse();
        try {
            JsonNode parsed = json.readTree(raw);
            if (parsed == null || !parsed.isObject()) throw ContentFailure.invalidResponse();
            return parsed;
        } catch (JacksonException invalid) {
            throw ContentFailure.invalidResponse();
        }
    }

    static JsonNode array(JsonNode value) {
        if (!value.isArray()) throw ContentFailure.invalidResponse();
        return value;
    }

    static Card card(JsonNode item, boolean ranking) {
        long id = id(item.path(ranking ? "illust_id" : "id"));
        return new Card(id, text(item, "title"), id(item.path(ranking ? "user_id" : "userId")),
                text(item, ranking ? "user_name" : "userName"), type(item.path(ranking ? "illust_type" : "illustType")),
                positive(item.path(ranking ? "illust_page_count" : "pageCount")), imagePath(id, 0),
                optionalLevel(item.path("xRestrict")), optionalLevel(item.path("aiType")),
                optionalInstant(item.path("createDate")), ranking ? rankingUploadTime(item)
                        : optionalInstant(item.path("uploadDate")));
    }

    private static Instant rankingUploadTime(JsonNode item) {
        JsonNode seconds = item.path("illust_upload_timestamp");
        if (seconds.isMissingNode() || seconds.isNull()) return null;
        try {
            return Instant.ofEpochSecond(id(seconds));
        } catch (java.time.DateTimeException malformed) {
            throw ContentFailure.invalidResponse();
        }
    }

    static Integer optionalCount(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? null : nonnegative(value);
    }

    static Integer optionalLevel(JsonNode value) {
        Integer level = optionalCount(value);
        if (level != null && level > 2) throw ContentFailure.invalidResponse();
        return level;
    }

    static Instant optionalInstant(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        try {
            return OffsetDateTime.parse(value.asString("")).toInstant();
        } catch (DateTimeParseException invalid) {
            throw ContentFailure.invalidResponse();
        }
    }

    private static String type(JsonNode value) {
        return switch (nonnegative(value)) {
            case 0 -> "illustration";
            case 1 -> "manga";
            case 2 -> "ugoira";
            default -> throw ContentFailure.invalidResponse();
        };
    }

    static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isString()) throw ContentFailure.invalidResponse();
        return value.asString();
    }

    static long id(JsonNode value) {
        return positiveId(value.asString(""));
    }

    static long positiveId(String raw) {
        try {
            long id = Long.parseLong(raw);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // The invalid upstream value is omitted from the public failure.
        }
        throw ContentFailure.invalidResponse();
    }

    static int nonnegative(JsonNode value) {
        try {
            int count = Integer.parseInt(value.asString(""));
            if (count >= 0) return count;
        } catch (NumberFormatException ignored) {
            // The invalid upstream value is omitted from the public failure.
        }
        throw ContentFailure.invalidResponse();
    }

    static int positive(JsonNode value) {
        int count = nonnegative(value);
        if (count == 0) throw ContentFailure.invalidResponse();
        return count;
    }

    private static String imagePath(long id, int index) {
        return "/api/pixiv-content/artworks/" + id + "/pages/" + index + "/image";
    }

    static URI cdnUri(String value) {
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getHost().toLowerCase(Locale.ROOT).endsWith(".pximg.net")
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)) return uri;
        } catch (IllegalArgumentException ignored) {
            // Treat a malformed URI the same as any untrusted upstream CDN target.
        }
        throw ContentFailure.invalidResponse();
    }
}
