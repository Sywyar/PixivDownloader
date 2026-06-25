package top.sywyar.pixivdownload.plugin.runtime.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusEvaluator.ObservedPlugin;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件状态评估器：兼容性 / 依赖 / 必选策略推导")
class PluginStatusEvaluatorTest {

    private final PluginStatusEvaluator evaluator = new PluginStatusEvaluator();

    @Test
    @DisplayName("健康插件保留观测到的生命周期状态（STARTED / DISABLED）")
    void healthyPluginsKeepLifecycleStatus() {
        PluginStatusReport report = evaluator.evaluate(List.of(
                observed("alpha", "1.0", PluginStatus.STARTED),
                observed("beta", "1.0", PluginStatus.DISABLED)), RequiredPluginPolicy.empty());

        assertThat(report.byId("alpha")).get().extracting(PluginDiagnostic::status)
                .isEqualTo(PluginStatus.STARTED);
        assertThat(report.byId("beta")).get().extracting(PluginDiagnostic::status)
                .isEqualTo(PluginStatus.DISABLED);
    }

    @Test
    @DisplayName("requires 版本过高 → INCOMPATIBLE，诊断含所需与核心版本")
    void higherRequiresYieldsIncompatible() {
        PluginDescriptor descriptor = descriptor("ext", "1.0",
                PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR + 1), List.of());

