package top.sywyar.pixivdownload.gallery;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendScope;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilter;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterField;
import top.sywyar.pixivdownload.core.gallery.query.GalleryFilterMode;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCountResult;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryProjectionBroker;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryWorkBroker;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code /pixiv-gallery.html} 使用的只读兼容接口。
 *
 * @deprecated 自 2026-07-17 起停止扩展；新代码不得依赖此路径，现有行为保留到主画廊内部调用完成迁移。
 */
@RestController
@RequestMapping("/api/gallery/unified")
@PluginManagedBean
@Deprecated(since = "1.0.0", forRemoval = false)
public class UnifiedGalleryController {

    static final String DEPRECATION_HEADER_VALUE = "@1784246400";
    private static final Pattern PUBLIC_ID = Pattern.compile("[a-z][a-z0-9-]{0,79}");
    private static final Pattern PUBLIC_CODE = Pattern.compile("[a-z][a-z0-9-]{0,95}");

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

    @ModelAttribute
    void addDeprecationHeader(HttpServletResponse response) {
        response.setHeader("Deprecation", DEPRECATION_HEADER_VALUE);
    }

    @GetMapping("/descriptors")
    public DescriptorResponse descriptors(HttpServletRequest request) {
        Set<GalleryDataAccess> access = access(request);
        var snapshot = registry.snapshot();
        var projections = snapshot.projections().stream()
                .filter(item -> access.contains(item.dataAccess())).toList();
        var works = snapshot.works().stream()
                .filter(item -> access.contains(item.dataAccess())).toList();
        return new DescriptorResponse(
                snapshot.generation(),
                projections,
                works,
                visibleFrontends(snapshot, access),
                access.contains(GalleryDataAccess.ADMIN_ONLY)
                        ? publicDiagnostics(snapshot.diagnostics()) : List.of());
    }

    @GetMapping("/projections")
    public GalleryProjectionPage projections(@RequestParam GalleryKind kind,
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
        GalleryProjectionPage page = projectionBroker.page(query(kind, sourceId, author, tag, ai, rating, media,
                sort, direction, cursor, limit), access(request));
        return new GalleryProjectionPage(page.projections(), page.nextCursor(), page.hasMore(),
                publicDiagnostics(page.diagnostics()));
    }

    @GetMapping("/count")
    public GalleryCountResult count(@RequestParam GalleryKind kind,
                        @RequestParam(required = false) String sourceId,
                        @RequestParam(required = false) List<String> author,
                        @RequestParam(required = false) List<String> tag,
                        @RequestParam(required = false) List<String> ai,
                        @RequestParam(required = false) List<String> rating,
                        @RequestParam(required = false) List<String> media,
                        HttpServletRequest request) {
        GalleryCountResult result = projectionBroker.count(query(kind, sourceId, author, tag, ai, rating, media,
                GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC, null, 1), access(request));
        return new GalleryCountResult(result.count(), publicDiagnostics(result.diagnostics()));
    }

    @GetMapping("/facets")
    public GalleryFacetPage facets(@RequestParam GalleryKind kind,
                         @RequestParam(required = false) String sourceId,
                         @RequestParam(required = false) List<String> author,
                         @RequestParam(required = false) List<String> tag,
                         @RequestParam(required = false) List<String> ai,
                         @RequestParam(required = false) List<String> rating,
                         @RequestParam(required = false) List<String> media,
                         HttpServletRequest request) {
        GalleryFacetPage result = projectionBroker.facets(query(kind, sourceId, author, tag, ai, rating, media,
                GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC, null, 500), access(request));
        return new GalleryFacetPage(result.facets(), publicDiagnostics(result.diagnostics()));
    }

