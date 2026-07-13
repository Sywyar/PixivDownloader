package top.sywyar.pixivdownload.core.schedule.migration;

/**
 * 把一条插件所有的旧计划任务转换为中性持久化模型。
 *
 * <p>适配器只会收到无凭证快照；旧 secret 的读取、复制、回读和清理全部由核心协调器完成。
 * 调用方也会使用当前 owner 已发布的 legacy alias 快照限制适配范围，适配器不能自行声明
 * 或占用任意来源类型。
 */
@FunctionalInterface
public interface LegacyScheduledTaskMigrationAdapter {

    LegacyScheduledTaskMigrationResult migrate(LegacyScheduledTaskSnapshot snapshot);
}
