package top.sywyar.pixivdownload.gallery;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.sywyar.pixivdownload.download.response.DownloadedResponse;
import top.sywyar.pixivdownload.download.response.PagedHistoryResponse;

import java.util.*;

@RestController
@RequestMapping("/api/gallery")
@RequiredArgsConstructor
public class GalleryController {

    private final GalleryService galleryService;

    @GetMapping("/artworks")
    public PagedHistoryResponse listArtworks(
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @RequestParam(required = false, defaultValue = "24") Integer size,
            @RequestParam(required = false, defaultValue = "date") String sort,
            @RequestParam(required = false, defaultValue = "desc") String order,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "any") String r18,
            @RequestParam(required = false, defaultValue = "any") String ai,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) String collectionIds,
            @RequestParam(required = false) String tagIds,
            @RequestParam(required = false) String notTagIds,
            @RequestParam(required = false) String orTagIds,
            @RequestParam(required = false) String authorIds,
            @RequestParam(required = false) String notAuthorIds,
            @RequestParam(required = false) String orAuthorIds,
            @RequestParam(required = false) Long authorId) {

        List<Long> requiredAuthorIds = parseLongList(authorIds);
        if (authorId != null && authorId > 0) {
            if (requiredAuthorIds == null) requiredAuthorIds = new ArrayList<>();
            if (!requiredAuthorIds.contains(authorId)) requiredAuthorIds.add(authorId);
        }
        GalleryQuery query = GalleryQuery.normalize(
                page, size, sort, order, search, r18, ai,
                parseFormats(format),
                parseLongList(collectionIds),
                parseLongList(tagIds),
                parseLongList(notTagIds),
                parseLongList(orTagIds),
                requiredAuthorIds,
                parseLongList(notAuthorIds),
                parseLongList(orAuthorIds));
        return galleryService.query(query);
    }

    @GetMapping("/tags")
    public Map<String, Object> listTags(
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "500") int limit) {
        List<GalleryRepository.TagOption> tags = galleryService.listTags(search, limit);
        return Map.of("tags", tags);
    }

    @GetMapping("/tags/lookup")
    public ResponseEntity<GalleryRepository.TagOption> findTag(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String translatedName) {
        GalleryRepository.TagOption tag = galleryService.findTag(name, translatedName);
        return tag == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(tag);
    }

    @GetMapping("/artwork/{artworkId}")
    public ResponseEntity<DownloadedResponse> artwork(@PathVariable long artworkId) {
        DownloadedResponse resp = galleryService.findArtwork(artworkId);
        return resp == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(resp);
    }

    @GetMapping("/artwork/{artworkId}/related")
    public ResponseEntity<List<DownloadedResponse>> related(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "12") int limit) {
        return ResponseEntity.ok(galleryService.related(artworkId, limit));
    }

    @GetMapping("/artwork/{artworkId}/by-author")
    public ResponseEntity<List<DownloadedResponse>> byAuthor(
            @PathVariable long artworkId,
            @RequestParam(defaultValue = "12") int limit) {
        return ResponseEntity.ok(galleryService.byAuthor(artworkId, limit));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private List<String> parseFormats(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> allowed = Set.of("jpg", "jpeg", "png", "gif", "webp");
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String v = part.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty() && allowed.contains(v)) out.add(v);
        }
        return out.isEmpty() ? null : out;
    }

    private List<Long> parseLongList(String csv) {
        if (csv == null || csv.isBlank()) return null;
        List<Long> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignore) {
                // 忽略非法项
            }
        }
        return out.isEmpty() ? null : out;
    }
}
