package top.sywyar.pixivdownload.quota;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.core.archive.ArchiveExportRules;
import top.sywyar.pixivdownload.i18n.LocalizedException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画廊/小说画廊批量导出共用的纯工具：ID 归一化、打包参数校验、
 * 压缩包内路径净化与 manifest 条目构造。gallery 与 novel 各自的批量服务
 * 只单向依赖本类，互相之间不依赖。
 */
public final class ArchiveExportSupport {

    /** 当前唯一支持的打包格式。 */
    public static final String FORMAT_ZIP = ArchiveExportRules.FORMAT_ZIP;

    /** 打包方式：以作品 ID 为顶层文件夹名。 */
    public static final String GROUP_BY_ID = ArchiveExportRules.GROUP_BY_ID;
    /** 打包方式：按作者/作品分类（默认）。 */
    public static final String GROUP_BY_AUTHOR = ArchiveExportRules.GROUP_BY_AUTHOR;

    private ArchiveExportSupport() {
    }

    // ---- 批量请求参数归一化 -----------------------------------------------------

    public static List<Long> normalizeIds(Collection<Long> ids) {
        return ArchiveExportRules.normalizeIds(ids);
    }

    public static Set<Long> normalizeIdSet(Collection<Long> ids) {
        return ArchiveExportRules.normalizeIdSet(ids);
    }

    public static List<Long> applyExclusions(Collection<Long> ids, Collection<Long> excludeIds) {
        return ArchiveExportRules.applyExclusions(ids, excludeIds);
    }

    /** 归一化打包格式；不支持的格式直接抛 400。 */
    public static String normalizeFormat(String format) {
        String normalized = ArchiveExportRules.normalizeFormatToken(format);
        if (!ArchiveExportRules.supportsFormat(normalized)) {
            throw new LocalizedException(HttpStatus.BAD_REQUEST,
                    "validation.archive.export.format.unsupported",
                    "不支持的打包格式：{0}", normalized);
        }
        return normalized;
    }

    /** 是否按作品 ID 打包（默认按作者/作品分类）。 */
    public static boolean groupById(String groupBy) {
        return ArchiveExportRules.groupById(groupBy);
    }

    // ---- 压缩包内路径与 manifest -------------------------------------------------

    public static String authorSegment(Long authorId, String authorName) {
        return ArchiveExportRules.authorSegment(authorId, authorName);
    }

    public static String workSegment(long id, String title) {
        return ArchiveExportRules.workSegment(id, title);
    }

    public static String safeRelativePath(String relativePath) {
        return ArchiveExportRules.safeRelativePath(relativePath);
    }

    public static String safeSegment(String value, String fallback) {
        return ArchiveExportRules.safeSegment(value, fallback);
    }

    public static Map<String, Object> fileManifest(String archivePath, Path originalPath) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("archivePath", archivePath);
        out.put("originalPath", originalPath == null ? null : originalPath.toAbsolutePath().normalize().toString());
        return out;
    }

    public static List<Map<String, Object>> tagNames(List<TagDto> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(tags.size());
        for (TagDto tag : tags) {
            if (tag == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tag.getTagId());
            item.put("name", tag.getName());
            item.put("translatedName", tag.getTranslatedName());
            out.add(item);
        }
        return out;
    }

    public static byte[] jsonBytes(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            return "[]\n".getBytes(StandardCharsets.UTF_8);
        }
    }

    // ---- 导出结果 -----------------------------------------------------------------

    public record ExportResult(String archiveToken, long archiveExpireSeconds, int workCount, int fileCount) {
        public static ExportResult empty() {
            return empty(0);
        }

        public static ExportResult empty(int workCount) {
            return new ExportResult(null, 0, workCount, 0);
        }

        public boolean emptyArchive() {
            return archiveToken == null || archiveToken.isBlank() || fileCount <= 0;
        }
    }
}
