package top.sywyar.pixivdownload.core.archive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 归档导出的纯 JDK 归一化与压缩包内路径规则。
 */
public final class ArchiveExportRules {

    private static final Pattern UNSAFE_PATH_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]+");

    public static final String FORMAT_ZIP = "zip";
    public static final String GROUP_BY_ID = "id";
    public static final String GROUP_BY_AUTHOR = "author";

    private ArchiveExportRules() {
    }

    public static List<Long> normalizeIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                normalized.add(id);
            }
        }
        return new ArrayList<>(normalized);
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

    /**
     * 只归一化格式 token，不负责产生面向用户的校验异常。
     */
    public static String normalizeFormatToken(String format) {
        if (!hasText(format)) {
            return FORMAT_ZIP;
        }
        return format.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean supportsFormat(String format) {
        return FORMAT_ZIP.equals(normalizeFormatToken(format));
    }

    public static boolean groupById(String groupBy) {
        return groupBy != null && GROUP_BY_ID.equalsIgnoreCase(groupBy.trim());
    }

    public static String authorSegment(Long authorId, String authorName) {
        String fallback = authorId == null || authorId <= 0 ? "unknown-author" : "author-" + authorId;
        if (!hasText(authorName)) {
            return fallback;
        }
        return safeSegment(authorName, fallback);
    }

    public static String workSegment(long id, String title) {
        return id + " - " + safeSegment(title, "untitled");
    }

    public static String safeRelativePath(String relativePath) {
        if (!hasText(relativePath)) {
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
        String source = hasText(value) ? value.trim() : fallback;
        String clean = UNSAFE_PATH_CHARS.matcher(source).replaceAll("_").trim();
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        if (!hasText(clean) || ".".equals(clean) || "..".equals(clean)) {
            clean = fallback;
        }
        if (clean.length() > 120) {
            clean = clean.substring(0, 120).trim();
        }
        return clean;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
