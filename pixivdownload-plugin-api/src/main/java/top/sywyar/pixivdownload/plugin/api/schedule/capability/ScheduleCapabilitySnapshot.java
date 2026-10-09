package top.sywyar.pixivdownload.plugin.api.schedule.capability;

import java.util.List;

/**
 * 一次原子发布的计划能力纯值快照。
 *
 * @param epoch 宿主计划能力注册表的纪元标识
 * @param revision 同一纪元内的非负修订号
 * @param owners 所有者集合
 */
public record ScheduleCapabilitySnapshot(
        String epoch,
        long revision,
        List<ScheduleCapabilityOwnerSnapshot> owners
) {

    /**
     * 创建 {@code ScheduleCapabilitySnapshot} 实例。
     *
     * @param epoch 纪元时间
     * @param revision 修订版本
     * @param owners 所有者集合
     */
    public ScheduleCapabilitySnapshot {
        if (epoch == null || epoch.isBlank()) {
            throw new IllegalArgumentException("schedule capability epoch must not be blank");
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("schedule capability revision must not be negative");
        }
        owners = List.copyOf(owners);
    }
}
