package top.sywyar.pixivdownload.plugin.api.schedule.source;

import java.nio.charset.StandardCharsets;

/** 宿主保存并交给来源插件解释的不透明任务定义。 */
public record ScheduledTaskDefinition(
        long taskId,
        String sourceType,
        String definitionSchema,
        int definitionVersion,
        String definitionJson,
        ScheduledTaskPresentation presentation
) {

    public static final int MAX_DEFINITION_BYTES = 1_048_576;

    public ScheduledTaskDefinition {
        if (taskId < 0) {
            throw new IllegalArgumentException("task id must not be negative");
        }
        sourceType = requireText(sourceType, "source type");
        definitionSchema = requireText(definitionSchema, "definition schema");
        if (definitionVersion <= 0) {
            throw new IllegalArgumentException("definition version must be positive");
        }
        requireText(definitionJson, "definition JSON");
        if (definitionJson.getBytes(StandardCharsets.UTF_8).length > MAX_DEFINITION_BYTES) {
            throw new IllegalArgumentException("definition JSON exceeds size limit");
        }
        presentation = presentation == null ? ScheduledTaskPresentation.empty() : presentation;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
