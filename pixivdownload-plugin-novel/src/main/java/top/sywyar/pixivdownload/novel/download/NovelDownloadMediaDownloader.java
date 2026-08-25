package top.sywyar.pixivdownload.novel.download;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.core.pixiv.PixivCoverUrlResolver;
import top.sywyar.pixivdownload.core.pixiv.PixivImageDownloader;
import top.sywyar.pixivdownload.core.pixiv.PixivImageTransferObserver;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/** 下载小说封面和正文内嵌图片，并维护图片持久化与传输预算。 */
@Slf4j
final class NovelDownloadMediaDownloader {

    /** 单本小说最多下载多少张内嵌图，避免极端情况吃满磁盘。 */
    private static final int MAX_EMBEDDED_IMAGES_PER_NOVEL = 200;

    private final NovelDatabase novelDatabase;
    private final PixivImageDownloader pixivImageDownloader;
    private final MessageResolver messages;

    NovelDownloadMediaDownloader(NovelDatabase novelDatabase,
                                 PixivImageDownloader pixivImageDownloader,
                                 MessageResolver messages) {
        this.novelDatabase = novelDatabase;
        this.pixivImageDownloader = pixivImageDownloader;
        this.messages = messages;
    }

    /**
     * 扫描 raw 中出现的 {@code [uploadedimage:id]}，逐张下载至 {@code {downloadPath}/embed_{id}.{ext}}，
     * 持久化映射到 {@code novel_images} 表。
     * Best-effort：单张失败不抛异常；URL 缺失或非 pximg.net 一律跳过。
     *
     * @return id → 实际落盘扩展名的映射（仅成功的条目）。
     */
    Map<String, String> downloadEmbeddedImages(long novelId, String rawContent,
                                               Map<String, String> urlMap,
                                               Path downloadPath, String cookie,
                                               NovelDownloadStatus status,
                                               AtomicLong remainingImageBytes) {
        Set<String> ids = NovelMarkupParser.findUploadedImageIds(rawContent);
        if (ids.isEmpty() || urlMap == null || urlMap.isEmpty()) {
            // 没有占位符或者前端没传 URL（可能为公开 API 限制等），直接跳过
            return Map.of();
        }
        // 清掉历史记录，避免遗留旧 ext
        novelDatabase.clearNovelImages(novelId);
        // 实际会尝试下载的张数（有 URL 的占位符，受预算上限约束），用于进度展示
        int plannedTotal = 0;
        for (String id : ids) {
            String url = urlMap.get(id);
            if (url != null && !url.isBlank()) plannedTotal++;
        }
        plannedTotal = Math.min(plannedTotal, MAX_EMBEDDED_IMAGES_PER_NOVEL);
        if (status != null) {
            status.setStage("downloading-images");
            status.setEmbeddedTotal(plannedTotal);
            status.setEmbeddedDone(0);
        }
        Map<String, String> success = new LinkedHashMap<>();
        URI referer = novelPageReferer(novelId);
        int budget = MAX_EMBEDDED_IMAGES_PER_NOVEL;
        for (String id : ids) {
            ensureNotCancelled(status);
            if (remainingImageBytes.get() <= 0) {
                break;
            }
            if (budget-- <= 0) {
                log.warn("novel embedded image budget exhausted: novelId={}", novelId);
                break;
            }
            String url = urlMap.get(id);
            if (url == null || url.isBlank()) continue;
            String ext = downloadOneEmbeddedImage(
                    novelId, id, url, referer, downloadPath, cookie, status, remainingImageBytes);
            if (ext != null) success.put(id, ext);
            if (status != null) status.setEmbeddedDone(status.getEmbeddedDone() + 1);
        }
        if (!success.isEmpty()) {
            log.info("novel embedded images downloaded: novelId={}, count={}/{}", novelId, success.size(), ids.size());
        }
        return success;
    }

    /**
     * 下载小说封面到 {@code {downloadPath}/{baseName}_thumb.{ext}}。
     * Best-effort：URL 缺失、host 非 .pximg.net、网络失败一律返回 null，调用方据此把 cover_ext 置 NULL。
     */
    String downloadCover(long novelId, String coverUrl, Path downloadPath, String baseName, String cookie,
                         NovelDownloadStatus status, AtomicLong remainingImageBytes) {
        if (coverUrl == null || coverUrl.isBlank()) return null;
        URI referer = novelPageReferer(novelId);
        for (String candidateUrl : PixivCoverUrlResolver.downloadCandidates(coverUrl)) {
            ensureNotCancelled(status);
            String ext = downloadCoverCandidate(
                    candidateUrl, referer, downloadPath, baseName, cookie, status, remainingImageBytes);
            if (ext != null) {
                return ext;
            }
        }
        return null;
    }

