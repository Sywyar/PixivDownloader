package top.sywyar.pixivdownload.gallery;

import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryAuthorFacet;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacet;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryTagFacet;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryMediaKey;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryProjectionKey;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaAsset;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjection;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryActor;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryAiStatus;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryContentRating;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryTag;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilter;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterCapability;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterField;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterMode;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.core.work.model.PagedResult;
import top.sywyar.pixivdownload.core.work.model.WorkMetadata;
import top.sywyar.pixivdownload.core.work.model.WorkSummary;
import top.sywyar.pixivdownload.core.work.model.WorkTag;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.query.AuthorQuery;
import top.sywyar.pixivdownload.core.work.query.AuthorSummary;
import top.sywyar.pixivdownload.core.work.query.TagOption;
import top.sywyar.pixivdownload.core.work.query.TagQuery;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@PluginManagedBean
public class PixivImageGalleryCapabilityProvider implements GalleryProjectionProvider, GalleryWorkProvider {

    static final String PROVIDER_ID = "pixiv-image-capability";
    static final String SOURCE_ID = "pixiv";
    static final String WORK_NAMESPACE = "artwork";
    private static final int DEFAULT_FACET_LIMIT = 500;
    private static final int METADATA_FILTER_MIN_BATCH_SIZE = 50;
    private static final int METADATA_FILTER_MAX_BATCH_SIZE = 200;

    private final WorkQueryService workQueryService;
    private final WorkMetadataRepository metadataRepository;

