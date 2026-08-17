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
    /**
     * 创建 {@code ScheduleTaskDefinitionUpdate} 实例。
     *
     * @param name 名称
     * @param sourceType 来源类型
     * @param sourceOwnerPluginId 来源所有者插件标识
     * @param definitionSchema 定义模式定义
     * @param definitionVersion 定义版本
     * @param definitionJson 定义JSON
     * @param presentationJson 展示信息JSON
     * @param triggerKind 触发方式类别
     * @param intervalMinutes 间隔分钟数
     * @param cronExpr {@code cronExpr} 对应的值
     * @param nextRunTime 下次值运行时间
     */
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
