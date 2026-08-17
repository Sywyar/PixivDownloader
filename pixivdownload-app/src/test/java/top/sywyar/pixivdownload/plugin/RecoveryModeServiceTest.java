package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.sdk.SdkVersion;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInstallation;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.status.RecoveryModeDecision;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

@DisplayName("恢复模式服务：据插件状态报告与必选策略判定是否进入恢复模式")
class RecoveryModeServiceTest {

    private static final RequiredPluginPolicy POLICY = RequiredPluginPolicy.of(List.of(
            new RequiredPlugin("download-workbench",
                    VersionRequirement.of(1, 0),
                    false, "plugin.recovery.missing.download-workbench")));

    private static RecoveryModeService service(RequiredPluginPolicy policy, PixivFeaturePlugin... plugins) {
        return service(policy, PluginInventory.empty(), plugins);
    }

    private static RecoveryModeService service(RequiredPluginPolicy policy, PluginInventory inventory,
                                               PixivFeaturePlugin... plugins) {
        PluginRegistry registry = new PluginRegistry(
                List.of(plugins), new PluginToggleProperties(), inventory.toDiscoveryResult());
        return new RecoveryModeService(
                new PluginStatusService(registry, inventory, policy), policy);
    }

    @Test
    @DisplayName("必选下载插件缺失：进入恢复模式")
    void missingRequiredPluginActivatesRecovery() {
        RecoveryModeService service = service(POLICY, new TestPlugin("core", PluginKind.CORE));

        assertThat(service.isActive()).isTrue();
        assertThat(service.decision().firstReason().orElseThrow().pluginId()).isEqualTo("download-workbench");
    }

    @Test
    @DisplayName("必选下载插件在场且 STARTED：正常运行，不进入恢复模式")
    void presentRequiredPluginIsOperational() {
        RecoveryModeService service = service(POLICY,
                startedDownloadWorkbenchInventory(), new TestPlugin("core", PluginKind.CORE));

        assertThat(service.isActive()).isFalse();
        assertThat(service.decision().reasons()).isEmpty();
    }

    @Test
    @DisplayName("判定结果在首次查询后缓存：重复查询返回同一实例")
    void decisionIsCached() {
        RecoveryModeService service = service(POLICY, new TestPlugin("core", PluginKind.CORE));

        RecoveryModeDecision first = service.decision();
        assertThat(service.decision()).isSameAs(first);
    }

    @Test
    @DisplayName("空必选策略：正常运行")
    void emptyPolicyIsOperational() {
        RecoveryModeService service =
                service(RequiredPluginPolicy.empty(), new TestPlugin("core", PluginKind.CORE));

        assertThat(service.isActive()).isFalse();
    }

    @Test
    @DisplayName("空必选策略下插件 start 失败：完整状态链进入恢复模式并指出插件")
    void pluginStartFailureActivatesRecovery() {
        PluginRegistry registry = new PluginRegistry(List.of(
                new TestPlugin("crashy", new IllegalStateException("startup exploded"))));
        registry.start();
        RecoveryModeService service = new RecoveryModeService(
                new PluginStatusService(registry, PluginInventory.empty(), RequiredPluginPolicy.empty()),
                RequiredPluginPolicy.empty());

        assertThat(service.isActive()).isTrue();
        assertThat(service.decision().firstReason().orElseThrow().pluginId()).isEqualTo("crashy");
        assertThat(service.decision().firstReason().orElseThrow().messages())
                .containsExactly(IllegalStateException.class.getName() + ": startup exploded");
    }

    private static PluginInventory startedDownloadWorkbenchInventory() {
        TestPlugin plugin = new TestPlugin("download-workbench");
        PluginDescriptor descriptor = new PluginDescriptor(
                "download-workbench",
                "download-workbench",
                "1.0.0",
                VersionRequirement.of(SdkVersion.MAJOR, SdkVersion.MINOR),
                List.of(),
                "top.sywyar.pixivdownload.download.DownloadWorkbenchPf4jPlugin",
                plugin.displayNamespace(),
                plugin.displayName(),
                plugin.description(),
                plugin.iconKey(),
                plugin.colorToken(),
                plugin.kind());
        return new PluginInventory(List.of(new PluginInstallation(
                descriptor, PluginStatus.STARTED, RecoveryModeServiceTest.class.getClassLoader(), plugin)), List.of());
    }

    private static final class TestPlugin implements PixivFeaturePlugin {
        private final String id;
        private final PluginKind kind;
        private final RuntimeException startFailure;

        TestPlugin(String id) {
            this(id, PluginKind.FEATURE, null);
        }

        TestPlugin(String id, PluginKind kind) {
            this(id, kind, null);
        }

        TestPlugin(String id, RuntimeException startFailure) {
            this(id, PluginKind.FEATURE, startFailure);
        }

        private TestPlugin(String id, PluginKind kind, RuntimeException startFailure) {
            this.id = id;
            this.kind = kind;
            this.startFailure = startFailure;
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

        @Override
        public void start() {
            if (startFailure != null) {
                throw startFailure;
            }
        }
    }
}
