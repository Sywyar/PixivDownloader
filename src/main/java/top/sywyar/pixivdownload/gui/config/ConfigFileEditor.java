package top.sywyar.pixivdownload.gui.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Surgical（行内替换）方式读写 config.yaml。
 * <p>
 * 只替换 value 部分，保留行尾注释、缩进、空行、section 标题注释，
 * 与 SnakeYAML 重序列化方案相比不会丢失文件格式。
 */
public class ConfigFileEditor {

    /** YAML sexagesimal pattern: values like "23:50" are misinterpreted as integers by SnakeYAML. */
    private static final Pattern YAML_SEXAGESIMAL = Pattern.compile("^-?\\d+:\\d+$");

    private final Path configPath;

    public ConfigFileEditor(Path configPath) {
        this.configPath = configPath;
    }

    // ── 读 ──────────────────────────────────────────────────────────────────────

    /**
     * 读取指定 key 的当前值，仅匹配活跃行（未注释）。
     * 若 key 不存在或被注释掉则返回 null；调用方应自行回退到字段默认值。
     */
    public String read(String key) throws IOException {
        for (String line : readLines()) {
            String trimmed = line.trim();
            if (matchesActiveKey(trimmed, key)) {
                return extractValue(trimmed);
            }
        }
        return null;
    }

    /** 批量读取，仅匹配活跃行（未注释），返回 key→value 的 Map（不存在的 key 不包含在结果中）。 */
    public Map<String, String> readAll(Collection<String> keys) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : readLines()) {
            String trimmed = line.trim();
            for (String key : keys) {
                if (!result.containsKey(key) && matchesActiveKey(trimmed, key)) {
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
     *   <li>找到注释行 → 取消注释并写入（config.yaml 中不允许注释行）</li>
     *   <li>未找到 → 追加到文件末尾</li>
     * </ul>
     */
    public synchronized void write(String key, String value) throws IOException {
        List<String> lines = new ArrayList<>(readLines());
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            if (matchesKey(lines.get(i).trim(), key)) {
                lines.set(i, buildLine(lines.get(i), key, value));
                found = true;
                break;
            }
        }
        if (!found) {
            lines.add(key + ": " + (value != null ? value : ""));
        }
        Files.write(configPath, lines, StandardCharsets.UTF_8);
    }

    /**
     * 批量写入，单次 I/O。
     * 所有 key 均写为活跃行，值为空时写入 "key: "（不注释）。
     */
    public synchronized void writeAll(Map<String, String> values) throws IOException {
        List<String> lines = new ArrayList<>(readLines());
        Set<String> written = new HashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String key = entry.getKey();
                if (!written.contains(key) && matchesKey(trimmed, key)) {
                    lines.set(i, buildLine(lines.get(i), key, entry.getValue()));
                    written.add(key);
                    break;
                }
            }
        }

        // 追加文件中不存在的 key（含 value 为空的字段，均写为活跃行）
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!written.contains(key)) {
                lines.add(key + ": " + (entry.getValue() != null ? entry.getValue() : ""));
            }
        }

        Files.write(configPath, lines, StandardCharsets.UTF_8);
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────────────

    /** 读取所有行；文件尚不存在（如 CLI 首次安装早于 config.yaml 生成）时按空文件处理。 */
    private List<String> readLines() throws IOException {
        if (!Files.exists(configPath)) {
            return List.of();
        }
        return Files.readAllLines(configPath, StandardCharsets.UTF_8);
    }

    /**
     * 判断某行（已 trim）是否与 key 匹配——仅匹配活跃行（未注释）。
     * 供 read()/readAll() 使用，避免读取注释中的示例值。
     */
    private boolean matchesActiveKey(String trimmed, String key) {
        return startsWithKey(trimmed, key);
    }

    /**
     * 判断某行（已 trim）是否与 key 匹配（活跃行或注释行均算）。
     * 供 write()/writeAll() 使用：若配置文件中曾有注释行，也能定位并将其改写为活跃行。
     */
    private boolean matchesKey(String trimmed, String key) {
        if (startsWithKey(trimmed, key)) return true;
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
        String val = (hashIdx >= 0 ? afterColon.substring(0, hashIdx) : afterColon).trim();
        if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")
                && YAML_SEXAGESIMAL.matcher(val.substring(1, val.length() - 1)).matches()) {
            val = val.substring(1, val.length() - 1);
        }
        return val;
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
     * 始终写为活跃行（不注释），config.yaml 中不允许注释行。
     */
    private String buildLine(String originalLine, String key, String value) {
        String ws = leadingWhitespace(originalLine);
        String inlineComment = extractInlineComment(originalLine.trim());
        String commentSuffix = inlineComment.isEmpty() ? "" : "  " + inlineComment;
        String v = (value == null) ? "" : value;
        if (YAML_SEXAGESIMAL.matcher(v).matches()) {
            v = "\"" + v + "\"";
        }
        return ws + key + ": " + v + commentSuffix;
    }
}
