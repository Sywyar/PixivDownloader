package top.sywyar.pixivdownload.plugin.api.schedule.source;

import java.util.Map;

/**
 * 插件缺席时仍可安全回显的任务实例快照。字段保存原始文本或受控 token，不保存已本地化文案与凭证。
 */
public record ScheduledTaskPresentation(
        String title,
        String summary,
        Map<String, String> attributes
) {

    public ScheduledTaskPresentation {
        title = normalize(title);
        summary = normalize(summary);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ScheduledTaskPresentation empty() {
        return new ScheduledTaskPresentation(null, null, Map.of());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
