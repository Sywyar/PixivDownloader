package top.sywyar.pixivdownload.gallery;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilter;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterField;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterMode;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryProjectionBroker;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryWorkBroker;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/gallery/unified")
@PluginManagedBean
public class UnifiedGalleryController {

    private final GalleryCapabilityRegistry registry;
    private final GalleryProjectionBroker projectionBroker;
    private final GalleryWorkBroker workBroker;
    private final SetupService setupService;

    public UnifiedGalleryController(GalleryCapabilityRegistry registry,
                                    GalleryProjectionBroker projectionBroker,
                                    GalleryWorkBroker workBroker,
                                    SetupService setupService) {
        this.registry = registry;
        this.projectionBroker = projectionBroker;
        this.workBroker = workBroker;
        this.setupService = setupService;
    }

    @GetMapping("/descriptors")
    public DescriptorResponse descriptors(HttpServletRequest request) {
        Set<GalleryDataAccess> access = access(request);
        var snapshot = registry.snapshot();
        return new DescriptorResponse(
                snapshot.projections().stream().filter(item -> access.contains(item.dataAccess())).toList(),
                snapshot.works().stream().filter(item -> access.contains(item.dataAccess())).toList(),
                snapshot.diagnostics());
    }

    @GetMapping("/projections")
    public Object projections(@RequestParam GalleryKind kind,
                              @RequestParam(required = false) String sourceId,
                              @RequestParam(required = false) List<String> author,
                              @RequestParam(required = false) List<String> tag,
                              @RequestParam(required = false) List<String> ai,
                              @RequestParam(required = false) List<String> rating,
                              @RequestParam(required = false) List<String> media,
                              @RequestParam(defaultValue = "DOWNLOADED_AT") GallerySortField sort,
                              @RequestParam(defaultValue = "DESC") GallerySortDirection direction,
                              @RequestParam(required = false) String cursor,
                              @RequestParam(defaultValue = "50") int limit,
                              HttpServletRequest request) {
        return projectionBroker.page(query(kind, sourceId, author, tag, ai, rating, media,
                sort, direction, cursor, limit), access(request));
    }

    @GetMapping("/count")
    public Object count(@RequestParam GalleryKind kind,
                        @RequestParam(required = false) String sourceId,
                        @RequestParam(required = false) List<String> author,
                        @RequestParam(required = false) List<String> tag,
                        @RequestParam(required = false) List<String> ai,
                        @RequestParam(required = false) List<String> rating,
                        @RequestParam(required = false) List<String> media,
                        HttpServletRequest request) {
        return projectionBroker.count(query(kind, sourceId, author, tag, ai, rating, media,
                GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC, null, 1), access(request));
    }

    @GetMapping("/facets")
    public Object facets(@RequestParam GalleryKind kind,
                         @RequestParam(required = false) String sourceId,
                         @RequestParam(required = false) List<String> author,
                         @RequestParam(required = false) List<String> tag,
                         @RequestParam(required = false) List<String> ai,
                         @RequestParam(required = false) List<String> rating,
                         @RequestParam(required = false) List<String> media,
                         HttpServletRequest request) {
        return projectionBroker.facets(query(kind, sourceId, author, tag, ai, rating, media,
                GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC, null, 500), access(request));
    }

    @GetMapping("/works/{sourceId}/{namespace}/{workId}")
    public ResponseEntity<?> work(@PathVariable String sourceId,
                                  @PathVariable String namespace,
                                  @PathVariable String workId,
                                  HttpServletRequest request) {
        var result = workBroker.find(new GalleryWorkKey(sourceId, namespace, workId), access(request));
        return result.work().<ResponseEntity<?>>map(work -> ResponseEntity.ok(
                new WorkResponse(work, result.diagnostics())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Set<GalleryDataAccess> access(HttpServletRequest request) {
        return setupService.hasAdminScope(request)
                ? Set.of(GalleryDataAccess.SHARED, GalleryDataAccess.ADMIN_ONLY)
                : Set.of(GalleryDataAccess.SHARED);
    }

    private static GalleryProjectionQuery query(GalleryKind kind, String sourceId,
                                                List<String> authors, List<String> tags,
                                                List<String> ai, List<String> ratings,
                                                List<String> media, GallerySortField sort,
                                                GallerySortDirection direction, String cursor, int limit) {
        List<GalleryFilter> filters = new ArrayList<>();
        add(filters, GalleryFilterField.AUTHOR, sourceId, authors);
        add(filters, GalleryFilterField.TAG, sourceId, tags);
        add(filters, GalleryFilterField.AI_STATUS, null, ai);
        add(filters, GalleryFilterField.CONTENT_RATING, null, ratings);
        add(filters, GalleryFilterField.CONTAINED_MEDIA_KIND, null, media);
        if (sourceId != null && !sourceId.isBlank()) {
            add(filters, GalleryFilterField.SOURCE, null, List.of(sourceId));
        }
        return new GalleryProjectionQuery(kind, sourceId, filters, sort, direction, cursor, limit);
    }

    private static void add(List<GalleryFilter> filters, GalleryFilterField field,
                            String sourceId, List<String> values) {
        if (values == null || values.isEmpty()) return;
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) continue;
            for (String item : value.split(",")) {
                if (!item.isBlank()) normalized.add(item.trim());
            }
        }
        if (!normalized.isEmpty()) filters.add(new GalleryFilter(
                field, GalleryFilterMode.ANY_OF, sourceId, normalized));
    }

    public record DescriptorResponse(
            List<top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor> projections,
            List<top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor> works,
            List<top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic> diagnostics) { }

    public record WorkResponse(
            top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork work,
            List<top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic> diagnostics) { }
}
