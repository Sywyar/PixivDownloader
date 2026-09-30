package top.sywyar.pixivdownload.core.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.core.pixiv.PixivDescriptionHtml;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.InsertArtworkArgument;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.download.request.RecoverMetadataRequest;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
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

    public ArtworkMetadataRecoveryService(PixivDatabase pixivDatabase,
                                          AuthorService authorService,
                                          DownloadConfig downloadConfig,
                                          AppMessages messages) {
        this.pixivDatabase = pixivDatabase;
        this.authorService = authorService;
        this.downloadConfig = downloadConfig;
        this.messages = messages;
    }

    /**
     * pixiv-batch 两阶段恢复入口：调用方已从 Pixiv 拉回元数据。
     * <ul>
     *   <li>DB 已有记录且 title 非空 → 返回原记录（不覆盖任何字段）</li>
     *   <li>DB 已有记录但 title 为空（说明先前是裸记录恢复出来的）→ 仅填补 NULL/空字段后返回最新记录</li>
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
                if (StringUtils.hasText(existing.title())) {
                    return existing;
                }
                pixivDatabase.fillArtworkMetadataIfMissing(artworkId,
                        StringUtils.hasText(meta.getTitle()) ? meta.getTitle() : null,
                        meta.getXRestrict(), meta.getIsAi(), meta.getAuthorId(),
                        StringUtils.hasText(normalizedDescription) ? normalizedDescription : null);
                observeAuthorIfPresent(artworkId, meta);
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
            Map<Integer, String> pageExt = scanDefaultTemplateFiles(dirFile, artworkId);
            if (pageExt.isEmpty()) {
                return null;
            }
            int count = meta.getPageCount() == null ? 0 : meta.getPageCount();
            if (count <= 0 || pageExt.size() != count
                    || !pageExt.containsKey(0) || !pageExt.containsKey(count - 1)
                    || pageExt.keySet().stream().anyMatch(page -> page < 0 || page >= count)) {
                log.info(logMessage("download.log.stale-record.incomplete",
                        id(artworkId), flatDir.toAbsolutePath()));
                return null;
            }
            LinkedHashSet<String> uniqueExts = new LinkedHashSet<>(new TreeMap<>(pageExt).values());
            String extensions = String.join(",", uniqueExts);
            String absoluteFolder = flatDir.toAbsolutePath().toString();
            log.info(logMessage("download.log.stale-record.restored",
                    id(artworkId), absoluteFolder));
            pixivDatabase.insertArtwork(InsertArtworkArgument.builder()
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
                    .build());
            observeAuthorIfPresent(artworkId, meta);
            return pixivDatabase.getArtwork(artworkId);
        }
    }

    private void observeAuthorIfPresent(Long artworkId, RecoverMetadataRequest meta) {
        if (meta.getAuthorId() == null) return;
        try {
            authorService.observe(meta.getAuthorId(), meta.getAuthorName());
        } catch (Exception e) {
            log.warn(logMessage("download.log.record-author.failed", id(artworkId)), e);
        }
    }

    private Map<Integer, String> scanDefaultTemplateFiles(File directory, long artworkId) {
        File[] files = directory.listFiles();
        if (files == null) {
            return Collections.emptyMap();
        }
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(String.valueOf(artworkId)) + "_p(\\d+)\\.([A-Za-z0-9]+)$");
        Map<Integer, String> pageExt = new HashMap<>();
        for (File file : files) {
            if (!top.sywyar.pixivdownload.common.PlainFilePathGuard.isPlainRegularFile(file.toPath())
                    || file.length() == 0 || !file.canRead()) continue;
            Matcher m = pattern.matcher(file.getName());
            if (!m.matches()) continue;
            String ext = m.group(2).toLowerCase(Locale.ROOT);
            if (!IMAGE_EXTENSIONS.contains(ext)) continue;
            int page;
            try { page = Integer.parseInt(m.group(1)); }
            catch (NumberFormatException invalid) { continue; }
            pageExt.merge(page, ext, (existing, incoming) -> existing);
        }
        return pageExt;
    }

    private String logMessage(String code, Object... args) {
        return messages.getForLog(code, args);
    }

    private String id(Long value) {
        return value == null ? "null" : String.valueOf(value);
    }
}
