package top.sywyar.pixivdownload.core.pixiv;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.core.pixiv.media.PixivMediaClient;
import top.sywyar.pixivdownload.core.pixiv.media.PixivMediaStream;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 经媒体流专用客户端（独立连接池、禁止重定向、不保存 Cookie、宿主代理）以流方式读取 Pixiv CDN 媒体。
 */
@Service
public class PixivMediaStreamService implements PixivMediaClient {

    private final RestTemplate mediaRestTemplate;

    public PixivMediaStreamService(@Qualifier("pixivMediaRestTemplate") RestTemplate mediaRestTemplate) {
        this.mediaRestTemplate = mediaRestTemplate;
    }

    @Override
    public PixivMediaStream open(URI source, URI referer, long maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        if (!isMediaSource(source) || !isPixivPage(referer)) {
            throw new PixivAjaxException(PixivAjaxFailure.INVALID_TARGET, 0);
        }
        long limit = Math.min(maximumBytes, PixivImageTransferObserver.MAX_IMAGE_BYTES);
        ClientHttpResponse response;
        try {
            var request = mediaRestTemplate.getRequestFactory().createRequest(source, HttpMethod.GET);
            // CDN 请求只带来源页，不发送 Pixiv 登录 Cookie。
            PixivRequestHeaders.applyImage(request.getHeaders(), referer.toString(), null);
            response = request.execute();
        } catch (IOException failure) {
            throw new PixivAjaxException(PixivAjaxFailure.TRANSPORT, 0);
        }
        try {
            int status = response.getStatusCode().value();
            if (status != 200) {
                throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, status,
                        PixivCredentialRetry.retryAfter(response.getHeaders().getFirst("Retry-After"),
                                java.time.Clock.systemUTC()));
            }
            long declared = declaredLength(response.getHeaders());
            if (declared > limit) {
                throw new PixivAjaxException(PixivAjaxFailure.RESPONSE_TOO_LARGE, 0);
            }
            return new PixivMediaStream(response.getBody(), declared, limit);
        } catch (IOException failure) {
            response.close();
            throw new PixivAjaxException(PixivAjaxFailure.TRANSPORT, 0);
        } catch (RuntimeException failure) {
            response.close();
            throw failure;
        }
    }

    private static long declaredLength(HttpHeaders headers) {
        List<String> values = headers.get(HttpHeaders.CONTENT_LENGTH);
        if (values == null || values.isEmpty()) {
            return -1;
        }
        if (values.size() == 1) {
            try {
                long value = Long.parseLong(values.getFirst().trim());
                if (value >= 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 无法用于界定正文长度的声明按传输失败处理。
            }
        }
        throw new PixivAjaxException(PixivAjaxFailure.TRANSPORT, 0);
    }

    private static boolean isMediaSource(URI source) {
        return isHttpsDefaultPort(source)
                && source.getHost().toLowerCase(Locale.ROOT).endsWith(".pximg.net");
    }

    private static boolean isPixivPage(URI referer) {
        return isHttpsDefaultPort(referer) && "www.pixiv.net".equalsIgnoreCase(referer.getHost());
    }

    private static boolean isHttpsDefaultPort(URI uri) {
        return uri != null
                && uri.isAbsolute()
                && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }
}
