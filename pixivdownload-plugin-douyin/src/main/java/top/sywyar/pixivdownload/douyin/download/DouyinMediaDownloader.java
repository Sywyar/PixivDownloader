package top.sywyar.pixivdownload.douyin.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.client.DouyinRequestHeaders;
import top.sywyar.pixivdownload.douyin.model.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.DouyinMediaType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public class DouyinMediaDownloader {

    private static final Logger log = LoggerFactory.getLogger(DouyinMediaDownloader.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Set<String> ALLOWED_MEDIA_HOST_SUFFIXES = Set.of(
            "douyin.com",
            "iesdouyin.com",
            "douyinvod.com",
            "douyinpic.com",
            "douyinstatic.com",
            "amemv.com",
            "byteimg.com",
            "bytedance.com",
            "bytecdn.cn",
            "pstatp.com",
            "snssdk.com");

    private final RestTemplate restTemplate;
    private final Predicate<String> mediaHostAllowed;
    private final boolean allowHttpForTests;

    public DouyinMediaDownloader(RestTemplate restTemplate) {
        this(restTemplate, DouyinMediaDownloader::defaultMediaHostAllowed, false);
    }

    DouyinMediaDownloader(RestTemplate restTemplate, Predicate<String> mediaHostAllowed) {
        this(restTemplate, mediaHostAllowed, true);
    }

    private DouyinMediaDownloader(RestTemplate restTemplate, Predicate<String> mediaHostAllowed,
                                  boolean allowHttpForTests) {
        this.restTemplate = restTemplate;
        this.mediaHostAllowed = mediaHostAllowed;
        this.allowHttpForTests = allowHttpForTests;
    }

    public List<DouyinDownloadedFile> download(List<DouyinMedia> media,
                                               Path directory,
                                               BooleanSupplier cancellationRequested)
            throws IOException, DouyinClientException {
        if (media == null || media.isEmpty()) {
            throw new DouyinClientException(DouyinClientErrorCode.MEDIA_URL_MISSING,
                    "Resolved Douyin work does not expose downloadable media");
        }
        Files.createDirectories(directory);
        List<DouyinDownloadedFile> files = new ArrayList<>();
        for (int i = 0; i < media.size(); i++) {
            ensureNotCancelled(cancellationRequested);
            files.add(downloadOne(media.get(i), i + 1, directory, cancellationRequested));
        }
        return files;
    }

    private DouyinDownloadedFile downloadOne(DouyinMedia media,
                                             int index,
                                             Path directory,
                                             BooleanSupplier cancellationRequested)
            throws IOException, DouyinClientException {
        validateMediaUrl(media.url());
        Path tmpBasePath = safeOutputPath(directory, fileName(media, index, media.extension()));
        Path tmp = tmpBasePath.resolveSibling(tmpBasePath.getFileName().toString() + ".tmp");
        if (!tmp.normalize().startsWith(directory.normalize())) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_URL, "Unsafe Douyin media filename");
        }
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                ensureNotCancelled(cancellationRequested);
                try {
                    DownloadResult result = executeDownload(media, tmp, cancellationRequested);
                    Path finalPath = safeOutputPath(directory, fileName(media, index, result.extension()));
                    Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING);
                    return new DouyinDownloadedFile(finalPath, result.bytes());
                } catch (DouyinClientException e) {
                    Files.deleteIfExists(tmp);
                    if (attempt >= MAX_ATTEMPTS || !retryable(e.code())) {
                        throw e;
                    }
                    sleepBeforeRetry(attempt, cancellationRequested);
                } catch (ResourceAccessException e) {
                    Files.deleteIfExists(tmp);
                    DouyinClientException mapped = new DouyinClientException(
                            isTimeout(e) ? DouyinClientErrorCode.NETWORK_TIMEOUT : DouyinClientErrorCode.NETWORK_ERROR,
                            "Douyin media download failed", e);
                    if (attempt >= MAX_ATTEMPTS) {
                        throw mapped;
                    }
                    sleepBeforeRetry(attempt, cancellationRequested);
                }
            }
            throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR, "Douyin media download failed");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private DownloadResult executeDownload(DouyinMedia media,
                                           Path tmp,
                                           BooleanSupplier cancellationRequested)
            throws DouyinClientException {
        try {
            HttpHeaders headers = new HttpHeaders();
            DouyinRequestHeaders.applyStandard(headers);
            headers.set(HttpHeaders.REFERER, DouyinRequestHeaders.REFERER);
            return restTemplate.execute(media.url(), HttpMethod.GET, request -> {
                request.getHeaders().putAll(headers);
            }, response -> {
                try {
                    return writeResponse(media, response, tmp, cancellationRequested);
                } catch (DouyinClientException e) {
                    throw new DownloadFailure(e);
                }
            });
        } catch (DownloadFailure e) {
            throw e.exception;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status == 403) {
                throw new DouyinClientException(DouyinClientErrorCode.HTTP_FORBIDDEN,
                        "Douyin media returned 403", e);
            }
            if (status == 429) {
                throw new DouyinClientException(DouyinClientErrorCode.RATE_LIMITED,
                        "Douyin media was rate limited", e);
            }
            throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR,
                    "Douyin media returned HTTP " + status, e);
        }
    }

    private DownloadResult writeResponse(DouyinMedia media,
                                         ClientHttpResponse response,
                                         Path tmp,
                                         BooleanSupplier cancellationRequested)
            throws IOException, DouyinClientException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            int status = response.getStatusCode().value();
            if (status == 403) {
                throw new DouyinClientException(DouyinClientErrorCode.HTTP_FORBIDDEN,
                        "Douyin media returned 403");
            }
            if (status == 429) {
                throw new DouyinClientException(DouyinClientErrorCode.RATE_LIMITED,
                        "Douyin media was rate limited");
            }
            throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR,
                    "Douyin media returned HTTP " + status);
        }
        long expected = response.getHeaders().getContentLength();
        long written = 0L;
        try (InputStream in = response.getBody();
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                ensureNotCancelled(cancellationRequested);
                out.write(buffer, 0, read);
                written += read;
            }
        }
        if (expected >= 0 && written != expected) {
            log.info("Douyin media Content-Length mismatch: host={}, expected={}, actual={}",
                    safeHost(media.url()), expected, written);
            throw new DouyinClientException(DouyinClientErrorCode.DOWNLOAD_SIZE_MISMATCH,
                    "Douyin media size did not match Content-Length");
        }
        String extension = extensionFromContentType(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .orElse(media.extension());
        return new DownloadResult(written, extension);
    }

    private void validateMediaUrl(URI uri) throws DouyinClientException {
        if (uri == null || uri.getHost() == null) {
            throw new DouyinClientException(DouyinClientErrorCode.MEDIA_URL_MISSING,
                    "Douyin media URL is missing");
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)
                && !(allowHttpForTests && "http".equalsIgnoreCase(scheme))) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_URL,
                    "Douyin media URL must use HTTPS");
        }
        if (!mediaHostAllowed.test(uri.getHost())) {
            log.info("Douyin media URL rejected non-Douyin target: host={}", safeHost(uri));
            throw new DouyinClientException(DouyinClientErrorCode.NON_DOUYIN_TARGET,
                    "Douyin media URL host is not allowed: host=" + safeHost(uri));
        }
    }

    private static Path safeOutputPath(Path directory, String fileName) throws DouyinClientException {
        Path path = directory.resolve(fileName).normalize();
        if (!path.startsWith(directory.normalize())) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_URL, "Unsafe Douyin media filename");
        }
        return path;
    }

    private static String fileName(DouyinMedia media, int index, String extension) {
        String stem = media.fileNameStem() == null || media.fileNameStem().isBlank()
                ? "media-" + index
                : media.fileNameStem();
        String ext = extension;
        if (media.type() == DouyinMediaType.LIVE_PHOTO_VIDEO && !"mp4".equals(ext)) {
            ext = "mp4";
        }
        return sanitize(stem) + "." + sanitizeExtension(ext);
    }

    private static String sanitize(String raw) {
        String value = raw == null ? "" : raw.trim();
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.isBlank()) {
            return "unknown";
        }
        return sanitized.length() > 120 ? sanitized.substring(0, 120) : sanitized;
    }

    private static String sanitizeExtension(String raw) {
        String ext = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return ext.isBlank() ? "bin" : ext;
    }

    private static boolean defaultMediaHostAllowed(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return ALLOWED_MEDIA_HOST_SUFFIXES.stream()
                .anyMatch(suffix -> normalized.equals(suffix) || normalized.endsWith("." + suffix));
    }

    private static boolean retryable(DouyinClientErrorCode code) {
        return code == DouyinClientErrorCode.NETWORK_ERROR
                || code == DouyinClientErrorCode.NETWORK_TIMEOUT
                || code == DouyinClientErrorCode.RATE_LIMITED
                || code == DouyinClientErrorCode.HTTP_RATE_LIMITED;
    }

    private static Optional<String> extensionFromContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return Optional.empty();
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (normalized.contains("image/jpeg") || normalized.contains("image/jpg")) {
            return Optional.of("jpg");
        }
        if (normalized.contains("image/png")) {
            return Optional.of("png");
        }
        if (normalized.contains("image/webp")) {
            return Optional.of("webp");
        }
        if (normalized.contains("image/gif")) {
            return Optional.of("gif");
        }
        if (normalized.contains("video/mp4")) {
            return Optional.of("mp4");
        }
        return Optional.empty();
    }

    private static void ensureNotCancelled(BooleanSupplier cancellationRequested) throws DouyinClientException {
        if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
            throw new DouyinClientException(DouyinClientErrorCode.CANCELLED, "Douyin media download cancelled");
        }
    }

    private static void sleepBeforeRetry(int attempt, BooleanSupplier cancellationRequested) throws DouyinClientException {
        long deadline = System.currentTimeMillis() + attempt * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ensureNotCancelled(cancellationRequested);
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DouyinClientException(DouyinClientErrorCode.CANCELLED,
                        "Douyin media download interrupted", e);
            }
        }
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current.getClass().getName().toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String safeHost(URI uri) {
        return uri == null || uri.getHost() == null ? "<none>" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static final class DownloadFailure extends RuntimeException {

        private final DouyinClientException exception;

        private DownloadFailure(DouyinClientException exception) {
            super(exception);
            this.exception = exception;
        }
    }

    private record DownloadResult(long bytes, String extension) {
    }
}
