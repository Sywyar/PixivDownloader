package top.sywyar.pixivdownload.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.author.AuthorService;
import top.sywyar.pixivdownload.common.PixivDescriptionHtml;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.ArtworkRecord;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.download.request.RecoverMetadataRequest;
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
 * 已下载插画的元数据恢复：pixiv-batch 两阶段恢复（{@link #recoverMetadata}）与基于磁盘扫描的
 * 裸记录重建（{@link #findArtworkOnDisk}）。两条路径只识别默认文件名模板
 * {@code {artwork_id}_p{page}.{ext}} 且页号从 0 连续的作品集合，缺页不恢复为已下载记录。
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
     *   <li>DB 无记录但 {@code {rootFolder}/{artworkId}/} 下有匹配默认模板的图片 → 用 meta + 实际页数/扩展名写完整记录</li>
     *   <li>否则返回 null（调用方按未下载处理）</li>
     * </ul>
     */
    public ArtworkRecord recoverMetadata(Long artworkId, RecoverMetadataRequest meta) {
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
        int count = contiguousPageCount(pageExt);
        if (count <= 0) {
            log.info(logMessage("download.log.stale-record.incomplete",
                    id(artworkId), flatDir.toAbsolutePath()));
            return null;
        }
        LinkedHashSet<String> uniqueExts = new LinkedHashSet<>(new TreeMap<>(pageExt).values());
        String extensions = String.join(",", uniqueExts);
        String absoluteFolder = flatDir.toAbsolutePath().toString();
        log.info(logMessage("download.log.stale-record.restored",
                id(artworkId), absoluteFolder));
        pixivDatabase.insertArtwork(artworkId,
                StringUtils.hasText(meta.getTitle()) ? meta.getTitle() : "",
                absoluteFolder, count, extensions,
                pixivDatabase.getUniqueTime(), meta.getXRestrict(), meta.getIsAi(),
                meta.getAuthorId(),
                normalizedDescription == null ? "" : normalizedDescription);
        observeAuthorIfPresent(artworkId, meta);
        return pixivDatabase.getArtwork(artworkId);
    }

    private void observeAuthorIfPresent(Long artworkId, RecoverMetadataRequest meta) {
        if (meta.getAuthorId() == null) return;
        try {
            authorService.observe(meta.getAuthorId(), meta.getAuthorName());
        } catch (Exception e) {
            log.warn(logMessage("download.log.record-author.failed", id(artworkId)), e);
        }
    }

    /**
     * DB 无记录但 {@code {rootFolder}/{artworkId}/} 下有匹配默认模板的图片时，补登记为裸记录
     * （title/作者等留空），使后续读取面把它视为已下载。仅识别符合默认文件名模板
     * {@code {artwork_id}_p{page}.{ext}} 的文件 —— 恢复出的记录会以 DEFAULT_TEMPLATE_ID 写回 DB，
     * 只有匹配该模板的文件才能被后续 resolveImageFile 查到。
     */
    public ArtworkRecord findArtworkOnDisk(Long artworkId) {
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
        int count = contiguousPageCount(pageExt);
        if (count <= 0) {
            log.info(logMessage("download.log.stale-record.incomplete",
                    id(artworkId), flatDir.toAbsolutePath()));
            return null;
        }
        // 按页号升序收集，使 extensions 顺序稳定（便于排查与单测断言）
        LinkedHashSet<String> uniqueExts = new LinkedHashSet<>(new TreeMap<>(pageExt).values());
        String extensions = String.join(",", uniqueExts);
        String absoluteFolder = flatDir.toAbsolutePath().toString();
        log.info(logMessage("download.log.stale-record.restored",
                id(artworkId), absoluteFolder));
        pixivDatabase.insertArtwork(artworkId, "", absoluteFolder, count, extensions,
                pixivDatabase.getUniqueTime(), null, null, null, "");
        return pixivDatabase.getArtwork(artworkId);
    }

    /**
     * 磁盘文件页号必须从 0 连续到最大页号才视为完整作品集合；
     * 缺页（如部分下载失败的残留文件）不得恢复为已下载记录，否则判重会挡住补齐下载。
     *
     * @return 连续时返回页数（最大页号 + 1），有缺页时返回 -1
     */
    private static int contiguousPageCount(Map<Integer, String> pageExt) {
        int maxPage = Collections.max(pageExt.keySet());
        return pageExt.size() == maxPage + 1 ? maxPage + 1 : -1;
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
            if (!file.isFile()) continue;
            Matcher m = pattern.matcher(file.getName());
            if (!m.matches()) continue;
            String ext = m.group(2).toLowerCase(Locale.ROOT);
            if (!IMAGE_EXTENSIONS.contains(ext)) continue;
            int page = Integer.parseInt(m.group(1));
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
