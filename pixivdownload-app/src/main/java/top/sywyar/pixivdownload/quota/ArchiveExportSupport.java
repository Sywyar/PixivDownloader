package top.sywyar.pixivdownload.quota;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.i18n.LocalizedException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 画廊/小说画廊批量导出共用的纯工具：ID 归一化、打包参数校验、
 * 压缩包内路径净化与 manifest 条目构造。gallery 与 novel 各自的批量服务
 * 只单向依赖本类，互相之间不依赖。
 */
public final class ArchiveExportSupport {

    private static final Pattern UNSAFE_PATH_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]+");

    /** 当前唯一支持的打包格式。 */
    public static final String FORMAT_ZIP = "zip";

    /** 打包方式：以作品 ID 为顶层文件夹名。 */
    public static final String GROUP_BY_ID = "id";
    /** 打包方式：按作者/作品分类（默认）。 */
    public static final String GROUP_BY_AUTHOR = "author";

    private ArchiveExportSupport() {
    }

    // ---- 批量请求参数归一化 -----------------------------------------------------

    public static List<Long> normalizeIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                out.add(id);
            }
        }
        return new ArrayList<>(out);
    }

    public static Set<Long> normalizeIdSet(Collection<Long> ids) {
        return new LinkedHashSet<>(normalizeIds(ids));
    }

    public static List<Long> applyExclusions(Collection<Long> ids, Collection<Long> excludeIds) {
        List<Long> normalized = normalizeIds(ids);
        Set<Long> exclusions = new LinkedHashSet<>(normalizeIds(excludeIds));
        if (exclusions.isEmpty()) {
            return normalized;
        }
        return normalized.stream().filter(id -> !exclusions.contains(id)).toList();
    }

    /** 归一化打包格式；不支持的格式直接抛 400。 */
    public static String normalizeFormat(String format) {
        if (!StringUtils.hasText(format)) {
            return FORMAT_ZIP;
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if (!FORMAT_ZIP.equals(normalized)) {
            throw new LocalizedException(HttpStatus.BAD_REQUEST,
                    "validation.archive.export.format.unsupported",
                    "不支持的打包格式：{0}", normalized);
        }
        return normalized;
    }

    /** 是否按作品 ID 打包（默认按作者/作品分类）。 */
    public static boolean groupById(String groupBy) {
        return groupBy != null && GROUP_BY_ID.equalsIgnoreCase(groupBy.trim());
    }

    // ---- 压缩包内路径与 manifest -------------------------------------------------

    public static String authorSegment(Long authorId, String authorName) {
        String fallback = authorId == null || authorId <= 0 ? "unknown-author" : "author-" + authorId;
        if (!StringUtils.hasText(authorName)) {
            return fallback;
        }
        return safeSegment(authorName, fallback);
    }

    public static String workSegment(long id, String title) {
        String safeTitle = safeSegment(title, "untitled");
        return id + " - " + safeTitle;
    }

    public static String safeRelativePath(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return "file";
        }
        String[] parts = relativePath.replace('\\', '/').split("/");
        List<String> safe = new ArrayList<>(parts.length);
        for (String part : parts) {
            safe.add(safeSegment(part, "file"));
        }
        return String.join("/", safe);
    }

    public static String safeSegment(String value, String fallback) {
        String source = StringUtils.hasText(value) ? value.trim() : fallback;
        String clean = UNSAFE_PATH_CHARS.matcher(source).replaceAll("_").trim();
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        if (!StringUtils.hasText(clean) || ".".equals(clean) || "..".equals(clean)) {
            clean = fallback;
        }
        if (clean.length() > 120) {
            clean = clean.substring(0, 120).trim();
        }
        return clean;
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
