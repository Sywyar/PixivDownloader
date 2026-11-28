package top.sywyar.pixivdownload.core.archive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 归档导出的纯 JDK 归一化与压缩包内路径规则。
 */
public final class ArchiveExportRules {

    private static final int SEGMENT_LENGTH = 120;
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
        Set<Long> exclusions = normalizeIdSet(excludeIds);
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
        for (int i = 0; i < parts.length; i++) {
            safe.add(i == parts.length - 1 ? safeFileName(parts[i]) : safeSegment(parts[i], "file"));
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
        return trimTrailingDots(truncate(cleanSegment(value, fallback), SEGMENT_LENGTH), fallback);
    }

    private static String cleanSegment(String value, String fallback) {
        String source = hasText(value) ? value.trim() : fallback;
        return trimTrailingDots(UNSAFE_PATH_CHARS.matcher(source).replaceAll("_").trim(), fallback);
    }

    private static String trimTrailingDots(String clean, String fallback) {
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        return hasText(clean) ? clean : fallback;
    }

    private static String truncate(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit;
        return value.substring(0, end).stripTrailing();
    }

    /**
     * 清理一个文件名，缩短长文件名时保留可容纳的扩展名。
     * @param value 原始文件名
     * @return 安全的包内文件名
     */
    public static String safeFileName(String value) {
        return trimTrailingDots(fileName(cleanSegment(value, "file"), ""), "file");
    }

    private static String fileName(String name, String marker) {
        // Preserve compound extensions when the shortened member still has room for its stem.
        int dot = name.indexOf('.', 1);
        int budget = SEGMENT_LENGTH - marker.length();
        if (dot >= 0 && name.length() - dot >= budget) dot = name.lastIndexOf('.');
        if (dot < 1 || name.length() - dot >= budget) dot = name.length();
        String suffix = name.substring(dot);
        return truncate(name.substring(0, dot), budget - suffix.length()) + marker + suffix;
    }

    /** 一个 ZIP 共用一份成员名集合；返回的最终路径可直接用于 manifest 和文件条目。 */
    public static final class EntryNames {
        private final Directory root = new Directory("");

        /** 创建一个 ZIP 导出使用的成员名集合。 */
        public EntryNames() {}

        /**
         * 登记相对成员名，并在文件名冲突时分配独立名称。
         * @param requested 请求的包内路径
         * @return 可直接写入 ZIP 的唯一相对路径
         * @throws IllegalArgumentException 路径为空或同名条目过多
         */
        public String reserve(String requested) {
            List<String> parts = new ArrayList<>();
            if (requested != null) {
                for (String part : requested.replace('\\', '/').split("/")) {
                    if (!part.isBlank() && !part.equals(".") && !part.equals("..")) parts.add(part);
                }
            }
            if (parts.isEmpty()) throw new IllegalArgumentException("Invalid archive entry name");
            Directory directory = root;
            StringBuilder path = new StringBuilder();
            for (int i = 0; i < parts.size() - 1; i++) {
                directory = directory.child(parts.get(i));
                path.append(directory.name).append('/');
            }
            return path.append(directory.allocate(parts.getLast())).toString();
        }

        private static final class Directory {
            private final String name;
            private final Set<String> used = new HashSet<>();
            private final Map<String, Integer> nextSuffix = new HashMap<>();
            private final Map<String, Directory> children = new HashMap<>();

            private Directory(String name) {
                this.name = name;
            }

            private Directory child(String requested) {
                return children.computeIfAbsent(requested, key -> new Directory(allocate(key)));
            }

            private String allocate(String requested) {
                // 即便在大小写不敏感的磁盘上解包，一个名称也只能用作文件或目录之一。
                String key = requested.toLowerCase(Locale.ROOT);
                if (used.add(key)) return requested;
                for (int i = nextSuffix.getOrDefault(key, 2); i < 10_000; i++) {
                    String candidate = fileName(requested, " (" + i + ")");
                    if (used.add(candidate.toLowerCase(Locale.ROOT))) {
                        nextSuffix.put(key, i + 1);
                        return candidate;
                    }
                }
                throw new IllegalArgumentException("Too many archive entries with the same name");
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
