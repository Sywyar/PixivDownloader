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
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * 已下载插画图片文件的核心 serving 端点（{@code /api/downloaded/thumbnail|thumbnail-file|rawfile|image}）。
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

    @GetMapping({
            "/downloaded/thumbnail/{artworkId}/{page}",
            "/downloaded/thumbnail-file/{artworkId}/{page}"
    })
    public ResponseEntity<Resource> getThumbnail(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        WorkAssetFile thumbnail = workAssetService.thumbnail(WorkType.ARTWORK, artworkId, page).orElse(null);
        return fileResponse(thumbnail, true);
    }

    @GetMapping({
            "/downloaded/rawfile/{artworkId}/{page}",
            "/downloaded/image/{artworkId}/{page}"
    })
    public ResponseEntity<Resource> getImage(
            @PathVariable Long artworkId,
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        WorkAssetFile raw = workAssetService.rawFile(WorkType.ARTWORK, artworkId, page).orElse(null);
        return fileResponse(raw, false);
    }

    private ResponseEntity<Resource> fileResponse(WorkAssetFile file, boolean cacheable) throws IOException {
        if (file == null || !Files.isRegularFile(file.path())) {
            return ResponseEntity.notFound().build();
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(mediaTypeForImageExtension(file.extension()))
                .contentLength(Files.size(file.path()))
                .lastModified(Files.getLastModifiedTime(file.path()).toMillis());
        if (cacheable) {
            response.cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePrivate());
        }
        return response.body(new FileSystemResource(file.path()));
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
