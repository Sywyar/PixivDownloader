package top.sywyar.pixivdownload.douyin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjection;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryPage;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryQuery;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;
import top.sywyar.pixivdownload.douyin.gallery.DouyinGalleryDataProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Source-owned read API for the independent Douyin gallery and work detail pages. */
@RestController
@RequestMapping("/api/douyin/gallery")
@PluginManagedBean
public class DouyinGalleryController {

    private static final int DEFAULT_LIMIT = 48;
    private static final int MAX_LIMIT = 100;
    private static final List<String> IMAGE_MEDIA = List.of("IMAGE");
    private static final List<String> VIDEO_MEDIA = List.of("VIDEO", "LIVE_PHOTO_VIDEO");

    private final DouyinHistoryService historyService;
    private final DouyinGalleryDataProvider dataProvider;

    public DouyinGalleryController(DouyinHistoryService historyService,
                                   DouyinGalleryDataProvider dataProvider) {
        this.historyService = historyService;
        this.dataProvider = dataProvider;
    }

    @GetMapping("/projections")
    public ResponseEntity<?> projections(
            @RequestParam(defaultValue = "ALL") String kind,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "48") int limit,
            @RequestParam(required = false) String search) {
        Category category = Category.parse(kind);
        Integer offset = parseCursor(cursor);
        if (category == null || offset == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid-gallery-query"));
        }
        int pageSize = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        DouyinHistoryPage page = historyService.search(new DouyinHistoryQuery(
                offset, pageSize, "time", "desc", search, List.of(), category.mediaTypes()));
        List<GalleryProjection> cards = page.works().stream()
                .map(work -> dataProvider.projection(work, category.kind(dataProvider, work)))
                .toList();
        int nextOffset = offset + cards.size();
        boolean hasMore = nextOffset < page.total();
        return ResponseEntity.ok(new GalleryProjectionPage(
                cards, hasMore ? String.valueOf(nextOffset) : null, hasMore, List.of()));
    }

    @GetMapping("/works/{workId}")
    public ResponseEntity<WorkResponse> work(@PathVariable String workId) {
        GalleryWorkKey key = new GalleryWorkKey(
                DouyinGalleryDataProvider.SOURCE_ID,
                DouyinGalleryDataProvider.WORK_NAMESPACE,
                workId);
        return dataProvider.find(key)
                .map(work -> ResponseEntity.ok(new WorkResponse(work)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static Integer parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor));
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private enum Category {
        ALL(List.of()),
        IMAGE(IMAGE_MEDIA),
        VIDEO(VIDEO_MEDIA);

        private final List<String> mediaTypes;

        Category(List<String> mediaTypes) {
            this.mediaTypes = mediaTypes;
        }

        static Category parse(String value) {
            try {
                return Category.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                return null;
            }
        }

        List<String> mediaTypes() {
            return mediaTypes;
        }

        GalleryKind kind(DouyinGalleryDataProvider provider, DouyinWorkRecord work) {
            return switch (this) {
                case IMAGE -> GalleryKind.IMAGE;
                case VIDEO -> GalleryKind.VIDEO;
                case ALL -> provider.primaryKind(work);
            };
        }
    }

    public record WorkResponse(GalleryWork work) { }
}
