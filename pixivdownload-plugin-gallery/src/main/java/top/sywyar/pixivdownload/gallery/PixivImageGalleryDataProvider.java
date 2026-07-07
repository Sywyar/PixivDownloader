package top.sywyar.pixivdownload.gallery;

import top.sywyar.pixivdownload.core.gallery.GalleryDataProvider;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryAuthorFacet;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetFilter;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetType;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryTagFacet;
import top.sywyar.pixivdownload.core.gallery.model.GalleryFieldStrategy;
import top.sywyar.pixivdownload.core.gallery.model.GalleryItem;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.GalleryPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryQuery;
import top.sywyar.pixivdownload.core.gallery.model.GallerySourceDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.GalleryWorkRef;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.work.model.PagedResult;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkSummary;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkTag;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.AuthorSummary;
import top.sywyar.pixivdownload.plugin.api.work.query.TagOption;
import top.sywyar.pixivdownload.plugin.api.work.query.TagQuery;
import top.sywyar.pixivdownload.plugin.api.work.query.WorkQuery;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkQueryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@PluginManagedBean
public class PixivImageGalleryDataProvider implements GalleryDataProvider {

    static final String PROVIDER_ID = "pixiv-image";
    static final String SOURCE_ID = "pixiv";
    private static final int DEFAULT_FACET_LIMIT = 500;
    private static final int METADATA_FILTER_MIN_BATCH_SIZE = 50;
    private static final int METADATA_FILTER_MAX_BATCH_SIZE = 200;

    private final WorkQueryService workQueryService;
    private final WorkMetadataRepository workMetadataRepository;

