package com.example.pixivdownload.downloadtype.web;

import com.example.pixivdownload.downloadtype.queue.ExampleDownloadQueue;
import com.example.pixivdownload.downloadtype.queue.ExampleDownloadQueue.QueueItem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic mock HTTP surface. A production plugin should replace the fixture generator with its own lawful
 * client and blocking download implementation while preserving stable machine-code error responses.
 */
@RestController
@PluginManagedBean
@RequestMapping("/api/example-download")
public final class ExampleDownloadController {

    private static final Pattern INPUT = Pattern.compile(
            "^(?:https://example\\.invalid/work/)?([0-9]{1,18})/?$",
            Pattern.CASE_INSENSITIVE);

    private final ExampleDownloadQueue queue;
    private final RequestOwnerIdentityResolver requestOwnerIdentityResolver;

    public ExampleDownloadController(
            ExampleDownloadQueue queue,
            RequestOwnerIdentityResolver requestOwnerIdentityResolver) {
        this.queue = queue;
        this.requestOwnerIdentityResolver = requestOwnerIdentityResolver;
    }

    @PostMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestBody ResolveRequest request) {
        String id = resolveId(request == null ? null : request.input());
        if (id == null) {
            return error(HttpStatus.BAD_REQUEST, "example.invalid-input", "error.invalid-input");
        }
        return ResponseEntity.ok(new WorkView(
                id,
                "Example " + id,
                canonicalUrl(id),
                "fixture",
                "ready"));
    }

    @PostMapping("/queue")
    public ResponseEntity<?> queue(@RequestBody QueueRequest request, HttpServletRequest httpRequest) {
        String id = resolveId(request == null ? null : request.id());
        if (id == null) {
            return error(HttpStatus.BAD_REQUEST, "example.invalid-input", "error.invalid-input");
        }
        RequestOwnerIdentity identity = requestOwnerIdentityResolver.resolve(httpRequest);
        QueueItem item = queue.complete(id, request.title(), identity);
        return ResponseEntity.ok(new QueueResponse("example.completed", "queue.completed", item));
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<?> status(@PathVariable String id, HttpServletRequest httpRequest) {
        String normalized = resolveId(id);
        if (normalized == null) {
            return error(HttpStatus.BAD_REQUEST, "example.invalid-input", "error.invalid-input");
        }
        RequestOwnerIdentity identity = requestOwnerIdentityResolver.resolve(httpRequest);
        return queue.find(normalized, identity)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "example.not-found", "error.not-found"));
    }

    @GetMapping("/user/{userId}/works")
    public PageView userWorks(
            @PathVariable String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize) {
        return page("user:" + userId, page, pageSize);
    }

    @GetMapping("/search")
    public PageView search(
            @RequestParam String word,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize) {
        return page("search:" + normalizeText(word, "fixture"), page, pageSize);
    }

    @GetMapping("/search/range")
    public RangePageView searchRange(
            @RequestParam String word,
            @RequestParam(defaultValue = "1") int startPage,
            @RequestParam(defaultValue = "1") int endPage,
            @RequestParam(defaultValue = "12") int pageSize) {
        int start = clamp(startPage, 1, 20);
        int end = clamp(endPage, start, 20);
        int effectivePageSize = clamp(pageSize, 1, 24);
        int total = 48;
        int requestedPages = end - start + 1;
        int lastAvailablePage = (int) Math.ceil((double) total / effectivePageSize);
        int actualEnd = Math.min(end, lastAvailablePage);
        List<WorkView> items = new ArrayList<>();
        for (int page = start; page <= actualEnd; page++) {
            items.addAll(page(
                    "search:" + normalizeText(word, "fixture"), page, effectivePageSize).items());
        }
        int fetchedPages = Math.max(0, actualEnd - start + 1);
        int reportedEnd = fetchedPages == 0 ? start : actualEnd;
        return new RangePageView(
                items, total, start, reportedEnd,
                requestedPages, requestedPages, fetchedPages, 0);
    }

    @GetMapping("/series/{seriesId}")
    public SeriesPageView series(
            @PathVariable String seriesId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize) {
        PageView works = page("series:" + seriesId, page, pageSize);
        return new SeriesPageView(
                new SeriesView(seriesId, "Example series " + seriesId, works.total()),
                works.items(),
                works.total(),
                works.page(),
                works.lastPage(),
                works.hasMore(),
                works.nextCursor());
    }

    @GetMapping("/quick")
    public PageView quick(@RequestParam(defaultValue = "12") int pageSize) {
        PageView firstBatch = page("quick:featured", 1, pageSize);
        return new PageView(firstBatch.items(), firstBatch.items().size(), 1, true, false, null);
    }

    @GetMapping("/gallery")
    public GalleryView gallery() {
        List<QueueItem> items = queue.snapshot();
        return new GalleryView(items, items.size());
    }

    private static PageView page(String seed, int rawPage, int rawPageSize) {
        int page = clamp(rawPage, 1, 20);
        int pageSize = clamp(rawPageSize, 1, 24);
        int total = 48;
        int start = (page - 1) * pageSize;
        if (start >= total) {
            return new PageView(List.of(), total, page, true, false, null);
        }
        int count = Math.min(pageSize, total - start);
        long base = 100_000L + Math.floorMod(seed.toLowerCase(Locale.ROOT).hashCode(), 800_000);
        List<WorkView> items = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            String id = Long.toString(base * 100L + start + offset);
            items.add(new WorkView(id, "Example " + id, canonicalUrl(id), seed, "ready"));
        }
        boolean last = start + count >= total;
        return new PageView(
                items, total, page, last, !last, last ? null : Integer.toString(page + 1));
    }

    private static String resolveId(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = INPUT.matcher(raw.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String canonicalUrl(String id) {
        return "https://example.invalid/work/" + id;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String messageKey) {
        return ResponseEntity.status(status).body(new ApiError(code, "example-download:" + messageKey));
    }

    public record ResolveRequest(String input) {
    }

    public record QueueRequest(String id, String title) {
    }

    public record QueueResponse(String code, String messageKey, QueueItem item) {
    }

    public record ApiError(String code, String messageKey) {
    }

    public record WorkView(String id, String title, String url, String source, String status) {
    }

    public record PageView(
            List<WorkView> items,
            int total,
            int page,
            boolean lastPage,
            boolean hasMore,
            String nextCursor
    ) {
        public PageView {
            items = List.copyOf(items);
        }
    }

    public record SeriesView(String id, String title, int total) {
    }

    public record SeriesPageView(
            SeriesView series,
            List<WorkView> items,
            int total,
            int page,
            boolean isLastPage,
            boolean hasMore,
            String nextCursor
    ) {
        public SeriesPageView {
            items = List.copyOf(items);
        }
    }

    public record RangePageView(
            List<WorkView> items,
            int total,
            int startPage,
            int endPage,
            int requestedPages,
            int acceptedPages,
            int fetchedPages,
            int limitPage
    ) {
        public RangePageView {
            items = List.copyOf(items);
        }
    }

    public record GalleryView(List<QueueItem> items, int total) {
        public GalleryView {
            items = List.copyOf(items);
        }
    }
}
