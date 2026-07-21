package top.sywyar.pixivdownload.core.schedule.capability;

import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptor;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptorProvider;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledCredentialPolicyTarget;
import top.sywyar.pixivdownload.core.schedule.migration.LegacyScheduledTaskMigrationRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceFrontendContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 一个 owner 待发布的完整计划任务能力集合。所有插件 getter 都在构造阶段读取并校验，registry 锁内只处理
 * 已准备好的不可变条目，避免插件代码重入宿主注册锁。
 */
public final class ScheduleOwnerBundle {

    record SourceDescriptorEntry(
            String sourceType,
            Set<String> aliases,
            ScheduledSourceDescriptor descriptor
    ) {
    }

    record SourceExecutorEntry(String sourceType, ScheduledSourceExecutor executor) {
    }

    record WorkExecutorEntry(String workType, ScheduledWorkExecutor executor) {
    }

    record CredentialPolicyEntry(String policyId, ScheduledCredentialPolicy policy) {
    }

    record GuardEntry(String guardId, ScheduledExecutionGuard guard) {
    }

    record LegacyPersistenceEntry(
            String sourceType,
            String definitionSchema,
            int definitionVersion,
            Set<String> possibleWorkTypes,
            Set<String> credentialPolicyIds
    ) {
    }

    private static final int MAX_CAPABILITY_ID_BYTES = 256;

    private final ScheduleCapabilityOwner owner;
    private final List<SourceDescriptorEntry> sourceDescriptors;
    private final List<SourceExecutorEntry> sourceExecutors;
    private final List<WorkExecutorEntry> workExecutors;
    private final List<CredentialPolicyEntry> credentialPolicies;
    private final List<GuardEntry> guards;
    private final List<LegacyPersistenceEntry> legacyPersistence;

    private ScheduleOwnerBundle(
            ScheduleCapabilityOwner owner,
            List<SourceDescriptorEntry> sourceDescriptors,
            List<SourceExecutorEntry> sourceExecutors,
            List<WorkExecutorEntry> workExecutors,
            List<CredentialPolicyEntry> credentialPolicies,
            List<GuardEntry> guards,
            List<LegacyPersistenceEntry> legacyPersistence) {
        this.owner = owner;
        this.sourceDescriptors = List.copyOf(sourceDescriptors);
        this.sourceExecutors = List.copyOf(sourceExecutors);
        this.workExecutors = List.copyOf(workExecutors);
        this.credentialPolicies = List.copyOf(credentialPolicies);
        this.guards = List.copyOf(guards);
        this.legacyPersistence = List.copyOf(legacyPersistence);
    }

    /**
     * 读取并准备一个 owner 的完整能力集合。此方法不得在 registry 锁内调用。
     */
    public static ScheduleOwnerBundle prepare(
            ScheduleCapabilityOwner owner,
            List<? extends ScheduledSourceDescriptor> sourceDescriptors,
            List<? extends ScheduledSourceExecutor> sourceExecutors,
            List<? extends ScheduledWorkExecutor> workExecutors,
            List<? extends ScheduledCredentialPolicy> credentialPolicies,
            List<? extends ScheduledExecutionGuard> guards) {
        return prepare(owner, sourceDescriptors, sourceExecutors, workExecutors,
                credentialPolicies, guards, List.of());
    }