    public PixivImageGalleryCapabilityProvider(WorkQueryService workQueryService,
                                               WorkMetadataRepository metadataRepository) {
        this.workQueryService = workQueryService;
        this.metadataRepository = metadataRepository;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<GalleryProjectionDescriptor> projections() {
        return List.of(new GalleryProjectionDescriptor(SOURCE_ID, GalleryKind.IMAGE,
                "gallery", "source.pixiv", 10, GalleryDataAccess.SHARED, Map.of(
                GalleryFilterField.AUTHOR, GalleryFilterCapability.supported(),
                GalleryFilterField.TAG, GalleryFilterCapability.supported(),
                GalleryFilterField.AI_STATUS, GalleryFilterCapability.supported(),
                GalleryFilterField.CONTENT_RATING, GalleryFilterCapability.supported(),
                GalleryFilterField.SOURCE, GalleryFilterCapability.constant(SOURCE_ID),
                GalleryFilterField.CONTAINED_MEDIA_KIND, GalleryFilterCapability.unknown())));
    }

    @Override
    public List<GalleryWorkDescriptor> works() {
        return List.of(new GalleryWorkDescriptor(SOURCE_ID, WORK_NAMESPACE, GalleryDataAccess.SHARED));
    }

    @Override
    public GalleryProjectionPage page(GalleryProjectionQuery query) {
        QuerySpec spec = QuerySpec.from(query);
        if (!spec.matches()) {
            return GalleryProjectionPage.empty();
        }
        PageSlice slice = loadPage(spec);
        List<GalleryProjection> projections = slice.metadata().stream()
                .map(PixivImageGalleryCapabilityProvider::projection)
                .toList();
        int next = spec.offset() + projections.size();
        return new GalleryProjectionPage(projections, slice.hasMore() ? String.valueOf(next) : null,
                slice.hasMore(), List.of());
    }

    @Override
    public long count(GalleryProjectionQuery query) {
        QuerySpec spec = QuerySpec.from(query);
        return spec.matches() ? loadPage(spec.withWindow(0, 1)).total() : 0;
    }

    @Override
    public GalleryFacetPage facets(GalleryProjectionQuery query) {
        QuerySpec spec = QuerySpec.from(query);
        if (!spec.compatible()) {
            return GalleryFacetPage.empty();
        }
        List<GalleryFacet> facets = new ArrayList<>();
        workQueryService.authors(new AuthorQuery(WorkType.ARTWORK, null)).stream()
                .map(PixivImageGalleryCapabilityProvider::toAuthorFacet)
                .forEach(facets::add);
        workQueryService.tags(new TagQuery(WorkType.ARTWORK, null, DEFAULT_FACET_LIMIT, null)).stream()
                .map(PixivImageGalleryCapabilityProvider::toTagFacet)
                .forEach(facets::add);
        return new GalleryFacetPage(facets, List.of());
    }

    @Override
    public Optional<GalleryWork> find(GalleryWorkKey key) {
        if (!SOURCE_ID.equals(key.sourceId()) || !WORK_NAMESPACE.equals(key.sourceWorkNamespace())) {
            return Optional.empty();
        }
        Long id = parseId(key.sourceWorkId());
        return id == null ? Optional.empty()
                : metadataRepository.find(WorkType.ARTWORK, id).map(meta -> work(key, meta));
    }

    private PageSlice loadPage(QuerySpec spec) {
        return spec.requiresMetadataFiltering() ? filteredPage(spec) : directPage(spec);
    }

    private PageSlice directPage(QuerySpec spec) {
        int limit = Math.max(1, spec.limit());
        int firstPage = spec.offset() / limit;
        int skipInPage = spec.offset() % limit;
        PagedResult<WorkSummary> first = workQueryService.search(toWorkQuery(spec, firstPage, limit));
        List<Long> ids = new ArrayList<>(limit);
        appendPageIds(ids, first.content(), skipInPage, limit);
        if (skipInPage > 0 && ids.size() < limit) {
            PagedResult<WorkSummary> next = workQueryService.search(toWorkQuery(spec, firstPage + 1, limit));
            appendPageIds(ids, next.content(), 0, limit);
        }
        List<WorkMetadata> metadata = ids.isEmpty()
                ? List.of()
                : metadataRepository.findAll(WorkType.ARTWORK, ids);
        return new PageSlice(metadata, first.totalElements(),
                (long) spec.offset() + metadata.size() < first.totalElements());
    }

    private PageSlice filteredPage(QuerySpec spec) {
        int limit = Math.max(1, spec.limit());
        int batchSize = metadataFilterBatchSize(limit);
        List<WorkMetadata> selected = new ArrayList<>(limit);
        long matched = 0;
        int page = 0;
        while (true) {
            PagedResult<WorkSummary> batch = workQueryService.search(toWorkQuery(spec, page, batchSize));
            if (batch.content().isEmpty()) {
                break;
            }
            List<Long> ids = batch.content().stream().map(WorkSummary::workId).toList();
            for (WorkMetadata metadata : metadataRepository.findAll(WorkType.ARTWORK, ids)) {
                if (!spec.matchesMetadata(metadata)) {
                    continue;
                }
                if (matched >= spec.offset() && selected.size() < limit) {
                    selected.add(metadata);
                }
                matched++;
            }
            page++;
            if (page >= batch.totalPages()) {
                break;
            }
        }
        return new PageSlice(selected, matched, (long) spec.offset() + selected.size() < matched);
    }

    private static WorkQuery toWorkQuery(QuerySpec spec, int page, int size) {
        return WorkQuery.builder(WorkType.ARTWORK)
                .page(page)
                .size(size)
                .sort(spec.sort())
                .order(spec.order())
                .r18("any")
                .ai("any")
                .tagIds(spec.tagIds())
                .authorIds(spec.authorIds())
                .build();
    }

    private static GalleryProjection projection(WorkMetadata meta) {
        GalleryWorkKey key = new GalleryWorkKey(SOURCE_ID, WORK_NAMESPACE, String.valueOf(meta.workId()));
        return new GalleryProjection(new GalleryProjectionKey(key, GalleryKind.IMAGE), meta.title(),
                meta.description(), "/api/downloaded/thumbnail-file/" + meta.workId() + "/0",
                actor(meta), tags(meta), instant(meta.uploadTime()), instant(meta.downloadTime()),
                instant(meta.moveTime()), Set.of(mediaKind(meta)), rating(meta.xRestrict()), ai(meta.isAi()),
                "page-0", attributes(meta));
    }

    private static GalleryWork work(GalleryWorkKey key, WorkMetadata meta) {
        List<GalleryMediaAsset> media = new ArrayList<>();
        GalleryMediaKind kind = mediaKind(meta);
        for (int page = 0; page < Math.max(1, meta.pageCount()); page++) {
            String id = "page-" + page;
            media.add(new GalleryMediaAsset(new GalleryMediaKey(key, id), kind,
                    "/api/downloaded/rawfile/" + meta.workId() + "/" + page,
                    "/api/downloaded/thumbnail-file/" + meta.workId() + "/" + page,
                    null, null, Map.of("page", String.valueOf(page))));
        }
        return new GalleryWork(key, meta.title(), meta.description(), actor(meta), tags(meta),
                instant(meta.uploadTime()), instant(meta.downloadTime()), instant(meta.moveTime()),
                rating(meta.xRestrict()), ai(meta.isAi()), media, Map.of());
    }

    private static Map<String, String> attributes(WorkMetadata meta) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "authorId", meta.authorId());
        put(out, "authorName", meta.authorName());
        put(out, "pageCount", meta.pageCount());
        put(out, "extensions", meta.extensions());
        put(out, "downloadedAt", meta.downloadTime());
        put(out, "uploadTime", meta.uploadTime());
        put(out, "seriesId", positive(meta.seriesId()));
        put(out, "seriesOrder", meta.seriesOrder());
        put(out, "seriesTitle", meta.seriesTitle());
        put(out, "moved", meta.moved());
        put(out, "xRestrict", meta.xRestrict());
        put(out, "contentRating", contentRating(meta.xRestrict()));
        put(out, "aiStatus", aiStatus(meta.isAi()));
        if (meta.isAi() != null) {
            put(out, "isAi", meta.isAi());
        }
        if (!meta.tags().isEmpty()) {
            put(out, "tagIds", meta.tags().stream()
                    .map(WorkTag::tagId)
                    .filter(id -> id != null)
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")));
            put(out, "tagNames", meta.tags().stream()
                    .map(WorkTag::name)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(Collectors.joining("\n")));
            put(out, "tagTranslatedNames", meta.tags().stream()
                    .map(WorkTag::translatedName)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(Collectors.joining("\n")));
        }
        return out;
    }

