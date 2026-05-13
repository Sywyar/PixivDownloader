package top.sywyar.pixivdownload.novel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.collection.CollectionService;
import top.sywyar.pixivdownload.common.PixivDescriptionHtml;
import top.sywyar.pixivdownload.common.SafePathSegment;
import top.sywyar.pixivdownload.download.ArtworkFileNameFormatter;
import top.sywyar.pixivdownload.download.DownloadActionResult;
import top.sywyar.pixivdownload.download.PixivBookmarkService;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.quota.UserQuotaService;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class NovelDownloadService {

    public enum NovelFormat {
        TXT("txt"), HTML("html"), EPUB("epub");

        private final String ext;

        NovelFormat(String ext) {
            this.ext = ext;
        }

        public String ext() { return ext; }

        public static NovelFormat parse(String value) {
            if (value == null || value.isBlank()) return TXT;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "html" -> HTML;
                case "epub" -> EPUB;
                default -> TXT;
            };
        }
    }

    private static final String PIXIV_REFERER = "https://www.pixiv.net/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final Set<String> COVER_EXT_WHITELIST = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> IMAGE_EXT_WHITELIST = Set.of("jpg", "jpeg", "png", "webp", "gif");
    /** 单本小说最多下载多少张内嵌图，避免极端情况吃满磁盘。 */
    private static final int MAX_EMBEDDED_IMAGES_PER_NOVEL = 200;

    private final DownloadConfig downloadConfig;
    private final PixivDatabase pixivDatabase;
    private final NovelDatabase novelDatabase;
    private final NovelSeriesService novelSeriesService;
    private final AuthorService authorService;
    private final CollectionService collectionService;
    private final PixivBookmarkService pixivBookmarkService;
    private final UserQuotaService userQuotaService;
    private final RestTemplate downloadRestTemplate;
    private final TaskScheduler taskScheduler;
    private final AppMessages messages;

    private final ConcurrentHashMap<String, NovelDownloadStatus> statusMap = new ConcurrentHashMap<>();

    public NovelDownloadService(DownloadConfig downloadConfig,
                                PixivDatabase pixivDatabase,
                                NovelDatabase novelDatabase,
                                NovelSeriesService novelSeriesService,
                                AuthorService authorService,
                                CollectionService collectionService,
                                PixivBookmarkService pixivBookmarkService,
                                @Nullable UserQuotaService userQuotaService,
                                @Qualifier("downloadRestTemplate") RestTemplate downloadRestTemplate,
                                @Qualifier("taskScheduler") TaskScheduler taskScheduler,
                                AppMessages messages) {
        this.downloadConfig = downloadConfig;
        this.pixivDatabase = pixivDatabase;
        this.novelDatabase = novelDatabase;
        this.novelSeriesService = novelSeriesService;
        this.authorService = authorService;
        this.collectionService = collectionService;
        this.pixivBookmarkService = pixivBookmarkService;
        this.userQuotaService = userQuotaService;
        this.downloadRestTemplate = downloadRestTemplate;
        this.taskScheduler = taskScheduler;
        this.messages = messages;
    }

    @Async
    public void download(NovelDownloadRequest request, String userUuid) {
        Long novelId = request.getNovelId();
        NovelDownloadRequest.Other other = request.getOther() == null
                ? new NovelDownloadRequest.Other() : request.getOther();
        NovelFormat format = NovelFormat.parse(other.getFormat());
        String title = request.getTitle() == null ? String.valueOf(novelId) : request.getTitle();
        NovelDownloadStatus status = new NovelDownloadStatus(novelId, title, format.ext(), userUuid);
        String statusKey = statusKey(novelId, userUuid);
        statusMap.put(statusKey, status);

        try {
            String rawContent = request.getContent() == null ? "" : request.getContent();
            status.setStage("preparing");

            // Resolve folder
            validateUserDownloadFolder(other);
            Path downloadRoot = resolveEffectiveDownloadRoot(other).toAbsolutePath().normalize();
            Path downloadPath = downloadRoot;
            if (other.isUserDownload() && other.getUsername() != null && !downloadConfig.isUserFlatFolder()) {
                downloadPath = downloadPath.resolve(SafePathSegment.requireSafeDirectoryName(other.getUsername()));
                if (other.getXRestrict() == 2) {
                    downloadPath = downloadPath.resolve("R18G");
                } else if (other.getXRestrict() == 1) {
                    downloadPath = downloadPath.resolve("R18");
                }
            }
            String folderName = "novel-" + novelId;
            downloadPath = downloadPath.resolve(folderName).normalize();
            ensureWithinDownloadRoot(downloadRoot, downloadPath);
            status.setFolderName(displayFolderName(downloadRoot, downloadPath));
            Files.createDirectories(downloadPath);
            status.setDownloadPath(downloadPath.toString());

            // Resolve filename template
            long timestamp = other.getFileNameTimestamp() != null
                    ? TimestampUtils.toMillis(other.getFileNameTimestamp())
                    : TimestampUtils.nowMillis();
            String template = ArtworkFileNameFormatter.normalizeTemplate(other.getFileNameTemplate());
            long templateId = pixivDatabase.getOrCreateFileNameTemplateId(template);
            String safeAuthorName = ArtworkFileNameFormatter.normalizeBaseName(
                    other.getAuthorName(), other.getAuthorId() == null ? "" : String.valueOf(other.getAuthorId()));
            Long fileAuthorNameId = safeAuthorName.isEmpty()
                    ? null : pixivDatabase.getOrCreateFileAuthorNameId(safeAuthorName);
            List<String> names = ArtworkFileNameFormatter.formatAll(
                    template, novelId, title, other.getAuthorId(), other.getAuthorName(),
                    timestamp, 1, other.isAi(), other.getXRestrict());
            String baseName = names.isEmpty() ? String.valueOf(novelId) : names.get(0);

            // Best-effort 内嵌图片下载（与正文同目录、embed_{id}.{ext}）；
            // 写入 HTML/EPUB 之前完成，使写入时即可解析为本地图片链接。
            Map<String, String> embeddedExts = downloadEmbeddedImages(
                    novelId, rawContent, other.getEmbeddedImages(), downloadPath, request.getCookie());

            // Write file
            status.setStage("writing");
            String ext = format.ext();
            Path outputFile = downloadPath.resolve(baseName + "." + ext);
            NovelMarkupParser.ImageResolver resolver = localFolderResolver(embeddedExts);
            switch (format) {
                case TXT -> writeTxt(outputFile, rawContent);
                case HTML -> writeHtml(outputFile, title, rawContent, other, resolver);
                case EPUB -> writeEpub(outputFile, title, other.getAuthorName(),
                        other.getLanguage(), rawContent);
            }

            // Best-effort 封面下载（与正文同目录、_thumb.{ext}）
            String coverExt = downloadCover(other.getCoverUrl(), downloadPath, baseName, request.getCookie());

            // Persist DB
            String description = PixivDescriptionHtml.normalizeLinks(other.getDescription());
            long uniqueTime = novelDatabase.getUniqueTime(
                    other.getUploadTimestamp() != null ? TimestampUtils.toMillis(other.getUploadTimestamp()) : timestamp);
            novelDatabase.insertNovel(novelId, title, downloadPath.toAbsolutePath().toString(), 1, ext, uniqueTime,
                    other.getXRestrict(), other.isAi(), other.getAuthorId(), description,
                    templateId, fileAuthorNameId, other.getSeriesId(), other.getSeriesOrder(),
                    other.getWordCount(), other.getTextLength(), other.getReadingTimeSeconds(),
                    other.getPageCount(), other.isOriginal(), other.getLanguage(), rawContent, coverExt);

            // Tags
            if (other.getTags() != null && !other.getTags().isEmpty()) {
                novelDatabase.clearNovelTags(novelId);
                novelDatabase.saveNovelTags(novelId, other.getTags());
            }
            // Author + series
            if (other.getAuthorId() != null && other.getAuthorId() > 0) {
                authorService.observe(other.getAuthorId(), other.getAuthorName());
            }
            if (other.getSeriesId() != null && other.getSeriesId() > 0) {
                // 前端/脚本若一并送来了系列简介/封面/tags，由 NovelSeriesService.observeWithMetadata 落库；
                // 否则退回到原来仅 upsert 标题/作者的 observeSeries()。
                boolean hasRichMeta = (other.getSeriesDescription() != null && !other.getSeriesDescription().isBlank())
                        || (other.getSeriesCoverUrl() != null && !other.getSeriesCoverUrl().isBlank())
                        || (other.getSeriesTags() != null && !other.getSeriesTags().isEmpty());
                if (hasRichMeta) {
                    novelSeriesService.observeWithMetadata(
                            other.getSeriesId(), other.getSeriesTitle(), other.getAuthorId(),
                            other.getSeriesDescription(), other.getSeriesCoverUrl(),
                            other.getSeriesTags(), request.getCookie());
                } else {
                    novelDatabase.observeSeries(other.getSeriesId(), other.getSeriesTitle(), other.getAuthorId());
                }
            }

            // Quota tracking (multi-mode guests)
            if (userUuid != null && userQuotaService != null) {
                userQuotaService.recordFolder(userUuid, downloadPath);
            }

            // Best-effort bookmark
            if (other.isBookmark()) {
                status.setBookmarkResult(pixivBookmarkService.bookmarkNovel(novelId, request.getCookie()));
            }

            // Best-effort collection
            if (other.getCollectionId() != null) {
                try {
                    boolean added = collectionService.addNovel(other.getCollectionId(), novelId);
                    status.setCollectionResult(added
                            ? DownloadActionResult.success(messages.get("collection.result.added"))
                            : DownloadActionResult.exists(messages.get("collection.result.exists")));
                } catch (Exception e) {
                    log.warn("novel collection add failed: novel={}, collection={}: {}",
                            novelId, other.getCollectionId(), e.getMessage(), e);
                    status.setCollectionResult(DownloadActionResult.failed(
                            messages.get("collection.result.failed")));
                }
            }

            status.setStage("completed");
            status.setCompleted(true);
            status.setEndTime(java.time.LocalDateTime.now());
            log.info("novel download completed: id={}, format={}, path={}", novelId, ext, downloadPath);
        } catch (Exception e) {
            log.error("novel download failed: id={}", novelId, e);
            status.setCompleted(true);
            status.setFailed(true);
            status.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            taskScheduler.schedule(
                    () -> statusMap.remove(statusKey),
                    Instant.now().plusSeconds(300));
        }
    }

    public NovelDownloadStatus getStatus(Long novelId) {
        return findAnyStatus(novelId);
    }

    public NovelDownloadStatus getStatus(Long novelId, String ownerUuid, boolean admin) {
        if (admin) {
            return findAnyStatus(novelId);
        }
        return statusMap.get(statusKey(novelId, ownerUuid));
    }

    public static void validateUserDownloadFolder(NovelDownloadRequest.Other other) {
        if (other != null && other.isUserDownload() && other.getUsername() != null) {
            SafePathSegment.requireSafeDirectoryName(other.getUsername());
        }
    }

    /**
     * 在所有 owner 中查找首个匹配 novelId 的下载状态。
     * 仅用于 admin / solo 路径——multi 模式下两个用户并发下载同一小说时返回任意一方，不保证稳定性。
     */
    private NovelDownloadStatus findAnyStatus(Long novelId) {
        if (novelId == null) {
            return null;
        }
        for (NovelDownloadStatus status : statusMap.values()) {
            if (novelId.equals(status.getNovelId())) {
                return status;
            }
        }
        return null;
    }

    private String statusKey(Long novelId, String ownerUuid) {
        return (ownerUuid == null ? "admin" : ownerUuid) + ":" + novelId;
    }

    private Path resolveEffectiveDownloadRoot(NovelDownloadRequest.Other other) {
        Path defaultRoot = Paths.get(downloadConfig.getRootFolder());
        if (other != null && other.getCollectionId() != null) {
            return collectionService.resolveDownloadRoot(other.getCollectionId(), defaultRoot);
        }
        return defaultRoot;
    }

    private void ensureWithinDownloadRoot(Path downloadRoot, Path downloadPath) {
        if (!downloadPath.startsWith(downloadRoot)) {
            throw LocalizedException.badRequest(
                    "download.path.segment.invalid",
                    "Unsafe download subdirectory: {0}",
                    downloadPath
            );
        }
    }

    private String displayFolderName(Path root, Path downloadPath) {
        try {
            return root.toAbsolutePath().normalize()
                    .relativize(downloadPath.toAbsolutePath().normalize())
                    .toString();
        } catch (IllegalArgumentException e) {
            return downloadPath.toString();
        }
    }

    /**
     * 下载小说封面到 {@code {downloadPath}/{baseName}_thumb.{ext}}。
     * Best-effort：URL 缺失、host 非 .pximg.net、网络失败一律返回 null，调用方据此把 cover_ext 置 NULL。
     */
    private String downloadCover(String coverUrl, Path downloadPath, String baseName, String cookie) {
        if (coverUrl == null || coverUrl.isBlank()) return null;
        for (String candidateUrl : NovelCoverUrlResolver.downloadCandidates(coverUrl)) {
            String ext = downloadCoverCandidate(candidateUrl, downloadPath, baseName, cookie);
            if (ext != null) {
                return ext;
            }
        }
        return null;
    }

    private String downloadCoverCandidate(String coverUrl, Path downloadPath, String baseName, String cookie) {
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
        String ext = inferCoverExt(uri.getPath());
        Path target = downloadPath.resolve(baseName + "_thumb." + ext);
        try {
            Boolean ok = downloadRestTemplate.execute(coverUrl, HttpMethod.GET,
                    request -> {
                        request.getHeaders().set("Referer", PIXIV_REFERER);
                        request.getHeaders().set("User-Agent", USER_AGENT);
                        if (cookie != null && !cookie.isBlank()) {
                            request.getHeaders().set("Cookie", cookie);
                        }
                    },
                    response -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return Boolean.FALSE;
                        }
                        Files.copy(response.getBody(), target, StandardCopyOption.REPLACE_EXISTING);
                        return Boolean.TRUE;
                    });
            if (Boolean.TRUE.equals(ok)) {
                return ext;
            }
            log.warn("novel cover download non-2xx: {}", coverUrl);
            return null;
        } catch (Exception e) {
            log.warn("novel cover download failed: {} — {}", coverUrl, e.getMessage());
            return null;
        }
    }

    private static String inferCoverExt(String path) {
        if (path == null) return "jpg";
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        if (dot < 0 || dot == last.length() - 1) return "jpg";
        String candidate = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 去掉可能的查询串残余
        int q = candidate.indexOf('?');
        if (q >= 0) candidate = candidate.substring(0, q);
        return COVER_EXT_WHITELIST.contains(candidate) ? candidate : "jpg";
    }

    private void writeTxt(Path file, String raw) throws IOException {
        String txt = NovelMarkupParser.render(raw, NovelMarkupParser.Format.TXT, imageLabels());
        Files.writeString(file, txt, StandardCharsets.UTF_8);
    }

    private void writeHtml(Path file, String title, String raw,
                           NovelDownloadRequest.Other other,
                           NovelMarkupParser.ImageResolver resolver) throws IOException {
        String body = NovelMarkupParser.render(raw, NovelMarkupParser.Format.HTML, resolver, imageLabels());
        StringBuilder html = new StringBuilder()
                .append("<!DOCTYPE html>\n")
                .append("<html lang=\"")
                .append(escapeHtml(other.getLanguage() == null ? "ja" : other.getLanguage()))
                .append("\">\n<head>\n<meta charset=\"UTF-8\">\n<title>")
                .append(escapeHtml(title))
                .append("</title>\n<style>\n")
                .append("body{font-family:serif;line-height:1.7;max-width:42em;margin:2em auto;padding:0 1em;}\n")
                .append("h1,h2{font-weight:700;}\n")
                .append("figure.novel-image{text-align:center;margin:1em 0;max-width:100%;}\n")
                .append("figure.novel-image img{display:block;margin:0 auto;max-width:90%;height:auto;}\n")
                .append(".novel-image-placeholder{color:#888;}\n.novel-jump{color:#888;font-size:0.85em;}\n")
                .append("ruby rt{font-size:0.6em;}\n")
                .append("</style>\n</head>\n<body>\n<h1>").append(escapeHtml(title)).append("</h1>\n")
                .append(body)
                .append("</body>\n</html>\n");
        Files.writeString(file, html.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 把已落盘的内嵌图扩展名映射成 {@link NovelMarkupParser.ImageResolver}：
     * {@code [uploadedimage:id]} → 同目录下相对路径 {@code embed_{id}.{ext}}。
     * pixivimage 暂不支持下载，保持占位符。
     */
    private static NovelMarkupParser.ImageResolver localFolderResolver(Map<String, String> exts) {
        if (exts == null || exts.isEmpty()) return NovelMarkupParser.ImageResolver.NONE;
        return new NovelMarkupParser.ImageResolver() {
            @Override public String uploadedImage(String id) {
                String ext = exts.get(id);
                return ext == null ? null : "embed_" + id + "." + ext;
            }
            @Override public String pixivImage(String id) { return null; }
        };
    }

    /**
     * 扫描 raw 中出现的 {@code [uploadedimage:id]}，逐张下载至 {@code {downloadPath}/embed_{id}.{ext}}，
     * 持久化映射到 {@code novel_images} 表。
     * Best-effort：单张失败不抛异常；URL 缺失或非 pximg.net 一律跳过。
     *
     * @return id → 实际落盘扩展名的映射（仅成功的条目）。
     */
    private Map<String, String> downloadEmbeddedImages(long novelId, String rawContent,
                                                       Map<String, String> urlMap,
                                                       Path downloadPath, String cookie) {
        Set<String> ids = NovelMarkupParser.findUploadedImageIds(rawContent);
        if (ids.isEmpty() || urlMap == null || urlMap.isEmpty()) {
            // 没有占位符或者前端没传 URL（可能为公开 API 限制等），直接跳过
            return Map.of();
        }
        // 清掉历史记录，避免遗留旧 ext
        novelDatabase.clearNovelImages(novelId);
        Map<String, String> success = new LinkedHashMap<>();
        int budget = MAX_EMBEDDED_IMAGES_PER_NOVEL;
        for (String id : ids) {
            if (budget-- <= 0) {
                log.warn("novel embedded image budget exhausted: novelId={}", novelId);
                break;
            }
            String url = urlMap.get(id);
            if (url == null || url.isBlank()) continue;
            String ext = downloadOneEmbeddedImage(novelId, id, url, downloadPath, cookie);
            if (ext != null) success.put(id, ext);
        }
        if (!success.isEmpty()) {
            log.info("novel embedded images downloaded: novelId={}, count={}/{}", novelId, success.size(), ids.size());
        }
        return success;
    }

    private String downloadOneEmbeddedImage(long novelId, String imageId, String url,
                                            Path downloadPath, String cookie) {
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
        String ext = inferImageExt(uri.getPath());
        Path target = downloadPath.resolve("embed_" + imageId + "." + ext);
        try {
            Boolean ok = downloadRestTemplate.execute(url, HttpMethod.GET,
                    request -> {
                        request.getHeaders().set("Referer", PIXIV_REFERER);
                        request.getHeaders().set("User-Agent", USER_AGENT);
                        if (cookie != null && !cookie.isBlank()) {
                            request.getHeaders().set("Cookie", cookie);
                        }
                    },
                    response -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return Boolean.FALSE;
                        }
                        Files.copy(response.getBody(), target, StandardCopyOption.REPLACE_EXISTING);
                        return Boolean.TRUE;
                    });
            if (Boolean.TRUE.equals(ok)) {
                novelDatabase.saveNovelImage(novelId, imageId, ext);
                return ext;
            }
            log.warn("novel embed image non-2xx: novelId={}, id={}", novelId, imageId);
            return null;
        } catch (Exception e) {
            log.warn("novel embed image download failed: novelId={}, id={}, url={} — {}",
                    novelId, imageId, url, e.getMessage());
            return null;
        }
    }

    private static String inferImageExt(String path) {
        if (path == null) return "jpg";
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        if (dot < 0 || dot == last.length() - 1) return "jpg";
        String candidate = last.substring(dot + 1).toLowerCase(Locale.ROOT);
        int q = candidate.indexOf('?');
        if (q >= 0) candidate = candidate.substring(0, q);
        return IMAGE_EXT_WHITELIST.contains(candidate) ? candidate : "jpg";
    }

    private void writeEpub(Path file, String title, String author, String language,
                           String raw) throws IOException {
        String body = NovelMarkupParser.render(raw, NovelMarkupParser.Format.XHTML, imageLabels());
        byte[] epub = NovelEpubWriter.write(title, author, language,
                List.of(new NovelEpubWriter.Chapter(title, body)),
                epubLabels());
        Files.write(file, epub);
    }

    private NovelMarkupParser.ImageLabels imageLabels() {
        return new NovelMarkupParser.ImageLabels() {
            @Override public String uploadedImage(String id) {
                return messages.get("novel.render.uploaded-image", id);
            }

            @Override public String pixivImage(String id) {
                return messages.get("novel.render.pixiv-image", id);
            }
        };
    }

    private NovelEpubWriter.Labels epubLabels() {
        return new NovelEpubWriter.Labels() {
            @Override public String untitled() {
                return messages.get("novel.epub.untitled");
            }

            @Override public String unknownAuthor() {
                return messages.get("novel.epub.unknown-author");
            }

            @Override public String chapter(int index) {
                return messages.get("novel.epub.chapter", index);
            }
        };
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