    public static ScheduleOwnerBundle prepare(
            ScheduleCapabilityOwner owner,
            List<? extends ScheduledSourceDescriptor> sourceDescriptors,
            List<? extends ScheduledSourceExecutor> sourceExecutors,
            List<? extends ScheduledWorkExecutor> workExecutors,
            List<? extends ScheduledCredentialPolicy> credentialPolicies,
            List<? extends ScheduledExecutionGuard> guards,
            List<? extends LegacySchedulePersistenceDescriptorProvider> legacyPersistenceProviders) {
        Objects.requireNonNull(owner, "owner");

        List<SourceDescriptorEntry> preparedDescriptors = prepareDescriptors(owner, sourceDescriptors);
        List<SourceExecutorEntry> preparedSourceExecutors = prepareSourceExecutors(owner, sourceExecutors);
        List<WorkExecutorEntry> preparedWorkExecutors = prepareWorkExecutors(owner, workExecutors);
        List<CredentialPolicyEntry> preparedPolicies = prepareCredentialPolicies(owner, credentialPolicies);
        List<GuardEntry> preparedGuards = prepareGuards(owner, guards);
        List<LegacyPersistenceEntry> preparedLegacyPersistence =
                prepareLegacyPersistence(owner, legacyPersistenceProviders);

        Map<String, SourceDescriptorEntry> descriptorByType = uniqueById(
                owner, "source descriptor", preparedDescriptors, SourceDescriptorEntry::sourceType);
        Map<String, SourceExecutorEntry> sourceExecutorByType = uniqueById(
                owner, "source executor", preparedSourceExecutors, SourceExecutorEntry::sourceType);
        if (!descriptorByType.keySet().equals(sourceExecutorByType.keySet())) {
            Set<String> missingExecutors = new LinkedHashSet<>(descriptorByType.keySet());
            missingExecutors.removeAll(sourceExecutorByType.keySet());
            Set<String> orphanExecutors = new LinkedHashSet<>(sourceExecutorByType.keySet());
            orphanExecutors.removeAll(descriptorByType.keySet());
            throw failure(owner, "source descriptor/executor mismatch; missing executors="
                    + missingExecutors + ", orphan executors=" + orphanExecutors);
        }

        uniqueById(owner, "work executor", preparedWorkExecutors, WorkExecutorEntry::workType);
        uniqueById(owner, "credential policy", preparedPolicies, CredentialPolicyEntry::policyId);
        uniqueById(owner, "execution guard", preparedGuards, GuardEntry::guardId);
        uniqueById(owner, "legacy persistence descriptor", preparedLegacyPersistence,
                LegacyPersistenceEntry::sourceType);

        for (LegacyPersistenceEntry persistence : preparedLegacyPersistence) {
            if (!descriptorByType.containsKey(persistence.sourceType())) {
                throw failure(owner, "legacy persistence descriptor has no source descriptor: "
                        + persistence.sourceType());
            }
            SourceDescriptorEntry descriptor = descriptorByType.get(persistence.sourceType());
            if (descriptor != null && (!descriptor.descriptor().definitionSchema()
                    .equals(persistence.definitionSchema())
                    || descriptor.descriptor().definitionVersion() != persistence.definitionVersion()
                    || !descriptor.descriptor().possibleWorkTypes().equals(persistence.possibleWorkTypes())
                    || !descriptor.descriptor().credentialPolicyIds()
                            .equals(persistence.credentialPolicyIds()))) {
                throw failure(owner, "legacy persistence descriptor differs from source descriptor: "
                        + persistence.sourceType());
            }
        }

        return new ScheduleOwnerBundle(owner, preparedDescriptors,
                preparedSourceExecutors, preparedWorkExecutors, preparedPolicies, preparedGuards,
                preparedLegacyPersistence);
    }

    public ScheduleCapabilityOwner owner() {
        return owner;
    }

    public boolean isEmpty() {
        return sourceDescriptors.isEmpty()
                && sourceExecutors.isEmpty()
                && workExecutors.isEmpty()
                && credentialPolicies.isEmpty()
                && guards.isEmpty();
    }

    /**
     * 从已准备、已验证的 owner bundle 投影 legacy alias → canonical source type。
     *
     * <p>alias 只来自稳定 descriptor；定义持久化信息可由独立 provider 盖章。不同来源对同一
     * alias 的占用在发布与旧数据迁移之前就 fail-fast。返回值只含字符串纯值，不暴露插件 Bean。
     */
    Map<String, LegacyScheduledTaskMigrationRoute> legacyMigrationRoutes(
            Map<String, String> credentialPolicyOwnersById) {
        Objects.requireNonNull(credentialPolicyOwnersById, "credentialPolicyOwnersById");
        Map<String, LegacyPersistenceEntry> persistenceByType = new LinkedHashMap<>();
        for (LegacyPersistenceEntry persistence : legacyPersistence) {
            persistenceByType.put(persistence.sourceType(), persistence);
        }
        Map<String, LegacyScheduledTaskMigrationRoute> routes = new LinkedHashMap<>();
        for (SourceDescriptorEntry descriptor : sourceDescriptors) {
            addMigrationRoutes(routes, migrationRoute(descriptor,
                    persistenceByType.get(descriptor.sourceType()), credentialPolicyOwnersById),
                    descriptor.aliases());
        }
        return Map.copyOf(routes);
    }

