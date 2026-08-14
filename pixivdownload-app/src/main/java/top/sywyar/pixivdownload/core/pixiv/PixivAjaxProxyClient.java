package top.sywyar.pixivdownload.core.pixiv;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class PixivAjaxProxyClient implements PixivAjaxClient {

    static final int MAX_JSON_RESPONSE_BYTES = 4 * 1024 * 1024;
    static final int MAX_SERIES_RESPONSE_BYTES = 1024 * 1024;

    private final RestTemplate restTemplate;

    public PixivAjaxProxyClient(
            @Qualifier("pixivCredentialRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String get(URI uri, String cookie) {
        requireAllowedTarget(uri);
        try {
            return exchange(uri, cookie);
        } catch (HttpStatusCodeException e) {
            throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new PixivAjaxException(PixivAjaxFailure.TRANSPORT, 0);
        }
    }

    private String exchange(URI uri, String cookie) {
        return restTemplate.execute(uri, HttpMethod.GET,
                request -> request.getHeaders().putAll(PixivRequestHeaders.ajax(cookie)),
                response -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new PixivAjaxException(
                                PixivAjaxFailure.HTTP_STATUS, response.getStatusCode().value());
                    }
                    int limit = responseLimit(uri);
                    long declaredLength = response.getHeaders().getContentLength();
                    if (declaredLength > limit) {
                        throw responseTooLarge();
                    }
                    try (var body = response.getBody()) {
                        byte[] bytes = body.readNBytes(limit + 1);
                        if (bytes.length > limit) {
                            throw responseTooLarge();
                        }
                        return new String(bytes, StandardCharsets.UTF_8);
                    }
                });
    }

    private static int responseLimit(URI uri) {
        String path = uri.getPath();
        return path.startsWith("/ajax/novel/series/")
                || path.startsWith("/ajax/novel/series_content/")
                ? MAX_SERIES_RESPONSE_BYTES
                : MAX_JSON_RESPONSE_BYTES;
    }

    private static PixivAjaxException responseTooLarge() {
        return new PixivAjaxException(PixivAjaxFailure.RESPONSE_TOO_LARGE, 0);
    }

    private static void requireAllowedTarget(URI uri) {
        if (!isAllowedTarget(uri)) {
            throw new PixivAjaxException(PixivAjaxFailure.INVALID_TARGET, 0);
        }
    }

    private static boolean isAllowedTarget(URI uri) {
        if (uri == null
                || !uri.isAbsolute()
                || !"https".equalsIgnoreCase(uri.getScheme())
                || !"www.pixiv.net".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        String rawPath = uri.getRawPath();
        String path = uri.getPath();
        if (rawPath == null
                || path == null
                || !rawPath.equals(uri.normalize().getRawPath())
                || containsEncodedSeparator(rawPath)
                || path.indexOf('\\') >= 0
                || containsDotSegment(path)) {
            return false;
        }
        return isSupportedPath(rawPath) && isSupportedPath(path);
    }

    private static boolean isSupportedPath(String path) {
        return path.startsWith("/ajax/") || "/rpc/index.php".equals(path);
    }

    private static boolean containsEncodedSeparator(String rawPath) {
        String lowerPath = rawPath.toLowerCase(Locale.ROOT);
        return lowerPath.contains("%2f") || lowerPath.contains("%5c");
    }

    private static boolean containsDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
