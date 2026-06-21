package top.sywyar.pixivdownload.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

/**
 * Best-effort downloader for Pixiv cover images (illust series / novel series / single novel).
 *
 * <p>Output naming: {@code {targetFolder}/{baseName}.{ext}} where {@code ext} is inferred from
 * the URL path and constrained to {@link #EXT_WHITELIST}. SSRF guard restricts host to
 * {@code *.pximg.net}.
 */
@Component
@Slf4j
public class PixivCoverDownloader {

    public static final Set<String> EXT_WHITELIST = Set.of("jpg", "jpeg", "png", "webp");

    private final RestTemplate downloadRestTemplate;

    public PixivCoverDownloader(
            @org.springframework.beans.factory.annotation.Qualifier("downloadRestTemplate")
            RestTemplate downloadRestTemplate) {
        this.downloadRestTemplate = downloadRestTemplate;
    }

    /**
     * Download the cover to {@code targetFolder/baseName.{ext}}. Returns the chosen
     * extension on success or {@code null} when the URL is empty/unsafe/unreachable.
     */
    public String download(String url, Path targetFolder, String baseName, String cookie) {
        if (url == null || url.isBlank()) return null;
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            log.warn("cover skipped — malformed url: {}", url);
            return null;
        }
        String host = uri.getHost();
        if (host == null || !host.endsWith(".pximg.net")) {
            log.warn("cover skipped — host not pximg.net: {}", host);
            return null;
        }
        String ext = inferExt(uri.getPath());
        Path target = targetFolder.resolve(baseName + "." + ext);
        try {
            Files.createDirectories(targetFolder);
            Boolean ok = downloadRestTemplate.execute(uri.toString(), HttpMethod.GET,
                    request -> PixivRequestHeaders.applyImage(request.getHeaders(), cookie),
                    response -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return Boolean.FALSE;
                        }
                        Files.copy(response.getBody(), target, StandardCopyOption.REPLACE_EXISTING);
                        return Boolean.TRUE;
                    });
            return Boolean.TRUE.equals(ok) ? ext : null;
        } catch (Exception e) {
            log.warn("cover download failed: {} — {}", uri, e.getMessage());
            return null;
        }
    }

    private static String inferExt(String path) {
        if (path == null) return "jpg";
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        if (dot < 0 || dot == last.length() - 1) return "jpg";
        String candidate = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        int q = candidate.indexOf('?');
        if (q >= 0) candidate = candidate.substring(0, q);
        return EXT_WHITELIST.contains(candidate) ? candidate : "jpg";
    }
}
