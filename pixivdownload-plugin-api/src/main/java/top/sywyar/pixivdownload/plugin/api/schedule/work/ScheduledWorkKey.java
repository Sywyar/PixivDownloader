package top.sywyar.pixivdownload.plugin.api.schedule.work;

import java.nio.charset.StandardCharsets;

/** 计划作品身份。只有 {@code workType + id} 共同构成全局去重键，id 始终按不透明 UTF-8 字符串处理。 */
public record ScheduledWorkKey(String workType, String id) {

    public static final int MAX_WORK_TYPE_BYTES = 128;
    public static final int MAX_ID_BYTES = 512;

    public ScheduledWorkKey {
        workType = validateCanonical(workType, "work type", MAX_WORK_TYPE_BYTES);
        id = validateOpaque(id, "work id", MAX_ID_BYTES);
    }

    private static String validateCanonical(String value, String label, int maxBytes) {
        String normalized = validateOpaque(value, label, maxBytes).trim();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds size limit");
        }
        return normalized;
    }

    private static String validateOpaque(String value, String label, int maxBytes) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " must not contain NUL");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds size limit");
        }
        return value;
    }
}
