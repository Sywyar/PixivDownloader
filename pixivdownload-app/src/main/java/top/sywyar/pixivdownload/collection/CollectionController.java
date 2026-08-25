package top.sywyar.pixivdownload.collection;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.sywyar.pixivdownload.collection.request.CollectionCreateRequest;
import top.sywyar.pixivdownload.collection.request.CollectionDownloadRootRequest;
import top.sywyar.pixivdownload.collection.request.CollectionRenameRequest;
import top.sywyar.pixivdownload.collection.response.CollectionListResponse;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/collections")
@RequiredArgsConstructor
public class CollectionController {

    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private final CollectionService collectionService;
    private final CollectionIconService iconService;
    private final GalleryRepository galleryRepository;
    private final GuestAccessGuard guestAccessGuard;

    @GetMapping
    public CollectionListResponse list(HttpServletRequest httpRequest) {
        List<Collection> all = collectionService.listAll();
        GuestInviteSession session = GuestAccessGuard.extractSession(httpRequest);
        if (session == null) return new CollectionListResponse(all);
        Set<Long> visible = galleryRepository.findVisibleCollectionIds(GuestRestriction.from(session));
        return new CollectionListResponse(all.stream()
                .filter(c -> visible.contains(c.id()))
                .toList());
    }

    @PostMapping
    public ResponseEntity<Collection> create(@Valid @RequestBody CollectionCreateRequest request) {
        Collection created = collectionService.create(request.getName(), request.getDownloadRoot());
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Collection> rename(@PathVariable long id,
                                             @Valid @RequestBody CollectionRenameRequest request) {
        return ResponseEntity.ok(collectionService.rename(id, request.getName()));
    }

    @PutMapping("/{id}/download-root")
    public ResponseEntity<Collection> updateDownloadRoot(@PathVariable long id,
                                                         @RequestBody CollectionDownloadRootRequest request) {
        String downloadRoot = request == null ? null : request.getDownloadRoot();
        return ResponseEntity.ok(collectionService.updateDownloadRoot(id, downloadRoot));
    }

    @PutMapping("/{id}/sort-order")
    public ResponseEntity<Collection> updateSortOrder(@PathVariable long id,
                                                      @RequestBody SortOrderRequest body) {
        Integer sortOrder = body == null ? null : body.sortOrder();
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
            throw LocalizedException.badRequest("collection.icon.empty", "图标文件为空");
        }
        if (file.getSize() > CollectionIconService.MAX_ICON_BYTES) {
            throw LocalizedException.badRequest("collection.icon.size.exceeded", "图标超出大小限制");
        }
        Collection updated = collectionService.setIcon(id, file.getBytes());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}/icon")
    public ResponseEntity<Collection> clearIcon(@PathVariable long id) {
        return ResponseEntity.ok(collectionService.clearIcon(id));
    }

    @GetMapping("/{id}/icon")
    public ResponseEntity<byte[]> downloadIcon(@PathVariable long id,
                                               HttpServletRequest httpRequest) throws IOException {
        requireGuestCollectionVisible(httpRequest, id);
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
                .header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                .body(bytes);
    }

    @PostMapping("/{id}/artworks/{artworkId}")
    public ResponseEntity<AddedResponse> addArtwork(@PathVariable long id, @PathVariable long artworkId) {
        boolean added = collectionService.addArtwork(id, artworkId);
        return ResponseEntity.ok(new AddedResponse(added));
    }

    @DeleteMapping("/{id}/artworks/{artworkId}")
    public ResponseEntity<RemovedResponse> removeArtwork(@PathVariable long id, @PathVariable long artworkId) {
        boolean removed = collectionService.removeArtwork(id, artworkId);
        return ResponseEntity.ok(new RemovedResponse(removed));
    }

    @GetMapping("/of/{artworkId}")
    public ResponseEntity<CollectionIdsResponse> collectionsOf(
            @PathVariable long artworkId,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        List<Long> ids = collectionService.collectionsOf(artworkId);
        return ResponseEntity.ok(new CollectionIdsResponse(ids));
    }

    @PostMapping("/memberships")
    public ResponseEntity<MembershipsResponse> memberships(@RequestBody ArtworkMembershipsRequest body) {
        List<Long> ids = body == null || body.artworkIds() == null ? List.of() : body.artworkIds();
        Map<Long, List<Long>> memberships = collectionService.membershipsOf(ids);
        return ResponseEntity.ok(new MembershipsResponse(memberships));
    }

    @PostMapping("/{id}/novels/{novelId}")
    public ResponseEntity<AddedResponse> addNovel(@PathVariable long id, @PathVariable long novelId) {
        boolean added = collectionService.addNovel(id, novelId);
        return ResponseEntity.ok(new AddedResponse(added));
    }

    @DeleteMapping("/{id}/novels/{novelId}")
    public ResponseEntity<RemovedResponse> removeNovel(@PathVariable long id, @PathVariable long novelId) {
        boolean removed = collectionService.removeNovel(id, novelId);
        return ResponseEntity.ok(new RemovedResponse(removed));
    }

    @GetMapping("/novels/of/{novelId}")
    public ResponseEntity<CollectionIdsResponse> novelCollectionsOf(
            @PathVariable long novelId,
            HttpServletRequest httpRequest) {
        guestAccessGuard.requireNovelVisible(httpRequest, novelId);
        List<Long> ids = collectionService.novelCollectionsOf(novelId);
        return ResponseEntity.ok(new CollectionIdsResponse(ids));
    }

    @PostMapping("/novels/memberships")
    public ResponseEntity<MembershipsResponse> novelMemberships(@RequestBody NovelMembershipsRequest body) {
        List<Long> ids = body == null || body.novelIds() == null ? List.of() : body.novelIds();
        Map<Long, List<Long>> memberships = collectionService.novelMembershipsOf(ids);
        return ResponseEntity.ok(new MembershipsResponse(memberships));
    }

    public record SortOrderRequest(Integer sortOrder) {}

    public record ArtworkMembershipsRequest(List<Long> artworkIds) {}

    public record NovelMembershipsRequest(List<Long> novelIds) {}

    public record AddedResponse(boolean added) {}

    public record RemovedResponse(boolean removed) {}

    public record CollectionIdsResponse(List<Long> collectionIds) {}

    public record MembershipsResponse(Map<Long, List<Long>> memberships) {}

    private void requireGuestCollectionVisible(HttpServletRequest request, long collectionId) {
        GuestInviteSession session = GuestAccessGuard.extractSession(request);
        if (session == null) return;
        Set<Long> visible = galleryRepository.findVisibleCollectionIds(GuestRestriction.from(session));
        if (!visible.contains(collectionId)) {
            throw new LocalizedException(HttpStatus.FORBIDDEN,
                    "guest.invite.forbidden",
                    "该作品不在你的可见范围内");
        }
    }
}
