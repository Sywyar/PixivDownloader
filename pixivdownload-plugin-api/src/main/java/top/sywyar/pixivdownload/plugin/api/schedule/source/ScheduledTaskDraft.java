package top.sywyar.pixivdownload.plugin.api.schedule.source;

import java.nio.charset.StandardCharsets;

/**
 * 宿主盖章后交给来源插件规范化的未持久化任务定义。草稿只包含纯数据，不得携带凭证或宿主服务引用。
 */
public record ScheduledTaskDraft(
        long taskId,
        String sourceType,
        String definitionSchema,
        int definitionVersion,
        String definitionJson,
        ScheduledTaskPresentation presentation
) {

    public ScheduledTaskDraft {
        if (taskId < 0) {
            throw new IllegalArgumentException("task id must not be negative");
        }
        sourceType = requireText(sourceType, "source type");
        definitionSchema = requireText(definitionSchema, "definition schema");
        if (definitionVersion <= 0) {
            throw new IllegalArgumentException("definition version must be positive");
        }
        requireText(definitionJson, "definition JSON");
        if (definitionJson.getBytes(StandardCharsets.UTF_8).length
                > ScheduledTaskDefinition.MAX_DEFINITION_BYTES) {
            throw new IllegalArgumentException("definition JSON exceeds size limit");
        }
        presentation = presentation == null ? ScheduledTaskPresentation.empty() : presentation;
    }

    /** 保留宿主盖章字段，把草稿原样提升为可持久化定义。 */
    public ScheduledTaskDefinition toDefinition() {
        return new ScheduledTaskDefinition(
                taskId,
                sourceType,
                definitionSchema,
                definitionVersion,
                definitionJson,
                presentation);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
