package top.sywyar.pixivdownload.pixivcontent;

import java.time.LocalDate;
import java.util.function.LongPredicate;
import org.springframework.beans.propertyeditors.StringArrayPropertyEditor;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivQueryParameters;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.core.web.HttpIds;
import top.sywyar.pixivdownload.core.web.HttpParameterException;
import top.sywyar.pixivdownload.core.web.KnownErrorCodes;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;

import static top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver.HEADER_NAME;

@RestController
@HttpApiGroup("pixiv-content-browse")
@RequestMapping(value = "/api/pixiv-content", produces = MediaType.APPLICATION_JSON_VALUE)
@KnownErrorCodes(status = "400", value = "error.request.param.invalid")
@KnownErrorCodes(status = "403", value = "PIXIV_ACCESS_DENIED")
@KnownErrorCodes(status = "404", value = "PIXIV_CONTENT_NOT_FOUND")
@KnownErrorCodes(status = "429", value = "PIXIV_RATE_LIMITED")
@KnownErrorCodes(status = "502", value = {"PIXIV_RESPONSE_INVALID", "PIXIV_RESPONSE_TOO_LARGE", "PIXIV_UNAVAILABLE"})
public final class PixivContentController {
    public enum RankingMode {
        daily, weekly, monthly, rookie, original, daily_ai, male, female, daily_r18, weekly_r18, male_r18, female_r18, daily_r18_ai, r18g;

        boolean supports(ContentModels.RankingContent content) {
            return switch (content) {
                case all -> true;
                case illust, manga -> this == daily || this == weekly || this == monthly || this == rookie || this == daily_r18 || this == weekly_r18;
                case ugoira -> this == daily || this == weekly || this == daily_r18 || this == weekly_r18;
            };
        }
    }
    public enum NovelRankingMode {
        daily, weekly, monthly, rookie, weekly_original, weekly_ai, male, female,
        daily_r18, weekly_r18, weekly_r18_ai, male_r18, female_r18
    }
    public enum LatestType { illust, manga, novel }
    public enum ImageSize { preview, original }
    public enum PersonalType {
        artwork(WorkType.ARTWORK), novel(WorkType.NOVEL);
        final WorkType workType;
        PersonalType(WorkType workType) { this.workType = workType; }
    }

    private final ContentQueries queries;
    private final ContentTransport remote;
    private final NovelContentQueries novels;
    private final PersonalContentQueries personal;
    private final DiscoveryQueries discovery;
    private final BrowseQueries browse;
    private final UgoiraAnimation animations;
    private final WorkVisibilityService visibility;
    private final MessageResolver messages;

    PixivContentController(ContentQueries queries, ContentTransport remote, WorkVisibilityService visibility,
                           MessageResolver messages) {
        this.queries = queries;
        this.remote = remote;
        this.novels = new NovelContentQueries(queries);
        this.personal = new PersonalContentQueries(queries);
        this.browse = new BrowseQueries(queries);
        this.discovery = new DiscoveryQueries(queries);
        this.animations = new UgoiraAnimation(queries, remote);
        this.visibility = visibility;
        this.messages = messages;
    }

    @InitBinder("tag")
    void bindTags(WebDataBinder binder) {
        // Each query parameter is one Pixiv tag, including any literal commas.
        binder.registerCustomEditor(String[].class, new StringArrayPropertyEditor(null));
    }

