package top.sywyar.pixivdownload.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginDiagnostic;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;

/**
 * 真实 Spring 上下文：无 {@code plugins/} 目录时，插件状态服务把 core-only 内置插件报告为
 * {@link PluginStatus#STARTED}，并把缺失的 required download-workbench 报告为未满足要求。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-absent",
        "setup.browser.auto-open=false"
})
@DisplayName("插件状态服务真实上下文：core-only 内置插件 STARTED、required download-workbench 缺失")
class PluginStatusServiceBootContextTest {

    private static final Map<String, String> originalProperties = new LinkedHashMap<>();

    static {
        Map.of(
                RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config",
                RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state",
                RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data",
                RuntimeFiles.PLUGINS_DIR_PROPERTY, "target/test-runtime/plugins-absent").forEach((key, value) -> {
            originalProperties.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
    }

    @AfterAll
    static void tearDownRuntimeDirs() {
        originalProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Autowired
    private PluginStatusService pluginStatusService;

    @Test
    @DisplayName("core-only 内置插件 STARTED，download-workbench 缺失 required")
    void builtInPluginsAreAllStartedAndCompatible() {
        PluginStatusReport report = pluginStatusService.report();

        assertThat(report.withStatus(PluginStatus.STARTED)).extracting(PluginDiagnostic::id)
                .containsExactlyInAnyOrder(
                        "core", "plugin-market");
        assertThat(report.withStatus(PluginStatus.MISSING_REQUIRED)).extracting(PluginDiagnostic::id)
                .containsExactly("download-workbench");
        assertThat(report.withStatus(PluginStatus.INCOMPATIBLE)).isEmpty();
        assertThat(report.withStatus(PluginStatus.FAILED)).isEmpty();
        assertThat(report.hasUnmetRequirement()).isTrue();
        // 有描述符的内置插件均与当前SDK 兼容；缺失 required 没有 descriptor。
        assertThat(report.diagnostics().stream().filter(diagnostic -> diagnostic.descriptor() != null).toList())
                .allSatisfy(diagnostic -> assertThat(diagnostic.descriptor().isSdkCompatible()).isTrue());
    }
}