    private static GalleryAuthorFacet toAuthorFacet(AuthorSummary summary) {
        return new GalleryAuthorFacet(SOURCE_ID, String.valueOf(summary.authorId()), summary.name(),
                summary.workCount());
    }

    private static GalleryTagFacet toTagFacet(TagOption tag) {
        return new GalleryTagFacet(SOURCE_ID, String.valueOf(tag.tagId()), tag.name(),
                tag.translatedName(), tag.workCount());
    }

    private static GalleryActor actor(WorkMetadata meta) {
        return meta.authorId() == null ? null
                : new GalleryActor(SOURCE_ID, String.valueOf(meta.authorId()), meta.authorName(), null);
    }

    private static List<GalleryTag> tags(WorkMetadata meta) {
        return meta.tags().stream()
                .filter(tag -> tag.tagId() != null)
                .map(tag -> new GalleryTag(SOURCE_ID, String.valueOf(tag.tagId()), tag.name()))
                .toList();
    }

    private static GalleryMediaKind mediaKind(WorkMetadata meta) {
        String ext = meta.extensions() == null ? "" : meta.extensions().toLowerCase(Locale.ROOT);
        return ext.contains("gif") || ext.contains("webp") ? GalleryMediaKind.UGOIRA : GalleryMediaKind.IMAGE;
    }

    private static GalleryContentRating rating(Integer value) {
        return value == null ? GalleryContentRating.UNKNOWN : value == 1 ? GalleryContentRating.R18
                : value == 2 ? GalleryContentRating.R18G : GalleryContentRating.SFW;
    }

    private static GalleryAiStatus ai(Boolean value) {
        return value == null ? GalleryAiStatus.UNKNOWN : value ? GalleryAiStatus.AI : GalleryAiStatus.NON_AI;
    }

    private static String contentRating(Integer xRestrict) {
        if (xRestrict == null) {
            return "unknown";
        }
        return switch (xRestrict) {
            case 1 -> "r18";
            case 2 -> "r18g";
            default -> "sfw";
        };
    }

    private static String aiStatus(Boolean ai) {
        if (ai == null) {
            return "unknown";
        }
        return ai ? "true" : "false";
    }

    private static void appendPageIds(List<Long> ids, List<WorkSummary> summaries, int skip, int limit) {
        for (int index = Math.max(0, skip); index < summaries.size() && ids.size() < limit; index++) {
            ids.add(summaries.get(index).workId());
        }
    }

    private static int metadataFilterBatchSize(int limit) {
        return Math.min(Math.max(limit, METADATA_FILTER_MIN_BATCH_SIZE), METADATA_FILTER_MAX_BATCH_SIZE);
    }

    private static void put(Map<String, String> map, String key, Object value) {
        if (value == null) {
            return;
        }
        String stringValue = String.valueOf(value);
        if (!stringValue.isBlank()) {
            map.put(key, stringValue);
        }
    }

