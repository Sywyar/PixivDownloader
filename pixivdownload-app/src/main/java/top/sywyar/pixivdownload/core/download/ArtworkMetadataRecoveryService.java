package top.sywyar.pixivdownload.core.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.asset.DownloadDirectories;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.InsertArtworkArgument;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.request.RecoverMetadataRequest;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按调用方提供的作品页数校验默认文件名后恢复记录；查询接口本身不推断磁盘作品。
 */
@Slf4j
@Service
public class ArtworkMetadataRecoveryService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final PixivDatabase pixivDatabase;
    private final AuthorService authorService;
    private final DownloadConfig downloadConfig;
    private final AppMessages messages;
    private final DownloadDirectories directories;

    public ArtworkMetadataRecoveryService(PixivDatabase pixivDatabase,
                                          AuthorService authorService,
                                          DownloadConfig downloadConfig,
                                          AppMessages messages,
                                          DownloadDirectories directories) {
        this.pixivDatabase = pixivDatabase;
        this.authorService = authorService;
        this.downloadConfig = downloadConfig;
        this.messages = messages;
        this.directories = directories;
    }

    /**
     * pixiv-batch 两阶段恢复入口：调用方已从 Pixiv 拉回元数据。
     * <ul>
     *   <li>DB 已有记录 → 仅补齐缺失字段，保留软删除记录</li>
     *   <li>DB 无记录且已知总页数，各页均有匹配默认模板的非空图片 → 登记元数据和文件事实</li>
     *   <li>否则返回 null（调用方按未下载处理）</li>
     * </ul>
     */
    public ArtworkRecord recoverMetadata(Long artworkId, RecoverMetadataRequest meta) {
        try (var workFileLease = top.sywyar.pixivdownload.core.work.service.WorkFileLock.acquire(top.sywyar.pixivdownload.core.work.model.WorkType.ARTWORK, artworkId)) {
            if (meta == null) {
                meta = new RecoverMetadataRequest();
            }
            ArtworkRecord existing = pixivDatabase.getArtwork(artworkId);
            String normalizedDescription = PixivDescriptionHtml.normalizeLinks(meta.getDescription());
            if (existing != null) {
                // 软删除标记的记录不做元数据回填：文件已删，记录只承担「已下载过，但被删除」的判重职责
                if (existing.deleted()) {
                    return existing;
                }
                pixivDatabase.fillArtworkMetadataIfMissing(artworkId,
                        StringUtils.hasText(meta.getTitle()) ? meta.getTitle() : null,
                        meta.getXRestrict(), meta.getIsAi(), meta.getAuthorId(),
                        StringUtils.hasText(normalizedDescription) ? normalizedDescription : null);
                if (existing.authorId() == null || existing.authorId().equals(meta.getAuthorId())) {
                    observeAuthorIfPresent(artworkId, meta);
                }
                return pixivDatabase.getArtwork(artworkId);
            }
            // DB 无记录：扫描磁盘 → 用 meta 写完整记录
            String rootFolder = downloadConfig.getRootFolder();
            File rootDir = new File(rootFolder);
            if (!rootDir.isDirectory()) {
                return null;
            }
            Path flatDir = Paths.get(rootFolder, String.valueOf(artworkId));
            File dirFile = flatDir.toFile();
            if (!dirFile.isDirectory()) {
                return null;
            }
            int count = meta.getPageCount() == null ? 0 : meta.getPageCount();
            List<String> pageExtensions = scanDefaultTemplateFiles(dirFile, artworkId, count);
            if (pageExtensions.isEmpty()) {
                log.info(logMessage("download.log.stale-record.incomplete", id(artworkId), flatDir.toAbsolutePath()));
                return null;
            }
            String extensions = String.join(",", new LinkedHashSet<>(pageExtensions));
            String absoluteFolder = flatDir.toAbsolutePath().toString();
            var record = InsertArtworkArgument.builder()
                    .artworkId(artworkId)
                    .title(StringUtils.hasText(meta.getTitle()) ? meta.getTitle() : "")
                    .folder(absoluteFolder)
                    .count(count)
                    .extensions(extensions)
                    .time(pixivDatabase.getUniqueTime())
                    .xRestrict(meta.getXRestrict())
                    .isAi(meta.getIsAi())
                    .authorId(meta.getAuthorId())
                    .description(normalizedDescription == null ? "" : normalizedDescription)
                    .build();
            boolean registered = directories.recoverArtwork(artworkId, flatDir,
                    () -> pixivDatabase.registerRecoveredArtwork(record, pageExtensions));
            if (!registered) return pixivDatabase.getArtwork(artworkId);
            log.info(logMessage("download.log.stale-record.restored", id(artworkId), absoluteFolder));
            observeAuthorIfPresent(artworkId, meta);
            return pixivDatabase.getArtwork(artworkId);
        }
    }

    private void observeAuthorIfPresent(Long artworkId, RecoverMetadataRequest meta) {
        if (meta.getAuthorId() == null) return;
        try {
            authorService.fillMissing(meta.getAuthorId(), meta.getAuthorName());
        } catch (Exception e) {
            log.warn(logMessage("download.log.record-author.failed", id(artworkId)), e);
        }
    }

    private List<String> scanDefaultTemplateFiles(File directory, long artworkId, int count) {
        if (count <= 0) return List.of();
        File[] files = directory.listFiles();
        if (files == null) return List.of();
        Pattern pattern = Pattern.compile("^" + artworkId + "_p(\\d+)\\.([A-Za-z0-9]+)$");
        var pages = new TreeMap<Integer, String>();
        for (File file : files) {
            Matcher match = pattern.matcher(file.getName());
            if (!match.matches()) continue;
            String extension = match.group(2).toLowerCase(Locale.ROOT);
            if (!IMAGE_EXTENSIONS.contains(extension)) continue;
            int page;
            try { page = Integer.parseInt(match.group(1)); }
            catch (NumberFormatException invalid) { return List.of(); }
            if (page >= count || !match.group(1).equals(String.valueOf(page))
                    || !top.sywyar.pixivdownload.common.PlainFilePathGuard.isPlainRegularFile(file.toPath())
                    || file.length() == 0 || !file.canRead() || pages.putIfAbsent(page, extension) != null) return List.of();
            if (!match.group(2).equals(extension)) {
                Path recorded = directory.toPath().resolve(artworkId + "_p" + page + "." + extension);
                try {
                    if (!Files.isSameFile(file.toPath(), recorded)) return List.of();
                } catch (IOException unreachable) { return List.of(); }
            }
        }
        return pages.size() == count ? List.copyOf(pages.values()) : List.of();
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private String id(Long value) {
        return value == null ? "null" : String.valueOf(value);
    }
}
