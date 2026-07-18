package top.sywyar.pixivdownload.novelgallery;

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
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
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
public class PixivNovelGalleryCapabilityProvider implements GalleryProjectionProvider, GalleryWorkProvider {

    static final String PROVIDER_ID = "pixiv-novel-capability";
    static final String SOURCE_ID = "pixiv";
    static final String WORK_NAMESPACE = "novel";

    private final PixivNovelGalleryDataProvider novelProvider;
    private final WorkMetadataRepository metadataRepository;
    private final NovelDatabase novelDatabase;

    public PixivNovelGalleryCapabilityProvider(PixivNovelGalleryDataProvider novelProvider,
                                               WorkMetadataRepository metadataRepository,
                                               NovelDatabase novelDatabase) {
        this.novelProvider = novelProvider;
        this.metadataRepository = metadataRepository;
        this.novelDatabase = novelDatabase;
    }

    @Override public String providerId() { return PROVIDER_ID; }

    @Override
    public List<GalleryProjectionDescriptor> projections() {
        return List.of(new GalleryProjectionDescriptor(SOURCE_ID, GalleryKind.NOVEL,
                "novel-gallery", "source.pixiv", 20, Map.of(
                GalleryFilterField.AUTHOR, GalleryFilterCapability.supported(),
                GalleryFilterField.TAG, GalleryFilterCapability.supported(),
                GalleryFilterField.AI_STATUS, GalleryFilterCapability.supported(),
                GalleryFilterField.CONTENT_RATING, GalleryFilterCapability.supported(),
                GalleryFilterField.SOURCE, GalleryFilterCapability.constant(SOURCE_ID),
                GalleryFilterField.CONTAINED_MEDIA_KIND, GalleryFilterCapability.constant("TEXT"))));
    }

    @Override public List<GalleryWorkDescriptor> works() {
        return List.of(new GalleryWorkDescriptor(SOURCE_ID, WORK_NAMESPACE));
    }

    @Override
    public GalleryProjectionPage page(GalleryProjectionQuery query) {
        AdaptedQuery adapted = adapt(query);
        if (!adapted.matches()) return GalleryProjectionPage.empty();
        GalleryPage page = novelProvider.query(adapted.query());
        List<Long> ids = page.items().stream().map(item -> Long.parseLong(item.ref().workId())).toList();
        Map<Long, WorkMetadata> metadata = new LinkedHashMap<>();
        metadataRepository.findAll(WorkType.NOVEL, ids).forEach(meta -> metadata.put(meta.workId(), meta));
        List<GalleryProjection> projections = page.items().stream()
                .map(item -> projection(item, metadata.get(Long.parseLong(item.ref().workId())))).toList();
        int next = adapted.query().offset() + projections.size();
        return new GalleryProjectionPage(projections, page.hasMore() ? String.valueOf(next) : null,
                page.hasMore(), page.diagnostics());
    }

    @Override public long count(GalleryProjectionQuery query) {
        AdaptedQuery adapted = adapt(query);
        return adapted.matches() ? novelProvider.query(new GalleryQuery(
                GalleryKind.NOVEL, SOURCE_ID, List.of(), adapted.query().filters(), 0, 1)).total() : 0;
    }

    @Override public GalleryFacetPage facets(GalleryProjectionQuery query) {
        AdaptedQuery adapted = adapt(query);
        return adapted.matches() ? novelProvider.facets(adapted.query()) : GalleryFacetPage.empty();
    }

    @Override
    public Optional<GalleryWork> find(GalleryWorkKey key) {
        if (!SOURCE_ID.equals(key.sourceId()) || !WORK_NAMESPACE.equals(key.sourceWorkNamespace())) return Optional.empty();
        Long id = parseId(key.sourceWorkId());
        return id == null ? Optional.empty() : metadataRepository.find(WorkType.NOVEL, id)
                .map(meta -> work(key, meta, novelDatabase.getNovel(id)));
    }

    private static GalleryProjection projection(GalleryItem item, WorkMetadata meta) {
        GalleryWorkKey key = new GalleryWorkKey(SOURCE_ID, WORK_NAMESPACE, item.ref().workId());
        Set<GalleryMediaKind> kinds = meta != null && meta.novel().coverExt() != null
                ? Set.of(GalleryMediaKind.TEXT, GalleryMediaKind.COVER) : Set.of(GalleryMediaKind.TEXT);
        return new GalleryProjection(new GalleryProjectionKey(key, GalleryKind.NOVEL), item.title(),
                meta == null ? null : meta.description(), item.thumbnailUrl(), meta == null ? null : actor(meta),
                meta == null ? List.of() : tags(meta), meta == null ? null : instant(meta.uploadTime()),
                meta == null ? null : instant(meta.downloadTime()), null, kinds,
                meta == null ? GalleryContentRating.UNKNOWN : rating(meta.xRestrict()),
                meta == null ? GalleryAiStatus.UNKNOWN : ai(meta.isAi()), "text", item.attributes());
    }

