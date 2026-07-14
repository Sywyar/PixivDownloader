package top.sywyar.pixivdownload.douyin.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.douyin.client.signature.DouyinSignedUriBuilder;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.douyin.model.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultDouyinClient implements DouyinClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultDouyinClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RENDER_DATA = Pattern.compile(
            "<script[^>]+id=[\"']RENDER_DATA[\"'][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UNIVERSAL_DATA = Pattern.compile(
            "window\\.__UNIVERSAL_DATA_FOR_REHYDRATION__\\s*=\\s*(\\{.*?})\\s*;</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROUTER_DATA = Pattern.compile(
            "window\\._ROUTER_DATA\\s*=\\s*(\\{.*?})\\s*;</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int DEFAULT_MIX_PAGE_SIZE = 20;
    private static final int MAX_MIX_ITEMS = 100;
    private static final int MAX_MIX_CURSOR_PAGES = 100;
    private static final int MAX_CURSOR_PAGES = 1_000;
    private static final List<String> DETAIL_AID_CANDIDATES = List.of("6383", "1128");

    private final DouyinUrlParser parser;
    private final RestTemplate downloadRestTemplate;
    private final DouyinShortLinkResolver shortLinkResolver;
    private final DouyinSignedUriBuilder signedUriBuilder;

    public DefaultDouyinClient(DouyinUrlParser parser,
                               RestTemplate downloadRestTemplate,
                               DouyinShortLinkResolver shortLinkResolver) {
        this(parser, downloadRestTemplate, shortLinkResolver, new DouyinSignedUriBuilder());
    }

    DefaultDouyinClient(DouyinUrlParser parser,
                        RestTemplate downloadRestTemplate,
                        DouyinShortLinkResolver shortLinkResolver,
                        DouyinSignedUriBuilder signedUriBuilder) {
        this.parser = parser;
        this.downloadRestTemplate = downloadRestTemplate;
        this.shortLinkResolver = shortLinkResolver;
        this.signedUriBuilder = signedUriBuilder;
    }

    @Override
    public DouyinCanonicalDownload resolveDownload(String input, String cookie) throws DouyinClientException {
        DouyinParsedInput parsed = parseAndResolve(input, cookie);
        if (parsed.kind().singleWork()) {
            DouyinWork work = resolvePublicWork(parsed, cookie);
            String stableUrl = "https://www.douyin.com/video/" + work.id();
            return new DouyinCanonicalDownload(DouyinCanonicalKind.SINGLE_WORK,
                    work.id(), stableUrl, work, input);
        }
        if (parsed.kind().downloadableCollection()) {
            return new DouyinCanonicalDownload(DouyinCanonicalKind.COLLECTION,
                    parsed.id(), parsed.canonicalUrl(), null, input);
        }
        if (parsed.kind() == DouyinParsedKind.USER_PROFILE) {
            return new DouyinCanonicalDownload(DouyinCanonicalKind.USER_SOURCE,
                    parsed.id(), parsed.canonicalUrl(), null, input);
        }
        throw unsupportedParsedKind(parsed.kind());
    }

    @Override
    public DouyinParsedInput resolveInput(String input, String cookie) throws DouyinClientException {
        return parseAndResolve(input, cookie);
    }

    @Override
    public DouyinWork resolvePublicWork(String input, String cookie) throws DouyinClientException {
        DouyinParsedInput parsed = parseAndResolve(input, cookie);
        if (!parsed.kind().singleWork()) {
            throw unsupportedParsedKind(parsed.kind());
        }
        return resolvePublicWork(parsed, cookie);
    }

    private DouyinWork resolvePublicWork(DouyinParsedInput parsed, String cookie) throws DouyinClientException {
        DouyinClientException apiFailure = null;
        try {
            return resolveFromAwemeDetailApi(parsed, cookie);
        } catch (DouyinClientException e) {
            if (!shouldTryPageFallback(e.code())) {
                throw e;
            }
            apiFailure = e;
        }
        try {
            Optional<DouyinWork> fromPage = resolveFromPage(parsed, cookie);
            if (fromPage.isPresent()) {
                return fromPage.get();
            }
        } catch (DouyinClientException e) {
            if (apiFailure != null && moreSpecific(apiFailure.code())) {
                throw apiFailure;
            }
            throw e;
        }
        throw apiFailure;
    }

    @Override
    public DouyinListing listUserWorks(String userId, int offset, int limit, String cookie) throws DouyinClientException {
        int safeOffset = Math.max(0, offset);
        int safeLimit = positivePageSize(limit);
        return collectLogicalSlice(userId, safeOffset, safeLimit, cookie,
                (cursor, count) -> listUserWorksPage(userId, cursor, count, cookie));
    }

    @Override
    public DouyinListing listUserWorksPage(String userId,
                                           String cursor,
                                           int limit,
                                           String cookie) throws DouyinClientException {
        String stableUserId = requireStableId(userId, "Douyin user id is required");
        String currentCursor = normalizeCursor(cursor);
        JsonNode root = fetchSignedJson("/aweme/v1/web/aweme/post/", params(
                "sec_user_id", stableUserId,
                "max_cursor", currentCursor,
                "count", positivePageSize(limit),
                "publish_video_strategy_type", 2), cookie);
        DouyinListing listing = workListing(root, 1, positivePageSize(limit),
                new ListingContext(stableUserId, null, null, null),
                "max_cursor", "aweme_list", "items", "data");
        return requireAdvancingCursor(stableUserId, currentCursor, listing);
    }

    @Override
    public DouyinListing listSeriesWorks(String seriesId, int page, int pageSize, String cookie) throws DouyinClientException {
        int safePage = Math.max(1, page);
        int safePageSize = pageSize > 0 ? Math.min(pageSize, MAX_MIX_ITEMS) : DEFAULT_MIX_PAGE_SIZE;
        MixInfo mix = fetchMixInfo(seriesId, cookie);
        Map<String, DouyinWork> worksById = new LinkedHashMap<>();
        long cursor = 0L;
        Set<Long> seenCursors = new LinkedHashSet<>();
        boolean hasMore = true;
        boolean exhausted = false;
        int fetchedPages = 0;
        int requestedEnd = (int) Math.min(MAX_MIX_ITEMS, (long) safePage * safePageSize);
        while (hasMore && worksById.size() < requestedEnd && fetchedPages < MAX_MIX_CURSOR_PAGES) {
            if (!seenCursors.add(cursor)) {
                log.info("Douyin mix pagination stopped because cursor did not advance: mixId={}", safeId(seriesId));
                exhausted = true;
                break;
            }
            MixPage pageData = fetchMixPage(seriesId, cursor, DEFAULT_MIX_PAGE_SIZE, cookie);
            fetchedPages++;
            for (JsonNode item : pageData.items()) {
                try {
                    DouyinWork work = workFromAweme(item, "https://www.douyin.com/video/" + text(item, "aweme_id"), mix.id(), mix.title());
                    worksById.putIfAbsent(work.id(), work);
                } catch (DouyinClientException e) {
                    if (e.code() != DouyinClientErrorCode.MEDIA_URL_MISSING
                            && e.code() != DouyinClientErrorCode.UNSUPPORTED_CONTENT) {
                        throw e;
                    }
                }
                if (worksById.size() >= MAX_MIX_ITEMS) {
                    break;
                }
            }
            hasMore = pageData.hasMore();
            if (!hasMore) {
                exhausted = true;
                break;
            }
            if (pageData.items().isEmpty()) {
                exhausted = true;
                break;
            }
            long next = pageData.nextCursor();
            if (next <= 0 || seenCursors.contains(next)) {
                exhausted = true;
                break;
            }
            cursor = next;
        }
        if (hasMore && worksById.size() < requestedEnd && fetchedPages >= MAX_MIX_CURSOR_PAGES) {
            exhausted = true;
            log.info("Douyin mix pagination stopped at the cursor page limit: mixId={}", safeId(seriesId));
        }
        List<DouyinWork> works = List.copyOf(worksById.values());
        int from = (int) Math.min((long) (safePage - 1) * safePageSize, works.size());
        int to = Math.min(from + safePageSize, works.size());
        List<DouyinWork> slice = works.subList(from, to);
        boolean capped = works.size() >= MAX_MIX_ITEMS;
        boolean last = exhausted || capped;
        int total = last ? works.size() : 0;
        return new DouyinListing(slice, total, safePage, safePageSize, last,
                mix.title(), mix.id(), mix.ownerName());
    }

    @Override
    public DouyinListing searchPublic(String word, int page, int pageSize, String cookie) throws DouyinClientException {
        String keyword = requireStableId(word, "Douyin search keyword is required");
        int safePage = Math.max(1, page);
        int safePageSize = positivePageSize(pageSize);
        DouyinListing listing = searchWorksPage(keyword,
                Long.toString(((long) safePage - 1L) * safePageSize), safePageSize, cookie);
        return new DouyinListing(listing.items(), listing.total(), safePage, safePageSize,
                listing.lastPage(), listing.title(), listing.ownerId(), listing.ownerName(),
                listing.nextCursor(), listing.hasMore());
    }

    @Override
    public DouyinListing searchWorksPage(String word,
                                         String cursor,
                                         int limit,
                                         String cookie) throws DouyinClientException {
        String keyword = requireStableId(word, "Douyin search keyword is required");
        int safeLimit = positivePageSize(limit);
        String offset = normalizeCursor(cursor);
        JsonNode root = fetchSignedJson("/aweme/v1/web/general/search/single/", params(
                "search_channel", "aweme_general",
                "keyword", keyword,
                "search_source", "normal_search",
                "query_correct_type", 1,
                "is_filter_search", 0,
                "offset", offset,
                "count", safeLimit), cookie);
        return searchListing(root, keyword, offset, safeLimit);
    }

    private DouyinListing collectLogicalSlice(String ownerId,
                                              int offset,
                                              int limit,
                                              String cookie,
                                              CursorPageFetcher fetcher) throws DouyinClientException {
        LinkedHashMap<String, DouyinWork> works = new LinkedHashMap<>();
        LinkedHashSet<String> seenCursors = new LinkedHashSet<>();
        String cursor = "";
        DouyinListing lastListing = DouyinListing.empty(1, limit);
        boolean hasMore = true;
        int pages = 0;
        int requestedEnd = Math.addExact(offset, limit);
        while (hasMore && works.size() < requestedEnd) {
            String cursorKey = normalizeCursor(cursor);
            if (!seenCursors.add(cursorKey)) {
                throw paginationStalled(ownerId, cursorKey);
            }
            if (pages++ >= MAX_CURSOR_PAGES) {
                throw paginationStalled(ownerId, cursorKey);
            }
            lastListing = fetcher.fetch(cursorKey, DEFAULT_MIX_PAGE_SIZE);
            for (DouyinWork work : lastListing.items()) {
                if (work != null && work.id() != null && !work.id().isBlank()) {
                    works.putIfAbsent(work.id(), work);
                }
            }
            hasMore = lastListing.hasMore();
            if (!hasMore) {
                break;
            }
            String next = normalizeCursor(lastListing.nextCursor());
            if (next.equals(cursorKey)) {
                throw paginationStalled(ownerId, cursorKey);
            }
            cursor = next;
        }
        List<DouyinWork> all = List.copyOf(works.values());
        int from = Math.min(offset, all.size());
        int to = Math.min(requestedEnd, all.size());
        List<DouyinWork> items = all.subList(from, to);
        int total = hasMore ? Math.max(to + 1, lastListing.total()) : all.size();
        return new DouyinListing(items, total, offset / Math.max(1, limit) + 1, limit,
                !hasMore, lastListing.title(), lastListing.ownerId(), lastListing.ownerName(),
                lastListing.nextCursor(), hasMore);
    }

    private DouyinListing workListing(JsonNode root,
                                      int page,
                                      int pageSize,
                                      ListingContext context,
                                      String cursorField,
                                      String... arrayFields) throws DouyinClientException {
        ensureSuccessful(root, "Douyin work listing");
        JsonNode list = firstArray(root, arrayFields).orElse(MAPPER.createArrayNode());
        LinkedHashMap<String, DouyinWork> works = new LinkedHashMap<>();
        for (JsonNode raw : list) {
            JsonNode aweme = unwrapAweme(raw);
            if (!aweme.isObject()) {
                continue;
            }
            try {
                DouyinWork work = workFromAweme(aweme,
                        "https://www.douyin.com/video/" + firstText(aweme, "aweme_id", "group_id", "id"),
                        context.collectionId(), context.collectionTitle());
                works.putIfAbsent(work.id(), work);
            } catch (DouyinClientException e) {
                if (e.code() != DouyinClientErrorCode.MEDIA_URL_MISSING
                        && e.code() != DouyinClientErrorCode.UNSUPPORTED_CONTENT) {
                    throw e;
                }
            }
        }
        boolean hasMore = hasMore(root);
        String next = cursorValue(root, cursorField, "cursor", "max_cursor", "offset");
        int base = Math.max(0, (page - 1) * pageSize);
        int total = exactOrEstimatedTotal(root, works.size(), base, hasMore);
        String ownerName = firstNonBlank(context.ownerName(), works.values().stream()
                .map(DouyinWork::authorName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(null));
        return new DouyinListing(List.copyOf(works.values()), total, page, pageSize,
                !hasMore, firstNonBlank(context.collectionTitle(), ownerName, context.ownerId()),
                context.ownerId(), ownerName, next, hasMore);
    }

    private DouyinListing searchListing(JsonNode root,
                                        String keyword,
                                        String offset,
                                        int pageSize) throws DouyinClientException {
        ensureSuccessful(root, "Douyin search");
        JsonNode list = firstArray(root, "data", "aweme_list", "items")
                .orElse(MAPPER.createArrayNode());
        LinkedHashMap<String, DouyinWork> works = new LinkedHashMap<>();
        for (JsonNode raw : list) {
            JsonNode aweme = unwrapAweme(raw);
            if (!aweme.isObject()) {
                continue;
            }
            try {
                DouyinWork work = workFromAweme(aweme,
                        "https://www.douyin.com/video/" + firstText(aweme, "aweme_id", "group_id", "id"),
                        null, null);
                works.putIfAbsent(work.id(), work);
            } catch (DouyinClientException e) {
                if (e.code() != DouyinClientErrorCode.MEDIA_URL_MISSING
                        && e.code() != DouyinClientErrorCode.UNSUPPORTED_CONTENT) {
                    throw e;
                }
            }
        }
        long base = parseCursorNumber(offset);
        boolean hasMore = hasMore(root);
        String next = cursorValue(root, "cursor", "offset");
        if (hasMore && (next.isBlank() || next.equals(normalizeCursor(offset)))) {
            next = Long.toString(base + Math.max(pageSize, works.size()));
        }
        int total = exactOrEstimatedTotal(root, works.size(), base, hasMore);
        int page = (int) Math.min(Integer.MAX_VALUE, base / Math.max(1, pageSize) + 1);
        return new DouyinListing(List.copyOf(works.values()), total, page, pageSize,
                !hasMore, keyword, null, null, next, hasMore);
    }

    private static JsonNode unwrapAweme(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return MAPPER.missingNode();
        }
        for (String field : List.of("aweme_info", "aweme_detail", "aweme")) {
            JsonNode candidate = raw.path(field);
            if (candidate.isObject()) {
                return candidate;
            }
        }
        JsonNode mixItems = raw.path("aweme_mix_info").path("mix_items");
        if (mixItems.isArray() && !mixItems.isEmpty()) {
            return unwrapAweme(mixItems.get(0));
        }
        return raw;
    }

    private static boolean hasMore(JsonNode root) {
        JsonNode value = root == null ? null : root.path("has_more");
        return value != null && (value.asInt(0) == 1 || value.asBoolean(false));
    }

    private static String cursorValue(JsonNode root, String... fields) {
        if (root == null || fields == null) {
            return "";
        }
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            JsonNode value = root.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
            if (value.isIntegralNumber()) {
                return value.asText();
            }
        }
        return "";
    }

    private static int exactOrEstimatedTotal(JsonNode root, int itemCount, long base, boolean hasMore) {
        Optional<Long> exact = firstLong(root, "total", "total_count", "aweme_count")
                .filter(value -> value >= 0);
        long total = exact.orElseGet(() -> base + itemCount + (hasMore ? 1L : 0L));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, total));
    }

    private static String normalizeCursor(String cursor) {
        return cursor == null || cursor.isBlank() ? "0" : cursor.trim();
    }

    private static long parseCursorNumber(String cursor) {
        try {
            return Math.max(0L, Long.parseLong(normalizeCursor(cursor)));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int positivePageSize(int value) {
        return value <= 0 ? DEFAULT_MIX_PAGE_SIZE : Math.min(value, 100);
    }

    private static String requireStableId(String value, String message) throws DouyinClientException {
        if (value == null || value.isBlank()) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_URL, message);
        }
        return value.trim();
    }

    private static void ensureSuccessful(JsonNode root, String operation) throws DouyinClientException {
        DouyinClientErrorCode classified = DouyinErrorClassifier.classifyJsonStatus(root);
        if (classified != null) {
            throw new DouyinClientException(classified, operation + " reported " + classified);
        }
    }

    private static DouyinClientException paginationStalled(String sourceId, String cursor) {
        return new DouyinClientException(DouyinClientErrorCode.PAGINATION_STALLED,
                "Douyin pagination did not advance: source=" + safeId(sourceId) + ", cursor=" + safeId(cursor));
    }

    private static DouyinListing requireAdvancingCursor(String sourceId,
                                                        String currentCursor,
                                                        DouyinListing listing) throws DouyinClientException {
        if (listing.hasMore()
                && (listing.nextCursor().isBlank() || currentCursor.equals(listing.nextCursor()))) {
            throw paginationStalled(sourceId, currentCursor);
        }
        return listing;
    }

    @FunctionalInterface
    private interface CursorPageFetcher {
        DouyinListing fetch(String cursor, int count) throws DouyinClientException;
    }

    private record ListingContext(String ownerId,
                                  String ownerName,
                                  String collectionId,
                                  String collectionTitle) {
    }

    private DouyinParsedInput parseAndResolve(String input, String cookie) throws DouyinClientException {
        DouyinParsedInput parsed = parser.parse(input)
                .orElseThrow(() -> new DouyinClientException(DouyinClientErrorCode.INVALID_URL,
                        "Unsupported Douyin URL"));
        if (parsed.kind() == DouyinParsedKind.SHORT_LINK) {
            return shortLinkResolver.resolve(parsed.canonicalUrl(), cookie);
        }
        return parsed;
    }

    private Optional<DouyinWork> resolveFromPage(DouyinParsedInput parsed, String cookie) throws DouyinClientException {
        byte[] body = fetchBytes(URI.create(parsed.canonicalUrl()), cookie);
        String html = new String(body, StandardCharsets.UTF_8);
        if (DouyinErrorClassifier.looksLikeLoginOrRiskText(html)) {
            throw new DouyinClientException(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE,
                    "Douyin page requires login or verification");
        }
        for (JsonNode root : extractPageJson(html)) {
            DouyinClientErrorCode classified = DouyinErrorClassifier.classifyJsonStatus(root);
            if (classified != null) {
                throw new DouyinClientException(classified, "Douyin page JSON reported " + classified);
            }
            JsonNode aweme = findFirstField(root, "aweme_detail").orElse(null);
            if (aweme == null || aweme.isMissingNode() || aweme.isNull()) {
                aweme = findAwemeById(root, parsed.id()).orElse(null);
            }
            if (aweme != null && aweme.isObject()) {
                return Optional.of(workFromAweme(aweme, parsed.canonicalUrl(), null, null));
            }
        }
        return Optional.empty();
    }

    private DouyinWork resolveFromAwemeDetailApi(DouyinParsedInput parsed, String cookie) throws DouyinClientException {
        DouyinClientException lastFailure = null;
        for (String aid : DETAIL_AID_CANDIDATES) {
            JsonNode root = fetchAwemeDetailRoot(parsed, aid, cookie);
            DouyinClientErrorCode classified = DouyinErrorClassifier.classifyJsonStatus(root);
            if (classified != null) {
                throw new DouyinClientException(classified, "Douyin aweme detail reported " + classified);
            }
            JsonNode detail = root.path("aweme_detail");
            if (detail.isObject()) {
                return workFromAweme(detail, parsed.canonicalUrl(), null, null);
            }
            JsonNode filterInfo = root.path("filter_detail");
            if (filterInfo.isObject() && firstText(filterInfo, "filter_reason") != null) {
                lastFailure = new DouyinClientException(DouyinClientErrorCode.SIGNATURE_REQUIRED,
                        "Douyin aweme detail filtered media for aid " + aid);
                continue;
            }
            lastFailure = new DouyinClientException(DouyinClientErrorCode.SIGNATURE_REQUIRED,
                    "Douyin aweme detail endpoint did not expose public media without signed web parameters");
        }
        throw lastFailure == null
                ? new DouyinClientException(DouyinClientErrorCode.SIGNATURE_REQUIRED,
                "Douyin aweme detail endpoint did not expose public media")
                : lastFailure;
    }

    private MixInfo fetchMixInfo(String mixId, String cookie) throws DouyinClientException {
        JsonNode root = fetchSignedJson("/aweme/v1/web/mix/detail/", params("mix_id", mixId), cookie);
        DouyinClientErrorCode classified = DouyinErrorClassifier.classifyJsonStatus(root);
        if (classified != null) {
            throw new DouyinClientException(classified, "Douyin mix detail reported " + classified);
        }
        JsonNode info = firstObject(root, "mix_info", "mix_detail").orElse(root.path("mix_info"));
        if (!info.isObject()) {
            return new MixInfo(mixId, "Douyin collection " + mixId, null);
        }
        String title = firstText(info, "mix_name", "name", "title");
        String owner = firstText(info.path("author"), "nickname", "unique_id", "short_id");
        return new MixInfo(mixId, blankToDefault(title, "Douyin collection " + mixId), owner);
    }

    private MixPage fetchMixPage(String mixId, long cursor, int count, String cookie) throws DouyinClientException {
        JsonNode root = fetchSignedJson("/aweme/v1/web/mix/aweme/", params(
                "mix_id", mixId,
                "cursor", cursor,
                "count", Math.max(1, Math.min(count, DEFAULT_MIX_PAGE_SIZE))), cookie);
        DouyinClientErrorCode classified = DouyinErrorClassifier.classifyJsonStatus(root);
        if (classified != null) {
            throw new DouyinClientException(classified, "Douyin mix page reported " + classified);
        }
        JsonNode list = firstArray(root, "aweme_list", "items", "data").orElse(MAPPER.createArrayNode());
        List<JsonNode> items = new ArrayList<>();
        list.forEach(items::add);
        long next = firstLong(root, "max_cursor", "cursor").orElse(0L);
        boolean hasMore = root.path("has_more").asInt(0) == 1 || root.path("has_more").asBoolean(false);
        return new MixPage(items, hasMore, next);
    }

    private static Map<String, Object> params(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Douyin API params must be key/value pairs");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    private JsonNode fetchAwemeDetailRoot(DouyinParsedInput parsed, String aid, String cookie)
            throws DouyinClientException {
        return fetchSignedJson("/aweme/v1/web/aweme/detail/",
                params("aweme_id", parsed.id(), "aid", aid),
                cookie,
                root -> {
                    DouyinClientErrorCode classified = DouyinErrorClassifier.classifyJsonStatus(root);
                    if (shouldTrySignatureCandidateFallback(classified)) {
                        return true;
                    }
                    JsonNode detail = root.path("aweme_detail");
                    if (detail.isObject()) {
                        return false;
                    }
                    JsonNode filterInfo = root.path("filter_detail");
                    return filterInfo.isObject() && firstText(filterInfo, "filter_reason") != null;
                });
    }

    private JsonNode fetchSignedJson(String path, Map<String, ?> endpointParams, String cookie)
            throws DouyinClientException {
        return fetchSignedJson(path, endpointParams, cookie,
                root -> shouldTrySignatureCandidateFallback(DouyinErrorClassifier.classifyJsonStatus(root)));
    }

    private JsonNode fetchSignedJson(String path,
                                     Map<String, ?> endpointParams,
                                     String cookie,
                                     Predicate<JsonNode> shouldTryNextCandidate)
            throws DouyinClientException {
        List<URI> candidates = signedUriBuilder.apiCandidates(path, endpointParams, cookie);
        DouyinClientException lastFailure = null;
        for (int i = 0; i < candidates.size(); i++) {
            boolean last = i == candidates.size() - 1;
            try {
                JsonNode root = fetchJson(candidates.get(i), cookie);
                if (!last && shouldTryNextCandidate.test(root)) {
                    lastFailure = new DouyinClientException(DouyinClientErrorCode.SIGNATURE_REQUIRED,
                            "Douyin API candidate requires fallback signature");
                    continue;
                }
                return root;
            } catch (DouyinClientException e) {
                if (!last && shouldTrySignatureCandidateFallback(e.code())) {
                    lastFailure = e;
                    log.debug("Douyin signed API candidate failed, trying fallback: path={}, code={}",
                            path, e.code());
                    continue;
                }
                throw e;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new DouyinClientException(DouyinClientErrorCode.SIGNATURE_REQUIRED,
                "Douyin API did not provide a usable signed candidate");
    }

    private JsonNode fetchJson(URI uri, String cookie) throws DouyinClientException {
        byte[] bytes = fetchBytes(uri, cookie);
        try {
            return MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (DouyinErrorClassifier.looksLikeLoginOrRiskText(body)) {
                throw new DouyinClientException(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE,
                        "Douyin response requires login or verification", e);
            }
            throw new DouyinClientException(DouyinClientErrorCode.SIGNATURE_REQUIRED,
                    "Douyin endpoint did not return public JSON", e);
        }
    }

    private byte[] fetchBytes(URI uri, String cookie) throws DouyinClientException {
        try {
            HttpHeaders headers = new HttpHeaders();
            DouyinRequestHeaders.applyCredentials(headers, uri, cookie);
            ResponseEntity<byte[]> response = downloadRestTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            byte[] body = response.getBody();
            return body == null ? new byte[0] : body;
        } catch (HttpStatusCodeException e) {
            byte[] body = e.getResponseBodyAsByteArray();
            if (e.getStatusCode().value() == 403) {
                if (DouyinErrorClassifier.looksLikeLoginOrRiskPage(body)) {
                    throw new DouyinClientException(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE,
                            "Douyin request reached a login or verification page", e);
                }
                throw new DouyinClientException(DouyinClientErrorCode.HTTP_FORBIDDEN,
                        "Douyin request returned 403", e);
            }
            if (e.getStatusCode().value() == 429) {
                throw new DouyinClientException(DouyinClientErrorCode.RATE_LIMITED,
                        "Douyin request was rate limited", e);
            }
            throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR,
                    "Douyin request returned HTTP " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new DouyinClientException(DouyinClientErrorCode.NETWORK_TIMEOUT,
                        "Douyin request timed out", e);
            }
            throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR,
                    "Douyin network request failed", e);
        }
    }

    private DouyinWork workFromAweme(JsonNode aweme,
                                     String pageUrl,
                                     String collectionId,
                                     String collectionTitle) throws DouyinClientException {
        String id = firstText(aweme, "aweme_id", "group_id", "id");
        if (id == null || id.isBlank()) {
            throw new DouyinClientException(DouyinClientErrorCode.UNSUPPORTED_CONTENT,
                    "Douyin aweme response has no work id");
        }
        String description = firstText(aweme, "desc");
        String itemTitle = firstText(aweme, "item_title");
        String caption = firstText(aweme, "caption");
        String shareTitle = firstText(aweme.path("share_info"), "share_title", "title");
        String title = firstNonBlank(itemTitle, shareTitle, description, caption);
        JsonNode author = aweme.path("author");
        String authorId = firstText(author, "uid", "sec_uid", "short_id");
        String authorName = firstText(author, "nickname", "unique_id", "short_id");
        String canonicalUrl = pageUrl == null || pageUrl.isBlank() ? "https://www.douyin.com/video/" + id : pageUrl;
        List<DouyinMedia> media = collectMedia(id, aweme);
        if (media.isEmpty()) {
            throw new DouyinClientException(DouyinClientErrorCode.MEDIA_URL_MISSING,
                    "Douyin response has no downloadable public media URL");
        }
        DouyinMedia primary = media.get(0);
        String thumbnail = firstUrl(aweme.path("video").path("cover"))
                .or(() -> firstUrl(aweme.path("cover")))
                .orElseGet(() -> media.stream()
                        .filter(item -> item.type() == DouyinMediaType.IMAGE)
                        .map(item -> item.url().toString())
                        .findFirst()
                        .orElse(""));
        DouyinWorkKind kind = classifyWorkKind(media);
        Long publishTime = firstLong(aweme, "create_time", "createTime")
                .filter(value -> value > 0 && value <= Instant.now().plusSeconds(86_400).getEpochSecond())
                .orElse(null);
        return new DouyinWork(id, blankToDefault(title, id), description, itemTitle, caption, authorId,
                authorName == null ? "" : authorName, canonicalUrl, thumbnail, primary.url(),
                media, kind, publishTime, collectionId, collectionTitle);
    }

    private List<DouyinMedia> collectMedia(String workId, JsonNode aweme) {
        List<DouyinMedia> imagePostMedia = collectImagePostMedia(workId, imageNodes(aweme));
        if (!imagePostMedia.isEmpty()) {
            return imagePostMedia;
        }
        return collectVideos(workId, aweme.path("video"));
    }

    private List<DouyinMedia> collectImagePostMedia(String workId, List<JsonNode> imageNodes) {
        List<DouyinMedia> media = new ArrayList<>();
        for (int nodeIndex = 0; nodeIndex < imageNodes.size(); nodeIndex++) {
            JsonNode image = imageNodes.get(nodeIndex);
            int pageIndex = nodeIndex + 1;
            Optional<UrlCandidate> url = bestImageUrl(image);
            if (url.isPresent()) {
                media.add(media(workId + "-p" + pageIndex, DouyinMediaType.IMAGE, url.get().url(),
                        workId + "-p" + String.format(Locale.ROOT, "%02d", pageIndex), url.get().node()));
            }

            List<DouyinMedia> videos = collectVideos(workId + "-live-p" + pageIndex, image.path("video"));
            for (DouyinMedia video : videos) {
                media.add(new DouyinMedia(video.id(), DouyinMediaType.LIVE_PHOTO_VIDEO, video.url(),
                        workId + "-live-p" + String.format(Locale.ROOT, "%02d", pageIndex),
                        video.extension(), video.sizeBytes(), video.contentType(), video.fallbackUrls()));
            }
        }
        return media;
    }

    private static List<JsonNode> imageNodes(JsonNode aweme) {
        for (JsonNode candidate : List.of(
                aweme.path("image_post_info").path("images"),
                aweme.path("image_post_info").path("image_list"),
                aweme.path("images"),
                aweme.path("image_list"))) {
            if (candidate.isArray() && !candidate.isEmpty()) {
                List<JsonNode> nodes = new ArrayList<>(candidate.size());
                candidate.forEach(nodes::add);
                return nodes;
            }
        }
        return List.of();
    }

    private List<DouyinMedia> collectVideos(String workId, JsonNode video) {
        if (!video.isObject()) {
            return List.of();
        }
        List<VideoCandidate> candidates = new ArrayList<>();
        JsonNode bitRate = video.path("bit_rate");
        if (bitRate.isArray()) {
            for (JsonNode item : bitRate) {
                firstUrl(item.path("play_addr")).ifPresent(url ->
                        candidates.add(new VideoCandidate(url, item.path("bit_rate").asLong(0L), item.path("play_addr"))));
            }
        }
        for (String field : List.of("play_addr", "play_addr_h264", "play_addr_265", "play_addr_256", "download_addr")) {
            firstUrl(video.path(field)).ifPresent(url ->
                    candidates.add(new VideoCandidate(url, 0L, video.path(field))));
        }
        return candidates.stream()
                .sorted(Comparator.comparingLong(VideoCandidate::quality).reversed())
                .map(candidate -> media(workId, DouyinMediaType.VIDEO, candidate.url(), workId, candidate.node()))
                .findFirst()
                .map(List::of)
                .orElse(List.of());
    }

    private DouyinMedia media(String id, DouyinMediaType type, String rawUrl, String stem, JsonNode node) {
        URI uri = URI.create(rawUrl);
        String extension = extensionFromUrl(uri).orElse(type == DouyinMediaType.IMAGE ? "jpg" : "mp4");
        Long size = firstLong(node, "data_size", "file_size", "size", "content_length").orElse(null);
        List<URI> fallbackUrls = allUrls(node).stream()
                .filter(candidate -> !candidate.equals(rawUrl))
                .map(DefaultDouyinClient::safeUri)
                .flatMap(Optional::stream)
                .toList();
        return new DouyinMedia(id, type, uri, stem, extension, size, null, fallbackUrls);
    }

    private static DouyinWorkKind classifyWorkKind(List<DouyinMedia> media) {
        boolean hasImage = media.stream().anyMatch(item -> item.type() == DouyinMediaType.IMAGE);
        boolean hasLiveVideo = media.stream().anyMatch(item -> item.type() == DouyinMediaType.LIVE_PHOTO_VIDEO);
        if (hasImage && hasLiveVideo) {
            return DouyinWorkKind.LIVE_PHOTO;
        }
        if (hasImage) {
            return DouyinWorkKind.IMAGE_NOTE;
        }
        return media.isEmpty() ? DouyinWorkKind.UNSUPPORTED : DouyinWorkKind.VIDEO;
    }

    private Optional<UrlCandidate> bestImageUrl(JsonNode image) {
        for (String field : List.of(
                "watermark_free_download_url_list",
                "url_list",
                "origin_image",
                "display_image",
                "download_url",
                "download_addr",
                "download_url_list",
                "owner_watermark_image")) {
            JsonNode candidate = image.path(field);
            Optional<String> found = field.endsWith("_list")
                    ? firstUrlFromArray(candidate)
                    : firstUrl(candidate);
            if (found.isPresent()) {
                return Optional.of(new UrlCandidate(found.get(), candidate));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstUrl(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (node.isTextual() && node.asText().startsWith("http")) {
            return Optional.of(node.asText());
        }
        Optional<String> direct = firstUrlFromArray(node.path("url_list"));
        if (direct.isPresent()) {
            return direct;
        }
        for (String field : List.of("uri", "url", "download_url")) {
            JsonNode value = node.path(field);
            if (value.isTextual() && value.asText().startsWith("http")) {
                return Optional.of(value.asText());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstUrlFromArray(JsonNode array) {
        if (!array.isArray()) {
            return Optional.empty();
        }
        for (JsonNode item : array) {
            if (item.isTextual() && item.asText().startsWith("http")) {
                return Optional.of(item.asText());
            }
        }
        return Optional.empty();
    }

    private static List<String> allUrls(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (node.isTextual() && node.asText().startsWith("http")) {
            urls.add(node.asText());
        }
        JsonNode list = node.isArray() ? node : node.path("url_list");
        if (list.isArray()) {
            for (JsonNode item : list) {
                if (item.isTextual() && item.asText().startsWith("http")) {
                    urls.add(item.asText());
                }
            }
        }
        for (String field : List.of("uri", "url", "download_url")) {
            JsonNode value = node.path(field);
            if (value.isTextual() && value.asText().startsWith("http")) {
                urls.add(value.asText());
            }
        }
        return List.copyOf(urls);
    }

    private static Optional<URI> safeUri(String rawUrl) {
        try {
            return Optional.of(URI.create(rawUrl));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static List<JsonNode> extractPageJson(String html) {
        List<JsonNode> nodes = new ArrayList<>();
        addScriptJson(nodes, RENDER_DATA.matcher(html), true);
        addScriptJson(nodes, UNIVERSAL_DATA.matcher(html), false);
        addScriptJson(nodes, ROUTER_DATA.matcher(html), false);
        return nodes;
    }

    private static void addScriptJson(List<JsonNode> nodes, Matcher matcher, boolean urlEncoded) {
        while (matcher.find()) {
            String json = htmlUnescape(matcher.group(1).trim());
            if (urlEncoded) {
                json = URLDecoder.decode(json, StandardCharsets.UTF_8);
            }
            try {
                nodes.add(MAPPER.readTree(json));
            } catch (JsonProcessingException ignored) {
                // Ignore one malformed hydration blob and keep scanning other page data.
            }
        }
    }

    private static Optional<JsonNode> findFirstField(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (node.isObject() && node.has(field)) {
            return Optional.of(node.get(field));
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                Optional<JsonNode> found = findFirstField(child, field);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<JsonNode> findAwemeById(JsonNode node, String id) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (node.isObject()) {
            String awemeId = firstText(node, "aweme_id", "group_id", "id");
            if (id.equals(awemeId) && (node.has("video") || node.has("image_post_info") || node.has("images"))) {
                return Optional.of(node);
            }
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                Optional<JsonNode> found = findAwemeById(child, id);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<JsonNode> firstObject(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode found = root.path(field);
            if (found.isObject()) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    private static Optional<JsonNode> firstArray(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode found = root.path(field);
            if (found.isArray()) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if ((value.isTextual() || value.isNumber()) && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        String value = firstText(node, field);
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Optional<Long> firstLong(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber()) {
                return Optional.of(value.asLong());
            }
            if (value.isTextual() && value.asText().matches("\\d+")) {
                return Optional.of(Long.parseLong(value.asText()));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extensionFromUrl(URI uri) {
        String path = uri == null ? "" : uri.getPath();
        int dot = path == null ? -1 : path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return Optional.empty();
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? Optional.of(ext) : Optional.empty();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String htmlUnescape(String value) {
        return value.replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static DouyinClientException unsupportedParsedKind(DouyinParsedKind kind) {
        DouyinClientErrorCode code = kind == DouyinParsedKind.MUSIC || kind == DouyinParsedKind.USER_PROFILE
                ? DouyinClientErrorCode.UNSUPPORTED_CONTENT
                : DouyinClientErrorCode.INVALID_URL;
        return new DouyinClientException(code, "Douyin URL kind is not supported for this operation: " + kind);
    }

    private static boolean moreSpecific(DouyinClientErrorCode code) {
        return code == DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE
                || code == DouyinClientErrorCode.COOKIE_EXPIRED
                || code == DouyinClientErrorCode.HTTP_FORBIDDEN
                || code == DouyinClientErrorCode.RATE_LIMITED
                || code == DouyinClientErrorCode.HTTP_RATE_LIMITED
                || code == DouyinClientErrorCode.MEDIA_URL_MISSING
                || code == DouyinClientErrorCode.UNSUPPORTED_CONTENT;
    }

    private static boolean shouldTryPageFallback(DouyinClientErrorCode code) {
        return code == DouyinClientErrorCode.SIGNATURE_REQUIRED
                || code == DouyinClientErrorCode.MEDIA_URL_MISSING
                || code == DouyinClientErrorCode.UNSUPPORTED_CONTENT;
    }

    private static boolean shouldTrySignatureCandidateFallback(DouyinClientErrorCode code) {
        return code == DouyinClientErrorCode.SIGNATURE_REQUIRED
                || code == DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE
                || code == DouyinClientErrorCode.HTTP_FORBIDDEN;
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current.getClass().getName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String safeId(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_-]+", "_");
    }

    private record VideoCandidate(String url, long quality, JsonNode node) {
    }

    private record UrlCandidate(String url, JsonNode node) {
    }

    private record MixInfo(String id, String title, String ownerName) {
    }

    private record MixPage(List<JsonNode> items, boolean hasMore, long nextCursor) {
    }
}