    private static Long positive(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private static Instant instant(long value) {
        return value <= 0 ? null : Instant.ofEpochMilli(value);
    }

    private static Instant instant(Long value) {
        return value == null ? null : instant(value.longValue());
    }

    private static Long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            return id > 0 ? id : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record PageSlice(List<WorkMetadata> metadata, long total, boolean hasMore) {
        private PageSlice {
            metadata = List.copyOf(metadata);
        }
    }

    private record QuerySpec(
            String sort,
            String order,
            String r18,
            String ai,
            List<Long> tagIds,
            List<Long> authorIds,
            int offset,
            int limit,
            boolean compatible,
            boolean matches) {

        static QuerySpec from(GalleryProjectionQuery query) {
            if (query == null || query.kind() != GalleryKind.IMAGE
                    || query.sourceId() != null && !SOURCE_ID.equals(query.sourceId())) {
                return unmatched();
            }
            String sort = query.sortField().name().equals("TITLE") ? "artworkId" : "date";
            String order = query.sortDirection().name().toLowerCase(Locale.ROOT);
            String r18 = null;
            String ai = null;
            Ids tagIds = new Ids(null, true);
            Ids authorIds = new Ids(null, true);
            boolean compatible = true;
            for (GalleryFilter filter : query.filters()) {
                if (filter.sourceId() != null && !SOURCE_ID.equals(filter.sourceId())) {
                    continue;
                }
                if (filter.mode() != GalleryFilterMode.ANY_OF) {
                    compatible = false;
                    continue;
                }
                switch (filter.field()) {
                    case AUTHOR -> authorIds = ids(filter.values());
                    case TAG -> tagIds = ids(filter.values());
                    case AI_STATUS -> ai = aiFilter(filter.values());
                    case CONTENT_RATING -> r18 = ratingFilter(filter.values());
                    case SOURCE -> compatible &= filter.values().contains(SOURCE_ID);
                    case CONTAINED_MEDIA_KIND -> compatible = false;
                }
            }
            int offset;
            try {
                offset = query.cursor() == null ? 0 : Math.max(0, Integer.parseInt(query.cursor()));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("invalid Pixiv image cursor", failure);
            }
            return new QuerySpec(sort, order, r18, ai, tagIds.values(), authorIds.values(),
                    offset, query.limit(), compatible,
                    compatible && tagIds.valid() && authorIds.valid());
        }

        private static QuerySpec unmatched() {
            return new QuerySpec("date", "desc", null, null, null, null,
                    0, 1, false, false);
        }

        QuerySpec withWindow(int newOffset, int newLimit) {
            return new QuerySpec(sort, order, r18, ai, tagIds, authorIds,
                    newOffset, newLimit, compatible, matches);
        }

        boolean requiresMetadataFiltering() {
            return isActive(r18) || isActive(ai);
        }

        boolean matchesMetadata(WorkMetadata metadata) {
            return matchesR18(metadata.xRestrict()) && matchesAi(metadata.isAi());
        }

        private boolean matchesR18(Integer xRestrict) {
            if (!isActive(r18)) {
                return true;
            }
            return switch (r18) {
                case "r18", "yes" -> xRestrict != null && xRestrict == 1;
                case "r18g" -> xRestrict != null && xRestrict == 2;
                case "r18plus" -> xRestrict != null && xRestrict >= 1;
                case "no", "sfw" -> xRestrict != null && xRestrict == 0;
                default -> true;
            };
        }

        private boolean matchesAi(Boolean isAi) {
            if (!isActive(ai)) {
                return true;
            }
            return switch (ai) {
                case "yes", "true" -> Boolean.TRUE.equals(isAi);
                case "no", "false" -> Boolean.FALSE.equals(isAi);
                default -> true;
            };
        }

        private static boolean isActive(String value) {
            return value != null && !value.isBlank() && !"any".equals(value) && !"all".equals(value);
        }
    }

    private record Ids(List<Long> values, boolean valid) {
        private Ids {
            values = values == null || values.isEmpty() ? null : List.copyOf(values);
        }
    }

    private static Ids ids(Set<String> values) {
        List<Long> ids = new ArrayList<>();
        for (String value : values) {
            for (String part : value.split(",")) {
                Long id = parseId(part.trim());
                if (id == null) {
                    return new Ids(null, false);
                }
                ids.add(id);
            }
        }
        return new Ids(ids, true);
    }

    private static String aiFilter(Set<String> values) {
        if (values.stream().anyMatch("AI"::equalsIgnoreCase)) {
            return "yes";
        }
        if (values.stream().anyMatch("NON_AI"::equalsIgnoreCase)) {
            return "no";
        }
        return "unknown";
    }

    private static String ratingFilter(Set<String> values) {
        if (values.stream().anyMatch("R18G"::equalsIgnoreCase)) {
            return "r18g";
        }
        if (values.stream().anyMatch("R18"::equalsIgnoreCase)) {
            return "r18";
        }
        if (values.stream().anyMatch("SFW"::equalsIgnoreCase)) {
            return "no";
        }
        return "unknown";
    }
}
