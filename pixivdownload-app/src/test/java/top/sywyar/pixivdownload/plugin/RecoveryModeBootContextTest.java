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
 * 真实 Spring 上下文：缺少外置 required recovery-sentinel 时，核心壳进入恢复模式；
 * 必选策略声明 {@code recovery-sentinel} 必选且不允许禁用。
 */
@SpringBootTest(properties = {
        "database.provider=database-sqlite",
        "pixivdownload.recovery-sentinel.required=true",
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-sentinel-absent",
        "setup.browser.auto-open=false"
})
@org.springframework.test.context.ContextConfiguration(initializers = PluginTestProvenance.VerifiedLocalSqliteBootstrapInitializer.class)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("恢复模式真实上下文：缺少外置 recovery-sentinel 时进入恢复模式、必选策略禁止禁用")
class RecoveryModeBootContextTest {

    static {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
        System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, "target/test-runtime/plugins-sentinel-absent");
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
    @DisplayName("缺少外置 recovery-sentinel：进入恢复模式")
    void missingExternalRecoverySentinelActivatesRecovery() {
        assertThat(recoveryModeService.isActive()).isTrue();
        assertThat(recoveryModeService.decision().firstReason().orElseThrow().pluginId())
                .isEqualTo("recovery-sentinel");
    }

    @Test
    @DisplayName("必选策略声明 recovery-sentinel 必选且不允许禁用")
    void policyDeclaresRecoverySentinelNonDisableable() {
        assertThat(requiredPluginPolicy.isRequired("recovery-sentinel")).isTrue();
        RequiredPlugin dw = requiredPluginPolicy.requirement("recovery-sentinel").orElseThrow();
        assertThat(dw.allowDisable()).isFalse();
        assertThat(dw.missingMessageKey()).isEqualTo("plugin.recovery.blocked");
    }
}
