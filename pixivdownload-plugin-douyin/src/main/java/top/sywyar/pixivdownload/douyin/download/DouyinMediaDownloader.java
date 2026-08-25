package top.sywyar.pixivdownload.douyin.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.client.DouyinErrorClassifier;
import top.sywyar.pixivdownload.douyin.client.request.DouyinRequestHeaders;
import top.sywyar.pixivdownload.douyin.download.validation.DouyinMediaPayloadValidator;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMediaType;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public class DouyinMediaDownloader {

    private static final Logger log = LoggerFactory.getLogger(DouyinMediaDownloader.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_REDIRECT_HOPS = 5;
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

    private final OutboundHttpClient httpClient;
    private final Predicate<String> mediaHostAllowed;
    private final Predicate<URI> credentialOriginAllowed;
    private final boolean allowHttpForTests;

    public DouyinMediaDownloader(OutboundHttpClient httpClient) {
        this(httpClient, DouyinMediaDownloader::defaultMediaHostAllowed,
                DouyinRequestHeaders::isCredentialOrigin, false);
    }

    DouyinMediaDownloader(OutboundHttpClient httpClient, Predicate<String> mediaHostAllowed) {
        this(httpClient, mediaHostAllowed, uri -> false, true);
    }

    DouyinMediaDownloader(OutboundHttpClient httpClient,
                          Predicate<String> mediaHostAllowed,
                          Predicate<URI> credentialOriginAllowed) {
        this(httpClient, mediaHostAllowed, credentialOriginAllowed, true);
    }

    private DouyinMediaDownloader(OutboundHttpClient httpClient,
                                  Predicate<String> mediaHostAllowed,
                                  Predicate<URI> credentialOriginAllowed,
                                  boolean allowHttpForTests) {
        this.httpClient = httpClient;
        this.mediaHostAllowed = mediaHostAllowed;
        this.credentialOriginAllowed = credentialOriginAllowed;
        this.allowHttpForTests = allowHttpForTests;
    }

    public List<DouyinDownloadedFile> download(List<DouyinMedia> media,
                                               Path directory,
                                               BooleanSupplier cancellationRequested)
            throws IOException, DouyinClientException {
        return download(media, directory, cancellationRequested, null);
    }

    public List<DouyinDownloadedFile> download(List<DouyinMedia> media,
                                               Path directory,
                                               BooleanSupplier cancellationRequested,
                                               String credential)
            throws IOException, DouyinClientException {
        if (media == null || media.isEmpty()) {
            throw new DouyinClientException(DouyinClientErrorCode.MEDIA_URL_MISSING,
                    "Resolved Douyin work does not expose downloadable media");
        }
        Files.createDirectories(directory);
        List<DouyinDownloadedFile> files = new ArrayList<>();
        for (int i = 0; i < media.size(); i++) {
            ensureNotCancelled(cancellationRequested);
            files.add(downloadOne(media.get(i), i + 1, directory, cancellationRequested, credential));
        }
        return files;
    }

    private DouyinDownloadedFile downloadOne(DouyinMedia media,
                                             int index,
                                             Path directory,
                                             BooleanSupplier cancellationRequested,
                                             String credential)
            throws IOException, DouyinClientException {
        validateMediaUrl(media.url());
        Path tmpBasePath = safeOutputPath(directory, fileName(media, index, media.extension()));
        Path tmp = tmpBasePath.resolveSibling(tmpBasePath.getFileName().toString() + ".tmp");
        if (!tmp.normalize().startsWith(directory.normalize())) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_URL, "Unsafe Douyin media filename");
        }
        List<URI> candidateUrls = candidateUrls(media);
        int maxAttempts = Math.max(MAX_ATTEMPTS, Math.min(5, candidateUrls.size()));
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                ensureNotCancelled(cancellationRequested);
                try {
                    URI candidateUrl = candidateUrls.get((attempt - 1) % candidateUrls.size());
                    DownloadResult result = executeDownload(
                            media, candidateUrl, tmp, cancellationRequested, credential);
                    Path finalPath = safeOutputPath(directory, fileName(media, index, result.extension()));
                    Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING);
                    return new DouyinDownloadedFile(finalPath, result.bytes(), result.contentType());
                } catch (DouyinClientException e) {
                    Files.deleteIfExists(tmp);
                    boolean alternateCandidateAvailable = candidateUrls.size() > 1 && attempt < maxAttempts;
                    if (attempt >= maxAttempts || (!retryable(e.code())
                            && !(alternateCandidateAvailable
                            && candidateSpecificFailure(e.code())))) {
                        throw e;
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
                                           URI initialUrl,
                                           Path tmp,
                                           BooleanSupplier cancellationRequested,
                                           String credential)
            throws DouyinClientException {
        URI current = initialUrl;
        for (int redirectHop = 0; redirectHop <= MAX_REDIRECT_HOPS; redirectHop++) {
            validateMediaUrl(current);
            ensureNotCancelled(cancellationRequested);
            Map<String, List<String>> headers = new java.util.LinkedHashMap<>(DouyinRequestHeaders.standard());
            if (credential != null && !credential.isBlank() && credentialOriginAllowed.test(current)) {
                headers.put("Cookie", List.of(credential));
            }
            URI requestUri = current;
            try (OutboundHttpStreamResponse response = httpClient.exchangeStream(new OutboundHttpRequest(
                    requestUri, "GET", headers, new byte[0]))) {
                int status = response.statusCode();
                if (!isRedirect(status)) {
                    return writeResponse(media, requestUri, response, tmp, cancellationRequested);
                }
                String location = firstHeader(response.headers(), "Location");
                if (location == null || location.isBlank()) {
                    throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR,
                            "Douyin media redirect has no Location");
                }
                if (redirectHop >= MAX_REDIRECT_HOPS) {
                    throw new DouyinClientException(DouyinClientErrorCode.REDIRECT_LOOP,
                            "Douyin media redirect limit exceeded");
                }
                current = requestUri.resolve(location);
            } catch (OutboundHttpTransportException e) {
                throw new DouyinClientException(
                        isTimeout(e) ? DouyinClientErrorCode.NETWORK_TIMEOUT : DouyinClientErrorCode.NETWORK_ERROR,
                        "Douyin media download failed", e);
            } catch (IOException e) {
                throw new DouyinClientException(
                        DouyinClientErrorCode.NETWORK_ERROR,
                        "Douyin media response could not be read", e);
            }
        }
        throw new DouyinClientException(DouyinClientErrorCode.REDIRECT_LOOP,
                "Douyin media redirect limit exceeded");
    }

    private DownloadResult writeResponse(DouyinMedia media,
                                         URI requestUri,
                                         OutboundHttpStreamResponse response,
                                         Path tmp,
                                         BooleanSupplier cancellationRequested)
            throws IOException, DouyinClientException {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            byte[] body = response.body().readNBytes(64 * 1024 + 1);
            DouyinClientErrorCode code = classifyMediaHttpStatus(status, body, requestUri);
            throw new DouyinClientException(
                    code == null ? DouyinClientErrorCode.NETWORK_ERROR : code,
                    "Douyin media returned HTTP " + status);
        }
        String responseContentType = firstHeader(response.headers(), "Content-Type");
        long responseLength = contentLength(response.headers());
        long metadataLength = media.sizeBytes() == null || media.sizeBytes() <= 0
                ? -1L : media.sizeBytes();
        if (responseLength >= 0 && metadataLength >= 0 && responseLength != metadataLength) {
            log.info("Douyin media response size differs from resolved metadata: host={}, expected={}, response={}",
                    safeHost(requestUri), metadataLength, responseLength);
            throw new DouyinClientException(DouyinClientErrorCode.DOWNLOAD_SIZE_MISMATCH,
                    "Douyin media response size did not match resolved metadata");
        }
        long expected = metadataLength >= 0 ? metadataLength : responseLength;
        long written = 0L;
        try {
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(tmp)) {
                byte[] prefix = in.readNBytes(DouyinMediaPayloadValidator.PREFIX_BYTES);
                DouyinMediaPayloadValidator.requireMediaPayload(responseContentType, prefix);
                ensureNotCancelled(cancellationRequested);
                out.write(prefix);
                written = prefix.length;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    ensureNotCancelled(cancellationRequested);
                    out.write(buffer, 0, read);
                    written += read;
                }
            }
        } catch (IOException e) {
            if (expected >= 0) {
                log.info("Douyin media response ended before Content-Length: host={}, expected={}",
                        safeHost(requestUri), expected);
                throw new DouyinClientException(DouyinClientErrorCode.DOWNLOAD_SIZE_MISMATCH,
                        "Douyin media response ended before Content-Length", e);
            }
            throw e;
        }
        if (expected >= 0 && written != expected) {
            log.info("Douyin media Content-Length mismatch: host={}, expected={}, actual={}",
                    safeHost(requestUri), expected, written);
            throw new DouyinClientException(DouyinClientErrorCode.DOWNLOAD_SIZE_MISMATCH,
                    "Douyin media size did not match Content-Length");
        }
        String extension = extensionFromContentType(responseContentType)
                .orElse(media.extension());
        return new DownloadResult(written, extension, responseContentType);
    }

    private static List<URI> candidateUrls(DouyinMedia media) {
        LinkedHashSet<URI> candidates = new LinkedHashSet<>();
        candidates.add(media.url());
        candidates.addAll(media.fallbackUrls());
        return List.copyOf(candidates);
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
        int port = uri.getPort();
        boolean standardPort = port == -1 || ("https".equalsIgnoreCase(scheme) && port == 443)
                || (allowHttpForTests && "http".equalsIgnoreCase(scheme));
        if (uri.getUserInfo() != null || !standardPort) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_URL,
                    "Douyin media URL has an unsafe origin");
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
                || code == DouyinClientErrorCode.DOWNLOAD_SIZE_MISMATCH
                || code == DouyinClientErrorCode.RATE_LIMITED
                || code == DouyinClientErrorCode.HTTP_RATE_LIMITED
                || code == DouyinClientErrorCode.UPSTREAM_SERVER_ERROR;
    }

    private static boolean candidateSpecificFailure(DouyinClientErrorCode code) {
        return code == DouyinClientErrorCode.HTTP_FORBIDDEN
                || code == DouyinClientErrorCode.UPSTREAM_CLIENT_ERROR
                || code == DouyinClientErrorCode.UPSTREAM_NOT_FOUND;
    }

    private DouyinClientErrorCode classifyMediaHttpStatus(int status, byte[] body, URI uri) {
        if (!credentialOriginAllowed.test(uri) && (status == 401 || status == 403)) {
            return DouyinClientErrorCode.UPSTREAM_CLIENT_ERROR;
        }
        return DouyinErrorClassifier.classifyHttpStatus(status, body);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }

    private static long contentLength(Map<String, List<String>> headers) {
        String value = firstHeader(headers, "Content-Length");
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
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

    private record DownloadResult(long bytes, String extension, String contentType) {
    }

}
