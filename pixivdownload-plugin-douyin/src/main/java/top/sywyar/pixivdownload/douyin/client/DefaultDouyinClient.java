package top.sywyar.pixivdownload.douyin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.douyin.client.redirect.DouyinShortLinkResolver;
import top.sywyar.pixivdownload.douyin.client.signature.DouyinSignedUriBuilder;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.listing.DouyinCollectionListing;
import top.sywyar.pixivdownload.douyin.model.listing.DouyinCollectionSummary;
import top.sywyar.pixivdownload.douyin.model.listing.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.input.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.model.input.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWork;
import top.sywyar.pixivdownload.douyin.model.favorite.DouyinFavoriteFolderListing;
import top.sywyar.pixivdownload.douyin.model.favorite.DouyinFavoriteFolderSummary;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static top.sywyar.pixivdownload.douyin.client.DouyinResponseMapper.blankToDefault;
import static top.sywyar.pixivdownload.douyin.client.DouyinResponseMapper.extractPageJson;
import static top.sywyar.pixivdownload.douyin.client.DouyinResponseMapper.findAwemeById;
import static top.sywyar.pixivdownload.douyin.client.DouyinResponseMapper.findFirstField;
import static top.sywyar.pixivdownload.douyin.client.DouyinResponseMapper.firstLong;
import static top.sywyar.pixivdownload.douyin.client.DouyinResponseMapper.firstNonBlank;
import static top.sywyar.pixivdownload.douyin.client.DouyinResponseMapper.firstObject;
import static top.sywyar.pixivdownload.douyin.client.DouyinResponseMapper.firstText;
import static top.sywyar.pixivdownload.douyin.client.DouyinResponseMapper.requireRecognizedArray;
import static top.sywyar.pixivdownload.douyin.client.DouyinResponseMapper.workFromAweme;

