package top.sywyar.pixivdownload.gallery;

import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryItem;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.GalleryPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryQuery;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryMediaKey;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryProjectionKey;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaAsset;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
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
import top.sywyar.pixivdownload.plugin.api.work.model.WorkMetadata;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkMetadataRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@PluginManagedBean
public class PixivImageGalleryCapabilityProvider implements GalleryProjectionProvider, GalleryWorkProvider {

    static final String PROVIDER_ID = "pixiv-image-capability";
    static final String SOURCE_ID = "pixiv";
    static final String WORK_NAMESPACE = "artwork";

    private final PixivImageGalleryDataProvider pixivProvider;
    private final WorkMetadataRepository metadataRepository;

    public PixivImageGalleryCapabilityProvider(PixivImageGalleryDataProvider pixivProvider,
                                               WorkMetadataRepository metadataRepository) {
        this.pixivProvider = pixivProvider;
        this.metadataRepository = metadataRepository;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<GalleryProjectionDescriptor> projections() {
        return List.of(new GalleryProjectionDescriptor(SOURCE_ID, GalleryKind.IMAGE,
                "gallery", "source.pixiv", 10, Map.of(
                GalleryFilterField.AUTHOR, GalleryFilterCapability.supported(),
                GalleryFilterField.TAG, GalleryFilterCapability.supported(),
                GalleryFilterField.AI_STATUS, GalleryFilterCapability.supported(),
                GalleryFilterField.CONTENT_RATING, GalleryFilterCapability.supported(),
                GalleryFilterField.SOURCE, GalleryFilterCapability.constant(SOURCE_ID),
                GalleryFilterField.CONTAINED_MEDIA_KIND, GalleryFilterCapability.unknown())));
    }

    @Override
    public List<GalleryWorkDescriptor> works() {
        return List.of(new GalleryWorkDescriptor(SOURCE_ID, WORK_NAMESPACE));
    }

    @Override
    public GalleryProjectionPage page(GalleryProjectionQuery query) {
        AdaptedQuery adapted = adapt(query);
        if (!adapted.matches()) return GalleryProjectionPage.empty();
        GalleryPage page = pixivProvider.query(adapted.query());
        List<Long> ids = page.items().stream().map(item -> Long.parseLong(item.ref().workId())).toList();
        Map<Long, WorkMetadata> metadata = new LinkedHashMap<>();
        metadataRepository.findAll(WorkType.ARTWORK, ids).forEach(meta -> metadata.put(meta.workId(), meta));
        List<GalleryProjection> projections = page.items().stream()
                .map(item -> projection(item, metadata.get(Long.parseLong(item.ref().workId())))).toList();
        int next = adapted.query().offset() + projections.size();
        return new GalleryProjectionPage(projections, page.hasMore() ? String.valueOf(next) : null,
                page.hasMore(), page.diagnostics());
    }

    @Override
    public long count(GalleryProjectionQuery query) {
        AdaptedQuery adapted = adapt(query);
        return adapted.matches() ? pixivProvider.query(new GalleryQuery(
                GalleryKind.IMAGE, SOURCE_ID, List.of(), adapted.query().filters(), 0, 1)).total() : 0;
    }

    @Override
    public GalleryFacetPage facets(GalleryProjectionQuery query) {
        AdaptedQuery adapted = adapt(query);
        return adapted.matches() ? pixivProvider.facets(adapted.query()) : GalleryFacetPage.empty();
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

    private static GalleryProjection projection(GalleryItem item, WorkMetadata meta) {
        GalleryWorkKey key = new GalleryWorkKey(SOURCE_ID, WORK_NAMESPACE, item.ref().workId());
        return new GalleryProjection(new GalleryProjectionKey(key, GalleryKind.IMAGE), item.title(),
                meta == null ? null : meta.description(), item.thumbnailUrl(),
                meta == null ? null : actor(meta), meta == null ? List.of() : tags(meta),
                meta == null ? null : instant(meta.uploadTime()),
                meta == null ? instant(item.attributes().get("downloadedAt")) : instant(meta.downloadTime()),
                meta == null ? null : instant(meta.moveTime()),
                meta == null ? Set.of(GalleryMediaKind.IMAGE) : Set.of(mediaKind(meta)),
                meta == null ? GalleryContentRating.UNKNOWN : rating(meta.xRestrict()),
                meta == null ? GalleryAiStatus.UNKNOWN : ai(meta.isAi()), "page-0", item.attributes());
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

    private static GalleryActor actor(WorkMetadata meta) {
        return meta.authorId() == null ? null
                : new GalleryActor(SOURCE_ID, String.valueOf(meta.authorId()), meta.authorName(), null);
    }

    private static List<GalleryTag> tags(WorkMetadata meta) {
        return meta.tags().stream().filter(tag -> tag.tagId() != null)
                .map(tag -> new GalleryTag(SOURCE_ID, String.valueOf(tag.tagId()), tag.name())).toList();
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

    private static Instant instant(long value) { return value <= 0 ? null : Instant.ofEpochMilli(value); }
    private static Instant instant(Long value) { return value == null ? null : instant(value.longValue()); }
    private static Instant instant(String value) {
        try { return value == null ? null : instant(Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static Long parseId(String value) {
        try { long id = Long.parseLong(value); return id > 0 ? id : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    private static AdaptedQuery adapt(GalleryProjectionQuery query) {
        if (query == null || query.kind() != GalleryKind.IMAGE
                || query.sourceId() != null && !SOURCE_ID.equals(query.sourceId())) {
            return new AdaptedQuery(null, false);
        }
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("sort", query.sortField().name().equals("TITLE") ? "artworkId" : "date");
        filters.put("order", query.sortDirection().name().toLowerCase(Locale.ROOT));
        boolean matches = true;
        for (GalleryFilter filter : query.filters()) {
            if (filter.sourceId() != null && !SOURCE_ID.equals(filter.sourceId())) continue;
            if (filter.mode() != GalleryFilterMode.ANY_OF) { matches = false; continue; }
            String csv = String.join(",", filter.values());
            switch (filter.field()) {
                case AUTHOR -> filters.put("authorIds", csv);
                case TAG -> filters.put("tagIds", csv);
                case AI_STATUS -> filters.put("ai", aiFilter(filter.values()));
                case CONTENT_RATING -> filters.put("r18", ratingFilter(filter.values()));
                case SOURCE -> matches &= filter.values().contains(SOURCE_ID);
                case CONTAINED_MEDIA_KIND -> matches = false;
            }
        }
        int offset;
        try { offset = query.cursor() == null ? 0 : Math.max(0, Integer.parseInt(query.cursor())); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("invalid Pixiv image cursor", e); }
        return new AdaptedQuery(new GalleryQuery(GalleryKind.IMAGE, SOURCE_ID, List.of(), filters,
                offset, query.limit()), matches);
    }

    private static String aiFilter(Set<String> values) {
        if (values.stream().anyMatch("AI"::equalsIgnoreCase)) return "yes";
        if (values.stream().anyMatch("NON_AI"::equalsIgnoreCase)) return "no";
        return "unknown";
    }
    private static String ratingFilter(Set<String> values) {
        if (values.stream().anyMatch("R18G"::equalsIgnoreCase)) return "r18g";
        if (values.stream().anyMatch("R18"::equalsIgnoreCase)) return "r18";
        if (values.stream().anyMatch("SFW"::equalsIgnoreCase)) return "no";
        return "unknown";
    }

    private record AdaptedQuery(GalleryQuery query, boolean matches) { }
}
