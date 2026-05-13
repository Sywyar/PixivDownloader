package top.sywyar.pixivdownload.series;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.gallery.GalleryRepository;
import top.sywyar.pixivdownload.gallery.GuestRestriction;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/series")
@RequiredArgsConstructor
public class MangaSeriesController {

    private final MangaSeriesService mangaSeriesService;
    private final GalleryRepository galleryRepository;

    @GetMapping
    public List<MangaSeries> getSeries(HttpServletRequest httpRequest) {
        Set<Long> filter = resolveGuestFilter(httpRequest);
        return mangaSeriesService.getAllSeries(filter);
    }

    @GetMapping("/paged")
    public MangaSeriesService.PagedSeries getPagedSeries(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "title") String sort,
            HttpServletRequest httpRequest) {
        Set<Long> filter = resolveGuestFilter(httpRequest);
        return mangaSeriesService.getPagedSeriesWithArtworks(page, size, search, sort, filter);
    }

    @GetMapping("/{seriesId}")
    public ResponseEntity<MangaSeriesDetail> getSeriesDetail(
            @PathVariable long seriesId,
            HttpServletRequest httpRequest) {
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        Set<Long> filter = resolveGuestFilter(session);
        if (filter != null && !filter.contains(seriesId)) {
            return ResponseEntity.notFound().build();
        }
        MangaSeriesDetail detail = mangaSeriesService.getSeriesDetail(seriesId);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        // 访客可见的章节数 ≠ 系列章节总数；返回前者，避免泄漏访客看不到的章节存在性。
        if (session != null) {
            long visibleCount = galleryRepository.countArtworksInSeries(seriesId, GuestRestriction.from(session));
            detail = new MangaSeriesDetail(
                    detail.seriesId(),
                    detail.title(),
                    detail.authorId(),
                    detail.authorName(),
                    visibleCount,
                    detail.updatedTime(),
                    detail.description(),
                    detail.coverExt(),
                    detail.coverFolder()
            );
        }
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/{seriesId}/cover")
    public ResponseEntity<byte[]> getSeriesCover(@PathVariable long seriesId,
                                                 HttpServletRequest httpRequest) throws IOException {
        Set<Long> filter = resolveGuestFilter(httpRequest);
        if (filter != null && !filter.contains(seriesId)) {
            return ResponseEntity.notFound().build();
        }
        MangaSeries series = mangaSeriesService.getSeries(seriesId);
        if (series == null || series.coverExt() == null || series.coverExt().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        // cover_folder 写入时已 toAbsolutePath().normalize()；缺省回退到当前 rootFolder 下的标准目录。
        Path folder = (series.coverFolder() != null && !series.coverFolder().isBlank())
                ? Path.of(series.coverFolder())
                : mangaSeriesService.resolveCoverDir(seriesId);
        Path file = folder.resolve("cover." + series.coverExt());
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mimeFor(series.coverExt())));
        headers.setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
        return ResponseEntity.ok().headers(headers).body(Files.readAllBytes(file));
    }

    /**
     * 从 Pixiv AJAX 拉取系列封面/简介并入库；要求 admin/solo 登录态（访客邀请会话仅放行 GET）。
     * 浏览器需带上 {@code X-Pixiv-Cookie} 头供后端访问 Pixiv。
     */
    @PostMapping("/{seriesId}/refresh")
    public ResponseEntity<MangaSeries> refreshSeries(
            @PathVariable long seriesId,
            @RequestHeader(value = "X-Pixiv-Cookie", required = false) String cookie) {
        MangaSeries refreshed = mangaSeriesService.refreshFromPixiv(seriesId, cookie);
        if (refreshed == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(refreshed);
    }

    private static String mimeFor(String ext) {
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "application/octet-stream";
        };
    }

    private Set<Long> resolveGuestFilter(HttpServletRequest httpRequest) {
        return resolveGuestFilter(GuestAccessGuard.extractSession(httpRequest));
    }

    private Set<Long> resolveGuestFilter(GuestInviteSession session) {
        if (session == null) return null;
        return galleryRepository.findVisibleSeriesIds(GuestRestriction.from(session));
    }
}
