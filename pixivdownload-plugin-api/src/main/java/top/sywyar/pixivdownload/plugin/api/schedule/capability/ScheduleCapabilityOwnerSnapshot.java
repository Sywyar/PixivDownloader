package top.sywyar.pixivdownload.plugin.api.schedule.capability;

import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 不含插件 Bean 的单个计划能力 owner 观测值。 */
public record ScheduleCapabilityOwnerSnapshot(
        ScheduleCapabilityOwner owner,
        long publicationId,
        String activationToken,
        Set<String> sourceTypes,
        Set<String> sourceAliases,
        Set<String> workTypes,
        Set<String> credentialPolicyIds,
        Set<String> guardIds,
        List<ScheduledSourceDescriptor> sourceDescriptors
) {

    /**
     * 创建 {@code ScheduleCapabilityOwnerSnapshot} 实例。
     *
     * @param owner 所有者
     * @param publicationId 发布项标识
     * @param activationToken 激活令牌
     * @param sourceTypes 来源类型集合
     * @param sourceAliases {@code sourceAliases} 对应的值
     * @param workTypes 作品类型集合
     * @param credentialPolicyIds 凭证策略标识集合
     * @param guardIds 守卫标识集合
     * @param sourceDescriptors {@code sourceDescriptors} 对应的值
     */
    public ScheduleCapabilityOwnerSnapshot {
        owner = Objects.requireNonNull(owner, "owner");
        if (publicationId <= 0L) {
            throw new IllegalArgumentException("schedule publication id must be positive");
        }
        if (activationToken == null || activationToken.isBlank()) {
            throw new IllegalArgumentException("schedule activation token must not be blank");
        }
        sourceTypes = Set.copyOf(sourceTypes);
        sourceAliases = Set.copyOf(sourceAliases);
        workTypes = Set.copyOf(workTypes);
        credentialPolicyIds = Set.copyOf(credentialPolicyIds);
        guardIds = Set.copyOf(guardIds);
        sourceDescriptors = List.copyOf(sourceDescriptors);
    }
}