        PluginStatusReport report = evaluator.evaluate(
                List.of(new ObservedPlugin(descriptor, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        PluginDiagnostic diagnostic = report.byId("ext").orElseThrow();
        assertThat(diagnostic.status()).isEqualTo(PluginStatus.INCOMPATIBLE);
        assertThat(diagnostic.messages()).anyMatch(m -> m.contains("requires core API"));
    }

    @Test
    @DisplayName("描述符非法（缺字段）→ FAILED，诊断携带校验错误")
    void invalidDescriptorYieldsFailed() {
        PluginDescriptor invalid = new PluginDescriptor("ext", "ext-pack", "1.0",
                PluginApiRequirement.of(1, 0), List.of(), "com.example.P", "ns", "  ", PluginKind.FEATURE);

        PluginStatusReport report = evaluator.evaluate(
                List.of(new ObservedPlugin(invalid, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        PluginDiagnostic diagnostic = report.byId("ext").orElseThrow();
        assertThat(diagnostic.status()).isEqualTo(PluginStatus.FAILED);
        assertThat(diagnostic.messages()).anyMatch(m -> m.contains("displayName"));
    }

    @Test
    @DisplayName("非可选依赖缺失 → 依赖方 MISSING_REQUIRED，诊断指明缺失依赖")
    void missingRequiredDependency() {
        PluginDescriptor dependent = descriptor("listen", "1.0", PluginApiRequirement.of(1, 0),
                List.of(new PluginDependencyRef("novel", "1.0", false)));

        PluginStatusReport report = evaluator.evaluate(
                List.of(new ObservedPlugin(dependent, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        PluginDiagnostic diagnostic = report.byId("listen").orElseThrow();
        assertThat(diagnostic.status()).isEqualTo(PluginStatus.MISSING_REQUIRED);
        assertThat(diagnostic.messages()).anyMatch(m -> m.contains("novel"));
    }

    @Test
    @DisplayName("可选依赖缺失不阻断启动：依赖方保留 STARTED")
    void missingOptionalDependencyDoesNotBlock() {
        PluginDescriptor dependent = descriptor("listen", "1.0", PluginApiRequirement.of(1, 0),
                List.of(new PluginDependencyRef("novel", "1.0", true)));

        PluginStatusReport report = evaluator.evaluate(
                List.of(new ObservedPlugin(dependent, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        assertThat(report.byId("listen")).get().extracting(PluginDiagnostic::status)
                .isEqualTo(PluginStatus.STARTED);
    }

    @Test
    @DisplayName("依赖存在但版本不兼容 → 依赖方 INCOMPATIBLE_REQUIRED")
    void incompatibleDependencyVersion() {
        PluginDescriptor dependency = descriptor("novel", "1.0", PluginApiRequirement.of(1, 0), List.of());
        PluginDescriptor dependent = descriptor("listen", "1.0", PluginApiRequirement.of(1, 0),
                List.of(new PluginDependencyRef("novel", "2.0", false)));

        PluginStatusReport report = evaluator.evaluate(List.of(
                new ObservedPlugin(dependency, PluginStatus.STARTED),
                new ObservedPlugin(dependent, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        assertThat(report.byId("novel")).get().extracting(PluginDiagnostic::status)
                .isEqualTo(PluginStatus.STARTED);
        PluginDiagnostic dependentDiag = report.byId("listen").orElseThrow();
        assertThat(dependentDiag.status()).isEqualTo(PluginStatus.INCOMPATIBLE_REQUIRED);
        assertThat(dependentDiag.messages()).anyMatch(m -> m.contains("novel") && m.contains("2.0"));
    }

    @Test
    @DisplayName("依赖在场且版本兼容、但被禁用（DISABLED）→ 依赖方不再 STARTED（MISSING_REQUIRED），诊断含依赖与其状态")
    void disabledDependencyBlocksDependent() {
        PluginDescriptor dependency = descriptor("novel", "1.0", PluginApiRequirement.of(1, 0), List.of());
        PluginDescriptor dependent = descriptor("listen", "1.0", PluginApiRequirement.of(1, 0),
                List.of(new PluginDependencyRef("novel", "1.0", false)));

        PluginStatusReport report = evaluator.evaluate(List.of(
                new ObservedPlugin(dependency, PluginStatus.DISABLED),
                new ObservedPlugin(dependent, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        assertThat(statusOf(report, "novel")).isEqualTo(PluginStatus.DISABLED);
        PluginDiagnostic dependentDiag = report.byId("listen").orElseThrow();
        assertThat(dependentDiag.status()).isEqualTo(PluginStatus.MISSING_REQUIRED);
        assertThat(dependentDiag.messages()).anyMatch(m -> m.contains("novel") && m.contains("DISABLED"));
        assertThat(report.hasUnmetRequirement()).isTrue();
    }

    @Test
    @DisplayName("依赖运行期失败（FAILED）→ 依赖方降级为 MISSING_REQUIRED（不保持 STARTED）")
    void failedDependencyBlocksDependent() {
        PluginDescriptor dependency = descriptor("novel", "1.0", PluginApiRequirement.of(1, 0), List.of());
        PluginDescriptor dependent = descriptor("listen", "1.0", PluginApiRequirement.of(1, 0),
                List.of(new PluginDependencyRef("novel", "1.0", false)));

        PluginStatusReport report = evaluator.evaluate(List.of(
                new ObservedPlugin(dependency, PluginStatus.FAILED),
                new ObservedPlugin(dependent, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        assertThat(statusOf(report, "novel")).isEqualTo(PluginStatus.FAILED);
        assertThat(statusOf(report, "listen")).isEqualTo(PluginStatus.MISSING_REQUIRED);
    }

    @Test
    @DisplayName("依赖自身核心 API 不兼容（INCOMPATIBLE）→ 依赖方降级为 INCOMPATIBLE_REQUIRED")
    void incompatibleDependencyBlocksDependent() {
        PluginDescriptor dependency = descriptor("novel", "1.0",
                PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR + 1), List.of());
        PluginDescriptor dependent = descriptor("listen", "1.0", PluginApiRequirement.of(1, 0),
                List.of(new PluginDependencyRef("novel", "1.0", false)));

        PluginStatusReport report = evaluator.evaluate(List.of(
                new ObservedPlugin(dependency, PluginStatus.STARTED),
                new ObservedPlugin(dependent, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        assertThat(statusOf(report, "novel")).isEqualTo(PluginStatus.INCOMPATIBLE);
        PluginDiagnostic dependentDiag = report.byId("listen").orElseThrow();
        assertThat(dependentDiag.status()).isEqualTo(PluginStatus.INCOMPATIBLE_REQUIRED);
        assertThat(dependentDiag.messages()).anyMatch(m -> m.contains("novel") && m.contains("INCOMPATIBLE"));
    }

    @Test
    @DisplayName("依赖不可用沿链传递：A→B→C，C 被禁用则 B、A 都不再 STARTED")
    void unavailabilityPropagatesTransitively() {
        PluginDescriptor c = descriptor("c", "1.0", PluginApiRequirement.of(1, 0), List.of());
        PluginDescriptor b = descriptor("b", "1.0", PluginApiRequirement.of(1, 0),
                List.of(new PluginDependencyRef("c", "1.0", false)));
        PluginDescriptor a = descriptor("a", "1.0", PluginApiRequirement.of(1, 0),
                List.of(new PluginDependencyRef("b", "1.0", false)));

        PluginStatusReport report = evaluator.evaluate(List.of(
                new ObservedPlugin(c, PluginStatus.DISABLED),
                new ObservedPlugin(b, PluginStatus.STARTED),
                new ObservedPlugin(a, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        assertThat(statusOf(report, "c")).isEqualTo(PluginStatus.DISABLED);
        assertThat(statusOf(report, "b")).isEqualTo(PluginStatus.MISSING_REQUIRED);
        assertThat(statusOf(report, "a")).isEqualTo(PluginStatus.MISSING_REQUIRED);
    }

    @Test
    @DisplayName("可选依赖即使不可用也不阻断：依赖方保留 STARTED")
    void unavailableOptionalDependencyDoesNotBlock() {
        PluginDescriptor dependency = descriptor("novel", "1.0", PluginApiRequirement.of(1, 0), List.of());
        PluginDescriptor dependent = descriptor("listen", "1.0", PluginApiRequirement.of(1, 0),
                List.of(new PluginDependencyRef("novel", "1.0", true)));

        PluginStatusReport report = evaluator.evaluate(List.of(
                new ObservedPlugin(dependency, PluginStatus.DISABLED),
                new ObservedPlugin(dependent, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        assertThat(statusOf(report, "listen")).isEqualTo(PluginStatus.STARTED);
    }

    @Test
    @DisplayName("依赖健康（STARTED）时依赖方照常 STARTED（不误降级）")
    void healthyDependencyKeepsDependentStarted() {
        PluginDescriptor dependency = descriptor("novel", "1.0", PluginApiRequirement.of(1, 0), List.of());
        PluginDescriptor dependent = descriptor("listen", "1.0", PluginApiRequirement.of(1, 0),
                List.of(new PluginDependencyRef("novel", "1.0", false)));

        PluginStatusReport report = evaluator.evaluate(List.of(
                new ObservedPlugin(dependency, PluginStatus.STARTED),
                new ObservedPlugin(dependent, PluginStatus.STARTED)), RequiredPluginPolicy.empty());

        assertThat(statusOf(report, "novel")).isEqualTo(PluginStatus.STARTED);
        assertThat(statusOf(report, "listen")).isEqualTo(PluginStatus.STARTED);
        assertThat(report.hasUnmetRequirement()).isFalse();
    }

    @Test
    @DisplayName("必选策略：必选 pluginId 未安装 → 追加 MISSING_REQUIRED（无描述符）")
    void requiredPolicyMissing() {
        RequiredPluginPolicy policy = RequiredPluginPolicy.of(List.of(
                new RequiredPlugin("download-workbench", PluginApiRequirement.unspecified(), false,
                        "plugin.required.download-workbench")));

        PluginStatusReport report = evaluator.evaluate(
                List.of(observed("gallery", "1.0", PluginStatus.STARTED)), policy);

        PluginDiagnostic diagnostic = report.byId("download-workbench").orElseThrow();
        assertThat(diagnostic.status()).isEqualTo(PluginStatus.MISSING_REQUIRED);
        assertThat(diagnostic.descriptor()).isNull();
        assertThat(diagnostic.requiredByPolicy()).isTrue();
        assertThat(diagnostic.messages()).anyMatch(m -> m.contains("download-workbench"));
        // 未触发恢复模式：评估器只产出诊断，不改变任何运行行为
        assertThat(report.hasUnmetRequirement()).isTrue();
    }

    @Test
    @DisplayName("必选策略：已安装但 API 不兼容的必选插件 → INCOMPATIBLE_REQUIRED（非普通 INCOMPATIBLE）")
    void requiredPolicyIncompatible() {
        PluginDescriptor descriptor = descriptor("download-workbench", "1.0",
                PluginApiRequirement.of(PluginApiVersion.MAJOR + 1, 0), List.of());
        RequiredPluginPolicy policy = RequiredPluginPolicy.of(List.of(
                new RequiredPlugin("download-workbench", PluginApiRequirement.unspecified(), false, null)));

        PluginStatusReport report = evaluator.evaluate(
                List.of(new ObservedPlugin(descriptor, PluginStatus.STARTED)), policy);

        assertThat(report.byId("download-workbench")).get().extracting(PluginDiagnostic::status)
                .isEqualTo(PluginStatus.INCOMPATIBLE_REQUIRED);
    }

    @Test
    @DisplayName("必选策略：已安装、自身兼容但不满足策略版本范围 → INCOMPATIBLE_REQUIRED")
    void requiredPolicyVersionRangeUnsatisfied() {
        PluginDescriptor descriptor = descriptor("download-workbench", "1.0",
                PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR), List.of());
        RequiredPluginPolicy policy = RequiredPluginPolicy.of(List.of(
                new RequiredPlugin("download-workbench", PluginApiRequirement.of(2, 0), false, null)));

        PluginStatusReport report = evaluator.evaluate(
                List.of(new ObservedPlugin(descriptor, PluginStatus.STARTED)), policy);

        PluginDiagnostic diagnostic = report.byId("download-workbench").orElseThrow();
        assertThat(diagnostic.status()).isEqualTo(PluginStatus.INCOMPATIBLE_REQUIRED);
        assertThat(diagnostic.requiredByPolicy()).isTrue();
        assertThat(diagnostic.messages()).anyMatch(m -> m.contains("2.0"));
    }

    @Test
    @DisplayName("必选策略：已安装、兼容、满足版本范围 → 保留生命周期状态但标记 requiredByPolicy")
    void requiredPolicySatisfied() {
        PluginDescriptor descriptor = descriptor("download-workbench", "1.4",
                PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR), List.of());
        RequiredPluginPolicy policy = RequiredPluginPolicy.of(List.of(
                new RequiredPlugin("download-workbench", PluginApiRequirement.of(1, 0), false, null)));

        PluginStatusReport report = evaluator.evaluate(
                List.of(new ObservedPlugin(descriptor, PluginStatus.STARTED)), policy);

        PluginDiagnostic diagnostic = report.byId("download-workbench").orElseThrow();
        assertThat(diagnostic.status()).isEqualTo(PluginStatus.STARTED);
        assertThat(diagnostic.requiredByPolicy()).isTrue();
        assertThat(report.hasUnmetRequirement()).isFalse();
    }

    private static PluginStatus statusOf(PluginStatusReport report, String id) {
        return report.byId(id).orElseThrow().status();
    }

    private static ObservedPlugin observed(String id, String version, PluginStatus baseStatus) {
        return new ObservedPlugin(
                descriptor(id, version, PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR),
                        List.of()),
                baseStatus);
    }

    private static PluginDescriptor descriptor(String id, String version, PluginApiRequirement requires,
                                               List<PluginDependencyRef> deps) {
        // plugin-class 必须是合法 Java FQN（不能含连字符，区别于可含连字符的 plugin id）
        String pluginClass = "com.example." + id.replace("-", "_");
        return new PluginDescriptor(id, id + "-pack", version, requires, deps,
                pluginClass, "ns", id + ".label", PluginKind.FEATURE);
    }
}
