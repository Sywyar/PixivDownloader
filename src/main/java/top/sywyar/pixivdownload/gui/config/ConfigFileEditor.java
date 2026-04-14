package top.sywyar.pixivdownload.gui.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Surgical（行内替换）方式读写 config.yaml。
 * <p>
 * 只替换 value 部分，保留行尾注释、缩进、空行、section 标题注释，
 * 与 SnakeYAML 重序列化方案相比不会丢失文件格式。
 */
public class ConfigFileEditor {

    private final Path configPath;

    public ConfigFileEditor(Path configPath) {
        this.configPath = configPath;
    }

    // ── 读 ──────────────────────────────────────────────────────────────────────

    /**
     * 读取指定 key 的当前值（无论该行是否被注释掉）。
     * 若 key 不存在返回 null。
     */
    public String read(String key) throws IOException {
        for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
            if (matchesKey(line.trim(), key)) {
                return extractValue(line.trim());
            }
        }
        return null;
    }

    /** 批量读取，返回 key→value 的 Map（不存在的 key 不包含在结果中）。 */
    public Map<String, String> readAll(Collection<String> keys) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            for (String key : keys) {
                if (!result.containsKey(key) && matchesKey(trimmed, key)) {
                    result.put(key, extractValue(trimmed));
                }
            }
        }
        return result;
    }

    // ── 写 ──────────────────────────────────────────────────────────────────────

    /**
     * 将 key 写入（或更新）config.yaml。
     * <ul>
     *   <li>找到活跃行 → 替换 value</li>
     *   <li>找到注释行 → 若 value 非空则取消注释并写入；若 value 为空则保持注释</li>
     *   <li>未找到 → 追加到文件末尾</li>
     * </ul>
     *
     * @param commentWhenEmpty 若 true，value 为空时将该行注释掉；false 则仍写入活跃行
     */
    public synchronized void write(String key, String value, boolean commentWhenEmpty) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(configPath, StandardCharsets.UTF_8));
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (matchesKey(lines.get(i).trim(), key)) {
                lines.set(i, buildLine(lines.get(i), key, value, commentWhenEmpty));
                found = true;
                break;
            }
        }
        if (!found && value != null && !value.isBlank()) {
            lines.add(key + ": " + value);
        }
        Files.write(configPath, lines, StandardCharsets.UTF_8);
    }

    /**
     * 批量写入，单次 I/O。
     * map 中 key 对应的 fieldSpec 提供 commentWhenEmpty 语义。
     */
    public synchronized void writeAll(Map<String, String> values,
                                      Map<String, Boolean> commentWhenEmptyMap) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(configPath, StandardCharsets.UTF_8));
        Set<String> written = new HashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                if (!written.contains(key) && matchesKey(trimmed, key)) {
                    boolean cwe = commentWhenEmptyMap.getOrDefault(key, false);
                    lines.set(i, buildLine(lines.get(i), key, entry.getValue(), cwe));
                    written.add(key);
                    break;
                }
            }
        }

        // 追加文件中不存在的 key
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!written.contains(entry.getKey())
                    && entry.getValue() != null && !entry.getValue().isBlank()) {
                lines.add(entry.getKey() + ": " + entry.getValue());
            }
        }

        Files.write(configPath, lines, StandardCharsets.UTF_8);
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────────────

    /**
     * 判断某行（已 trim）是否与 key 匹配（活跃行或注释行均算）。
     */
    private boolean matchesKey(String trimmed, String key) {
        // 活跃行："key: ..." 或 "key :"
        if (startsWithKey(trimmed, key)) return true;
        // 注释行："# key: ..."
        if (trimmed.startsWith("#")) {
            String uncommented = trimmed.substring(1).trim();
            return startsWithKey(uncommented, key);
        }
        return false;
    }

    private boolean startsWithKey(String line, String key) {
        return line.startsWith(key + ":") || line.startsWith(key + " :");
    }

    /** 从行中提取 value（冒号后、行尾注释前的内容，去掉首尾空格）。 */
    private String extractValue(String trimmed) {
        String work = trimmed.startsWith("#") ? trimmed.substring(1).trim() : trimmed;
        int colonIdx = work.indexOf(':');
        if (colonIdx < 0) return "";
        String afterColon = work.substring(colonIdx + 1);
        int hashIdx = afterColon.indexOf('#');
        return (hashIdx >= 0 ? afterColon.substring(0, hashIdx) : afterColon).trim();
    }

    /** 从行中提取行尾注释（"# ..." 部分，含前导空格）。 */
    private String extractInlineComment(String trimmed) {
        String work = trimmed.startsWith("#") ? trimmed.substring(1).trim() : trimmed;
        int colonIdx = work.indexOf(':');
        if (colonIdx < 0) return "";
        String afterColon = work.substring(colonIdx + 1);
        int hashIdx = afterColon.indexOf('#');
        return hashIdx >= 0 ? afterColon.substring(hashIdx).trim() : "";
    }

    /** 获取行首导空白（用于保留缩进）。 */
    private String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
        return line.substring(0, i);
    }

    /**
     * 构建替换后的完整行，保留行首缩进和行尾注释。
     */
    private String buildLine(String originalLine, String key, String value, boolean commentWhenEmpty) {
        String ws = leadingWhitespace(originalLine);
        String inlineComment = extractInlineComment(originalLine.trim());
        String commentSuffix = inlineComment.isEmpty() ? "" : "  " + inlineComment;

        boolean makeComment = commentWhenEmpty && (value == null || value.isBlank());

        if (makeComment) {
            // 保留旧 value（如果有），并注释掉整行
            String oldValue = extractValue(originalLine.trim());
            String displayValue = (oldValue.isBlank() ? "" : oldValue);
            return ws + "# " + key + ": " + displayValue + commentSuffix;
        } else {
            String v = (value == null) ? "" : value;
            return ws + key + ": " + v + commentSuffix;
        }
    }
}
