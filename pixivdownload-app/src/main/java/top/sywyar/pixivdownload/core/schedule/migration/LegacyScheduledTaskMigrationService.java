package top.sywyar.pixivdownload.core.schedule.migration;

import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityReservation;

/**
 * 宿主 contribution registrar 使用的旧计划任务迁移语义口。
 *
 * <p>接口不暴露 JDBC、事务或数据库实现。调用方只能提交 registrar 当前持有的不透明 reservation；
 * 实现必须向其绑定的 registry 校验对象身份与有效期，并从预留项内部推导 owner 与 alias route。
 */
public interface LegacyScheduledTaskMigrationService {

    OwnerMigrationReport migrateReservedOwner(
            ScheduleCapabilityReservation reservation,
            LegacyScheduledTaskMigrationAdapter adapter);

    /** 不持有适配器、异常或凭证的 owner 迁移摘要。 */
    record OwnerMigrationReport(
            String ownerPluginId,
            int examined,
            int migrated,
            int rejected,
            int failed
    ) {}
}