    List<SourceDescriptorEntry> sourceDescriptors() {
        return sourceDescriptors;
    }

    List<SourceExecutorEntry> sourceExecutors() {
        return sourceExecutors;
    }

    List<WorkExecutorEntry> workExecutors() {
        return workExecutors;
    }

    List<CredentialPolicyEntry> credentialPolicies() {
        return credentialPolicies;
    }

    List<GuardEntry> guards() {
        return guards;
    }


    private void addMigrationRoutes(
            Map<String, LegacyScheduledTaskMigrationRoute> routes,
            LegacyScheduledTaskMigrationRoute route,
            Set<String> aliases) {
        for (String alias : aliases) {
            LegacyScheduledTaskMigrationRoute previous = routes.putIfAbsent(alias, route);
            if (previous != null && !previous.equals(route)) {
                throw failure(owner, "legacy source alias claimed by multiple canonical sources: " + alias);
            }
        }
    }

    private LegacyScheduledTaskMigrationRoute migrationRoute(
            SourceDescriptorEntry descriptor,
            LegacyPersistenceEntry persistence,
            Map<String, String> credentialPolicyOwnersById) {
        if (persistence == null) {
            return LegacyScheduledTaskMigrationRoute.descriptorBound(
                    descriptor.sourceType(),
                    descriptor.descriptor().definitionSchema(),
                    descriptor.descriptor().definitionVersion(),
                    descriptor.descriptor().possibleWorkTypes(),
                    credentialPolicyTargets(
                            descriptor.sourceType(),
                            descriptor.descriptor().credentialPolicyIds(),
                            credentialPolicyOwnersById));
        }
        return LegacyScheduledTaskMigrationRoute.descriptorBound(
                descriptor.sourceType(), persistence.definitionSchema(), persistence.definitionVersion(),
                persistence.possibleWorkTypes(), credentialPolicyTargets(
                        descriptor.sourceType(),
                        persistence.credentialPolicyIds(),
                        credentialPolicyOwnersById));
    }

    private Set<LegacyScheduledCredentialPolicyTarget> credentialPolicyTargets(
            String sourceType,
            Set<String> policyIds,
            Map<String, String> credentialPolicyOwnersById) {
        Set<LegacyScheduledCredentialPolicyTarget> targets = new LinkedHashSet<>();
        for (String policyId : policyIds) {
            String policyOwner = credentialPolicyOwnersById.get(policyId);
            if (policyOwner == null) {
                throw failure(owner, "legacy persistence descriptor references unavailable credential policy "
                        + policyId + " for source " + sourceType);
            }
            targets.add(new LegacyScheduledCredentialPolicyTarget(policyId, policyOwner));
        }
        return Set.copyOf(targets);
    }

    private static List<LegacyPersistenceEntry> prepareLegacyPersistence(
            ScheduleCapabilityOwner owner,
            List<? extends LegacySchedulePersistenceDescriptorProvider> providers) {
        List<LegacyPersistenceEntry> result = new ArrayList<>();
        for (LegacySchedulePersistenceDescriptorProvider provider :
                copyInput(owner, "legacy persistence descriptor providers", providers)) {
            List<LegacySchedulePersistenceDescriptor> descriptors = readPluginCallback(
                    owner,
                    "legacy persistence descriptors",
                    () -> List.copyOf(provider.legacySchedulePersistenceDescriptors()));
            for (LegacySchedulePersistenceDescriptor descriptor : descriptors) {
                Set<String> workTypes = new LinkedHashSet<>();
                for (String workType : descriptor.possibleWorkTypes()) {
                    workTypes.add(requireCapabilityId(
                            workType, "legacy persistence possible work type"));
                }
                Set<String> credentialPolicyIds = new LinkedHashSet<>();
                for (String policyId : descriptor.credentialPolicyIds()) {
                    credentialPolicyIds.add(requireCapabilityId(
                            policyId, "legacy persistence credential policy id"));
                }
                result.add(new LegacyPersistenceEntry(
                        requireCapabilityId(descriptor.sourceType(), "legacy persistence source type"),
                        requireCapabilityId(descriptor.definitionSchema(),
                                "legacy persistence definition schema"),
                        descriptor.definitionVersion(),
                        Set.copyOf(workTypes),
                        Set.copyOf(credentialPolicyIds)));
            }
        }
        return List.copyOf(result);
    }

