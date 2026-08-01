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
