package top.sywyar.pixivdownload.core.pixiv;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetchException;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFailure;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetcher;

import java.net.URI;
import java.util.Locale;

@Service
public class PixivThumbnailFetchService implements PixivThumbnailFetcher {

    private final RestTemplate restTemplate;

    public PixivThumbnailFetchService(
            @Qualifier("pixivImageRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public byte[] fetch(URI source) {
        requireAllowedTarget(source);
        try {
            HttpEntity<Void> entity = new HttpEntity<>(PixivRequestHeaders.image(null));
            ResponseEntity<byte[]> response =
                    restTemplate.exchange(source, HttpMethod.GET, entity, byte[].class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new PixivThumbnailFetchException(
                        PixivThumbnailFailure.HTTP_STATUS,
                        response.getStatusCode().value()
                );
            }
            byte[] body = response.getBody();
            return body == null ? new byte[0] : body;
        } catch (PixivThumbnailFetchException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new PixivThumbnailFetchException(
                    PixivThumbnailFailure.HTTP_STATUS,
                    e.getStatusCode().value()
            );
        } catch (RestClientException e) {
            throw new PixivThumbnailFetchException(PixivThumbnailFailure.TRANSPORT, 0);
        }
    }

    private static void requireAllowedTarget(URI source) {
        if (!isAllowedTarget(source)) {
            throw new PixivThumbnailFetchException(PixivThumbnailFailure.INVALID_TARGET, 0);
        }
    }

    private static boolean isAllowedTarget(URI source) {
        if (source == null
                || !source.isAbsolute()
                || !"https".equalsIgnoreCase(source.getScheme())
                || source.getHost() == null
                || source.getUserInfo() != null
                || source.getFragment() != null
                || (source.getPort() != -1 && source.getPort() != 443)) {
            return false;
        }
        String host = source.getHost().toLowerCase(Locale.ROOT);
        return host.endsWith(".pximg.net") || "embed.pixiv.net".equals(host);
    }
}