    private static List<SourceDescriptorEntry> prepareDescriptors(
            ScheduleCapabilityOwner owner, List<? extends ScheduledSourceDescriptor> descriptors) {
        List<SourceDescriptorEntry> result = new ArrayList<>();
        for (ScheduledSourceDescriptor descriptor : copyInput(owner, "source descriptors", descriptors)) {
            String sourceType = requireCapabilityId(descriptor.sourceType(), "source type");
            Set<String> aliases = copyAliases(descriptor.legacyAliases(), sourceType);
            requireCapabilityId(descriptor.definitionSchema(), "source definition schema");
            if (descriptor.definitionVersion() <= 0) {
                throw new IllegalStateException("source definition version must be positive");
            }
            descriptor.acquisitionModes().forEach(
                    value -> requireCapabilityId(value, "source acquisition mode"));
            descriptor.possibleWorkTypes().forEach(value -> requireCapabilityId(value, "possible work type"));
            descriptor.credentialPolicyIds().forEach(value -> requireCapabilityId(value, "credential policy id"));
            descriptor.guardIds().forEach(value -> requireCapabilityId(value, "guard id"));
            validateFrontend(descriptor.frontend(), sourceType);
            result.add(new SourceDescriptorEntry(sourceType, aliases, descriptor));
        }
        return List.copyOf(result);
    }

    private static List<SourceExecutorEntry> prepareSourceExecutors(
            ScheduleCapabilityOwner owner, List<? extends ScheduledSourceExecutor> executors) {
        List<SourceExecutorEntry> result = new ArrayList<>();
        for (ScheduledSourceExecutor executor : copyInput(owner, "source executors", executors)) {
            String sourceType = readPluginCallback(
                    owner, "source executor type", executor::sourceType);
            result.add(new SourceExecutorEntry(
                    requireCapabilityId(sourceType, "source executor type"), executor));
        }
        return List.copyOf(result);
    }

    private static List<WorkExecutorEntry> prepareWorkExecutors(
            ScheduleCapabilityOwner owner, List<? extends ScheduledWorkExecutor> executors) {
        List<WorkExecutorEntry> result = new ArrayList<>();
        for (ScheduledWorkExecutor executor : copyInput(owner, "work executors", executors)) {
            String workType = readPluginCallback(
                    owner, "work executor type", executor::workType);
            result.add(new WorkExecutorEntry(
                    requireCapabilityId(workType, "work executor type"), executor));
        }
        return List.copyOf(result);
    }

    private static List<CredentialPolicyEntry> prepareCredentialPolicies(
            ScheduleCapabilityOwner owner, List<? extends ScheduledCredentialPolicy> policies) {
        List<CredentialPolicyEntry> result = new ArrayList<>();
        for (ScheduledCredentialPolicy policy : copyInput(owner, "credential policies", policies)) {
            String policyId = readPluginCallback(
                    owner, "credential policy id", policy::policyId);
            result.add(new CredentialPolicyEntry(
                    requireCapabilityId(policyId, "credential policy id"), policy));
        }
        return List.copyOf(result);
    }

    private static List<GuardEntry> prepareGuards(
            ScheduleCapabilityOwner owner, List<? extends ScheduledExecutionGuard> guards) {
        List<GuardEntry> result = new ArrayList<>();
        for (ScheduledExecutionGuard guard : copyInput(owner, "execution guards", guards)) {
            String guardId = readPluginCallback(owner, "guard id", guard::guardId);
            result.add(new GuardEntry(requireCapabilityId(guardId, "guard id"), guard));
        }
        return List.copyOf(result);
    }