    @GetMapping("/search")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    @KnownErrorCodes(status = "403", value = "PIXIV_PREMIUM_REQUIRED")
    public ResponseEntity<ContentModels.ArtworkList> search(@RequestParam String word,
            @RequestParam(defaultValue = "all") ContentModels.ArtworkSearchType type,
            @RequestParam(required = false) Integer minBookmarks, @RequestParam(required = false) Integer maxBookmarks,
            @RequestParam(required = false) Integer minWidth, @RequestParam(required = false) Integer maxWidth,
            @RequestParam(required = false) Integer minHeight, @RequestParam(required = false) Integer maxHeight,
            @RequestParam(required = false) SearchFilters.AspectRatio aspectRatio,
            @RequestParam(defaultValue = "date_d") PixivQueryParameters.Order order,
            @RequestParam(defaultValue = "all") PixivQueryParameters.Mode mode,
            @RequestParam(defaultValue = "s_tag") ContentModels.SearchMode sMode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var filters = new SearchFilters(minBookmarks, maxBookmarks, minWidth, maxWidth, minHeight, maxHeight, aspectRatio, null, null, null);
        String session = cookie(credential);
        var result = personal.search(session, filters, () -> queries.search(requiredWord(word), order.name(), mode.name(), sMode.name(),
                type, startDate, endDate, listPage(page), session, filters));
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, WorkType.ARTWORK)) : result);
    }

    @GetMapping("/rankings")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<ContentModels.Ranking> rankings(@RequestParam(defaultValue = "daily") RankingMode mode,
            @RequestParam(defaultValue = "all") ContentModels.RankingContent content,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        if (!mode.supports(content)) throw new HttpParameterException("query", "content", "invalid_value");
        String session = cookie(credential);
        var day = ContentQueries.checkDate(date, "date");
        int number = listPage(page);
        var result = mode.name().contains("r18")
                ? personal.authenticated(session, uid -> queries.rankings(mode.name(), content, day, number, session))
                : queries.rankings(mode.name(), content, day, number, session);
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, WorkType.ARTWORK)) : result);
    }

    @GetMapping("/tags/suggest")
    public ResponseEntity<ContentModels.TagSuggestions> suggestTags(@RequestParam String word) {
        return privateResponse(discovery.suggestions(remote.suggest(requiredWord(word))));
    }

    @GetMapping("/artworks/{id}/related")
    @KnownErrorCodes(status = "410", value = "PIXIV_RECOMMENDATION_EXPIRED")
    public ResponseEntity<ContentModels.RelatedArtworks> related(@PathVariable String id, @RequestParam(defaultValue = "18") int limit,
            @RequestParam(required = false) String cursor, @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        long artworkId = visibleId(id, scope, WorkType.ARTWORK);
        return privateResponse(discovery.related(artworkId, limit, cursor, cookie(credential), visible(scope, WorkType.ARTWORK)));
    }

    @GetMapping("/artworks/{id}/comments")
    public ResponseEntity<ContentModels.Comments> comments(@PathVariable String id, @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        return privateResponse(discovery.comments(visibleId(id, scope, WorkType.ARTWORK), false, listPage(page), cookie(credential)));
    }

    @GetMapping("/artworks/{id}")
    public ResponseEntity<ContentModels.Artwork> artwork(@PathVariable String id,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        return privateResponse(queries.artwork(visibleId(id, scope, WorkType.ARTWORK), cookie(credential)));
    }

    @GetMapping(value = "/artworks/{id}/pages/{pageIndex}/image",
            produces = {"image/jpeg", "image/png", "image/gif", "image/webp"})
    public ResponseEntity<Resource> image(@PathVariable String id, @PathVariable int pageIndex,
            @RequestParam(defaultValue = "preview") ImageSize size,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        if (pageIndex < 0) throw new HttpParameterException("path", "pageIndex", "out_of_range");
        long artworkId = visibleId(id, scope, WorkType.ARTWORK);
        return queries.readImage(artworkId, pageIndex, size.name(), cookie(credential), uri -> remote.image(uri, artworkId));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<ContentModels.User> user(@PathVariable String id,
            @RequestHeader(value = HEADER_NAME, required = false) String credential) {
        return privateResponse(queries.user(id(id), cookie(credential)));
    }

    @GetMapping(value = "/users/{id}/avatar", produces = {"image/jpeg", "image/png", "image/gif", "image/webp"})
    public ResponseEntity<Resource> avatar(@PathVariable String id, @RequestHeader(value = HEADER_NAME, required = false) String credential) {
        long userId = id(id);
        var user = queries.user(userId, cookie(credential));
        if (user.avatarUrl() == null) throw ContentFailure.notFound();
        return remote.avatar(java.net.URI.create(user.avatarUrl()), userId);
    }

    @GetMapping("/users/{id}/series")
    public ResponseEntity<ContentModels.UserSeries> userSeries(@PathVariable String id,
            @RequestParam(defaultValue = "manga") ContentModels.SeriesType type, @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        return privateResponse(discovery.userSeries(id(id), type, listPage(page), cookie(credential), !scope.enforceVisibility()));
    }

    @GetMapping("/users/{id}/artworks")
    @KnownErrorCodes(status = "422", value = "PIXIV_TAG_SEARCH_TOO_BROAD")
    public ResponseEntity<ContentModels.ArtworkList> userArtworks(@PathVariable String id,
            @RequestParam(required = false) String[] tag, @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var result = queries.userArtworks(id(id), listPage(page), cookie(credential), tag);
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, WorkType.ARTWORK)) : result);
    }

    @GetMapping("/series/{id}")
    public ResponseEntity<ContentModels.Series> series(@PathVariable String id, @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var result = queries.series(id(id), listPage(page), cookie(credential));
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, WorkType.ARTWORK)) : result);
    }

    @GetMapping("/me")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<ContentModels.User> me(@RequestHeader(value = HEADER_NAME, required = false) String credential) {
        return privateResponse(personal.me(cookie(credential)));
    }

    @GetMapping("/me/bookmarks")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<PersonalContentModels.PersonalPage> bookmarks(
            @RequestParam(defaultValue = "artwork") PersonalType type,
            @RequestParam(defaultValue = "show") PixivQueryParameters.Rest rest, @RequestParam(defaultValue = "") String tag,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var result = personal.bookmarks(type.name(), rest.name(), tag, listPage(page), cookie(credential));
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, type.workType)) : result);
    }

    @GetMapping("/me/follow-latest")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<PersonalContentModels.PersonalPage> followLatest(
            @RequestParam(defaultValue = "artwork") PersonalType type, @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var result = personal.followLatest(type.name(), listPage(page), cookie(credential));
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, type.workType)) : result);
    }

    @GetMapping("/novels/search")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    @KnownErrorCodes(status = "403", value = "PIXIV_PREMIUM_REQUIRED")
    public ResponseEntity<NovelContentModels.NovelList> searchNovels(@RequestParam String word,
            @RequestParam(required = false) Integer minBookmarks, @RequestParam(required = false) Integer maxBookmarks,
            @RequestParam(required = false) Integer minCharacters, @RequestParam(required = false) Integer maxCharacters,
            @RequestParam(required = false) String language,
            @RequestParam(defaultValue = "date_d") PixivQueryParameters.Order order,
            @RequestParam(defaultValue = "all") PixivQueryParameters.Mode mode,
            @RequestParam(defaultValue = "s_tag") ContentModels.SearchMode sMode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var filters = new SearchFilters(minBookmarks, maxBookmarks, null, null, null, null, null, minCharacters, maxCharacters, language);
        String session = cookie(credential);
        var result = personal.search(session, filters, () -> novels.search(requiredWord(word), order.name(), mode.name(), sMode.name(),
                startDate, endDate, listPage(page), session, filters));
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, WorkType.NOVEL)) : result);
    }

    @GetMapping("/novels/{id}")
    public ResponseEntity<NovelContentModels.Novel> novel(@PathVariable String id,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var result = novels.novel(visibleId(id, scope, WorkType.NOVEL), cookie(credential));
        return privateResponse(scope.enforceVisibility() ? result.filterIllustrations(visible(scope, WorkType.ARTWORK)) : result);
    }

    @GetMapping("/users/{id}/novels")
    @KnownErrorCodes(status = "422", value = "PIXIV_TAG_SEARCH_TOO_BROAD")
    public ResponseEntity<NovelContentModels.NovelList> userNovels(@PathVariable String id,
            @RequestParam(required = false) String[] tag, @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var result = novels.userNovels(id(id), listPage(page), cookie(credential), tag);
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, WorkType.NOVEL)) : result);
    }

    @GetMapping("/novels/rankings")
    public ResponseEntity<NovelContentModels.NovelRanking> novelRankings(@RequestParam(defaultValue = "daily") NovelRankingMode mode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var result = novels.rankings(mode.name(), ContentQueries.checkDate(date, "date"), listPage(page), cookie(credential));
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, WorkType.NOVEL)) : result);
    }

    @GetMapping(value = "/novels/{id}/cover", produces = {"image/jpeg", "image/png", "image/gif", "image/webp"})
    public ResponseEntity<Resource> novelCover(@PathVariable String id,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        long novelId = visibleId(id, scope, WorkType.NOVEL);
        return remote.novelImage(novels.cover(novelId, cookie(credential)), novelId);
    }

    @GetMapping(value = "/novels/{id}/images/{imageId}", produces = {"image/jpeg", "image/png", "image/gif", "image/webp"})
    public ResponseEntity<Resource> novelImage(@PathVariable String id, @PathVariable String imageId,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        long novelId = visibleId(id, scope, WorkType.NOVEL);
        long embeddedId = HttpIds.positive("path", "imageId", imageId);
        return remote.novelImage(novels.uploadedImage(novelId, embeddedId, cookie(credential)), novelId);
    }

    @GetMapping("/novel-series/{id}")
    public ResponseEntity<NovelContentModels.NovelSeries> novelSeries(@PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var result = novels.series(id(id), listPage(page), cookie(credential));
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, WorkType.NOVEL)) : result);
    }

    @GetMapping(value = "/artworks/{id}/animation", produces = "image/apng")
    @KnownErrorCodes(status = "503", value = "PIXIV_ANIMATION_BUSY")
    public ResponseEntity<Resource> animation(@PathVariable String id,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        return animations.read(visibleId(id, scope, WorkType.ARTWORK), cookie(credential));
    }

    @GetMapping("/me/following")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<PersonalContentModels.FollowingPage> following(
            @RequestParam(defaultValue = "show") PixivQueryParameters.Rest rest, @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential) {
        return privateResponse(personal.following(rest.name(), listPage(page), cookie(credential)));
    }

    @GetMapping("/me/collections")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<PersonalContentModels.CollectionPage> collections(@RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var result = personal.collections(listPage(page), cookie(credential));
        return privateResponse(scope.enforceVisibility() ? result.withoutCovers() : result);
    }

    @GetMapping("/me/collections/{collectionId}/works")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<PersonalContentModels.PersonalPage> collectionWorks(@PathVariable String collectionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        var result = personal.collectionWorks(collectionId, listPage(page), cookie(credential));
        return privateResponse(scope.enforceVisibility()
                ? result.filter(visible(scope, WorkType.ARTWORK), visible(scope, WorkType.NOVEL)) : result);
    }

    @GetMapping("/users/search")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<PersonalContentModels.FollowingPage> searchUsers(@RequestParam String word,
            @RequestParam(defaultValue = "false") boolean exact, @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential) {
        return privateResponse(browse.users(requiredWord(word), listPage(page), exact, cookie(credential)));
    }

    @GetMapping("/tags/search")
    public ResponseEntity<ContentModels.TagPage> searchTags(@RequestParam String word, @RequestParam(defaultValue = "1") int page) {
        return privateResponse(tagPage(requiredWord(word), listPage(page)));
    }

    @GetMapping("/search/users-and-tags")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<ContentModels.UserAndTagSearch> searchUsersAndTags(@RequestParam String word,
            @RequestParam(defaultValue = "false") boolean exact, @RequestParam(defaultValue = "1") int userPage,
            @RequestParam(defaultValue = "1") int tagPage, @RequestHeader(value = HEADER_NAME, required = false) String credential) {
        requiredWord(word);
        listPage(userPage, "userPage");
        listPage(tagPage, "tagPage");
        var users = browse.users(word, userPage, exact, cookie(credential));
        return privateResponse(new ContentModels.UserAndTagSearch(users, tagPage(word, tagPage)));
    }

    private ContentModels.TagPage tagPage(String word, int page) {
        return browse.tags(word, page, discovery.suggestions(remote.suggest(word)));
    }

    @GetMapping("/users/{id}/bookmarks")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<PersonalContentModels.PersonalPage> publicBookmarks(@PathVariable String id,
            @RequestParam(defaultValue = "artwork") PersonalType type, @RequestParam(defaultValue = "") String tag,
            @RequestParam(defaultValue = "1") int page, @RequestHeader(value = HEADER_NAME, required = false) String credential,
            WorkVisibilityScope scope) {
        var result = personal.publicBookmarks(id(id), type.name(), tag, listPage(page), cookie(credential));
        return privateResponse(scope.enforceVisibility() ? result.filter(visible(scope, type.workType)) : result);
    }

    @GetMapping("/me/bookmark-tags")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<ContentModels.BookmarkTags> bookmarkTags(@RequestParam(defaultValue = "artwork") PersonalType type,
            @RequestParam(defaultValue = "show") PixivQueryParameters.Rest rest,
            @RequestHeader(value = HEADER_NAME, required = false) String credential) {
        String session = cookie(credential);
        return privateResponse(personal.authenticated(session, uid ->
                browse.bookmarkTags(uid, type == PersonalType.novel, "hide".equals(rest.name()), session)));
    }

    @GetMapping("/me/watchlist")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<ContentModels.Watchlist> watchlist(@RequestParam(defaultValue = "manga") ContentModels.SeriesType type,
            @RequestParam(defaultValue = "1") int page, @RequestHeader(value = HEADER_NAME, required = false) String credential) {
        int number = listPage(page);
        String session = cookie(credential);
        return privateResponse(personal.authenticated(session, uid -> browse.watchlist(type, number, session)));
    }

    @GetMapping("/novels/{id}/related")
    @KnownErrorCodes(status = "410", value = "PIXIV_RECOMMENDATION_EXPIRED")
    public ResponseEntity<ContentModels.RelatedNovels> relatedNovels(@PathVariable String id,
            @RequestParam(defaultValue = "18") int limit, @RequestParam(required = false) String cursor,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        return privateResponse(discovery.relatedNovels(visibleId(id, scope, WorkType.NOVEL), limit, cursor,
                cookie(credential), visible(scope, WorkType.NOVEL)));
    }

    @GetMapping("/novels/{id}/comments")
    public ResponseEntity<ContentModels.Comments> novelComments(@PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestHeader(value = HEADER_NAME, required = false) String credential,
            WorkVisibilityScope scope) {
        return privateResponse(discovery.comments(visibleId(id, scope, WorkType.NOVEL), true, listPage(page), cookie(credential)));
    }
    @GetMapping("/artworks/{id}/comments/{commentId}/replies")
    public ResponseEntity<ContentModels.Comments> artworkReplies(@PathVariable String id, @PathVariable String commentId,
            @RequestParam(defaultValue = "1") int rootPage, @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        return privateResponse(discovery.replies(visibleId(id, scope, WorkType.ARTWORK), false,
                HttpIds.positive("path", "commentId", commentId), listPage(rootPage, "rootPage"), listPage(page), cookie(credential)));
    }

    @GetMapping("/novels/{id}/comments/{commentId}/replies")
    public ResponseEntity<ContentModels.Comments> novelReplies(@PathVariable String id, @PathVariable String commentId,
            @RequestParam(defaultValue = "1") int rootPage, @RequestParam(defaultValue = "1") int page,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        return privateResponse(discovery.replies(visibleId(id, scope, WorkType.NOVEL), true,
                HttpIds.positive("path", "commentId", commentId), listPage(rootPage, "rootPage"), listPage(page), cookie(credential)));
    }

    @GetMapping("/collections/search")
    public ResponseEntity<PersonalContentModels.CollectionPage> searchCollections(@RequestParam String word,
            @RequestParam(defaultValue = "1") int page, @RequestHeader(value = HEADER_NAME, required = false) String credential,
            WorkVisibilityScope scope) {
        var result = browse.collections(requiredWord(word), listPage(page), cookie(credential));
        return privateResponse(scope.enforceVisibility() ? result.withoutCovers() : result);
    }

    @GetMapping("/users/{id}/collections")
    public ResponseEntity<PersonalContentModels.CollectionPage> userCollections(@PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestHeader(value = HEADER_NAME, required = false) String credential,
            WorkVisibilityScope scope) {
        var result = personal.collectionsFor(Long.toString(id(id)), listPage(page), cookie(credential), true);
        return privateResponse(scope.enforceVisibility() ? result.withoutCovers() : result);
    }

    @GetMapping("/collections/{collectionId}/works")
    public ResponseEntity<PersonalContentModels.PersonalPage> publicCollectionWorks(@PathVariable String collectionId,
            @RequestParam(defaultValue = "1") int page, @RequestHeader(value = HEADER_NAME, required = false) String credential,
            WorkVisibilityScope scope) {
        var result = personal.readCollection(collectionId, listPage(page), cookie(credential), true);
        return privateResponse(scope.enforceVisibility()
                ? result.filter(visible(scope, WorkType.ARTWORK), visible(scope, WorkType.NOVEL)) : result);
    }

    @GetMapping("/discover")
    public ResponseEntity<ContentModels.WorkFeed> discover(@RequestParam(defaultValue = "artwork") PersonalType type,
            WorkVisibilityScope scope) {
        return privateResponse(browse.discover(type == PersonalType.novel, null, visible(scope, type.workType)));
    }

    @GetMapping("/me/recommendations")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<ContentModels.WorkFeed> recommendations(@RequestParam(defaultValue = "artwork") PersonalType type,
            @RequestHeader(value = HEADER_NAME, required = false) String credential, WorkVisibilityScope scope) {
        String session = cookie(credential);
        return privateResponse(personal.authenticated(session, uid -> browse.homepage(type == PersonalType.novel, session,
                visible(scope, type.workType))));
    }

    @GetMapping("/latest")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    public ResponseEntity<ContentModels.WorkFeed> latest(@RequestParam(defaultValue = "illust") LatestType type,
            @RequestParam(required = false) Long cursor, @RequestHeader(value = HEADER_NAME, required = false) String credential,
            WorkVisibilityScope scope) {
        boolean novel = type == LatestType.novel;
        return privateResponse(browse.latest(novel, type == LatestType.manga, cursor, cookie(credential),
                visible(scope, novel ? WorkType.NOVEL : WorkType.ARTWORK)));
    }

    @GetMapping("/novel-series/search")
    @KnownErrorCodes(status = "400", value = AcquisitionCredentialResolver.REQUIRED_CODE)
    @KnownErrorCodes(status = "403", value = "PIXIV_PREMIUM_REQUIRED")
    public ResponseEntity<ContentModels.GroupedNovels> searchNovelSeries(@RequestParam String word,
            @RequestParam(defaultValue = "date_d") PixivQueryParameters.Order order,
            @RequestParam(defaultValue = "all") PixivQueryParameters.Mode mode,
            @RequestParam(defaultValue = "s_tag") ContentModels.SearchMode sMode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Integer minBookmarks, @RequestParam(required = false) Integer maxBookmarks,
            @RequestParam(required = false) Integer minCharacters, @RequestParam(required = false) Integer maxCharacters,
            @RequestParam(required = false) String language,
            @RequestParam(defaultValue = "1") int page, @RequestHeader(value = HEADER_NAME, required = false) String credential,
            WorkVisibilityScope scope) {
        var filters = new SearchFilters(minBookmarks, maxBookmarks, null, null, null, null, null, minCharacters, maxCharacters, language);
        String session = cookie(credential);
        return privateResponse(personal.search(session, filters, () -> browse.groupedNovels(requiredWord(word), order.name(),
                mode.name(), sMode.name(), startDate, endDate, listPage(page), session, filters, visible(scope, WorkType.NOVEL))));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(PixivAjaxException.class)
    public ResponseEntity<ApiErrorResponse> ajaxFailure(PixivAjaxException failure) {
        return error(ContentFailure.from(failure), failure.retryAfterMillis());
    }

    @ExceptionHandler(ContentFailure.class)
    public ResponseEntity<ApiErrorResponse> contentFailure(ContentFailure failure) {
        return error(failure, 0);
    }

    private ResponseEntity<ApiErrorResponse> error(ContentFailure failure, long retryAfterMillis) {
        var response = ResponseEntity.status(failure.status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore());
        if (failure.status == 429 && retryAfterMillis > 0)
            response.header("Retry-After", Long.toString(Math.ceilDiv(retryAfterMillis, 1000L)));
        return response.body(ApiErrorResponse.of(failure.code, messages.get(failure.messageKey)));
    }

    private static int listPage(int page) {
        return listPage(page, "page");
    }

    private static int listPage(int page, String field) {
        if (page < 1) throw new HttpParameterException("query", field, "out_of_range");
        return page;
    }

    private static long id(String value) {
        return HttpIds.positive("path", "id", value);
    }

    private static String requiredWord(String value) {
        if (value.isBlank()) throw new HttpParameterException("query", "word", "required");
        return value;
    }

    private long visibleId(String value, WorkVisibilityScope scope, WorkType type) {
        long id = id(value);
        visibility.requireVisible(scope, type, id);
        return id;
    }

    private LongPredicate visible(WorkVisibilityScope scope, WorkType type) {
        return id -> visibility.isVisible(scope, type, id);
    }

    private static String cookie(String value) {
        return AcquisitionCredentialResolver.resolve(value);
    }
}