    @GetMapping("/works/{sourceId}/{namespace}/{workId}")
    public ResponseEntity<?> work(@PathVariable String sourceId,
                                  @PathVariable String namespace,
                                  @PathVariable String workId,
                                  HttpServletRequest request) {
        var result = workBroker.find(new GalleryWorkKey(sourceId, namespace, workId), access(request));
        return result.work().<ResponseEntity<?>>map(work -> ResponseEntity.ok(
                new WorkResponse(work, publicDiagnostics(result.diagnostics()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Set<GalleryDataAccess> access(HttpServletRequest request) {
        return setupService.hasAdminScope(request)
                ? Set.of(GalleryDataAccess.SHARED, GalleryDataAccess.ADMIN_ONLY)
                : Set.of(GalleryDataAccess.SHARED);
    }

    private static List<GalleryFrontendContribution> visibleFrontends(
            GalleryCapabilityRegistry.Snapshot snapshot,
            Set<GalleryDataAccess> access) {
        return snapshot.frontendContributions().stream()
                .filter(frontend -> isFrontendVisible(frontend, snapshot, access))
                .map(GalleryCapabilityRegistry.RegisteredFrontendContribution::contribution)
                .toList();
    }

    private static boolean isFrontendVisible(
            GalleryCapabilityRegistry.RegisteredFrontendContribution frontend,
            GalleryCapabilityRegistry.Snapshot snapshot,
            Set<GalleryDataAccess> access) {
        String owner = frontend.ownerPluginId();
        GalleryFrontendScope scope = frontend.contribution().scope();
        var projections = snapshot.projectionProviders().stream()
                .filter(provider -> owner.equals(provider.ownerPluginId()))
                .flatMap(provider -> provider.descriptors().stream())
                .filter(descriptor -> matches(scope.sourceIds(), descriptor.sourceId()))
                .filter(descriptor -> matches(scope.galleryKinds(), descriptor.kind()))
                .toList();
        var works = snapshot.workProviders().stream()
                .filter(provider -> owner.equals(provider.ownerPluginId()))
                .flatMap(provider -> provider.descriptors().stream())
                .filter(descriptor -> matches(scope.sourceIds(), descriptor.sourceId()))
                .filter(descriptor -> matches(
                        scope.sourceWorkNamespaces(), descriptor.sourceWorkNamespace()))
                .toList();

        if (!provesScope(scope, projections, works)) {
            return false;
        }
        Set<GalleryDataAccess> authoritativeAccess = new LinkedHashSet<>();
        projections.forEach(descriptor -> authoritativeAccess.add(descriptor.dataAccess()));
        works.forEach(descriptor -> authoritativeAccess.add(descriptor.dataAccess()));
        return !authoritativeAccess.isEmpty() && access.containsAll(authoritativeAccess);
    }

    private static boolean provesScope(
            GalleryFrontendScope scope,
            List<top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor> projections,
            List<top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor> works) {
        if (!scope.galleryKinds().isEmpty()) {
            for (GalleryKind kind : scope.galleryKinds()) {
                if (scope.sourceIds().isEmpty()) {
                    if (projections.stream().noneMatch(descriptor -> descriptor.kind() == kind)) {
                        return false;
                    }
                } else {
                    for (String sourceId : scope.sourceIds()) {
                        if (projections.stream().noneMatch(descriptor ->
                                descriptor.sourceId().equals(sourceId) && descriptor.kind() == kind)) {
                            return false;
                        }
                    }
                }
            }
        }
        if (!scope.sourceWorkNamespaces().isEmpty()) {
            for (String namespace : scope.sourceWorkNamespaces()) {
                if (scope.sourceIds().isEmpty()) {
                    if (works.stream().noneMatch(descriptor ->
                            descriptor.sourceWorkNamespace().equals(namespace))) {
                        return false;
                    }
                } else {
                    for (String sourceId : scope.sourceIds()) {
                        if (works.stream().noneMatch(descriptor ->
                                descriptor.sourceId().equals(sourceId)
                                        && descriptor.sourceWorkNamespace().equals(namespace))) {
                            return false;
                        }
                    }
                }
            }
        }
        if (scope.galleryKinds().isEmpty() && scope.sourceWorkNamespaces().isEmpty()
                && !scope.sourceIds().isEmpty()) {
            for (String sourceId : scope.sourceIds()) {
                boolean projected = projections.stream()
                        .anyMatch(descriptor -> descriptor.sourceId().equals(sourceId));
                boolean resolved = works.stream()
                        .anyMatch(descriptor -> descriptor.sourceId().equals(sourceId));
                if (!projected && !resolved) {
                    return false;
                }
            }
        }
        return !projections.isEmpty() || !works.isEmpty();
    }

    private static <T> boolean matches(Set<T> expected, T actual) {
        return expected.isEmpty() || expected.contains(actual);
    }

    private static List<GalleryDiagnostic> publicDiagnostics(List<GalleryDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return List.of();
        return diagnostics.stream().filter(java.util.Objects::nonNull).map(item ->
                new GalleryDiagnostic(
                        safeMachineValue(item.providerId(), PUBLIC_ID),
                        safeMachineValue(item.sourceId(), PUBLIC_ID),
                        item.kind(),
                        java.util.Objects.requireNonNullElse(
                                safeMachineValue(item.code(), PUBLIC_CODE), "gallery-diagnostic"),
                        null)).toList();
    }

    private static String safeMachineValue(String value, Pattern pattern) {
        if (value == null) return null;
        String normalized = value.trim();
        return pattern.matcher(normalized).matches() ? normalized : null;
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
            long generation,
            List<top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor> projections,
            List<top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor> works,
            List<GalleryFrontendContribution> frontends,
            List<GalleryDiagnostic> diagnostics) { }

    public record WorkResponse(
            top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork work,
            List<GalleryDiagnostic> diagnostics) { }
}
