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
import top.sywyar.pixivdownload.douyin.model.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.DouyinCollectionListing;
import top.sywyar.pixivdownload.douyin.model.DouyinCollectionSummary;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final int MAX_CURSOR_PAGES = 1_000;
    private static final int MAX_FAVORITE_CURSOR_LENGTH = 512;
    private static final int MAX_FAVORITE_CURSOR_PART_LENGTH = 256;
    private static final int MAX_API_ATTEMPTS = 3;
    private static final long[] API_RETRY_DELAYS_MS = {1_000L, 2_000L};
    private static final String FAVORITE_CURSOR_PREFIX = "fw1";
    private static final List<String> DETAIL_AID_CANDIDATES = List.of("6383", "1128");

    private final DouyinUrlParser parser;
    private final RestTemplate downloadRestTemplate;
    private final DouyinShortLinkResolver shortLinkResolver;
    private final DouyinSignedUriBuilder signedUriBuilder;
    private final RetrySleeper retrySleeper;

    public DefaultDouyinClient(DouyinUrlParser parser,
                               RestTemplate downloadRestTemplate,
                               DouyinShortLinkResolver shortLinkResolver) {
        this(parser, downloadRestTemplate, shortLinkResolver,
                new DouyinSignedUriBuilder(), Thread::sleep);
    }

    DefaultDouyinClient(DouyinUrlParser parser,
                        RestTemplate downloadRestTemplate,
                        DouyinShortLinkResolver shortLinkResolver,
                        DouyinSignedUriBuilder signedUriBuilder) {
        this(parser, downloadRestTemplate, shortLinkResolver, signedUriBuilder, Thread::sleep);
    }

    DefaultDouyinClient(DouyinUrlParser parser,
                        RestTemplate downloadRestTemplate,
                        DouyinShortLinkResolver shortLinkResolver,
                        DouyinSignedUriBuilder signedUriBuilder,
                        RetrySleeper retrySleeper) {
        this.parser = parser;
        this.downloadRestTemplate = downloadRestTemplate;
        this.shortLinkResolver = shortLinkResolver;
        this.signedUriBuilder = signedUriBuilder;
        this.retrySleeper = retrySleeper;
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
        if (parsed.kind() == DouyinParsedKind.MUSIC) {
            return new DouyinCanonicalDownload(DouyinCanonicalKind.MUSIC_SOURCE,
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
        JsonNode root = fetchApiJson("/aweme/v1/web/aweme/post/", params(
                "sec_user_id", stableUserId,
                "max_cursor", currentCursor,
                "count", positivePageSize(limit),
                "locate_query", false,
                "show_live_replay_strategy", 1,
                "need_time_list", 1,
                "time_list_query", 0,
                "whale_cut_token", "",
                "cut_version", 1,
                "publish_video_strategy_type", 2), cookie);
        DouyinListing listing = workListing(root, 1, positivePageSize(limit),
                new ListingContext(stableUserId, null, null, null),
                "max_cursor", "aweme_list", "items", "data");
        return requireAdvancingCursor(stableUserId, currentCursor, listing);
    }

    @Override
    public DouyinListing listSeriesWorks(String seriesId, int page, int pageSize, String cookie) throws DouyinClientException {
        int safePage = Math.max(1, page);
        int safePageSize = positivePageSize(pageSize);
        String stableSeriesId = requireStableId(seriesId, "Douyin collection id is required");
        MixInfo mix = fetchMixInfo(stableSeriesId, cookie);
        LinkedHashMap<String, DouyinWork> works = new LinkedHashMap<>();
        LinkedHashSet<Long> seenCursors = new LinkedHashSet<>();
        long cursor = 0L;
        boolean hasMore = true;
        int pages = 0;
        int candidateCount = 0;
        long requestedEnd = (long) safePage * safePageSize;
        while (hasMore && works.size() < requestedEnd) {
            if (!seenCursors.add(cursor) || pages++ >= MAX_CURSOR_PAGES) {
                throw paginationStalled(stableSeriesId, Long.toString(cursor));
            }
            MixPage pageData = fetchMixPage(stableSeriesId, cursor, DEFAULT_MIX_PAGE_SIZE, cookie);
            candidateCount += pageData.items().size();
            for (JsonNode item : pageData.items()) {
                try {
                    DouyinWork work = workFromAweme(item,
                            "https://www.douyin.com/video/" + firstText(item, "aweme_id", "group_id", "id"),
                            mix.id(), mix.title());
                    works.putIfAbsent(work.id(), work);
                } catch (DouyinClientException e) {
                    if (e.code() != DouyinClientErrorCode.MEDIA_URL_MISSING
                            && e.code() != DouyinClientErrorCode.UNSUPPORTED_CONTENT) {
                        throw e;
                    }
                }
            }
            hasMore = pageData.hasMore();
            if (!hasMore) {
                break;
            }
            long next = pageData.nextCursor();
            if (next < 0 || next == cursor || seenCursors.contains(next)) {
                throw paginationStalled(stableSeriesId, Long.toString(cursor));
            }
            cursor = next;
        }
        if (candidateCount > 0 && works.isEmpty()) {
            throw new DouyinClientException(
                    DouyinClientErrorCode.RESPONSE_CANDIDATES_FILTERED,
                    "Douyin mix page candidates did not contain a downloadable work");
        }
        List<DouyinWork> all = List.copyOf(works.values());
        int from = (int) Math.min((long) (safePage - 1) * safePageSize, all.size());
        int to = Math.min(from + safePageSize, all.size());
        int total = hasMore ? 0 : all.size();
        return new DouyinListing(all.subList(from, to), total, safePage, safePageSize,
                !hasMore, mix.title(), mix.id(), mix.ownerName(), Long.toString(cursor), hasMore);
    }

    @Override
    public DouyinListing listSeriesWorksPage(String seriesId,
                                             String cursor,
                                             int limit,
                                             String cookie) throws DouyinClientException {
        String stableSeriesId = requireStableId(seriesId, "Douyin collection id is required");
        String currentCursor = normalizeCursor(cursor);
        int safeLimit = positivePageSize(limit);
        MixInfo mix = fetchMixInfo(stableSeriesId, cookie);
        JsonNode root = fetchApiJson("/aweme/v1/web/mix/aweme/", params(
                "mix_id", stableSeriesId,
                "cursor", currentCursor,
                "count", safeLimit), cookie);
        DouyinListing listing = workListing(root, 1, safeLimit,
                new ListingContext(mix.id(), mix.ownerName(), mix.id(), mix.title()),
                "max_cursor", "aweme_list", "items", "data");
        return requireAdvancingCursor(stableSeriesId, currentCursor, listing);
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
        JsonNode root = fetchApiJson("/aweme/v1/web/general/search/single/", params(
                "keyword", keyword,
                "search_channel", "aweme_video_web",
                "sort_type", 0,
                "publish_time", 0,
                "search_source", "normal_search",
                "query_correct_type", 1,
                "is_filter_search", 0,
                "offset", offset,
                "count", safeLimit), cookie);
        return searchListing(root, keyword, offset, safeLimit);
    }

    @Override
    public DouyinListing listMusicWorksPage(String musicId,
                                            String cursor,
                                            int limit,
                                            String cookie) throws DouyinClientException {
        String stableMusicId = requireStableId(musicId, "Douyin music id is required");
        int safeLimit = positivePageSize(limit);
        String currentCursor = normalizeCursor(cursor);
        JsonNode root = fetchApiJson("/aweme/v1/web/music/aweme/", params(
                "music_id", stableMusicId,
                "cursor", currentCursor,
                "count", safeLimit), cookie);
        DouyinListing listing = workListing(root, 1, safeLimit,
                new ListingContext(stableMusicId, stableMusicId, null, null),
                "cursor", "aweme_list", "items", "data");
        return requireAdvancingCursor(stableMusicId, currentCursor, listing);
    }

    @Override
    public DouyinAccount resolveAccount(String cookie) throws DouyinClientException {
        JsonNode root = fetchApiJson("/aweme/v1/web/user/profile/self/", Map.of(), cookie);
        ensureSuccessful(root, "Douyin account profile");
        JsonNode user = firstObject(root, "user", "user_info", "data")
                .orElse(root.path("user"));
        String secUserId = firstText(user, "sec_uid", "sec_user_id");
        String uid = firstText(user, "uid", "short_id");
        String uniqueId = firstText(user, "unique_id", "short_id");
        String displayName = firstText(user, "nickname", "unique_id", "short_id");
        String accountKey = firstNonBlank(uid, secUserId);
        if (accountKey == null || secUserId == null) {
            throw new DouyinClientException(DouyinClientErrorCode.COOKIE_EXPIRED,
                    "Douyin account profile did not expose an authenticated identity");
        }
        return new DouyinAccount(accountKey, secUserId,
                blankToDefault(displayName, accountKey), uniqueId);
    }

    @Override
    public DouyinListing listAccountWorksPage(DouyinAccountSource source,
                                              String cursor,
                                              int limit,
                                              String cookie) throws DouyinClientException {
        return listAccountWorksPage(resolveAccount(cookie), source, cursor, limit, cookie);
    }

    @Override
    public DouyinListing listAccountWorksPage(DouyinAccount account,
                                              DouyinAccountSource source,
                                              String cursor,
                                              int limit,
                                              String cookie) throws DouyinClientException {
        if (account == null) {
            throw new DouyinClientException(DouyinClientErrorCode.COOKIE_EXPIRED,
                    "Douyin account identity is required");
        }
        if (source == null || source == DouyinAccountSource.OWN_WORKS) {
            return listUserWorksPage(account.secUserId(), cursor, limit, cookie);
        }
        if (source == DouyinAccountSource.FAVORITE_WORKS) {
            return listFavoriteWorksPage(account, cursor, limit, cookie);
        }
        String path = source == DouyinAccountSource.LIKED_WORKS
                ? "/aweme/v1/web/aweme/favorite/"
                : "/aweme/v1/web/aweme/listcollection/";
        String cursorName = source == DouyinAccountSource.LIKED_WORKS ? "max_cursor" : "cursor";
        String currentCursor = normalizeCursor(cursor);
        LinkedHashMap<String, Object> request = new LinkedHashMap<>();
        if (source == DouyinAccountSource.LIKED_WORKS) {
            request.put("sec_user_id", account.secUserId());
            request.put(cursorName, currentCursor);
            request.put("count", positivePageSize(limit));
            request.put("locate_query", false);
        } else {
            request.put(cursorName, currentCursor);
            request.put("count", positivePageSize(limit));
        }
        JsonNode root = fetchApiJson(path, request, cookie);
        DouyinListing listing = workListing(root, 1, positivePageSize(limit),
                new ListingContext(account.accountKey(), account.displayName(), null, null), cursorName,
                "aweme_list", "items", "data");
        DouyinListing accountListing = new DouyinListing(
                listing.items(), listing.total(), listing.page(), listing.pageSize(),
                listing.lastPage(), listing.title(), account.accountKey(), account.displayName(),
                listing.nextCursor(), listing.hasMore());
        return requireAdvancingCursor(account.accountKey(), currentCursor, accountListing);
    }

    private DouyinListing listFavoriteWorksPage(DouyinAccount account,
                                                 String cursor,
                                                 int limit,
                                                 String cookie) throws DouyinClientException {
        int safeLimit = positivePageSize(limit);
        FavoriteWorksCursor state = decodeFavoriteWorksCursor(cursor);
        for (int step = 0; step < MAX_CURSOR_PAGES; step++) {
            FavoriteFolderPage folderPage = fetchFavoriteFolderPage(
                    state.foldersCursor(), safeLimit, cookie);
            if (folderPage.items().isEmpty()) {
                if (folderPage.hasMore()) {
                    state = new FavoriteWorksCursor(folderPage.nextCursor(), null, "0");
                    continue;
                }
                return favoriteAccountListing(account, List.of(), safeLimit, null);
            }

            int folderIndex = state.folderId() == null
                    ? 0
                    : indexOfFavoriteFolder(folderPage.items(), state.folderId());
            if (folderIndex < 0) {
                throw new DouyinClientException(DouyinClientErrorCode.PAGINATION_STALLED,
                        "Douyin favorite works cursor no longer identifies a folder");
            }
            FavoriteFolderRef folder = folderPage.items().get(folderIndex);
            String worksCursor = folder.id().equals(state.folderId())
                    ? state.worksCursor()
                    : "0";
            DouyinListing works = fetchFavoriteFolderWorksPage(
                    folder, worksCursor, safeLimit, cookie);
            FavoriteWorksCursor next = nextFavoriteWorksCursor(
                    state.foldersCursor(), folderPage, folderIndex, folder, works);
            if (works.items().isEmpty() && next != null) {
                state = next;
                continue;
            }
            return favoriteAccountListing(account, works.items(), safeLimit, next);
        }
        throw new DouyinClientException(DouyinClientErrorCode.PAGINATION_STALLED,
                "Douyin favorite works pagination exceeded the safe traversal limit");
    }

    private FavoriteFolderPage fetchFavoriteFolderPage(String cursor,
                                                        int limit,
                                                        String cookie) throws DouyinClientException {
        String currentCursor = normalizeCursor(cursor);
        JsonNode root = fetchApiJson("/aweme/v1/web/collects/list/", params(
                "cursor", currentCursor,
                "count", positivePageSize(limit)), cookie);
        ensureSuccessful(root, "Douyin favorite folder listing");
        JsonNode candidates = requireRecognizedArray(root,
                "Douyin favorite folder response", "collects_list", "collect_list", "items", "data");
        LinkedHashMap<String, FavoriteFolderRef> folders = new LinkedHashMap<>();
        for (JsonNode raw : candidates) {
            JsonNode folder = raw.path("collects_info").isObject()
                    ? raw.path("collects_info") : raw;
            String id = firstText(folder, "collects_id", "collects_id_str", "id");
            if (id == null) {
                continue;
            }
            folders.putIfAbsent(id, new FavoriteFolderRef(id,
                    blankToDefault(firstText(folder, "collects_name", "name", "title"), id)));
        }
        if (!candidates.isEmpty() && folders.isEmpty()) {
            throw new DouyinClientException(DouyinClientErrorCode.RESPONSE_CANDIDATES_FILTERED,
                    "Douyin favorite folder candidates did not contain a stable folder id");
        }
        boolean hasMore = hasMore(root);
        String nextCursor = cursorValue(root, "cursor", "max_cursor");
        if (hasMore && (nextCursor.isBlank() || nextCursor.equals(currentCursor))) {
            throw new DouyinClientException(DouyinClientErrorCode.PAGINATION_STALLED,
                    "Douyin favorite folder cursor did not advance");
        }
        return new FavoriteFolderPage(List.copyOf(folders.values()), nextCursor, hasMore);
    }

    private DouyinListing fetchFavoriteFolderWorksPage(FavoriteFolderRef folder,
                                                        String cursor,
                                                        int limit,
                                                        String cookie) throws DouyinClientException {
        String currentCursor = normalizeCursor(cursor);
        JsonNode root = fetchApiJson("/aweme/v1/web/collects/video/list/", params(
                "collects_id", folder.id(),
                "cursor", currentCursor,
                "count", positivePageSize(limit)), cookie);
        ensureSuccessful(root, "Douyin favorite folder works");
        JsonNode candidates = requireRecognizedArray(root,
                "Douyin favorite works response", "aweme_list", "items", "data");
        DouyinListing listing = workListing(root, 1, positivePageSize(limit),
                new ListingContext(folder.id(), folder.title(), folder.id(), folder.title()),
                "cursor", "aweme_list", "items", "data");
        if (!candidates.isEmpty() && listing.items().isEmpty()) {
            throw new DouyinClientException(DouyinClientErrorCode.RESPONSE_CANDIDATES_FILTERED,
                    "Douyin favorite works candidates did not contain a downloadable work");
        }
        return requireAdvancingCursor(folder.id(), currentCursor, listing);
    }

    private static FavoriteWorksCursor nextFavoriteWorksCursor(String foldersCursor,
                                                                FavoriteFolderPage folderPage,
                                                                int folderIndex,
                                                                FavoriteFolderRef folder,
                                                                DouyinListing works) {
        if (works.hasMore()) {
            return new FavoriteWorksCursor(foldersCursor, folder.id(), works.nextCursor());
        }
        if (folderIndex + 1 < folderPage.items().size()) {
            return new FavoriteWorksCursor(foldersCursor,
                    folderPage.items().get(folderIndex + 1).id(), "0");
        }
        if (folderPage.hasMore()) {
            return new FavoriteWorksCursor(folderPage.nextCursor(), null, "0");
        }
        return null;
    }

    private static DouyinListing favoriteAccountListing(DouyinAccount account,
                                                         List<DouyinWork> items,
                                                         int pageSize,
                                                         FavoriteWorksCursor next)
            throws DouyinClientException {
        boolean hasMore = next != null;
        String nextCursor = hasMore ? encodeFavoriteWorksCursor(next) : "";
        int total = items.size() + (hasMore ? 1 : 0);
        return new DouyinListing(List.copyOf(items), total, 1, pageSize,
                !hasMore, account.displayName(), account.accountKey(), account.displayName(),
                nextCursor, hasMore);
    }

    private static int indexOfFavoriteFolder(List<FavoriteFolderRef> folders, String folderId) {
        for (int index = 0; index < folders.size(); index++) {
            if (folders.get(index).id().equals(folderId)) {
                return index;
            }
        }
        return -1;
    }

    private static FavoriteWorksCursor decodeFavoriteWorksCursor(String cursor)
            throws DouyinClientException {
        String normalized = normalizeCursor(cursor);
        if ("0".equals(normalized)) {
            return new FavoriteWorksCursor("0", null, "0");
        }
        if (normalized.length() > MAX_FAVORITE_CURSOR_LENGTH) {
            throw invalidFavoriteCursor();
        }
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 4 || !FAVORITE_CURSOR_PREFIX.equals(parts[0])) {
            throw invalidFavoriteCursor();
        }
        try {
            String foldersCursor = decodeFavoriteCursorPart(parts[1]);
            String folderId = decodeFavoriteCursorPart(parts[2]);
            String worksCursor = decodeFavoriteCursorPart(parts[3]);
            if (foldersCursor == null || worksCursor == null
                    || (folderId == null && !"0".equals(worksCursor))) {
                throw invalidFavoriteCursor();
            }
            return new FavoriteWorksCursor(foldersCursor, folderId, worksCursor);
        } catch (IllegalArgumentException e) {
            throw invalidFavoriteCursor();
        }
    }

    private static String encodeFavoriteWorksCursor(FavoriteWorksCursor cursor)
            throws DouyinClientException {
        String encoded = FAVORITE_CURSOR_PREFIX + "."
                + encodeFavoriteCursorPart(cursor.foldersCursor()) + "."
                + encodeFavoriteCursorPart(cursor.folderId()) + "."
                + encodeFavoriteCursorPart(cursor.worksCursor());
        if (encoded.length() > MAX_FAVORITE_CURSOR_LENGTH) {
            throw new DouyinClientException(DouyinClientErrorCode.PAGINATION_STALLED,
                    "Douyin favorite works cursor exceeded the supported length");
        }
        return encoded;
    }

    private static String encodeFavoriteCursorPart(String value) {
        if (value == null) {
            return "-";
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeFavoriteCursorPart(String encoded) {
        if ("-".equals(encoded)) {
            return null;
        }
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        String value = new String(decoded, StandardCharsets.UTF_8);
        if (value.isBlank() || value.length() > MAX_FAVORITE_CURSOR_PART_LENGTH
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("Invalid favorite cursor part");
        }
        return value;
    }

    private static DouyinClientException invalidFavoriteCursor() {
        return new DouyinClientException(DouyinClientErrorCode.UNSUPPORTED_CONTENT,
                "Invalid Douyin favorite works cursor");
    }

    @Override
    public DouyinCollectionListing listFavoriteCollections(String cursor,
                                                            int limit,
                                                            String cookie) throws DouyinClientException {
        int safeLimit = positivePageSize(limit);
        String currentCursor = normalizeCursor(cursor);
        JsonNode root = fetchApiJson("/aweme/v1/web/mix/listcollection/", params(
                "cursor", currentCursor,
                "count", safeLimit), cookie);
        ensureSuccessful(root, "Douyin favorite collection listing");
        JsonNode array = requireRecognizedArray(root,
                "Douyin favorite collection response",
                "mix_list", "mix_infos", "items", "data");
        List<DouyinCollectionSummary> items = new ArrayList<>();
        for (JsonNode raw : array) {
            JsonNode mix = raw.has("mix_info") ? raw.path("mix_info") : raw;
            String id = firstText(mix, "mix_id", "id");
            if (id == null) {
                continue;
            }
            JsonNode author = mix.path("author");
            long rawWorkCount = firstLong(mix, "aweme_count", "count", "item_count").orElse(0L);
            int workCount = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, rawWorkCount));
            items.add(new DouyinCollectionSummary(
                    id,
                    blankToDefault(firstText(mix, "mix_name", "name", "title"), id),
                    workCount,
                    firstText(author, "uid", "sec_uid"),
                    firstText(author, "nickname", "unique_id")));
        }
        if (!array.isEmpty() && items.isEmpty()) {
            throw new DouyinClientException(
                    DouyinClientErrorCode.RESPONSE_CANDIDATES_FILTERED,
                    "Douyin favorite collection candidates did not contain a stable collection id");
        }
        boolean hasMore = hasMore(root);
        String next = cursorValue(root, "cursor", "max_cursor");
        if (hasMore && (next.isBlank() || currentCursor.equals(next))) {
            throw paginationStalled("favorite-collections", currentCursor);
        }
        int total = exactOrEstimatedTotal(root, items.size(), parseCursorNumber(currentCursor), hasMore);
        return new DouyinCollectionListing(items, total, next, hasMore);
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
        JsonNode list = requireRecognizedArray(
                root, "Douyin work listing response", arrayFields);
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
        if (!list.isEmpty() && works.isEmpty()) {
            throw new DouyinClientException(
                    DouyinClientErrorCode.RESPONSE_CANDIDATES_FILTERED,
                    "Douyin work listing candidates did not contain a downloadable work");
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
        JsonNode list = requireRecognizedArray(
                root, "Douyin search response", "data", "aweme_list", "items");
        LinkedHashMap<String, DouyinWork> works = new LinkedHashMap<>();
        int candidateCount = 0;
        int filteredCount = 0;
        for (JsonNode raw : list) {
            candidateCount++;
            JsonNode aweme = unwrapAweme(raw);
            if (!aweme.isObject()) {
                filteredCount++;
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
                filteredCount++;
            }
        }
        if (candidateCount > 0 && works.isEmpty()) {
            log.warn("Douyin search candidates produced no downloadable works: candidates={}, filtered={}",
                    candidateCount, filteredCount);
            throw new DouyinClientException(DouyinClientErrorCode.RESPONSE_CANDIDATES_FILTERED,
                    "Douyin search response candidates did not contain a downloadable work");
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

    private record FavoriteFolderRef(String id, String title) {
    }

    private record FavoriteFolderPage(List<FavoriteFolderRef> items,
                                      String nextCursor,
                                      boolean hasMore) {
    }

    private record FavoriteWorksCursor(String foldersCursor,
                                       String folderId,
                                       String worksCursor) {
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
        JsonNode root = fetchApiJson("/aweme/v1/web/mix/detail/", params("mix_id", mixId), cookie);
        DouyinClientErrorCode classified = DouyinErrorClassifier.classifyJsonStatus(root);
        if (classified != null) {
            throw new DouyinClientException(classified, "Douyin mix detail reported " + classified);
        }
        JsonNode info = firstObject(root, "mix_info", "mix_detail")
                .orElseThrow(() -> new DouyinClientException(
                        DouyinClientErrorCode.RESPONSE_STRUCTURE_UNRECOGNIZED,
                        "Douyin mix detail response did not contain a recognized detail object"));
        String title = firstText(info, "mix_name", "name", "title");
        String owner = firstText(info.path("author"), "nickname", "unique_id", "short_id");
        return new MixInfo(mixId, blankToDefault(title, mixId), owner);
    }

    private MixPage fetchMixPage(String mixId, long cursor, int count, String cookie) throws DouyinClientException {
        JsonNode root = fetchApiJson("/aweme/v1/web/mix/aweme/", params(
                "mix_id", mixId,
                "cursor", cursor,
                "count", Math.max(1, Math.min(count, DEFAULT_MIX_PAGE_SIZE))), cookie);
        DouyinClientErrorCode classified = DouyinErrorClassifier.classifyJsonStatus(root);
        if (classified != null) {
            throw new DouyinClientException(classified, "Douyin mix page reported " + classified);
        }
        JsonNode list = requireRecognizedArray(
                root, "Douyin mix page response", "aweme_list", "items", "data");
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
        return fetchApiJson("/aweme/v1/web/aweme/detail/",
                params("aweme_id", parsed.id(), "aid", aid), cookie);
    }

    private JsonNode fetchApiJson(String path, Map<String, ?> endpointParams, String cookie)
            throws DouyinClientException {
        RetryableApiRequestException lastFailure = null;
        for (int attempt = 0; attempt < MAX_API_ATTEMPTS; attempt++) {
            DouyinSignedUriBuilder.SignedRequest request =
                    signedUriBuilder.request(path, endpointParams, cookie);
            try {
                return fetchJson(request.uri(), request.cookie());
            } catch (RetryableApiRequestException error) {
                lastFailure = error;
                if (attempt + 1 >= MAX_API_ATTEMPTS) {
                    throw error;
                }
                log.debug("Retrying Douyin API request after retryable upstream response: path={}, attempt={}",
                        path, attempt + 1);
                pauseBeforeRetry(API_RETRY_DELAYS_MS[attempt]);
            }
        }
        throw lastFailure == null
                ? new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR,
                "Douyin API request did not execute")
                : lastFailure;
    }

    private JsonNode fetchJson(URI uri, String cookie) throws DouyinClientException {
        byte[] bytes = fetchBytes(uri, cookie);
        if (bytes.length == 0) {
            throw new RetryableApiRequestException(DouyinClientErrorCode.SIGNATURE_REQUIRED,
                    "Douyin endpoint returned an empty response");
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || root.isMissingNode() || root.isNull()) {
                throw new DouyinClientException(DouyinClientErrorCode.RESPONSE_STRUCTURE_UNRECOGNIZED,
                        "Douyin endpoint returned an empty JSON response");
            }
            return root;
        } catch (JsonProcessingException e) {
            if (DouyinErrorClassifier.looksLikeLoginOrRiskText(body)) {
                throw new DouyinClientException(DouyinClientErrorCode.LOGIN_OR_VERIFY_PAGE,
                        "Douyin response requires login or verification", e);
            }
            if (DouyinErrorClassifier.looksLikeSignatureText(body)) {
                throw new DouyinClientException(DouyinClientErrorCode.SIGNATURE_REQUIRED,
                        "Douyin endpoint rejected the signed request", e);
            }
            throw new DouyinClientException(DouyinClientErrorCode.RESPONSE_STRUCTURE_UNRECOGNIZED,
                    "Douyin endpoint did not return valid JSON", e);
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
            DouyinClientErrorCode code = DouyinErrorClassifier.classifyHttpStatus(
                    e.getStatusCode().value(), body);
            DouyinClientErrorCode resolved = code == null ? DouyinClientErrorCode.NETWORK_ERROR : code;
            if (e.getStatusCode().value() == 429 || e.getStatusCode().value() >= 500) {
                throw new RetryableApiRequestException(resolved,
                        "Douyin request returned HTTP " + e.getStatusCode().value(), e);
            }
            throw new DouyinClientException(resolved,
                    "Douyin request returned HTTP " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new RetryableApiRequestException(DouyinClientErrorCode.NETWORK_TIMEOUT,
                        "Douyin request timed out", e);
            }
            throw new RetryableApiRequestException(DouyinClientErrorCode.NETWORK_ERROR,
                    "Douyin network request failed", e);
        }
    }

    private void pauseBeforeRetry(long delayMs) throws DouyinClientException {
        try {
            retrySleeper.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DouyinClientException(DouyinClientErrorCode.CANCELLED,
                    "Douyin API retry interrupted", e);
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

    private List<DouyinMedia> collectMedia(String workId, JsonNode aweme) throws DouyinClientException {
        List<DouyinMedia> imagePostMedia = collectImagePostMedia(workId, imageNodes(aweme));
        if (!imagePostMedia.isEmpty()) {
            return imagePostMedia;
        }
        return collectVideos(workId, aweme.path("video"));
    }

    private List<DouyinMedia> collectImagePostMedia(String workId, List<JsonNode> imageNodes)
            throws DouyinClientException {
        List<DouyinMedia> media = new ArrayList<>();
        for (int nodeIndex = 0; nodeIndex < imageNodes.size(); nodeIndex++) {
            JsonNode image = imageNodes.get(nodeIndex);
            int pageIndex = nodeIndex + 1;
            Optional<UrlCandidate> imageUrl = bestImageUrl(image);
            Optional<DouyinMedia> motion = collectLivePhotoVideo(workId, pageIndex, image);
            boolean declaresMotion = declaresLivePhotoMotion(image);
            if (declaresMotion && (imageUrl.isEmpty() || motion.isEmpty())) {
                throw new DouyinClientException(DouyinClientErrorCode.MEDIA_URL_MISSING,
                        "Douyin live photo item did not contain a complete image and motion pair");
            }
            if (imageUrl.isEmpty()) {
                continue;
            }
            media.add(media(workId + "-p" + pageIndex, DouyinMediaType.IMAGE, imageUrl.get().url(),
                    workId + "-p" + String.format(Locale.ROOT, "%02d", pageIndex), imageUrl.get().node()));
            motion.ifPresent(media::add);
        }
        return media;
    }

    private Optional<DouyinMedia> collectLivePhotoVideo(String workId, int pageIndex, JsonNode image) {
        String id = workId + "-live-p" + pageIndex;
        String stem = workId + "-live-p" + String.format(Locale.ROOT, "%02d", pageIndex);
        Optional<DouyinMedia> nested = collectVideos(id, image.path("video")).stream().findFirst();
        if (nested.isPresent()) {
            DouyinMedia video = nested.get();
            return Optional.of(new DouyinMedia(video.id(), DouyinMediaType.LIVE_PHOTO_VIDEO, video.url(),
                    stem, video.extension(), video.sizeBytes(), video.contentType(), video.fallbackUrls()));
        }
        for (String field : List.of("video_play_addr", "video_download_addr")) {
            JsonNode address = image.path(field);
            Optional<String> url = firstUrl(address);
            if (url.isPresent()) {
                return Optional.of(media(id, DouyinMediaType.LIVE_PHOTO_VIDEO,
                        url.get(), stem, address));
            }
        }
        return Optional.empty();
    }

    private static boolean declaresLivePhotoMotion(JsonNode image) {
        JsonNode nested = image.path("video");
        if (nested.isObject() && !nested.isEmpty()) {
            return true;
        }
        for (String field : List.of("video_play_addr", "video_download_addr")) {
            JsonNode address = image.path(field);
            if (!address.isMissingNode() && !address.isNull()
                    && !(address.isObject() && address.isEmpty())) {
                return true;
            }
        }
        return false;
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

    private static JsonNode requireRecognizedArray(
            JsonNode root,
            String responseName,
            String... fields) throws DouyinClientException {
        Optional<JsonNode> array = firstArray(root, fields);
        if (array.isPresent()) {
            return array.get();
        }
        if (root != null) {
            for (String field : fields) {
                if (root.has(field) && root.get(field).isNull()) {
                    return MAPPER.createArrayNode();
                }
            }
        }
        throw new DouyinClientException(
                DouyinClientErrorCode.RESPONSE_STRUCTURE_UNRECOGNIZED,
                responseName + " did not contain a recognized result array");
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
                || code == DouyinClientErrorCode.RESPONSE_STRUCTURE_UNRECOGNIZED
                || code == DouyinClientErrorCode.MEDIA_URL_MISSING
                || code == DouyinClientErrorCode.UNSUPPORTED_CONTENT;
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

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long delayMs) throws InterruptedException;
    }

    private static final class RetryableApiRequestException extends DouyinClientException {
        private RetryableApiRequestException(DouyinClientErrorCode code, String message) {
            super(code, message);
        }

        private RetryableApiRequestException(DouyinClientErrorCode code, String message, Throwable cause) {
            super(code, message, cause);
        }
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