    private String downloadCoverCandidate(String coverUrl, URI referer, Path downloadPath,
                                          String baseName, String cookie,
                                          NovelDownloadStatus status, AtomicLong remainingImageBytes) {
        URI uri;
        try {
            uri = URI.create(coverUrl);
        } catch (IllegalArgumentException e) {
            log.warn("novel cover skipped — malformed url: {}", coverUrl);
            return null;
        }
        String host = uri.getHost();
        if (host == null || !host.endsWith(".pximg.net")) {
            log.warn("novel cover skipped — host not pximg.net: {}", host);
            return null;
        }
        Path targetStem = downloadPath.resolve(baseName + "_thumb");
        try {
            String extension = downloadImageWithinBudget(
                    uri,
                    referer,
                    targetStem,
                    cookie,
                    coverTransferObserver(status),
                    remainingImageBytes);
            if (extension != null) {
                return extension;
            }
            log.warn("novel cover download non-2xx: {}", coverUrl);
            return null;
        } catch (Exception e) {
            log.warn("novel cover download failed: {} — {}", coverUrl, e.getMessage());
            return null;
        }
    }

    private String downloadOneEmbeddedImage(long novelId, String imageId, String url, URI referer,
                                            Path downloadPath, String cookie,
                                            NovelDownloadStatus status, AtomicLong remainingImageBytes) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("novel embed image skipped — malformed url: novelId={}, id={}, url={}", novelId, imageId, url);
            return null;
        }
        String host = uri.getHost();
        if (host == null || !host.endsWith(".pximg.net")) {
            log.warn("novel embed image skipped — host not pximg.net: novelId={}, id={}, host={}", novelId, imageId, host);
            return null;
        }
        Path targetStem = downloadPath.resolve("embed_" + imageId);
        try {
            String extension = downloadImageWithinBudget(
                    uri,
                    referer,
                    targetStem,
                    cookie,
                    cancellationObserver(status),
                    remainingImageBytes);
            if (extension != null) {
                novelDatabase.saveNovelImage(novelId, imageId, extension);
                return extension;
            }
            log.warn("novel embed image non-2xx: novelId={}, id={}", novelId, imageId);
            return null;
        } catch (Exception e) {
            log.warn("novel embed image download failed: novelId={}, id={}, url={} — {}",
                    novelId, imageId, url, e.getMessage());
            return null;
        }
    }

    private static URI novelPageReferer(long novelId) {
        return URI.create("https://www.pixiv.net/novel/show.php?id=" + novelId);
    }

    private PixivImageTransferObserver coverTransferObserver(NovelDownloadStatus status) {
        return new PixivImageTransferObserver() {
            @Override
            public void checkCancelled() {
                ensureNotCancelled(status);
            }

            @Override
            public void onContentLength(long contentLength) {
                if (status != null) {
                    status.setCoverTotalBytes(contentLength);
                }
            }

            @Override
            public void onBytesTransferred(long transferredBytes) {
                if (status != null) {
                    status.setCoverDownloadedBytes(transferredBytes);
                }
            }
        };
    }

    private PixivImageTransferObserver cancellationObserver(NovelDownloadStatus status) {
        return new PixivImageTransferObserver() {
            @Override
            public void checkCancelled() {
                ensureNotCancelled(status);
            }
        };
    }

    private String downloadImageWithinBudget(URI source, URI referer, Path targetStem, String cookie,
                                             PixivImageTransferObserver delegate,
                                             AtomicLong remainingImageBytes) throws IOException {
        long maximumBytes = Math.min(PixivImageTransferObserver.MAX_IMAGE_BYTES, remainingImageBytes.get());
        if (maximumBytes <= 0) {
            return null;
        }
        AtomicLong transferredBytes = new AtomicLong();
        try {
            return pixivImageDownloader.downloadImage(
                    source, referer, targetStem, cookie, new PixivImageTransferObserver() {
                @Override
                public long maximumBytes() {
                    return maximumBytes;
                }

                @Override
                public void checkCancelled() {
                    delegate.checkCancelled();
                }

                @Override
                public void onContentLength(long contentLength) {
                    delegate.onContentLength(contentLength);
                }

                @Override
                public void onBytesTransferred(long transferred) {
                    transferredBytes.set(transferred);
                    delegate.onBytesTransferred(transferred);
                }
            });
        } finally {
            remainingImageBytes.updateAndGet(
                    remaining -> Math.max(0L, remaining - transferredBytes.get()));
        }
    }

    private void ensureNotCancelled(NovelDownloadStatus status) {
        if (status != null && status.isCancelled()) {
            throw new CancellationException(messages.get("download.cancelled"));
        }
    }
}