    public PixivImageGalleryDataProvider(WorkQueryService workQueryService,
                                         WorkMetadataRepository workMetadataRepository) {
        this.workQueryService = workQueryService;
        this.workMetadataRepository = workMetadataRepository;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<GallerySourceDescriptor> sources() {
        return List.of(new GallerySourceDescriptor(
                PROVIDER_ID,
                SOURCE_ID,
                Set.of(GalleryKind.IMAGE),
                "gallery",
                "source.pixiv",
                10,
                List.of(
                        GalleryFieldStrategy.supported("r18"),
                        GalleryFieldStrategy.supported("ai"))));
    }

    @Override
    public GalleryPage query(GalleryQuery query) {
        if (!canServe(query)) {
            return GalleryPage.empty(query == null ? 0 : query.offset(), query == null ? 0 : query.limit(), List.of());
        }
        QueryFilter filter = QueryFilter.from(query);
        if (!filter.valid()) {
            return GalleryPage.empty(query.offset(), query.limit(), List.of());
        }
        if (filter.requiresMetadataFiltering()) {
            return filteredQuery(query, filter);
        }
        return pagedQuery(query, filter);
    }

    @Override
    public GalleryFacetPage facets(GalleryQuery query) {
        if (!canServe(query) || hasForeignFacetSource(query)) {
            return GalleryFacetPage.empty();
        }
        int limit = facetLimit(query);
        List<GalleryAuthorFacet> authors = workQueryService.authors(new AuthorQuery(WorkType.ARTWORK, null))
                .stream()
                .map(PixivImageGalleryDataProvider::toAuthorFacet)
                .toList();
        List<GalleryTagFacet> tags = workQueryService.tags(new TagQuery(WorkType.ARTWORK, facetSearch(query), limit, null))
                .stream()
                .map(PixivImageGalleryDataProvider::toTagFacet)
                .toList();
        List<top.sywyar.pixivdownload.core.gallery.facet.GalleryFacet> facets =
                new ArrayList<>(authors.size() + tags.size());
        facets.addAll(authors);
        facets.addAll(tags);
        return new GalleryFacetPage(facets, List.of());
    }

    private GalleryPage pagedQuery(GalleryQuery query, QueryFilter filter) {
        int limit = Math.max(1, query.limit());
        int firstPage = query.offset() / limit;
        int skipInPage = query.offset() % limit;
        PagedResult<WorkSummary> first = workQueryService.search(toWorkQuery(filter, firstPage, limit));
        List<Long> ids = new ArrayList<>(limit);
        appendPageIds(ids, first.content(), skipInPage, limit);
        if (skipInPage > 0 && ids.size() < limit) {
            PagedResult<WorkSummary> next = workQueryService.search(toWorkQuery(filter, firstPage + 1, limit));
            appendPageIds(ids, next.content(), 0, limit);
        }
        List<GalleryItem> items = toItems(ids);
        return new GalleryPage(items, first.totalElements(),
                query.offset() + items.size() < first.totalElements(), query.offset(), query.limit(), List.of());
    }

    private GalleryPage filteredQuery(GalleryQuery query, QueryFilter filter) {
        int limit = Math.max(1, query.limit());
        int batchSize = metadataFilterBatchSize(limit);
        QueryFilter baseFilter = filter.withoutMetadataFilters();
        List<GalleryItem> items = new ArrayList<>(limit);
        long matched = 0;
        int page = 0;
        while (true) {
            PagedResult<WorkSummary> batch = workQueryService.search(toWorkQuery(baseFilter, page, batchSize));
            if (batch.content().isEmpty()) {
                break;
            }
            List<Long> ids = batch.content().stream()
                    .map(WorkSummary::workId)
                    .toList();
            for (WorkMetadata metadata : workMetadataRepository.findAll(WorkType.ARTWORK, ids)) {
                if (!filter.matchesMetadata(metadata)) {
                    continue;
                }
                if (matched >= query.offset() && items.size() < limit) {
                    items.add(toItem(metadata));
                }
                matched++;
            }
            page++;
            if (page >= batch.totalPages()) {
                break;
            }
        }
        long pageEnd = (long) query.offset() + items.size();
        return new GalleryPage(items, matched, pageEnd < matched, query.offset(), query.limit(), List.of());
    }

    private WorkQuery toWorkQuery(QueryFilter filter, int page, int size) {
        return WorkQuery.builder(WorkType.ARTWORK)
                .page(page)
                .size(size)
                .sort(filter.sort())
                .order(filter.order())
                .search(filter.search())
                .searchType(filter.searchType())
                .r18("any")
                .ai("any")
                .formats(filter.formats())
                .collectionIds(filter.collectionIds())
                .tagIds(filter.tagIds())
                .authorIds(filter.authorIds())
                .seriesIds(filter.seriesIds())
                .build();
    }

    private List<GalleryItem> toItems(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return workMetadataRepository.findAll(WorkType.ARTWORK, ids).stream()
                .map(this::toItem)
                .toList();
    }

    private static int metadataFilterBatchSize(int limit) {
        return Math.min(Math.max(limit, METADATA_FILTER_MIN_BATCH_SIZE), METADATA_FILTER_MAX_BATCH_SIZE);
    }

    private GalleryItem toItem(WorkMetadata meta) {
        long workId = meta.workId();
        return new GalleryItem(
                new GalleryWorkRef(PROVIDER_ID, SOURCE_ID, GalleryKind.IMAGE, String.valueOf(workId)),
                meta.title(),
                "/api/downloaded/thumbnail-file/" + workId + "/0",
                "/pixiv-artwork.html?id=" + workId,
                attributes(meta));
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
        return new GalleryAuthorFacet(SOURCE_ID, String.valueOf(summary.authorId()), summary.name(), summary.workCount());
    }

    private static GalleryTagFacet toTagFacet(TagOption tag) {
        return new GalleryTagFacet(SOURCE_ID, String.valueOf(tag.tagId()), tag.name(), tag.translatedName(), tag.workCount());
    }

    private static void appendPageIds(List<Long> ids, List<WorkSummary> summaries, int skip, int limit) {
        for (int i = Math.max(0, skip); i < summaries.size() && ids.size() < limit; i++) {
            ids.add(summaries.get(i).workId());
        }
    }

    private static boolean canServe(GalleryQuery query) {
        if (query == null || query.kind() != GalleryKind.IMAGE) {
            return false;
        }
        return query.sourceId() == null || SOURCE_ID.equals(query.sourceId());
    }

    private static boolean hasForeignFacetSource(GalleryQuery query) {
        return query != null && query.facetFilters().stream()
                .map(GalleryFacetFilter::sourceId)
                .anyMatch(sourceId -> sourceId != null && !SOURCE_ID.equals(sourceId));
    }

    private static String facetSearch(GalleryQuery query) {
        if (query == null) {
            return null;
        }
        return firstNonBlank(query.filters(), "facetSearch", "tagSearch");
    }

    private static int facetLimit(GalleryQuery query) {
        if (query == null) {
            return DEFAULT_FACET_LIMIT;
        }
        String value = firstNonBlank(query.filters(), "facetLimit");
        if (value == null) {
            return DEFAULT_FACET_LIMIT;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return DEFAULT_FACET_LIMIT;
        }
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

    private record QueryFilter(
            String sort,
            String order,
            String search,
            String searchType,
            String r18,
            String ai,
            List<String> formats,
            List<Long> collectionIds,
            List<Long> tagIds,
            List<Long> authorIds,
            List<Long> seriesIds,
            boolean valid) {

        static QueryFilter from(GalleryQuery query) {
            Map<String, String> filters = query.filters();
            Ids collectionIds = ids(filters, "collectionIds", "collectionId");
            Ids tagIds = ids(filters, "tagIds", "tagId");
            Ids authorIds = ids(filters, "authorIds", "authorId");
            Ids seriesIds = ids(filters, "seriesIds", "seriesId");
            Ids facetTagIds = facetIds(query, GalleryFacetType.TAG);
            Ids facetAuthorIds = facetIds(query, GalleryFacetType.AUTHOR);
            boolean valid = collectionIds.valid() && tagIds.valid() && authorIds.valid()
                    && seriesIds.valid() && facetTagIds.valid() && facetAuthorIds.valid();
            return new QueryFilter(
                    valueOrDefault(filters, "sort", "date"),
                    valueOrDefault(filters, "order", "desc"),
                    firstNonBlank(filters, "search"),
                    valueOrDefault(filters, "searchType", "all"),
                    normalize(firstNonBlank(filters, "r18", "contentRating")),
                    normalize(firstNonBlank(filters, "ai", "aiStatus")),
                    csv(filters, "formats", "format"),
                    collectionIds.values(),
                    merge(tagIds.values(), facetTagIds.values()),
                    merge(authorIds.values(), facetAuthorIds.values()),
                    seriesIds.values(),
                    valid && !hasForeignFacetSource(query));
        }

        QueryFilter withoutMetadataFilters() {
            return new QueryFilter(sort, order, search, searchType, "any", "any", formats,
                    collectionIds, tagIds, authorIds, seriesIds, valid);
        }

        boolean requiresMetadataFiltering() {
            return isActive(r18) || isActive(ai);
        }

        boolean matchesMetadata(WorkMetadata meta) {
            return matchesR18(meta.xRestrict()) && matchesAi(meta.isAi());
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

    private static Ids facetIds(GalleryQuery query, GalleryFacetType type) {
        List<Long> out = new ArrayList<>();
        for (GalleryFacetFilter filter : query.facetFilters()) {
            if (filter.type() != type) {
                continue;
            }
            Long id = parsePositiveLong(filter.value());
            if (id == null) {
                return new Ids(null, false);
            }
            out.add(id);
        }
        return new Ids(out, true);
    }

    private static Ids ids(Map<String, String> filters, String pluralKey, String singularKey) {
        List<String> values = csv(filters, pluralKey, singularKey);
        if (values == null || values.isEmpty()) {
            return new Ids(null, true);
        }
        List<Long> out = new ArrayList<>();
        for (String value : values) {
            Long id = parsePositiveLong(value);
            if (id == null) {
                return new Ids(null, false);
            }
            out.add(id);
        }
        return new Ids(out, true);
    }

    private static Long parsePositiveLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<Long> merge(List<Long> first, List<Long> second) {
        if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
            return null;
        }
        Set<Long> out = new LinkedHashSet<>();
        if (first != null) {
            out.addAll(first);
        }
        if (second != null) {
            out.addAll(second);
        }
        return List.copyOf(out);
    }

    private static List<String> csv(Map<String, String> filters, String pluralKey, String singularKey) {
        String value = firstNonBlank(filters, pluralKey, singularKey);
        if (value == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    private static String valueOrDefault(Map<String, String> filters, String key, String fallback) {
        String value = firstNonBlank(filters, key);
        return value == null ? fallback : value;
    }

    private static String firstNonBlank(Map<String, String> filters, String... keys) {
        for (String key : keys) {
            String value = filters.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
