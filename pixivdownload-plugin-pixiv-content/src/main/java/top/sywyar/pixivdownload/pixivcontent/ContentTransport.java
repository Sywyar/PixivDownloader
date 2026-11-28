package top.sywyar.pixivdownload.pixivcontent;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxException;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;
import top.sywyar.pixivdownload.core.pixiv.media.PixivMediaClient;
import top.sywyar.pixivdownload.core.pixiv.media.PixivMediaStream;

/** 经宿主媒体端口读取 Pixiv CDN 媒体，并在回传前核对图片文件头。 */
final class ContentTransport {
    private final PixivMediaClient media;

    ContentTransport(PixivMediaClient media) {
        this.media = media;
    }

    ResponseEntity<Resource> image(URI uri, long artworkId) {
        return image(uri, ContentQueries.WEB + "/artworks/" + artworkId);
    }

    ResponseEntity<Resource> avatar(URI uri, long userId) {
        return image(uri, ContentQueries.WEB + "/users/" + userId);
    }

    ResponseEntity<Resource> novelImage(URI uri, long novelId) {
        return image(uri, ContentQueries.WEB + "/novel/show.php?id=" + novelId);
    }

    /** 调用方负责关闭返回的流，并按归档自身的字节预算读取。 */
    PixivMediaStream animationZip(URI uri, long artworkId, long maximumBytes) {
        return open(uri, ContentQueries.WEB + "/artworks/" + artworkId, maximumBytes);
    }

    private ResponseEntity<Resource> image(URI uri, String referer) {
        PixivMediaStream stream = open(uri, referer, PixivImageTransferObserver.MAX_IMAGE_BYTES);
        try {
            var body = new BufferedInputStream(stream);
            body.mark(12);
            MediaType type = imageType(body.readNBytes(12));
            body.reset();
            var result = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff").contentType(type);
            if (stream.declaredLength() >= 0) result.contentLength(stream.declaredLength());
            // ResourceHttpMessageConverter 负责关闭该流，下游提前断开时同样关闭；回传中途失败由包装记录并中止。
            return result.body(new InputStreamResource(new AbortLoggingStream(body, RequestDiagnostics.current(),
                    failure -> stream.exceededLimit() ? "media_size_limit" : "media_transfer_failed")));
        } catch (IOException failure) {
            close(stream);
            throw ContentFailure.transport();
        } catch (RuntimeException failure) {
            close(stream);
            throw failure;
        }
    }

    private PixivMediaStream open(URI uri, String referer, long maximumBytes) {
        ContentQueries.cdnUri(uri.toString());
        try {
            return media.open(uri, URI.create(referer), maximumBytes);
        } catch (PixivAjaxException failure) {
            throw ContentFailure.from(failure);
        }
    }

    private static void close(PixivMediaStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // 响应已经失败，关闭只用于释放上游连接。
        }
    }

    private static MediaType imageType(byte[] h) {
        if (h.length < 12) throw ContentFailure.invalidResponse();
        if (h[0] == (byte) 0xff && h[1] == (byte) 0xd8 && h[2] == (byte) 0xff) return MediaType.IMAGE_JPEG;
        if (Arrays.equals(h, 0, 8, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, 0, 8))
            return MediaType.IMAGE_PNG;
        var magic = new String(h, StandardCharsets.ISO_8859_1);
        if (magic.startsWith("GIF87a") || magic.startsWith("GIF89a")) return MediaType.IMAGE_GIF;
        if (magic.startsWith("RIFF") && magic.endsWith("WEBP")) return MediaType.parseMediaType("image/webp");
        throw ContentFailure.invalidResponse();
    }
}
