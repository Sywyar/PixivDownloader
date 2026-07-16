package top.sywyar.pixivdownload.plugin.management;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("插件期望启用态配置服务")
class PluginEnabledConfigurationServiceTest {

    private static final String PLUGIN_ID = "demo-ext";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(PluginLifecyclePolicy.class)
    @DisplayName("任意外置生命周期策略均写入扁平配置，同步内存开关并保留行尾注释")
    void persistsThenUpdatesRuntimeToggle(PluginLifecyclePolicy policy) throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, "plugins.demo-ext.enabled: true  # keep\n", StandardCharsets.UTF_8);
        PluginStatusService status = statusWith(descriptor(policy));
        PluginToggleProperties toggles = new PluginToggleProperties();
        PluginEnabledConfigurationService service = new PluginEnabledConfigurationService(
                status, RequiredPluginPolicy.empty(), toggles, config);

        PluginEnabledConfigurationService.PluginEnabledState result = service.update(PLUGIN_ID, false);

        assertThat(result.id()).isEqualTo(PLUGIN_ID);
        assertThat(result.enabled()).isFalse();
        assertThat(result.lifecyclePolicy()).isEqualTo(policy);
        assertThat(Files.readString(config, StandardCharsets.UTF_8))
                .contains("plugins.demo-ext.enabled: false  # keep");
        assertThat(toggles.isEnabled(PLUGIN_ID)).isFalse();
    }

    @Test
    @DisplayName("必选插件不允许写入期望启用态")
    void refusesRequiredPlugin() {
        RequiredPluginPolicy policy = RequiredPluginPolicy.of(List.of(
                new RequiredPluginPolicy.RequiredPlugin(
                        PLUGIN_ID, PluginApiRequirement.unspecified(), false, "plugin.recovery.blocked")));
        PluginEnabledConfigurationService service = new PluginEnabledConfigurationService(
                statusWith(descriptor(PluginLifecyclePolicy.HOT_RELOAD)), policy,
                new PluginToggleProperties(), tempDir.resolve("config.yaml"));

        assertThatThrownBy(() -> service.update(PLUGIN_ID, false))
                .isInstanceOf(PluginManagementException.class)
                .extracting(thrown -> ((PluginManagementException) thrown).code())
                .isEqualTo(PluginManagementErrorCode.REQUIRED_PLUGIN);
        assertThat(tempDir.resolve("config.yaml")).doesNotExist();
    }

    @Test
    @DisplayName("内置插件不允许写入期望启用态")
    void refusesBuiltInPlugin() {
        PluginEnabledConfigurationService service = new PluginEnabledConfigurationService(
                mock(PluginStatusService.class), RequiredPluginPolicy.empty(),
                new PluginToggleProperties(), tempDir.resolve("config.yaml"));

        assertThatThrownBy(() -> service.update("core", false))
                .isInstanceOf(PluginManagementException.class)
                .extracting(thrown -> ((PluginManagementException) thrown).code())
                .isEqualTo(PluginManagementErrorCode.BUILT_IN_PLUGIN);
        assertThat(tempDir.resolve("config.yaml")).doesNotExist();
    }

    @Test
    @DisplayName("未知插件不允许把路径变量写入配置")
    void refusesUnknownPlugin() {
        PluginStatusService status = mock(PluginStatusService.class);
        when(status.report()).thenReturn(PluginStatusReport.empty());
        PluginEnabledConfigurationService service = new PluginEnabledConfigurationService(
                status, RequiredPluginPolicy.empty(), new PluginToggleProperties(),
                tempDir.resolve("config.yaml"));

        assertThatThrownBy(() -> service.update("unknown-ext", false))
                .isInstanceOf(PluginManagementException.class)
                .extracting(thrown -> ((PluginManagementException) thrown).code())
                .isEqualTo(PluginManagementErrorCode.UNKNOWN_PLUGIN);
        assertThat(tempDir.resolve("config.yaml")).doesNotExist();
    }

    @Test
    @DisplayName("配置写入失败时不改变当前内存开关")
    void failedWriteDoesNotMutateRuntimeToggle() throws Exception {
        Path directoryAsFile = Files.createDirectory(tempDir.resolve("config.yaml"));
        PluginToggleProperties toggles = new PluginToggleProperties();
        PluginEnabledConfigurationService service = new PluginEnabledConfigurationService(
                statusWith(descriptor(PluginLifecyclePolicy.BACKEND_RESTART)),
                RequiredPluginPolicy.empty(), toggles, directoryAsFile);

        assertThatThrownBy(() -> service.update(PLUGIN_ID, false))
                .isInstanceOf(PluginManagementException.class)
                .extracting(thrown -> ((PluginManagementException) thrown).code())
                .isEqualTo(PluginManagementErrorCode.TOGGLE_PERSIST_FAILED);
        assertThat(toggles.isEnabled(PLUGIN_ID)).isTrue();
    }

    private static PluginStatusService statusWith(PluginDescriptor descriptor) {
        PluginStatusService status = mock(PluginStatusService.class);
        when(status.report()).thenReturn(new PluginStatusReport(List.of(
                new PluginDiagnostic(descriptor.id(), PluginStatus.INSTALLED, descriptor, false, List.of()))));
        return status;
    }

    private static PluginDescriptor descriptor(PluginLifecyclePolicy policy) {
        return new PluginDescriptor(
                PLUGIN_ID, PLUGIN_ID, "1.0.0", PluginApiRequirement.unspecified(), List.of(),
                PLUGIN_ID + ".Plugin", PLUGIN_ID, "plugin.name", "plugin.summary",
                "puzzle", "neutral", PluginKind.FEATURE, List.of(), policy);
    }
}
