package top.sywyar.pixivdownload.captcha;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiGroup;

/** 只提供已签发挑战的图片；此端点不能创建或校验挑战。 */
@HttpApiGroup("captcha-captcha")
@RestController
public final class CaptchaImageController {
    private final ImageChallengeStore store;
    CaptchaImageController(ImageChallengeStore store) { this.store = store; }

    @ApiResponse(responseCode = "200", content = @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", content = @Content)
    @GetMapping("/api/captcha/images/{id}/{index:[01]}")
    public ResponseEntity<byte[]> image(@Parameter(schema = @Schema(minLength = 36, maxLength = 36)) @PathVariable("id") String id, @Parameter(schema = @Schema(type = "integer", minimum = "0", maximum = "1")) @PathVariable("index") int index) {
        byte[] bytes = id.length() == 36 ? store.image(id, index) : null;
        return ResponseEntity.status(bytes == null ? 404 : 200).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").contentType(MediaType.IMAGE_PNG).body(bytes);
    }
}
