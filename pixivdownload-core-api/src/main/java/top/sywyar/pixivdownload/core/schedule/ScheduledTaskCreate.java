package top.sywyar.pixivdownload.core.schedule;

/**
 * 已经来源能力校验的 canonical 计划任务创建命令。
 *
 * <p>本命令只表达首次创建的业务字段；自增 id、运行认领、结果、挂起、checkpoint、凭证和 CAS 版本
 * 均由核心持久化实现初始化或经 {@link ScheduledTaskStore} 的专用方法变更。
 */
public record ScheduledTaskCreate(
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
        Long nextRunTime,
        long createdTime
) {
    public ScheduledTaskCreate {
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
        if (presentationJson == null) {
            throw new IllegalArgumentException("presentationJson must not be null");
        }
        requireText(triggerKind, "triggerKind");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
