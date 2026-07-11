package top.sywyar.pixivdownload.douyin.gallery;

import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryAuthorFacet;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacet;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryMediaKey;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryProjectionKey;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaAsset;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjection;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryActor;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryAiStatus;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryContentRating;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilter;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterCapability;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterField;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterMode;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryPage;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryQuery;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@PluginManagedBean
public class DouyinGalleryDataProvider implements GalleryProjectionProvider, GalleryWorkProvider {

    public static final String PROVIDER_ID = "douyin-gallery";
    public static final String SOURCE_ID = "douyin";
    public static final String WORK_NAMESPACE = "aweme";
    private static final List<String> IMAGE_MEDIA_TYPES = List.of("IMAGE");
    private static final List<String> VIDEO_MEDIA_TYPES = List.of("VIDEO", "LIVE_PHOTO_VIDEO");
    private static final int FACET_LIMIT = 500;

    private final DouyinHistoryService historyService;

    public DouyinGalleryDataProvider(DouyinHistoryService historyService) {
        this.historyService = historyService;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<GalleryProjectionDescriptor> projections() {
        Map<GalleryFilterField, GalleryFilterCapability> capabilities = Map.of(
                GalleryFilterField.AUTHOR, GalleryFilterCapability.supported(),
                GalleryFilterField.TAG, GalleryFilterCapability.unsupported(),
                GalleryFilterField.AI_STATUS, GalleryFilterCapability.unknown(),
                GalleryFilterField.CONTENT_RATING, GalleryFilterCapability.constant("SFW"),
                GalleryFilterField.SOURCE, GalleryFilterCapability.constant(SOURCE_ID),
                GalleryFilterField.CONTAINED_MEDIA_KIND, GalleryFilterCapability.supported());
        return List.of(
                new GalleryProjectionDescriptor(SOURCE_ID, GalleryKind.IMAGE,
                        "douyin", "source.douyin", 30, GalleryDataAccess.ADMIN_ONLY, capabilities),
                new GalleryProjectionDescriptor(SOURCE_ID, GalleryKind.VIDEO,
                        "douyin", "source.douyin", 30, GalleryDataAccess.ADMIN_ONLY, capabilities));
    }

    @Override
    public List<GalleryWorkDescriptor> works() {
        return List.of(new GalleryWorkDescriptor(SOURCE_ID, WORK_NAMESPACE, GalleryDataAccess.ADMIN_ONLY));
    }

    @Override
    public GalleryProjectionPage page(GalleryProjectionQuery query) {
        QuerySpec spec = QuerySpec.from(query);
        if (!spec.matches()) {
            return GalleryProjectionPage.empty();
        }
        DouyinHistoryPage page = historyService.search(spec.historyQuery(query.limit()));
        List<GalleryProjection> projections = page.works().stream()
                .map(work -> projection(work, query.kind()))
                .toList();
        int nextOffset = spec.offset() + projections.size();
        boolean hasMore = nextOffset < page.total();
        return new GalleryProjectionPage(projections, hasMore ? String.valueOf(nextOffset) : null,
                hasMore, List.of());
    }

    @Override
    public long count(GalleryProjectionQuery query) {
        QuerySpec spec = QuerySpec.from(query);
        return spec.matches() ? historyService.search(spec.historyQuery(1)).total() : 0;
    }

    @Override
    public GalleryFacetPage facets(GalleryProjectionQuery query) {
        QuerySpec spec = QuerySpec.from(query);
        if (!spec.matches()) {
            return GalleryFacetPage.empty();
        }
        DouyinHistoryQuery historyQuery = spec.historyQuery(FACET_LIMIT);
        List<GalleryFacet> facets = historyService.authorFacets(historyQuery).stream()
                .map(author -> (GalleryFacet) new GalleryAuthorFacet(
                        SOURCE_ID, author.authorId(), author.name(), author.workCount()))
                .toList();
        return new GalleryFacetPage(facets, List.of());
    }

    @Override
    public Optional<GalleryWork> find(GalleryWorkKey key) {
        if (!SOURCE_ID.equals(key.sourceId()) || !WORK_NAMESPACE.equals(key.sourceWorkNamespace())) {
            return Optional.empty();
        }
        return historyService.findById(key.sourceWorkId()).map(work -> toWork(key, work));
    }

    /** Builds the source-owned card projection used by the independent Douyin gallery page. */
    public GalleryProjection projection(DouyinWorkRecord work, GalleryKind kind) {
        GalleryWorkKey workKey = new GalleryWorkKey(SOURCE_ID, WORK_NAMESPACE, work.workId());
        List<DouyinWorkFileRecord> files = historyService.findFilesByWorkId(work.workId());
        Set<GalleryMediaKind> mediaKinds = new LinkedHashSet<>();
        files.stream().map(DouyinGalleryDataProvider::mediaKind).forEach(mediaKinds::add);
        String preferredMediaId = files.stream()
                .filter(file -> projectionContains(kind, mediaKind(file)))
                .findFirst().map(DouyinGalleryDataProvider::mediaId).orElse(null);
        String thumbnailUrl = files.stream()
                .filter(file -> mediaKind(file) == GalleryMediaKind.IMAGE
                        || mediaKind(file) == GalleryMediaKind.COVER)
                .findFirst()
                .map(file -> mediaUrl(work.workId(), file.fileIndex()))
                .orElse(null);
        return new GalleryProjection(
                new GalleryProjectionKey(workKey, kind),
                firstNonBlank(work.title(), work.itemTitle(), work.caption(), work.workId()),
                firstNonBlank(work.description(), work.caption()),
                thumbnailUrl,
                actor(work),
                List.of(),
                instant(work.publishTime()),
                instant(work.time()),
                instant(work.time()),
                mediaKinds,
                GalleryContentRating.SFW,
                GalleryAiStatus.UNKNOWN,
                preferredMediaId,
                attributes(work));
    }

    /** Chooses one stable list category for an unfiltered work without changing its work identity. */
    public GalleryKind primaryKind(DouyinWorkRecord work) {
        return historyService.findFilesByWorkId(work.workId()).stream()
                .map(DouyinGalleryDataProvider::mediaKind)
                .anyMatch(kind -> kind == GalleryMediaKind.IMAGE)
                ? GalleryKind.IMAGE : GalleryKind.VIDEO;
    }

    private GalleryWork toWork(GalleryWorkKey key, DouyinWorkRecord work) {
        List<GalleryMediaAsset> media = historyService.findFilesByWorkId(work.workId()).stream()
                .map(file -> toMedia(key, file))
                .toList();
        return new GalleryWork(
                key,
                firstNonBlank(work.title(), work.itemTitle(), work.caption(), work.workId()),
                firstNonBlank(work.description(), work.caption()),
                actor(work),
                List.of(),
                instant(work.publishTime()),
                instant(work.time()),
                instant(work.time()),
                GalleryContentRating.SFW,
                GalleryAiStatus.UNKNOWN,
                media,
                attributes(work));
    }

    private static GalleryMediaAsset toMedia(GalleryWorkKey workKey, DouyinWorkFileRecord file) {
        String id = mediaId(file);
        String url = mediaUrl(workKey.sourceWorkId(), file.fileIndex());
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "fileName", file.fileName());
        put(attributes, "extension", file.extension());
        put(attributes, "bytes", file.bytes());
        put(attributes, "fileIndex", file.fileIndex());
        return new GalleryMediaAsset(new GalleryMediaKey(workKey, id), mediaKind(file), url,
                mediaKind(file) == GalleryMediaKind.COVER ? url : null,
                file.contentType(), null, attributes);
    }

