package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;

/**
 * 真实 Spring 上下文：缺少外置 required download-workbench 时，核心壳进入恢复模式；
 * 必选策略声明 {@code download-workbench} 必选且不允许禁用。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-absent",
        "setup.browser.auto-open=false"
})
@DisplayName("恢复模式真实上下文：缺少外置 download-workbench 时进入恢复模式、必选策略禁止禁用")
class RecoveryModeBootContextTest {

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
    private RecoveryModeService recoveryModeService;
    @Autowired
    private RequiredPluginPolicy requiredPluginPolicy;

    @Test
    @DisplayName("缺少外置 download-workbench：进入恢复模式")
    void missingExternalDownloadWorkbenchActivatesRecovery() {
        assertThat(recoveryModeService.isActive()).isTrue();
        assertThat(recoveryModeService.decision().firstReason().orElseThrow().pluginId())
                .isEqualTo("download-workbench");
    }

    @Test
    @DisplayName("必选策略声明 download-workbench 必选且不允许禁用")
    void policyDeclaresDownloadWorkbenchNonDisableable() {
        assertThat(requiredPluginPolicy.isRequired("download-workbench")).isTrue();
        RequiredPlugin dw = requiredPluginPolicy.requirement("download-workbench").orElseThrow();
        assertThat(dw.allowDisable()).isFalse();
        assertThat(dw.missingMessageKey()).isEqualTo("plugin.recovery.missing.download-workbench");
    }
}
