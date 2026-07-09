package top.sywyar.pixivdownload.core.gallery.runtime;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacet;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjection;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Fail-soft projection query broker with a versioned cross-source k-way merge cursor. */
@Component
public class GalleryProjectionBroker {

    private static final String CURSOR_VERSION = "gallery-merge-v1";

    private final GalleryCapabilityRegistry registry;

    public GalleryProjectionBroker(GalleryCapabilityRegistry registry) {
        this.registry = registry;
    }

    public GalleryProjectionPage page(GalleryProjectionQuery query) {
        if (query == null) {
            return new GalleryProjectionPage(List.of(), null, false, List.of(new GalleryDiagnostic(
                    null, null, null, "gallery-query-null", "Gallery projection query must not be null")));
        }
        List<GalleryDiagnostic> diagnostics = new ArrayList<>(registry.snapshot().diagnostics());
        List<Route> routes = routes(query);
        Cursor cursor = decodeCursor(query, routes, query.cursor());
        Comparator<Candidate> comparator = (left, right) -> compare(
                left.projection(), right.projection(), query.sortField(), query.sortDirection());
        PriorityQueue<Candidate> queue = new PriorityQueue<>(comparator);
        Map<String, RouteState> states = new LinkedHashMap<>();

        for (Route route : routes) {
            CursorEntry entry = cursor.entries().getOrDefault(route.sourceId(), CursorEntry.start());
            RouteState state = new RouteState(route, entry.cursor(), entry.exhausted());
            states.put(route.sourceId(), state);
            fetch(state, query, diagnostics).ifPresent(queue::add);
        }

        List<GalleryProjection> merged = new ArrayList<>();
        while (merged.size() < query.limit() && !queue.isEmpty()) {
            Candidate candidate = queue.poll();
            merged.add(candidate.projection());
            RouteState state = states.get(candidate.route().sourceId());
            state.consume(candidate.page());
            fetch(state, query, diagnostics).ifPresent(queue::add);
        }

        boolean hasMore = states.values().stream().anyMatch(state -> !state.exhausted());
        String nextCursor = hasMore ? encodeCursor(query, states) : null;
        return new GalleryProjectionPage(merged, nextCursor, hasMore, diagnostics);
    }

