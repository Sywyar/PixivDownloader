package top.sywyar.pixivdownload.plugin.api.schedule.work;

import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledCredentialText;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledSensitiveFieldNames;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 插件缺席时可读的安全作品展示快照；只保存原始文本、引用或受控 token。 */
public record ScheduledWorkPresentation(
        String title,
        String author,
        String thumbnailReference,
        Map<String, String> attributes
) {

    public static final int MAX_ATTRIBUTES = 32;
    public static final int MAX_TITLE_BYTES = 4_096;
    public static final int MAX_AUTHOR_BYTES = 1_024;
    public static final int MAX_THUMBNAIL_REFERENCE_BYTES = 4_096;
    public static final int MAX_ATTRIBUTE_KEY_BYTES = 64;
    public static final int MAX_ATTRIBUTE_VALUE_BYTES = 4_096;
    public static final int MAX_ATTRIBUTE_TOTAL_BYTES = 32_768;

    private static final Pattern ATTRIBUTE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");

    public ScheduledWorkPresentation {
        title = normalize(title, "presentation title", MAX_TITLE_BYTES);
        author = normalize(author, "presentation author", MAX_AUTHOR_BYTES);
        thumbnailReference = normalize(
                thumbnailReference, "presentation thumbnail reference",
                MAX_THUMBNAIL_REFERENCE_BYTES);
        attributes = validateAttributes(attributes);
    }

    public static ScheduledWorkPresentation empty() {
        return new ScheduledWorkPresentation(null, null, null, Map.of());
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
            throw new IllegalArgumentException("presentation attributes exceed count limit");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !ATTRIBUTE_KEY.matcher(key).matches()
                    || ScheduledSensitiveFieldNames.isSensitiveFieldName(key)) {
                throw new IllegalArgumentException("presentation attribute key is invalid");
            }
            if (value == null || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("presentation attribute value is invalid");
            }
            if (ScheduledCredentialText.containsCredentialMaterial(value)) {
                throw new IllegalArgumentException(
                        "presentation attribute value contains credential material");
            }
            int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
            int valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (keyBytes > MAX_ATTRIBUTE_KEY_BYTES
                    || valueBytes > MAX_ATTRIBUTE_VALUE_BYTES) {
                throw new IllegalArgumentException("presentation attribute exceeds size limit");
            }
            totalBytes = Math.addExact(totalBytes, Math.addExact(keyBytes, valueBytes));
            if (totalBytes > MAX_ATTRIBUTE_TOTAL_BYTES) {
                throw new IllegalArgumentException("presentation attributes exceed total size limit");
            }
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }
}
