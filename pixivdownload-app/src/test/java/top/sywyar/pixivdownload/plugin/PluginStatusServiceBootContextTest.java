package top.sywyar.pixivdownload.plugin;

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

/**
 * 真实 Spring 上下文：无 {@code plugins/} 目录时，插件状态服务把七个内置插件都报告为 {@link PluginStatus#STARTED}、
 * 描述符均与当前核心 API 兼容、无任何不兼容 / 失败 / 未满足必选要求（空必选策略）。验证状态模型「可由后端查询」端到端就绪。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-absent",
        "setup.browser.auto-open=false"
})
@DisplayName("插件状态服务真实上下文：内置插件全报告 STARTED、无未满足要求")
class PluginStatusServiceBootContextTest {

    static {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
        System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, "target/test-runtime/plugins-absent");
    }

    @AfterAll
    static void tearDownRuntimeDirs() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
    }

    @Autowired
    private PluginStatusService pluginStatusService;

    @Test
    @DisplayName("七个内置插件全部 STARTED、API 兼容，无不兼容 / 失败 / 未满足必选要求（stats 已外置、不在内置清单）")
    void builtInPluginsAreAllStartedAndCompatible() {
        PluginStatusReport report = pluginStatusService.report();

        assertThat(report.withStatus(PluginStatus.STARTED)).extracting(PluginDiagnostic::id)
                .containsExactlyInAnyOrder(
                        "core", "download-workbench", "schedule", "gallery", "novel", "duplicate", "plugin-market");
        assertThat(report.withStatus(PluginStatus.INCOMPATIBLE)).isEmpty();
        assertThat(report.withStatus(PluginStatus.FAILED)).isEmpty();
        assertThat(report.hasUnmetRequirement()).isFalse();
        // 内置插件描述符均与当前核心 API 兼容
        assertThat(report.diagnostics())
                .allSatisfy(diagnostic -> assertThat(diagnostic.descriptor().isApiCompatible()).isTrue());
    }
}
