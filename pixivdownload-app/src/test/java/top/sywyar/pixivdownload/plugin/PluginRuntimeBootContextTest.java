package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.PluginDirectoryState;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeStatus;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Spring 上下文：核心壳在没有 {@code plugins/} 目录时照常启动（输出缺失诊断、不报错），
 * PF4J 运行时骨架经 {@link PluginRuntimeConfiguration} 装配并暴露 {@link PluginRuntimeStatus}；
 * 且内置插件注册 / required 语义不因新接线而改变（七个内置插件仍在活动快照）。
 *
 * <p>插件目录指向 {@code target/test-runtime/plugins-absent}（保证不存在、且不会被创建），
 * 复刻验收点「无 plugins/ 目录启动不报错并输出缺失诊断」。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-absent",
        "setup.browser.auto-open=false"
})
@DisplayName("PF4J 运行时骨架的真实上下文：无插件目录照常启动 + 内置插件语义不变")
class PluginRuntimeBootContextTest {

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
    private PluginRuntimeManager pluginRuntimeManager;
    @Autowired
    private PluginRuntimeStatus pluginRuntimeStatus;
    @Autowired
    private PluginRegistry pluginRegistry;

    @Test
    @DisplayName("无 plugins/ 目录：上下文照常加载，运行时状态报告 ABSENT、零加载、零失败、未创建目录")
    void contextLoadsWithMissingPluginsDirectory() {
        assertThat(pluginRuntimeStatus.state()).isEqualTo(PluginDirectoryState.ABSENT);
        assertThat(pluginRuntimeStatus.directoryPresent()).isFalse();
        assertThat(pluginRuntimeStatus.loadedPluginIds()).isEmpty();
        assertThat(pluginRuntimeStatus.hasFailures()).isFalse();
        assertThat(pluginRuntimeStatus.directory().getFileName()).isEqualTo(Path.of("plugins-absent"));
        assertThat(Files.exists(pluginRuntimeStatus.directory())).isFalse();
        // 骨架已就绪：缺失目录路径不构造 PF4J 实例
        assertThat(pluginRuntimeManager.pluginManager()).isEmpty();
    }

    @Test
    @DisplayName("内置插件注册 / required 语义不因 PF4J 骨架接线而改变：七个内置插件仍在活动快照")
    void builtInPluginRegistrationIntact() {
        assertThat(pluginRegistry.plugins())
                .extracting(PixivFeaturePlugin::id)
                .containsExactlyInAnyOrder(
                        "core", "download-workbench", "schedule", "gallery", "novel", "stats", "duplicate");
    }
}
