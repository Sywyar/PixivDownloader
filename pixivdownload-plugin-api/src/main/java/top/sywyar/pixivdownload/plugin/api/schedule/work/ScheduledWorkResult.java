package top.sywyar.pixivdownload.plugin.api.schedule.work;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 单作品同步执行的安全结果；部分失败或关系未写入必须抛执行异常，不能返回已完成。 */
public record ScheduledWorkResult(
        Outcome outcome,
        String resultCode,
        Map<String, String> attributes
) {

    public static final int MAX_ATTRIBUTES = 16;
    public static final int MAX_ATTRIBUTE_VALUE_BYTES = 4_096;
    public static final int MAX_ATTRIBUTE_TOTAL_BYTES = 16_384;

    private static final Pattern MACHINE_CODE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern ATTRIBUTE_KEY =
            Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");

    public enum Outcome {
        COMPLETED,
        ALREADY_COMPLETED,
        SKIPPED
    }

    public ScheduledWorkResult {
        if (outcome == null) {
            throw new IllegalArgumentException("work outcome must not be null");
        }
        if (resultCode == null || !MACHINE_CODE.matcher(resultCode.trim()).matches()) {
            throw new IllegalArgumentException("work result code must be a bounded machine code");
        }
        resultCode = resultCode.trim();
        attributes = validateAttributes(attributes);
    }

    public static ScheduledWorkResult completed() {
        return new ScheduledWorkResult(Outcome.COMPLETED, "work.completed", Map.of());
    }

    public static ScheduledWorkResult alreadyCompleted() {
        return new ScheduledWorkResult(Outcome.ALREADY_COMPLETED, "work.already-completed", Map.of());
    }

    private static Map<String, String> validateAttributes(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("work result attributes exceed count limit");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !ATTRIBUTE_KEY.matcher(key).matches()
                    || value == null || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("work result attribute is invalid");
            }
            int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
            int valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (valueBytes > MAX_ATTRIBUTE_VALUE_BYTES) {
                throw new IllegalArgumentException("work result attribute exceeds size limit");
            }
            totalBytes = Math.addExact(totalBytes, Math.addExact(keyBytes, valueBytes));
            if (totalBytes > MAX_ATTRIBUTE_TOTAL_BYTES) {
                throw new IllegalArgumentException("work result attributes exceed total size limit");
            }
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }
}
