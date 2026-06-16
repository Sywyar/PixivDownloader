package top.sywyar.pixivdownload.core.asset;

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
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.plugin.api.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.work.service.WorkAssetService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 已下载插画图片字节的核心 serving 端点（{@code /api/downloaded/thumbnail|thumbnail-file|rawfile|image}）。
 * <p>
 * 「读已下载作品的本地图片字节」是核心本地资产能力，不随下载执行功能启停而变化：画廊 / 橱窗 /
 * 系列 / 作品详情页运行期都靠这些 URL 取图。因此本控制器归核心、按 {@code (WorkType, workId)}
 * 统一经 {@link WorkAssetService} 取文件（与小说封面 / 内嵌图自 serving 同形态），不直接依赖下载侧实现类。
 * 访问级别由 {@code CorePlugin.routes()} 声明、{@code AuthFilter} 执行（URL 不变，鉴权语义不变）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorkAssetFileController {

    private final WorkAssetService workAssetService;
    private final GuestAccessGuard guestAccessGuard;
    private final AppMessages messages;

    @GetMapping("/downloaded/thumbnail/{artworkId}/{page}")
    public ResponseEntity<ImageResponse> getThumbnail(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        WorkAssetFile thumbnail = workAssetService.thumbnail(WorkType.ARTWORK, artworkId, page).orElse(null);
        if (thumbnail == null) {
            return ResponseEntity.status(404).build();
        }
        byte[] fileBytes = Files.readAllBytes(thumbnail.path());
        BufferedImage image = ImageIO.read(thumbnail.path().toFile());
        int width = image == null ? 0 : image.getWidth();
        int height = image == null ? 0 : image.getHeight();
        String base64Image = Base64.getEncoder().encodeToString(fileBytes);
        return ResponseEntity.ok(new ImageResponse(true, base64Image, thumbnail.extension(),
                base64Image.length(), width, height,
                messages.get("download.image.thumbnail.fetch-success")));
    }

    @GetMapping("/downloaded/thumbnail-file/{artworkId}/{page}")
    public ResponseEntity<Resource> getThumbnailFile(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        WorkAssetFile thumbnail = workAssetService.thumbnail(WorkType.ARTWORK, artworkId, page).orElse(null);
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
        WorkAssetFile raw = workAssetService.rawFile(WorkType.ARTWORK, artworkId, page).orElse(null);
        if (raw == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = Files.readAllBytes(raw.path());
        return ResponseEntity.ok().contentType(mediaTypeForImageExtension(raw.extension())).body(bytes);
    }

    @GetMapping("/downloaded/image/{artworkId}/{page}")
    public ResponseEntity<ImageResponse> getImage(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        WorkAssetFile raw = workAssetService.rawFile(WorkType.ARTWORK, artworkId, page).orElse(null);
        if (raw == null) {
            return ResponseEntity.status(404).build();
        }
        Path path = raw.path();
        String extension = raw.extension();

        // WebP 完整图：直接回原始字节（base64），由 rawfile 端点更高效，此处保留兼容路径。
        if ("webp".equals(extension)) {
            byte[] fileBytes = Files.readAllBytes(path);
            String base64Image = Base64.getEncoder().encodeToString(fileBytes);
            return ResponseEntity.ok(new ImageResponse(true, base64Image, "webp",
                    base64Image.length(), 0, 0,
                    messages.get("download.image.ugoira.fetch-success")));
        }

        BufferedImage image = ImageIO.read(path.toFile());
        ByteArrayOutputStream bass = new ByteArrayOutputStream();
        ImageIO.write(image, extension, bass);
        String base64Image = Base64.getEncoder().encodeToString(bass.toByteArray());
        return ResponseEntity.ok(new ImageResponse(true, base64Image, extension,
                base64Image.length(), image.getWidth(), image.getHeight(),
                messages.get("download.image.thumbnail.fetch-success")));
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
