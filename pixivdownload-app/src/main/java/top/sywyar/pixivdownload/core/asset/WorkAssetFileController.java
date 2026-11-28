package top.sywyar.pixivdownload.core.asset;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 已下载插画图片文件的核心 serving 端点（{@code /api/downloaded/thumbnail|thumbnail-file|rawfile|image}）。
 * <p>
 * 「读已下载作品的本地图片字节」是核心本地资产能力，不随下载执行功能启停而变化：画廊 / 橱窗 /
 * 系列 / 作品详情页运行期都靠这些 URL 取图。因此本控制器归核心、按 {@code (WorkType, workId)}
 * 统一经 {@link WorkAssetService} 取文件（与小说封面 / 内嵌图自 serving 同形态），不直接依赖下载侧实现类。
 * 访问级别由 {@code CorePlugin.routes()} 声明、{@code AuthFilter} 执行（URL 不变，鉴权语义不变）。
 */
@HttpApiGroup("core-library")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WorkAssetFileController {

    private final WorkAssetService workAssetService;
    private final GuestAccessGuard guestAccessGuard;

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "304", content = @Content)
    @ApiResponse(responseCode = "404", content = @Content)
    @Parameter(name = "Range", in = ParameterIn.HEADER, example = "bytes=0-99", schema = @Schema(type = "string"))
    @Parameter(name = "If-None-Match", in = ParameterIn.HEADER, schema = @Schema(type = "string"))
    @GetMapping({
            "/downloaded/thumbnail/{artworkId}/{page}",
            "/downloaded/thumbnail-file/{artworkId}/{page}"
    })
    public ResponseEntity<Resource> getThumbnail(
            @PathVariable Long artworkId,
            @Parameter(example = "0", schema = @Schema(type = "integer", format = "int32", minimum = "0"))
            @PathVariable int page,
            @RequestParam(defaultValue = "512") int size,
            @RequestParam(defaultValue = "false") boolean cover,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        WorkAssetFile thumbnail = (cover
                ? workAssetService.coverThumbnail(WorkType.ARTWORK, artworkId, page, size)
                : workAssetService.thumbnail(WorkType.ARTWORK, artworkId, page, size)).orElse(null);
        return fileResponse(thumbnail);
    }

    @ApiResponse(responseCode = "200", content = {
            @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "video/*", schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "404", content = @Content)
    @Parameter(name = "Range", in = ParameterIn.HEADER, example = "bytes=0-99", schema = @Schema(type = "string"))
    @Parameter(name = "If-None-Match", in = ParameterIn.HEADER, schema = @Schema(type = "string"))
    @ApiResponse(responseCode = "304", content = @Content)
    @GetMapping("/downloaded/image/{artworkId}/{page}")
    public ResponseEntity<Resource> getImage(
            @PathVariable Long artworkId,
            @Parameter(example = "0", schema = @Schema(type = "integer", format = "int32", minimum = "0"))
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        WorkAssetFile preview = workAssetService.previewFile(WorkType.ARTWORK, artworkId, page).orElse(null);
        if (preview != null && "zip".equalsIgnoreCase(preview.extension())) {
            preview = workAssetService.thumbnail(WorkType.ARTWORK, artworkId, page).orElse(null);
        }
        return fileResponse(preview);
    }

    @ApiResponse(responseCode = "200", content = {
            @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "video/*", schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary"))})
    @ApiResponse(responseCode = "404", content = @Content)
    @Parameter(name = "Range", in = ParameterIn.HEADER, example = "bytes=0-99", schema = @Schema(type = "string"))
    @Parameter(name = "If-None-Match", in = ParameterIn.HEADER, schema = @Schema(type = "string"))
    @ApiResponse(responseCode = "304", content = @Content)
    @GetMapping("/downloaded/rawfile/{artworkId}/{page}")
    public ResponseEntity<Resource> getRawFile(
            @PathVariable Long artworkId,
            @Parameter(example = "0", schema = @Schema(type = "integer", format = "int32", minimum = "0"))
            @PathVariable int page,
            HttpServletRequest httpRequest) throws IOException {
        guestAccessGuard.requireVisible(httpRequest, artworkId);
        return fileResponse(workAssetService.rawFile(WorkType.ARTWORK, artworkId, page).orElse(null));
    }

    private ResponseEntity<Resource> fileResponse(WorkAssetFile file) throws IOException {
        if (file == null || !Files.isRegularFile(file.path())) {
            return ResponseEntity.notFound().build();
        }
        // 作品可切换到同名、同时间戳的另一份文件，只有当前表示的 ETag 能用于条件读取。
        String identity = file.path() + "\n" + Files.getLastModifiedTime(file.path()) + "\n" + Files.size(file.path());
        var version = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.ok().contentType(mediaTypeForImageExtension(file.extension()))
                .eTag("W/\"" + version + "\"").cacheControl(CacheControl.noCache().cachePrivate())
                .body(new FileSystemResource(file.path()));
    }

    private MediaType mediaTypeForImageExtension(String extension) {
        String normalized = extension == null ? "" : extension.toLowerCase();
        return switch (normalized) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "apng" -> MediaType.parseMediaType("image/apng");
            case "webm" -> MediaType.parseMediaType("video/webm");
            case "zip" -> MediaType.parseMediaType("application/zip");
            case "mp4" -> MediaType.parseMediaType("video/mp4");
            default -> MediaType.IMAGE_PNG;
        };
    }
}