    private static <T> List<T> copyInput(
            ScheduleCapabilityOwner owner, String label, List<? extends T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            if (value == null) {
                throw failure(owner, "null entry in " + label);
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static Set<String> copyAliases(Set<String> values, String canonical) {
        if (values == null) {
            throw new IllegalStateException("source aliases must not be null: " + canonical);
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String value : values) {
            String alias = requireAlias(value);
            if (alias.equals(canonical)) {
                throw new IllegalStateException("source alias duplicates its canonical type: " + canonical);
            }
            if (!copy.add(alias)) {
                throw new IllegalStateException("duplicate source alias: " + alias);
            }
        }
        return Set.copyOf(copy);
    }

    private static void validateFrontend(ScheduledSourceFrontendContribution frontend, String sourceType) {
        if (frontend == null) {
            return;
        }
        if (frontend.contractVersion() != ScheduledSourceFrontendContribution.CURRENT_CONTRACT_VERSION) {
            throw new IllegalStateException("unsupported schedule source frontend contract version for "
                    + sourceType + ": " + frontend.contractVersion());
        }
        if (!isSafeSameOriginAbsolutePath(frontend.moduleUrl())) {
            throw new IllegalStateException("unsafe schedule source frontend module URL for "
                    + sourceType + ": " + frontend.moduleUrl());
        }
    }

    private static boolean isSafeSameOriginAbsolutePath(String value) {
        if (value == null || value.isBlank() || value.charAt(0) != '/') {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current) || Character.isISOControl(current) || current == '\\') {
                return false;
            }
        }
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
                return false;
            }
            String path = uri.getRawPath();
            if (path == null || path.length() <= 1 || !path.startsWith("/") || path.startsWith("//")
                    || path.contains("//")) {
                return false;
            }
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (lowerPath.contains("%2e") || lowerPath.contains("%2f")
                    || lowerPath.contains("%5c") || lowerPath.contains("%00")
                    || lowerPath.contains("%25")) {
                return false;
            }
            for (String segment : path.split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static String requireCapabilityId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " must not be blank");
        }
        String normalized = value.trim();
        if (!normalized.equals(value)) {
            throw new IllegalStateException(label + " must already be normalized: " + value);
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_CAPABILITY_ID_BYTES) {
            throw new IllegalStateException(label + " exceeds size limit");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isWhitespace(normalized.charAt(index))
                    || Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalStateException(label + " contains whitespace or control characters: " + value);
            }
        }
        return normalized;
    }

    private static String requireAlias(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("source alias must not be blank");
        }
        String normalized = value.trim();
        if (!normalized.equals(value)) {
            throw new IllegalStateException("source alias must already be normalized: " + value);
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_CAPABILITY_ID_BYTES) {
            throw new IllegalStateException("source alias exceeds size limit");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isWhitespace(normalized.charAt(index))
                    || Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalStateException("source alias contains whitespace or control characters: " + value);
            }
        }
        return normalized;
    }

    private static <T> Map<String, T> uniqueById(
            ScheduleCapabilityOwner owner, String label, List<T> values,
            java.util.function.Function<T, String> idExtractor) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            String id = idExtractor.apply(value);
            if (result.putIfAbsent(id, value) != null) {
                throw failure(owner, "duplicate " + label + ": " + id);
            }
        }
        return Map.copyOf(result);
    }

    private static IllegalStateException readFailure(
            ScheduleCapabilityOwner owner, String label, Throwable cause) {
        return new IllegalStateException("failed to prepare schedule " + label + " (owner: " + owner
                + "; plugin error type: " + cause.getClass().getName() + ")");
    }

    private static <T> T readPluginCallback(
            ScheduleCapabilityOwner owner, String label, Supplier<T> callback) {
        try {
            return callback.get();
        } catch (Throwable failure) {
            rethrowFatal(failure);
            throw readFailure(owner, label, failure);
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    private static IllegalStateException failure(ScheduleCapabilityOwner owner, String message) {
        return new IllegalStateException(message + " (owner: " + owner + ")");
    }
}
