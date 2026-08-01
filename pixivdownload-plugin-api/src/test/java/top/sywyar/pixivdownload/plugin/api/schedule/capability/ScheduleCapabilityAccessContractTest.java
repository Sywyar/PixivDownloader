package top.sywyar.pixivdownload.plugin.api.schedule.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("计划能力稳定访问契约")
class ScheduleCapabilityAccessContractTest {

    @Test
    @DisplayName("owner 校验稳定标识并按包、功能与代际排序")
    void ownerValidatesIdentityAndUsesStableOrdering() {
        ScheduleCapabilityOwner first =
                new ScheduleCapabilityOwner("feature-a", "package-a", 2L);
        ScheduleCapabilityOwner laterGeneration =
                new ScheduleCapabilityOwner("feature-a", "package-a", 3L);
        ScheduleCapabilityOwner laterFeature =
                new ScheduleCapabilityOwner("feature-b", "package-a", 1L);
        ScheduleCapabilityOwner laterPackage =
                new ScheduleCapabilityOwner("feature-a", "package-b", 1L);

        assertThat(List.of(laterPackage, laterGeneration, laterFeature, first).stream()
                .sorted()
                .toList())
                .containsExactly(first, laterGeneration, laterFeature, laterPackage);
        assertThat(new ScheduleCapabilityOwner(" feature-a ", " package-a ", 0L))
                .isEqualTo(new ScheduleCapabilityOwner("feature-a", "package-a", 0L));
        assertThatThrownBy(() -> new ScheduleCapabilityOwner("Feature", "package-a", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleCapabilityOwner("feature-a", "package_a", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleCapabilityOwner("feature-a", "package-a", -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("快照在构造时复制集合且发布后不可变")
    void snapshotsDefensivelyCopyCollections() {
        ScheduleCapabilityOwner owner =
                new ScheduleCapabilityOwner("fixture", "fixture-package", 1L);
        Set<String> sourceTypes = new HashSet<>(Set.of("fixture-source"));
        Set<String> aliases = new HashSet<>(Set.of("fixture-alias"));
        Set<String> workTypes = new HashSet<>(Set.of("fixture-work"));
        Set<String> policies = new HashSet<>(Set.of("fixture-policy"));
        Set<String> guards = new HashSet<>(Set.of("fixture-guard"));
        List<ScheduleCapabilityOwnerSnapshot> owners = new ArrayList<>();
        ScheduleCapabilityOwnerSnapshot ownerSnapshot =
                new ScheduleCapabilityOwnerSnapshot(
                        owner,
                        1L,
                        "activation-token",
                        sourceTypes,
                        aliases,
                        workTypes,
                        policies,
                        guards,
                        List.of());
        owners.add(ownerSnapshot);
        ScheduleCapabilitySnapshot snapshot =
                new ScheduleCapabilitySnapshot("epoch", 1L, owners);

        sourceTypes.add("late-source");
        aliases.clear();
        workTypes.clear();
        policies.clear();
        guards.clear();
        owners.clear();

        assertThat(ownerSnapshot.sourceTypes()).containsExactly("fixture-source");
        assertThat(ownerSnapshot.sourceAliases()).containsExactly("fixture-alias");
        assertThat(ownerSnapshot.workTypes()).containsExactly("fixture-work");
        assertThat(ownerSnapshot.credentialPolicyIds()).containsExactly("fixture-policy");
        assertThat(ownerSnapshot.guardIds()).containsExactly("fixture-guard");
        assertThat(snapshot.owners()).containsExactly(ownerSnapshot);
        assertThatThrownBy(() -> ownerSnapshot.sourceTypes().add("mutation"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.owners().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("单项与复合租约暴露精确作品执行器 publication 身份")
    void leasesExposeExactWorkExecutorPublicationIdentity() {
        assertThat(Arrays.stream(ScheduleCapabilityLease.class.getDeclaredMethods())
                .map(Method::getName))
                .contains("owner", "publicationId");
        assertThat(Arrays.stream(ScheduleExecutionLease.class.getDeclaredMethods())
                .map(Method::getName))
                .contains(
                        "workExecutorOwner",
                        "workExecutorOwners",
                        "workExecutorPublicationId",
                        "workExecutorPublicationIds");
    }

    @Test
    @DisplayName("稳定访问面只暴露快照、准备、激活与 currentness 操作")
    void accessSurfaceRemainsNarrowAndDoesNotExposeRuntimeImplementations() {
        Method[] methods = ScheduleCapabilityAccess.class.getDeclaredMethods();
        String signatures = Arrays.stream(methods)
                .map(Method::toGenericString)
                .collect(Collectors.joining("\n"));

        assertThat(methods)
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "snapshot",
                        "prepareOwner",
                        "prepareWorkExecutor",
                        "credentialPolicyOwner",
                        "prepareSource",
                        "activate",
                        "activate",
                        "whileCurrentPublication",
                        "prepareExpansion",
                        "activate");
        assertThat(signatures)
                .contains(
                        ScheduleCapabilityLease.class.getName(),
                        SchedulePlanningLease.class.getName(),
                        ScheduleExecutionLease.class.getName())
                .doesNotContain(
                        "top.sywyar.pixivdownload.core.schedule.capability",
                        "org.springframework",
                        "org.pf4j");
    }
}
