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

    /**
     * {@code ZIP} 对应的值格式标识。
     */
    public static final String FORMAT_ZIP = "zip";
    /**
     * 按标识分组的标识。
     */
    public static final String GROUP_BY_ID = "id";
    /**
     * 按作者分组的标识。
     */
    public static final String GROUP_BY_AUTHOR = "author";

    private ArchiveExportRules() {
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param ids 标识集合
     * @return 方法返回的列表
     */
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

    /**
     * 执行对应操作并返回结果。
     *
     * @param ids 标识集合
     * @return 方法返回的集合
     */
    public static Set<Long> normalizeIdSet(Collection<Long> ids) {
        return new LinkedHashSet<>(normalizeIds(ids));
    }

    /**
     * 执行对应操作。
     *
     * @param ids 标识集合
     * @param excludeIds 排除项标识集合
     * @return 方法返回的列表
     */
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
     *
     * @param format 格式
     * @return 方法返回的字符串
     */
    public static String normalizeFormatToken(String format) {
        if (!hasText(format)) {
            return FORMAT_ZIP;
        }
        return format.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 判断格式是否满足条件。
     *
     * @param format 格式
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean supportsFormat(String format) {
        return FORMAT_ZIP.equals(normalizeFormatToken(format));
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param groupBy {@code groupBy} 对应的值
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean groupById(String groupBy) {
        return groupBy != null && GROUP_BY_ID.equalsIgnoreCase(groupBy.trim());
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param authorId 作者标识
     * @param authorName 作者名称
     * @return 方法返回的字符串
     */
    public static String authorSegment(Long authorId, String authorName) {
        String fallback = authorId == null || authorId <= 0 ? "unknown-author" : "author-" + authorId;
        if (!hasText(authorName)) {
            return fallback;
        }
        return safeSegment(authorName, fallback);
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param id 标识
     * @param title 标题
     * @return 方法返回的字符串
     */
    public static String workSegment(long id, String title) {
        return id + " - " + safeSegment(title, "untitled");
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param relativePath 相对路径
     * @return 方法返回的字符串
     */
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

    /**
     * 执行对应操作并返回结果。
     *
     * @param value 值
     * @param fallback 回退项
     * @return 方法返回的字符串
     */
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
