package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.common.AppVersion;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.BuildChannel;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

@DisplayName("桌面宿主构建信息")
class AppDesktopUiHostBuildInfoTest {
    @Test
    @DisplayName("桌面构建渠道只采用显式标记，缺失及未过滤的标记显示未知")
    void buildChannelRequiresExplicitMetadata() {
        var host = new AppDesktopUiHost(0, mock(DesktopUiHost.ConfigFile.class));
        try (var metadata = mockStatic(AppVersion.class)) {
            metadata.when(AppVersion::getBuildChannelMarker).thenReturn("local");
            assertThat(host.applicationBuildChannel()).isEqualTo(BuildChannel.LOCAL);
            metadata.when(AppVersion::getBuildChannelMarker).thenReturn("release");
            assertThat(host.applicationBuildChannel()).isEqualTo(BuildChannel.RELEASE);
            metadata.when(AppVersion::getBuildChannelMarker).thenReturn(" nightly ");
            assertThat(host.applicationBuildChannel()).isEqualTo(BuildChannel.NIGHTLY);
            for (String marker : new String[]{null, "", " ", "@app.build.channel@", "unexpected"}) {
                metadata.when(AppVersion::getBuildChannelMarker).thenReturn(marker);
                assertThat(host.applicationBuildChannel()).isEqualTo(BuildChannel.UNKNOWN);
            }
        }
    }

    @Test
    @DisplayName("开发模式采用实际开发启动开关且不改变构建渠道")
    void developmentModeUsesRuntimeFlag() {
        String key = PluginDevelopmentArtifacts.ENABLED_PROPERTY;
        String previous = System.getProperty(key);
        try {
            var host = new AppDesktopUiHost(0, mock(DesktopUiHost.ConfigFile.class));
            var channel = host.applicationBuildChannel();
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
