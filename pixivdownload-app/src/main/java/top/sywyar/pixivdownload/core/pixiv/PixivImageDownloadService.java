package top.sywyar.pixivdownload.core.pixiv;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.common.PixivRequestHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

@Service
public class PixivImageDownloadService implements PixivImageDownloader {

    private final RestTemplate downloadRestTemplate;

    public PixivImageDownloadService(
            @Qualifier("pixivImageRestTemplate") RestTemplate downloadRestTemplate) {
        this.downloadRestTemplate = downloadRestTemplate;
    }

    @Override
    public boolean download(
            URI source,
            URI referer,
            Path target,
            String cookie,
            PixivImageTransferObserver observer
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(observer, "observer");
        if (!isAllowedImageSource(source) || !isAllowedReferer(referer)) {
            return false;
        }

        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Boolean downloaded = downloadRestTemplate.execute(
                    source,
                    HttpMethod.GET,
                    request -> PixivRequestHeaders.applyImage(request.getHeaders(), referer.toString(), cookie),
                    response -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return Boolean.FALSE;
                        }
                        long contentLength = response.getHeaders().getContentLength();
                        observer.onContentLength(contentLength > 0 ? contentLength : 0);
                        observer.onBytesTransferred(0);
                        copy(response.getBody(), target, observer);
                        return Boolean.TRUE;
                    });
            return Boolean.TRUE.equals(downloaded);
        } catch (HttpStatusCodeException e) {
            return false;
        } catch (RestClientException e) {
            throw new IOException("Pixiv image transfer failed");
        }
    }

    private static boolean isAllowedImageSource(URI source) {
        if (source == null) {
            return false;
        }
        String host = source.getHost();
        return "https".equalsIgnoreCase(source.getScheme())
                && host != null
                && host.toLowerCase(Locale.ROOT).endsWith(".pximg.net")
                && source.getUserInfo() == null
                && source.getFragment() == null
                && (source.getPort() == -1 || source.getPort() == 443);
    }

    private static boolean isAllowedReferer(URI referer) {
        return referer != null
                && "https".equalsIgnoreCase(referer.getScheme())
                && "www.pixiv.net".equalsIgnoreCase(referer.getHost())
                && referer.getUserInfo() == null
                && referer.getFragment() == null
                && (referer.getPort() == -1 || referer.getPort() == 443);
    }

    private static void copy(
            InputStream inputStream,
            Path target,
            PixivImageTransferObserver observer
    ) throws IOException {
        try (InputStream in = inputStream;
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            long transferred = 0;
            while ((read = in.read(buffer)) != -1) {
                observer.checkCancelled();
                out.write(buffer, 0, read);
                transferred += read;
                observer.onBytesTransferred(transferred);
            }
        }
    }
}