    public GalleryCountResult count(GalleryProjectionQuery query) {
        List<GalleryDiagnostic> diagnostics = new ArrayList<>(registry.snapshot().diagnostics());
        long count = 0;
        for (Route route : routes(query)) {
            try {
                count = Math.addExact(count, route.provider().count(forRoute(query, route, null, query.limit())));
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic(route, query, "gallery-provider-count-failed", failure));
            }
        }
        return new GalleryCountResult(count, diagnostics);
    }

    public GalleryFacetPage facets(GalleryProjectionQuery query) {
        List<GalleryDiagnostic> diagnostics = new ArrayList<>(registry.snapshot().diagnostics());
        List<GalleryFacet> facets = new ArrayList<>();
        for (Route route : routes(query)) {
            try {
                GalleryFacetPage page = route.provider().facets(forRoute(query, route, null, query.limit()));
                if (page == null) {
                    diagnostics.add(diagnostic(route, query, "gallery-provider-null-facet-page", null));
                    continue;
                }
                facets.addAll(page.facets());
                diagnostics.addAll(page.diagnostics());
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic(route, query, "gallery-provider-facets-failed", failure));
            }
        }
        return new GalleryFacetPage(facets, diagnostics);
    }

    private List<Route> routes(GalleryProjectionQuery query) {
        List<Route> routes = new ArrayList<>();
        for (GalleryCapabilityRegistry.RegisteredProjectionProvider registered
                : registry.resolveProjections(query.kind(), query.sourceId())) {
            for (GalleryProjectionDescriptor descriptor : registered.descriptors()) {
                if (descriptor.kind() != query.kind()) {
                    continue;
                }
                if (query.sourceId() != null && !query.sourceId().equals(descriptor.sourceId())) {
                    continue;
                }
                routes.add(new Route(registered.providerId(), descriptor.sourceId(), registered.provider()));
            }
        }
        routes.sort(Comparator.comparing(Route::sourceId));
        return routes;
    }

    private static java.util.Optional<Candidate> fetch(RouteState state,
                                                        GalleryProjectionQuery query,
                                                        List<GalleryDiagnostic> diagnostics) {
        if (state.exhausted()) {
            return java.util.Optional.empty();
        }
        GalleryProjectionQuery providerQuery = forRoute(query, state.route(), state.cursor(), 1);
        try {
            GalleryProjectionPage page = state.route().provider().page(providerQuery);
            if (page == null) {
                diagnostics.add(diagnostic(state.route(), query, "gallery-provider-null-page", null));
                state.exhaust();
                return java.util.Optional.empty();
            }
            diagnostics.addAll(page.diagnostics());
            if (page.projections().isEmpty()) {
                if (page.hasMore()) {
                    diagnostics.add(diagnostic(state.route(), query, "gallery-provider-empty-cursor-page", null));
                }
                state.exhaust();
                return java.util.Optional.empty();
            }
            if (page.projections().size() != 1) {
                diagnostics.add(diagnostic(state.route(), query, "gallery-provider-ignored-page-limit", null));
                state.exhaust();
                return java.util.Optional.empty();
            }
            GalleryProjection projection = page.projections().get(0);
            if (projection.key().kind() != query.kind()
                    || !state.route().sourceId().equals(projection.key().workKey().sourceId())) {
                diagnostics.add(diagnostic(state.route(), query, "gallery-provider-identity-mismatch", null));
                state.exhaust();
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Candidate(state.route(), projection, page));
        } catch (RuntimeException failure) {
            diagnostics.add(diagnostic(state.route(), query, "gallery-provider-page-failed", failure));
            state.exhaust();
            return java.util.Optional.empty();
        }
    }

    private static GalleryProjectionQuery forRoute(GalleryProjectionQuery query, Route route,
                                                    String cursor, int limit) {
        return new GalleryProjectionQuery(query.kind(), route.sourceId(), query.filters(), query.sortField(),
                query.sortDirection(), cursor, limit);
    }

    private static int compare(GalleryProjection left, GalleryProjection right,
                               GallerySortField field, GallerySortDirection direction) {
        int compared;
        if (field == GallerySortField.TITLE) {
            compared = compareValues(left.title(), right.title(), String.CASE_INSENSITIVE_ORDER, direction);
        } else {
            compared = compareValues(instant(left, field), instant(right, field),
                    Comparator.naturalOrder(), direction);
        }
        return compared != 0 ? compared : stableKey(left).compareTo(stableKey(right));
    }

    private static Instant instant(GalleryProjection projection, GallerySortField field) {
        return switch (field) {
            case CREATED_AT -> projection.createdAt();
            case DOWNLOADED_AT -> projection.downloadedAt();
            case UPDATED_AT -> projection.updatedAt();
            case TITLE -> null;
        };
    }

    private static <T> int compareValues(T left, T right, Comparator<T> comparator,
                                         GallerySortDirection direction) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        int compared = comparator.compare(left, right);
        return direction == GallerySortDirection.DESC ? -compared : compared;
    }

    private static String stableKey(GalleryProjection projection) {
        var key = projection.key().workKey();
        return key.sourceId() + '\u0000' + key.sourceWorkNamespace() + '\u0000'
                + key.sourceWorkId() + '\u0000' + projection.key().kind().name();
    }

    private static String encodeCursor(GalleryProjectionQuery query, Map<String, RouteState> states) {
        StringBuilder raw = new StringBuilder(CURSOR_VERSION).append('\n')
                .append(query.kind()).append('\t').append(query.sortField()).append('\t')
                .append(query.sortDirection()).append('\n');
        for (RouteState state : states.values()) {
            raw.append(base64(state.route().sourceId())).append('\t');
            if (state.exhausted()) {
                raw.append('E');
            } else if (state.cursor() == null) {
                raw.append('S');
            } else {
                raw.append('C').append('\t').append(base64(state.cursor()));
            }
            raw.append('\n');
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(GalleryProjectionQuery query, List<Route> routes, String encoded) {
        if (encoded == null) {
            return new Cursor(Map.of());
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] lines = raw.split("\\n");
            if (lines.length < 2 || !CURSOR_VERSION.equals(lines[0])) {
                throw new IllegalArgumentException("unsupported gallery merge cursor version");
            }
            String expected = query.kind() + "\t" + query.sortField() + "\t" + query.sortDirection();
            if (!expected.equals(lines[1])) {
                throw new IllegalArgumentException("gallery merge cursor does not match query ordering");
            }
            Set<String> allowed = routes.stream().map(Route::sourceId).collect(java.util.stream.Collectors.toSet());
            Map<String, CursorEntry> entries = new LinkedHashMap<>();
            for (int i = 2; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                String[] parts = lines[i].split("\\t", -1);
                String sourceId = unbase64(parts[0]);
                if (!allowed.contains(sourceId) || entries.containsKey(sourceId)) {
                    throw new IllegalArgumentException("gallery merge cursor contains unknown source");
                }
                CursorEntry entry = switch (parts[1]) {
                    case "E" -> new CursorEntry(null, true);
                    case "S" -> CursorEntry.start();
                    case "C" -> {
                        if (parts.length != 3) {
                            throw new IllegalArgumentException("invalid gallery provider cursor entry");
                        }
                        yield new CursorEntry(unbase64(parts[2]), false);
                    }
                    default -> throw new IllegalArgumentException("invalid gallery provider cursor state");
                };
                entries.put(sourceId, entry);
            }
            return new Cursor(entries);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid gallery merge cursor", failure);
        }
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unbase64(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static GalleryDiagnostic diagnostic(Route route, GalleryProjectionQuery query,
                                                  String code, RuntimeException failure) {
        String message = failure == null ? code : failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return new GalleryDiagnostic(route.providerId(), route.sourceId(), query.kind(), code, message);
    }

    private record Route(String providerId, String sourceId, GalleryProjectionProvider provider) {
    }

    private record Candidate(Route route, GalleryProjection projection, GalleryProjectionPage page) {
    }

    private record Cursor(Map<String, CursorEntry> entries) {
        Cursor {
            entries = Map.copyOf(entries);
        }
    }

    private record CursorEntry(String cursor, boolean exhausted) {
        static CursorEntry start() {
            return new CursorEntry(null, false);
        }
    }

    private static final class RouteState {
        private final Route route;
        private String cursor;
        private boolean exhausted;

        private RouteState(Route route, String cursor, boolean exhausted) {
            this.route = route;
            this.cursor = cursor;
            this.exhausted = exhausted;
        }

        Route route() {
            return route;
        }

        String cursor() {
            return cursor;
        }

        boolean exhausted() {
            return exhausted;
        }

        void consume(GalleryProjectionPage page) {
            if (page.hasMore()) {
                cursor = page.nextCursor();
            } else {
                exhaust();
            }
        }

        void exhaust() {
            exhausted = true;
            cursor = null;
        }
    }
}
