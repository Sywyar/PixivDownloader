package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatus;
import top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;

/**
 * 真实 Spring 上下文：打开显式开关 {@code pixivdownload.recovery-sentinel.required=true}，并把插件目录指向一个不存在的
 * 目录（即未安装 recovery-sentinel 外置插件）。核心已把 recovery-sentinel 与 download-workbench 都声明为必选，因此启动时会检测到
 * 两个必选缺失项并进入恢复模式。该用例用于验证配置开关、必选策略 Bean、插件状态服务与 {@link RecoveryModeService} 在真实容器里串起来后
 * 的失配行为。
 *
 * <p>用独立的属性集与目录（{@code plugins-recovery-sentinel-absent}），与其它 boot 用例的上下文缓存隔离；
 * {@link DirtiesContext} 在类结束后关闭这套上下文。
 */
@SpringBootTest(properties = {
        "pixivdownload.recovery-sentinel.required=true",
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-recovery-sentinel-absent",
        "setup.browser.auto-open=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("恢复模式真实上下文：要求 recovery-sentinel 但其缺失时，应用进入恢复模式")
class RecoverySentinelRecoveryBootContextTest {

    static {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
        System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, "target/test-runtime/plugins-recovery-sentinel-absent");
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
    @DisplayName("开关打开后 recovery-sentinel 被声明为必选")
    void policyDeclaresRecoverySentinelRequired() {
        assertThat(requiredPluginPolicy.isRequired("recovery-sentinel")).isTrue();
        assertThat(requiredPluginPolicy.isRequired("download-workbench")).isTrue();
    }

    @Test
    @DisplayName("必选插件缺失：应用进入恢复模式，并识别到多个必选缺失项")
    void missingRequiredSentinelEntersRecovery() {
        assertThat(recoveryModeService.isActive()).isTrue();
        assertThat(recoveryModeService.decision().reasons()).extracting("pluginId")
                .containsExactlyInAnyOrder("download-workbench", "recovery-sentinel");
        assertThat(recoveryModeService.decision().firstReason().orElseThrow().status())
                .isEqualTo(PluginStatus.MISSING_REQUIRED);
    }
}
