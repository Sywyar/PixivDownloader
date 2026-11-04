package top.sywyar.pixivdownload.captcha;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 只提供已签发挑战的图片；此端点不能创建或校验挑战。 */
@RestController
public final class CaptchaImageController {
    private final ImageChallengeStore store;
    CaptchaImageController(ImageChallengeStore store) { this.store = store; }

    @GetMapping("/api/captcha/images/{id}/{index:[01]}")
    public ResponseEntity<byte[]> image(@PathVariable("id") String id, @PathVariable("index") int index) {
        byte[] bytes = id.length() == 36 ? store.image(id, index) : null;
        return ResponseEntity.status(bytes == null ? 404 : 200).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").contentType(MediaType.IMAGE_PNG).body(bytes);
    }
}