    private static GalleryWork work(GalleryWorkKey key, WorkMetadata meta, NovelRecord record) {
        List<GalleryMediaAsset> media = new ArrayList<>();
        media.add(new GalleryMediaAsset(new GalleryMediaKey(key, "text"), GalleryMediaKind.TEXT,
                null, null, "text/plain", record == null ? null : record.rawContent(), Map.of()));
        if (meta.novel().coverExt() != null) {
            String url = "/api/gallery/novel/" + meta.workId() + "/cover";
            media.add(new GalleryMediaAsset(new GalleryMediaKey(key, "cover"), GalleryMediaKind.COVER,
                    url, url, null, null, Map.of()));
        }
        for (String imageId : meta.novel().embeddedImageIds()) {
            media.add(new GalleryMediaAsset(new GalleryMediaKey(key, "embedded-" + imageId),
                    GalleryMediaKind.IMAGE, "/api/gallery/novel/" + meta.workId() + "/image/" + imageId,
                    null, null, null, Map.of("sourceImageId", imageId)));
        }
        return new GalleryWork(key, meta.title(), meta.description(), actor(meta), tags(meta),
                instant(meta.uploadTime()), instant(meta.downloadTime()), null,
                rating(meta.xRestrict()), ai(meta.isAi()), media, Map.of());
    }

    private static GalleryActor actor(WorkMetadata meta) { return meta.authorId() == null ? null
            : new GalleryActor(SOURCE_ID, String.valueOf(meta.authorId()), meta.authorName(), null); }
    private static List<GalleryTag> tags(WorkMetadata meta) { return meta.tags().stream()
            .filter(tag -> tag.tagId() != null)
            .map(tag -> new GalleryTag(SOURCE_ID, String.valueOf(tag.tagId()), tag.name())).toList(); }
    private static GalleryContentRating rating(Integer v) { return v == null ? GalleryContentRating.UNKNOWN
            : v == 1 ? GalleryContentRating.R18 : v == 2 ? GalleryContentRating.R18G : GalleryContentRating.SFW; }
    private static GalleryAiStatus ai(Boolean v) { return v == null ? GalleryAiStatus.UNKNOWN
            : v ? GalleryAiStatus.AI : GalleryAiStatus.NON_AI; }
    private static Instant instant(long v) { return v <= 0 ? null : Instant.ofEpochMilli(v); }
    private static Instant instant(Long v) { return v == null ? null : instant(v.longValue()); }
    private static Long parseId(String v) { try { long id = Long.parseLong(v); return id > 0 ? id : null; }
        catch (NumberFormatException ignored) { return null; } }

    private static AdaptedQuery adapt(GalleryProjectionQuery query) {
        if (query == null || query.kind() != GalleryKind.NOVEL
                || query.sourceId() != null && !SOURCE_ID.equals(query.sourceId())) return new AdaptedQuery(null, false);
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("sort", query.sortField().name().equals("TITLE") ? "novelId" : "date");
        filters.put("order", query.sortDirection().name().toLowerCase(Locale.ROOT));
        boolean matches = true;
        for (GalleryFilter filter : query.filters()) {
            if (filter.sourceId() != null && !SOURCE_ID.equals(filter.sourceId())) continue;
            if (filter.mode() != GalleryFilterMode.ANY_OF) { matches = false; continue; }
            String csv = String.join(",", filter.values());
            switch (filter.field()) {
                case AUTHOR -> filters.put("authorIds", csv);
                case TAG -> filters.put("tagIds", csv);
                case AI_STATUS -> filters.put("ai", filter.values().stream().anyMatch("AI"::equalsIgnoreCase) ? "yes"
                        : filter.values().stream().anyMatch("NON_AI"::equalsIgnoreCase) ? "no" : "unknown");
                case CONTENT_RATING -> filters.put("r18", filter.values().stream().anyMatch("R18G"::equalsIgnoreCase)
                        ? "r18g" : filter.values().stream().anyMatch("R18"::equalsIgnoreCase) ? "r18"
                        : filter.values().stream().anyMatch("SFW"::equalsIgnoreCase) ? "no" : "unknown");
                case SOURCE -> matches &= filter.values().contains(SOURCE_ID);
                case CONTAINED_MEDIA_KIND -> matches &= filter.values().stream().anyMatch("TEXT"::equalsIgnoreCase);
            }
        }
        int offset;
        try { offset = query.cursor() == null ? 0 : Math.max(0, Integer.parseInt(query.cursor())); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("invalid Pixiv novel cursor", e); }
        return new AdaptedQuery(new GalleryQuery(GalleryKind.NOVEL, SOURCE_ID, List.of(), filters,
                offset, query.limit()), matches);
    }

    private record AdaptedQuery(GalleryQuery query, boolean matches) { }
}
