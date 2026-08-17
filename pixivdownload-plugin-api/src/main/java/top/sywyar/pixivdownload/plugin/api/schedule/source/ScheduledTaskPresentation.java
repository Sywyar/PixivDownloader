package top.sywyar.pixivdownload.plugin.api.schedule.source;

import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledCredentialText;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledSensitiveFieldNames;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 插件缺席时仍可安全回显的任务实例快照。字段保存原始文本或受控 token，不保存已本地化文案与凭证。
 */
public record ScheduledTaskPresentation(
        String title,
        String summary,
        Map<String, String> attributes
) {

    /**
     * 允许的最大属性。
     */
    public static final int MAX_ATTRIBUTES = 32;
    /**
     * 标题允许占用的最大 UTF-8 字节数。
     */
    public static final int MAX_TITLE_BYTES = 4_096;
    /**
     * 摘要允许占用的最大 UTF-8 字节数。
     */
    public static final int MAX_SUMMARY_BYTES = 4_096;
    /**
     * {@code ATTRIBUTE_KEY} 对应的值允许占用的最大 UTF-8 字节数。
     */
    public static final int MAX_ATTRIBUTE_KEY_BYTES = 64;
    /**
     * {@code ATTRIBUTE_VALUE} 对应的值允许占用的最大 UTF-8 字节数。
     */
    public static final int MAX_ATTRIBUTE_VALUE_BYTES = 4_096;
    /**
     * {@code ATTRIBUTE_TOTAL} 对应的值允许占用的最大 UTF-8 字节数。
     */
    public static final int MAX_ATTRIBUTE_TOTAL_BYTES = 32_768;

    private static final Pattern ATTRIBUTE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");

    /**
     * 创建 {@code ScheduledTaskPresentation} 实例。
     *
     * @param title 标题
     * @param summary 摘要
     * @param attributes 属性
     */
    public ScheduledTaskPresentation {
        title = normalize(title, "task presentation title", MAX_TITLE_BYTES);
        summary = normalize(summary, "task presentation summary", MAX_SUMMARY_BYTES);
        attributes = validateAttributes(attributes);
    }

    /**
     * 返回空的 {@code ScheduledTaskPresentation} 实例。
     *
     * @return 方法返回的 {@code ScheduledTaskPresentation} 实例
     */
    public static ScheduledTaskPresentation empty() {
        return new ScheduledTaskPresentation(null, null, Map.of());
    }

    private static String normalize(String value, String label, int maxBytes) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " must not contain NUL");
        }
        if (ScheduledCredentialText.containsCredentialMaterial(normalized)) {
            throw new IllegalArgumentException(label + " must not contain credential material");
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds size limit");
        }
        return normalized;
    }

    private static Map<String, String> validateAttributes(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("task presentation attributes exceed count limit");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !ATTRIBUTE_KEY.matcher(key).matches()
                    || ScheduledSensitiveFieldNames.isSensitiveFieldName(key)) {
                throw new IllegalArgumentException("task presentation attribute key is invalid");
            }
            if (value == null || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("task presentation attribute value is invalid");
            }
            if (ScheduledSensitiveFieldNames.isSensitiveMetadataFieldName(key)
                    && !ScheduledSensitiveFieldNames.isSafeMetadataValue(key, value)) {
                throw new IllegalArgumentException(
                        "task presentation credential metadata value is invalid");
            }
            if (ScheduledCredentialText.containsCredentialMaterial(value)) {
                throw new IllegalArgumentException(
                        "task presentation attribute value contains credential material");
            }
            int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
            int valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (keyBytes > MAX_ATTRIBUTE_KEY_BYTES
                    || valueBytes > MAX_ATTRIBUTE_VALUE_BYTES) {
                throw new IllegalArgumentException("task presentation attribute exceeds size limit");
            }
            totalBytes = Math.addExact(totalBytes, Math.addExact(keyBytes, valueBytes));
            if (totalBytes > MAX_ATTRIBUTE_TOTAL_BYTES) {
                throw new IllegalArgumentException("task presentation attributes exceed total size limit");
            }
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }
}
