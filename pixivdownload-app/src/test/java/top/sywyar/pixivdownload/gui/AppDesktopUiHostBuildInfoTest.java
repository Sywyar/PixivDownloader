package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.common.AppVersion;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("桌面宿主构建信息")
class AppDesktopUiHostBuildInfoTest {
    @Test
    @DisplayName("开发模式采用实际开发启动开关且不改变构建渠道")
    void developmentModeUsesRuntimeFlag() {
        String key = PluginDevelopmentArtifacts.ENABLED_PROPERTY;
        String previous = System.getProperty(key);
        try {
            var host = new AppDesktopUiHost(0, mock(DesktopUiHost.ConfigFile.class));
            var channel = AppVersion.getBuildChannel();
            System.clearProperty(key);
            assertThat(host.developmentMode()).isFalse();
            assertThat(host.applicationBuildChannel()).isEqualTo(channel);
            System.setProperty(key, "true");
            assertThat(host.developmentMode()).isTrue();
            assertThat(host.applicationBuildChannel()).isEqualTo(channel);
            System.setProperty(key, "false");
            assertThat(host.developmentMode()).isFalse();
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }
}
