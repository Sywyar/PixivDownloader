package top.sywyar.pixivdownload.core.pixiv;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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

    private final RestTemplate restTemplate;

    public PixivAjaxProxyClient(
            @Qualifier("pixivCredentialRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String proxyGet(String url, String cookie) {
        return proxyGetUri(URI.create(url), cookie);
    }

    public String proxyGetUri(URI uri, String cookie) {
        requireAllowedTarget(uri);
        return exchange(uri, cookie);
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
        HttpEntity<Void> entity = new HttpEntity<>(PixivRequestHeaders.ajax(cookie));
        ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, entity, byte[].class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new PixivAjaxException(PixivAjaxFailure.HTTP_STATUS, response.getStatusCode().value());
        }
        byte[] body = response.getBody();
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
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
