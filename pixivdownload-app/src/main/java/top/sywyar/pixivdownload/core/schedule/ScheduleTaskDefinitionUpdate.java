package top.sywyar.pixivdownload.core.schedule;

/** 经来源能力校验后的任务定义编辑命令。 */
public record ScheduleTaskDefinitionUpdate(
        String name,
        String sourceType,
        String sourceOwnerPluginId,
        String definitionSchema,
        int definitionVersion,
        String definitionJson,
        String presentationJson,
        String triggerKind,
        Integer intervalMinutes,
        String cronExpr,
        Long nextRunTime
) {
    public ScheduleTaskDefinitionUpdate {
        requireText(name, "name");
        requireText(sourceType, "sourceType");
        requireText(sourceOwnerPluginId, "sourceOwnerPluginId");
        requireText(definitionSchema, "definitionSchema");
        if (definitionVersion <= 0) {
            throw new IllegalArgumentException("definitionVersion must be positive");
        }
        if (definitionJson == null) {
            throw new IllegalArgumentException("definitionJson must not be null");
        }
        requireText(triggerKind, "triggerKind");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