public class DefaultDouyinClient implements DouyinClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultDouyinClient.class);
    private static final int DEFAULT_MIX_PAGE_SIZE = 20;
    private static final int MAX_CURSOR_PAGES = 1_000;
    private static final List<String> DETAIL_AID_CANDIDATES = List.of("6383", "1128");

    private final DouyinUrlParser parser;
    private final DouyinShortLinkResolver shortLinkResolver;
    private final DouyinApiTransport transport;

    public DefaultDouyinClient(DouyinUrlParser parser,
                               OutboundHttpClient httpClient,
                               DouyinShortLinkResolver shortLinkResolver) {
        this(parser, httpClient, shortLinkResolver,
                new DouyinSignedUriBuilder(), Thread::sleep);
    }

    DefaultDouyinClient(DouyinUrlParser parser,
                        OutboundHttpClient httpClient,
                        DouyinShortLinkResolver shortLinkResolver,
                        DouyinSignedUriBuilder signedUriBuilder) {
        this(parser, httpClient, shortLinkResolver, signedUriBuilder, Thread::sleep);
    }

    DefaultDouyinClient(DouyinUrlParser parser,
                        OutboundHttpClient httpClient,
                        DouyinShortLinkResolver shortLinkResolver,
                        DouyinSignedUriBuilder signedUriBuilder,
                        DouyinApiTransport.RetrySleeper retrySleeper) {
        this.parser = parser;
        this.shortLinkResolver = shortLinkResolver;
        this.transport = new DouyinApiTransport(httpClient, signedUriBuilder, retrySleeper);
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
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/aweme/post/", params(
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
    public DouyinListing listUserLikedWorks(String userId,
                                            int offset,
                                            int limit,
                                            String cookie) throws DouyinClientException {
        String stableUserId = requireStableId(userId, "Douyin user id is required");
        int safeOffset = Math.max(0, offset);
        int safeLimit = positivePageSize(limit);
        return collectLogicalSlice(stableUserId, safeOffset, safeLimit, cookie,
                (cursor, count) -> listUserLikedWorksPage(stableUserId, cursor, count, cookie));
    }

    @Override
    public DouyinListing listUserLikedWorksPage(String userId,
                                                String cursor,
                                                int limit,
                                                String cookie) throws DouyinClientException {
        String stableUserId = requireStableId(userId, "Douyin user id is required");
        return listLikedWorksPage(stableUserId, stableUserId, null, cursor, limit, cookie);
    }

    private DouyinListing listLikedWorksPage(String targetUserId,
                                             String ownerId,
                                             String ownerName,
                                             String cursor,
                                             int limit,
                                             String cookie) throws DouyinClientException {
        String currentCursor = normalizeCursor(cursor);
        int safeLimit = positivePageSize(limit);
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/aweme/favorite/", params(
                "sec_user_id", targetUserId,
                "max_cursor", currentCursor,
                "count", safeLimit,
                "locate_query", false), cookie);
        DouyinListing listing = workListing(root, 1, safeLimit,
                new ListingContext(ownerId, ownerName, null, null),
                "max_cursor", "aweme_list", "items", "data");
        return requireAdvancingCursor(ownerId, currentCursor, listing);
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
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/mix/aweme/", params(
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
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/general/search/single/", params(
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
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/music/aweme/", params(
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
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/user/profile/self/", Map.of(), cookie);
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
        return listLikedWorksPage(account.secUserId(), account.accountKey(), account.displayName(),
                cursor, limit, cookie);
    }

    private DouyinListing listFavoriteWorksPage(DouyinAccount account,
                                                 String cursor,
                                                 int limit,
                                                 String cookie) throws DouyinClientException {
        int safeLimit = positivePageSize(limit);
        String currentCursor = normalizeCursor(cursor);
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/aweme/listcollection/", params(
                "cursor", currentCursor,
                "count", safeLimit), cookie);
        DouyinListing listing = workListing(root, 1, safeLimit,
                new ListingContext(account.accountKey(), account.displayName(), null, null),
                "cursor", "aweme_list", "items", "data");
        DouyinListing accountListing = new DouyinListing(
                listing.items(), listing.total(), listing.page(), listing.pageSize(),
                listing.lastPage(), listing.title(), account.accountKey(), account.displayName(),
                listing.nextCursor(), listing.hasMore());
        return requireAdvancingCursor(account.accountKey(), currentCursor, accountListing);
    }

    @Override
    public DouyinCollectionListing listFavoriteCollections(String cursor,
                                                            int limit,
                                                            String cookie) throws DouyinClientException {
        int safeLimit = positivePageSize(limit);
        String currentCursor = normalizeCursor(cursor);
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/mix/listcollection/", params(
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

    @Override
    public DouyinFavoriteFolderListing listFavoriteFolders(String cursor,
                                                            int limit,
                                                            String cookie) throws DouyinClientException {
        int safeLimit = positivePageSize(limit);
        String currentCursor = normalizeCursor(cursor);
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/collects/list/", params(
                "cursor", currentCursor,
                "count", safeLimit), cookie);
        ensureSuccessful(root, "Douyin favorite folder listing");
        JsonNode candidates = requireRecognizedArray(root,
                "Douyin favorite folder response", "collects_list", "collect_list", "items", "data");
        LinkedHashMap<String, DouyinFavoriteFolderSummary> folders = new LinkedHashMap<>();
        for (JsonNode raw : candidates) {
            JsonNode folder = raw.path("collects_info").isObject()
                    ? raw.path("collects_info") : raw;
            String id = firstText(folder, "collects_id", "collects_id_str", "id");
            if (id == null) {
                continue;
            }
            folders.putIfAbsent(id, new DouyinFavoriteFolderSummary(
                    id, blankToDefault(firstText(folder, "collects_name", "name", "title"), id)));
        }
        if (!candidates.isEmpty() && folders.isEmpty()) {
            throw new DouyinClientException(
                    DouyinClientErrorCode.RESPONSE_CANDIDATES_FILTERED,
                    "Douyin favorite folder candidates did not contain a stable folder id");
        }
        boolean hasMore = hasMore(root);
        String next = cursorValue(root, "cursor", "max_cursor");
        if (hasMore && (next.isBlank() || currentCursor.equals(next))) {
            throw paginationStalled("favorite-folders", currentCursor);
        }
        int total = exactOrEstimatedTotal(root, folders.size(), parseCursorNumber(currentCursor), hasMore);
        return new DouyinFavoriteFolderListing(List.copyOf(folders.values()), total, next, hasMore);
    }

    @Override
    public DouyinListing listFavoriteFolderWorksPage(String folderId,
                                                      String cursor,
                                                      int limit,
                                                      String cookie) throws DouyinClientException {
        String stableFolderId = requireStableId(folderId, "Douyin favorite folder id is required");
        int safeLimit = positivePageSize(limit);
        String currentCursor = normalizeCursor(cursor);
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/collects/video/list/", params(
                "collects_id", stableFolderId,
                "cursor", currentCursor,
                "count", safeLimit), cookie);
        DouyinListing listing = workListing(root, 1, safeLimit,
                new ListingContext(stableFolderId, null, stableFolderId, null),
                "cursor", "aweme_list", "items", "data");
        return requireAdvancingCursor(stableFolderId, currentCursor, listing);
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
            return MissingNode.getInstance();
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
        byte[] body = transport.fetchBytes(URI.create(parsed.canonicalUrl()), cookie);
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
            JsonNode root = transport.fetchApiJson("/aweme/v1/web/aweme/detail/",
                    params("aweme_id", parsed.id(), "aid", aid), cookie);
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
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/mix/detail/", params("mix_id", mixId), cookie);
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
        JsonNode root = transport.fetchApiJson("/aweme/v1/web/mix/aweme/", params(
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

    private static String safeId(String raw) {
        return raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_-]+", "_");
    }

    private record MixInfo(String id, String title, String ownerName) {
    }

    private record MixPage(List<JsonNode> items, boolean hasMore, long nextCursor) {
    }
}
