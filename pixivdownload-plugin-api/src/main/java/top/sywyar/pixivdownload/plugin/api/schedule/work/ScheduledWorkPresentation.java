package top.sywyar.pixivdownload.plugin.api.schedule.work;

import java.util.Map;

/** 插件缺席时可读的安全作品展示快照；只保存原始文本、引用或受控 token。 */
public record ScheduledWorkPresentation(
        String title,
        String author,
        String thumbnailReference,
        Map<String, String> attributes
) {

    public ScheduledWorkPresentation {
        title = normalize(title);
        author = normalize(author);
        thumbnailReference = normalize(thumbnailReference);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ScheduledWorkPresentation empty() {
        return new ScheduledWorkPresentation(null, null, null, Map.of());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
