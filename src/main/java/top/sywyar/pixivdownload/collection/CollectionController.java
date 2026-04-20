package top.sywyar.pixivdownload.collection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.sywyar.pixivdownload.collection.request.CollectionCreateRequest;
import top.sywyar.pixivdownload.collection.request.CollectionRenameRequest;
import top.sywyar.pixivdownload.collection.response.CollectionListResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/collections")
@RequiredArgsConstructor
@Slf4j
public class CollectionController {

    private final CollectionService collectionService;
    private final CollectionIconService iconService;

    @GetMapping
    public CollectionListResponse list() {
        return new CollectionListResponse(collectionService.listAll());
    }

    @PostMapping
    public ResponseEntity<Collection> create(@RequestBody CollectionCreateRequest request) {
        Collection created = collectionService.create(request.getName());
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Collection> rename(@PathVariable long id,
                                             @RequestBody CollectionRenameRequest request) {
        return ResponseEntity.ok(collectionService.rename(id, request.getName()));
    }

    @PutMapping("/{id}/sort-order")
    public ResponseEntity<Collection> updateSortOrder(@PathVariable long id,
                                                      @RequestBody Map<String, Integer> body) {
        Integer sortOrder = body.get("sortOrder");
        return ResponseEntity.ok(collectionService.updateSortOrder(id, sortOrder == null ? 0 : sortOrder));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        collectionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/icon")
    public ResponseEntity<Collection> uploadIcon(@PathVariable long id,
                                                 @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("图标文件为空");
        }
        if (file.getSize() > CollectionIconService.MAX_ICON_BYTES) {
            throw new IllegalArgumentException("图标超出大小限制");
        }
        Collection updated = collectionService.setIcon(id, file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}/icon")
    public ResponseEntity<Collection> clearIcon(@PathVariable long id) {
        return ResponseEntity.ok(collectionService.clearIcon(id));
    }

    @GetMapping("/{id}/icon")
    public ResponseEntity<byte[]> downloadIcon(@PathVariable long id) throws IOException {
        Collection c = collectionService.get(id);
        if (c == null || c.iconExt() == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = iconService.findExistingIcon(id, c.iconExt());
        if (path == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = Files.readAllBytes(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(iconService.contentType(c.iconExt())))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(bytes);
    }

    @PostMapping("/{id}/artworks/{artworkId}")
    public ResponseEntity<Map<String, Object>> addArtwork(@PathVariable long id, @PathVariable long artworkId) {
        boolean added = collectionService.addArtwork(id, artworkId);
        return ResponseEntity.ok(Map.of("added", added));
    }

    @DeleteMapping("/{id}/artworks/{artworkId}")
    public ResponseEntity<Map<String, Object>> removeArtwork(@PathVariable long id, @PathVariable long artworkId) {
        boolean removed = collectionService.removeArtwork(id, artworkId);
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    @GetMapping("/of/{artworkId}")
    public ResponseEntity<Map<String, Object>> collectionsOf(@PathVariable long artworkId) {
        List<Long> ids = collectionService.collectionsOf(artworkId);
        return ResponseEntity.ok(Map.of("collectionIds", ids));
    }

    @PostMapping("/memberships")
    public ResponseEntity<Map<String, Object>> memberships(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body == null ? List.of() : body.getOrDefault("artworkIds", List.of());
        Map<Long, List<Long>> memberships = collectionService.membershipsOf(ids);
        return ResponseEntity.ok(Map.of("memberships", memberships));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleServerError(IllegalStateException e) {
        log.warn("Collection operation failed", e);
        return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
    }
}
