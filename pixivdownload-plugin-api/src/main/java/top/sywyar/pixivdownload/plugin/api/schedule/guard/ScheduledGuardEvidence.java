package top.sywyar.pixivdownload.plugin.api.schedule.guard;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledSensitiveFieldNames;

/**
 * Guard 决定附带的受控、安全证据属性，例如上游事件时间与已裁剪摘要。宿主只把它作为挂起详情或观测数据，
 * 并在持久化、通知或日志边界再次执行凭证材料检查；插件不得放入 Cookie、Authorization、token 或签名。
 */
public record ScheduledGuardEvidence(Map<String, String> attributes) {

    public static final int MAX_ATTRIBUTES = 16;
    public static final int MAX_KEY_BYTES = 64;
    public static final int MAX_VALUE_BYTES = 4_096;
    public static final int MAX_TOTAL_BYTES = 16_384;

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");

    public ScheduledGuardEvidence {
        if (attributes == null || attributes.isEmpty()) {
            attributes = Map.of();
        } else {
            if (attributes.size() > MAX_ATTRIBUTES) {
                throw new IllegalArgumentException("guard evidence has too many attributes");
            }
            Map<String, String> copy = new LinkedHashMap<>();
            int totalBytes = 0;
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String key = validateKey(entry.getKey());
                String value = validateValue(entry.getValue());
                totalBytes = addBytes(totalBytes, key, value);
                if (copy.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("guard evidence has duplicate normalized keys");
                }
            }
            attributes = Map.copyOf(copy);
        }
    }

    public static ScheduledGuardEvidence empty() {
        return new ScheduledGuardEvidence(Map.of());
    }

    private static String validateKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("guard evidence key must not be blank");
        }
        String normalized = value.trim();
        if (!KEY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("guard evidence key has invalid characters");
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("guard evidence key exceeds size limit");
        }
        if (ScheduledSensitiveFieldNames.isSensitiveFieldName(normalized)) {
            throw new IllegalArgumentException("guard evidence key must not declare credential material");
        }
        return normalized;
    }

    private static String validateValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("guard evidence value must not be null");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("guard evidence value must not contain NUL");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("guard evidence value exceeds size limit");
        }
        return value;
    }

    private static int addBytes(int current, String key, String value) {
        int next;
        try {
            next = Math.addExact(current, key.getBytes(StandardCharsets.UTF_8).length);
            next = Math.addExact(next, value.getBytes(StandardCharsets.UTF_8).length);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("guard evidence exceeds total size limit");
        }
        if (next > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("guard evidence exceeds total size limit");
        }
        return next;
    }
}
