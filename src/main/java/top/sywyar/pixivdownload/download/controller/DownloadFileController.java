package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.download.DownloadService;
import top.sywyar.pixivdownload.download.response.ImageResponse;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadFileController {

    private final DownloadService downloadService;
    private final GuestAccessGuard guestAccessGuard;

    @GetMapping("/downloaded/thumbnail/{artworkId}/{page}")
    public ResponseEntity<ImageResponse> getThumbnail(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        ImageResponse image = downloadService.getImageResponse(artworkId, page, true);
        return image != null ? ResponseEntity.ok(image) : ResponseEntity.status(404).build();
    }

    @GetMapping("/downloaded/thumbnail-file/{artworkId}/{page}")
    public ResponseEntity<Resource> getThumbnailFile(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        DownloadService.ThumbnailFile thumbnail = downloadService.getThumbnailFile(artworkId, page);
        if (thumbnail == null || !Files.isRegularFile(thumbnail.path())) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(thumbnail.path());
        return ResponseEntity.ok()
                .contentType(mediaTypeForImageExtension(thumbnail.extension()))
                .contentLength(Files.size(thumbnail.path()))
                .lastModified(Files.getLastModifiedTime(thumbnail.path()).toMillis())
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePrivate())
                .body(resource);
    }

    @GetMapping("/downloaded/rawfile/{artworkId}/{page}")
    public ResponseEntity<byte[]> getRawFile(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        File file = downloadService.getImageFile(artworkId, page);
        if (file == null) return ResponseEntity.notFound().build();

        byte[] bytes = Files.readAllBytes(file.toPath());
        String name = file.getName().toLowerCase();
        MediaType mediaType;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            mediaType = MediaType.IMAGE_JPEG;
        } else if (name.endsWith(".gif")) {
            mediaType = MediaType.IMAGE_GIF;
        } else if (name.endsWith(".webp")) {
            mediaType = MediaType.parseMediaType("image/webp");
        } else {
            mediaType = MediaType.IMAGE_PNG;
        }

        return ResponseEntity.ok().contentType(mediaType).body(bytes);
    }

    @GetMapping("/downloaded/image/{artworkId}/{page}")
    public ResponseEntity<ImageResponse> getImage(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        ImageResponse image = downloadService.getImageResponse(artworkId, page, false);
        return image != null ? ResponseEntity.ok(image) : ResponseEntity.status(404).build();
    }

    private MediaType mediaTypeForImageExtension(String extension) {
        String normalized = extension == null ? "" : extension.toLowerCase();
        return switch (normalized) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.IMAGE_PNG;
        };
    }
}
