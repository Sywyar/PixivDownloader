package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.PluginLoadFailure;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件状态服务：综合内置注册中心 + 外置清点 + 必选策略产出报告")
class PluginStatusServiceTest {

    @Test
    @DisplayName("内置（启用 / 禁用）、外置（兼容接入 / 不兼容拒绝）、坏包失败的状态在报告中清晰可查")
    void reportReflectsBuiltInExternalAndFailures() {
        ClassLoader extCl = new ClassLoader(getClass().getClassLoader()) {
        };
        PluginInstallation compatible = new PluginInstallation(
                external("ext-foo", "1.0.0", PluginApiRequirement.of(PluginApiVersion.MAJOR, PluginApiVersion.MINOR)),
                PluginStatus.STARTED, extCl, new TestPlugin("ext-foo"));
        PluginInstallation incompatible = new PluginInstallation(
                external("ext-bad", "1.0.0", PluginApiRequirement.of(PluginApiVersion.MAJOR + 1, 0)),
                PluginStatus.INCOMPATIBLE, extCl, null);
        PluginInventory inventory = new PluginInventory(
                List.of(compatible, incompatible),
                List.of(new PluginLoadFailure("broken-pack.jar", "not a valid plugin jar")));

        // 注册中心从清点投影出的发现结果接入（与生产装配同路径）：ext-foo 接入、ext-bad 被拒
        PluginToggleProperties toggles = new PluginToggleProperties();
        PluginToggleProperties.PluginToggle galleryOff = new PluginToggleProperties.PluginToggle();
        galleryOff.setEnabled(false);
        toggles.put("gallery", galleryOff);
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("duplicate"), new TestPlugin("gallery")),
                toggles, inventory.toDiscoveryResult());

        PluginStatusReport report = new PluginStatusService(registry, inventory, RequiredPluginPolicy.empty()).report();

        assertThat(statusOf(report, "duplicate")).isEqualTo(PluginStatus.STARTED);
        assertThat(statusOf(report, "gallery")).isEqualTo(PluginStatus.DISABLED);
        assertThat(statusOf(report, "ext-foo")).isEqualTo(PluginStatus.STARTED);
        assertThat(statusOf(report, "ext-bad")).isEqualTo(PluginStatus.INCOMPATIBLE);
        assertThat(statusOf(report, "broken-pack.jar")).isEqualTo(PluginStatus.FAILED);
    }

    @Test
    @DisplayName("必选策略要求一个未安装的 pluginId：报告追加 MISSING_REQUIRED（仅诊断、不触发恢复模式）")
    void requiredPolicyMissingIsReportedNotEnforced() {
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE)), new PluginToggleProperties());
        RequiredPluginPolicy policy = RequiredPluginPolicy.of(List.of(
                new RequiredPlugin("download-workbench", PluginApiRequirement.unspecified(), false,
                        "plugin.required.download-workbench")));

        PluginStatusReport report =
                new PluginStatusService(registry, PluginInventory.empty(), policy).report();

        assertThat(statusOf(report, "core")).isEqualTo(PluginStatus.STARTED);
        PluginDiagnostic missing = report.byId("download-workbench").orElseThrow();
        assertThat(missing.status()).isEqualTo(PluginStatus.MISSING_REQUIRED);
        assertThat(missing.requiredByPolicy()).isTrue();
        assertThat(report.hasUnmetRequirement()).isTrue();
    }

    @Test
    @DisplayName("空必选策略 + 无外置插件：只报告内置插件、无未满足要求")
    void emptyPolicyAndNoExternalReportsBuiltInsOnly() {
        PluginRegistry registry = new PluginRegistry(
                List.of(new TestPlugin("core", PluginKind.CORE), new TestPlugin("duplicate")),
                new PluginToggleProperties());

        PluginStatusReport report = new PluginStatusService(
                registry, PluginInventory.empty(), RequiredPluginPolicy.empty()).report();

        assertThat(report.diagnostics()).extracting(PluginDiagnostic::id)
                .containsExactlyInAnyOrder("core", "duplicate");
        assertThat(report.hasUnmetRequirement()).isFalse();
        assertThat(report.withStatus(PluginStatus.STARTED)).hasSize(2);
    }

    private static PluginStatus statusOf(PluginStatusReport report, String id) {
        return report.byId(id).orElseThrow().status();
    }

    private static PluginDescriptor external(String id, String version, PluginApiRequirement requires) {
        return new PluginDescriptor(id, id + "-pack", version, requires, List.of(),
                "com.example." + id.replace("-", "_"), id + ".label", PluginKind.FEATURE);
    }

    private static final class TestPlugin implements PixivFeaturePlugin {
        private final String id;
        private final PluginKind kind;

        TestPlugin(String id) {
            this(id, PluginKind.FEATURE);
        }

        TestPlugin(String id, PluginKind kind) {
            this.id = id;
            this.kind = kind;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return id + ".label";
        }

        @Override
        public String description() {
            return id + ".summary";
        }

        @Override
        public PluginKind kind() {
            return kind;
        }
    }
}