    private static String mediaUrl(String workId, int fileIndex) {
        return "/api/douyin/history/" + workId + "/media/" + fileIndex;
    }

    private static GalleryActor actor(DouyinWorkRecord work) {
        return isBlank(work.authorId()) ? null
                : new GalleryActor(SOURCE_ID, work.authorId(), work.authorName(), null);
    }

    private static Map<String, String> attributes(DouyinWorkRecord work) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "sourceUrl", work.sourceUrl());
        put(out, "canonicalUrl", work.canonicalUrl());
        put(out, "collectionId", work.collectionId());
        put(out, "collectionTitle", work.collectionTitle());
        put(out, "collectionOrder", work.collectionOrder());
        put(out, "fileCount", work.count());
        return out;
    }

    private static GalleryMediaKind mediaKind(DouyinWorkFileRecord file) {
        if (file.mediaType() == null) {
            return GalleryMediaKind.UNKNOWN;
        }
        try {
            return GalleryMediaKind.valueOf(file.mediaType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return GalleryMediaKind.UNKNOWN;
        }
    }

    private static String mediaId(DouyinWorkFileRecord file) {
        return isBlank(file.mediaId()) ? "index-" + file.fileIndex() : file.mediaId().trim();
    }

    private static boolean projectionContains(GalleryKind kind, GalleryMediaKind mediaKind) {
        return kind == GalleryKind.IMAGE && mediaKind == GalleryMediaKind.IMAGE
                || kind == GalleryKind.VIDEO
                && (mediaKind == GalleryMediaKind.VIDEO || mediaKind == GalleryMediaKind.LIVE_PHOTO_VIDEO);
    }

    private static Instant instant(Long millis) {
        return millis == null || millis <= 0 ? null : Instant.ofEpochMilli(millis);
    }

    private static Instant instant(long millis) {
        return millis <= 0 ? null : Instant.ofEpochMilli(millis);
    }

    private static void put(Map<String, String> out, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            out.put(key, String.valueOf(value));
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record QuerySpec(int offset, List<String> authorIds, List<String> mediaTypes, boolean matches,
                             GallerySortField sortField, GallerySortDirection sortDirection) {

        static QuerySpec from(GalleryProjectionQuery query) {
            if (query == null || !SOURCE_ID.equals(query.sourceId())
                    && query.sourceId() != null || query.kind() == GalleryKind.NOVEL) {
                return new QuerySpec(0, List.of(), List.of(), false,
                        GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC);
            }
            boolean matches = true;
            Set<String> authors = new LinkedHashSet<>();
            List<String> mediaTypes = query.kind() == GalleryKind.IMAGE ? IMAGE_MEDIA_TYPES : VIDEO_MEDIA_TYPES;
            for (GalleryFilter filter : query.filters()) {
                if (filter.sourceId() != null && !SOURCE_ID.equals(filter.sourceId())) {
                    continue;
                }
                if (filter.mode() != GalleryFilterMode.ANY_OF) {
                    matches = false;
                    continue;
                }
                switch (filter.field()) {
                    case AUTHOR -> authors.addAll(filter.values());
                    case TAG -> matches = false;
                    case AI_STATUS -> matches &= filter.values().stream().anyMatch("UNKNOWN"::equalsIgnoreCase);
                    case CONTENT_RATING -> matches &= filter.values().stream().anyMatch("SFW"::equalsIgnoreCase);
                    case SOURCE -> matches &= filter.values().contains(SOURCE_ID);
                    case CONTAINED_MEDIA_KIND -> matches &= filter.values().stream()
                            .map(value -> value.toUpperCase(Locale.ROOT))
                            .anyMatch(value -> mediaTypes.contains(value));
                }
            }
            return new QuerySpec(parseOffset(query.cursor()), List.copyOf(authors), mediaTypes, matches,
                    query.sortField(), query.sortDirection());
        }

        DouyinHistoryQuery historyQuery(int limit) {
            return new DouyinHistoryQuery(offset, limit, sort(sortField),
                    sortDirection == GallerySortDirection.ASC ? "asc" : "desc",
                    null, authorIds, mediaTypes);
        }

        private static int parseOffset(String cursor) {
            if (cursor == null) {
                return 0;
            }
            try {
                return Math.max(0, Integer.parseInt(cursor));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("invalid Douyin gallery cursor", failure);
            }
        }

        private static String sort(GallerySortField field) {
            return switch (field) {
                case TITLE -> "title";
                case CREATED_AT -> "publishTime";
                case DOWNLOADED_AT, UPDATED_AT -> "time";
            };
        }
    }
}
