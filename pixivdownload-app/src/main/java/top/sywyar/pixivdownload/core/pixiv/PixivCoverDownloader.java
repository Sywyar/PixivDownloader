package top.sywyar.pixivdownload.core.pixiv;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;

/**
 * Best-effort downloader for Pixiv cover images (illust series / novel series / single novel).
 *
 * <p>Output naming: {@code {targetFolder}/{baseName}.{ext}} where {@code ext} is selected from
 * the verified response type and file signature.
 */
@Component
@Slf4j
public class PixivCoverDownloader {

    private static final URI DEFAULT_REFERER = URI.create("https://www.pixiv.net/");

    private final PixivImageDownloader imageDownloader;

    public PixivCoverDownloader(PixivImageDownloader imageDownloader) {
        this.imageDownloader = imageDownloader;
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
        try {
            return imageDownloader.downloadImage(
                    uri,
                    DEFAULT_REFERER,
                    targetFolder.resolve(baseName),
                    cookie,
                    new PixivImageTransferObserver() {
                    });
        } catch (Exception e) {
            log.warn("cover download failed: {} — {}", uri, e.getMessage());
            return null;
        }
    }
}
