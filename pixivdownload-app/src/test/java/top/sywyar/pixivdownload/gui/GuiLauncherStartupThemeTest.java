package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.theme.GuiThemeManager;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginEnabledSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.discovery.DiscoveredFeaturePlugin;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginDiscoveryResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GuiLauncher 首窗主题插件快照")
class GuiLauncherStartupThemeTest {

    @Test
    @DisplayName("首窗主题只使用发现桥盖章 id 不重读插件自报 id")
    void startupThemeUsesCapturedPluginId() {
        PixivFeaturePlugin plugin = new ThrowingIdPlugin();
        PluginDiscoveryResult discovery = new PluginDiscoveryResult(List.of(
                new DiscoveredFeaturePlugin("trusted-theme", "trusted-theme", plugin,
                        getClass().getClassLoader())), List.of());

        List<GuiThemeManager.ThemePluginSource> sources = GuiLauncher.buildStartupThemePlugins(
                PluginEnabledSnapshot.empty(), discovery);

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.pluginId()).isEqualTo("trusted-theme");
            assertThat(source.plugin()).isSameAs(plugin);
        });
    }

    @Test
    @DisplayName("禁用判断同样只消费发现桥盖章 id")
    void disabledFilterUsesCapturedPluginId() {
        PluginDiscoveryResult discovery = new PluginDiscoveryResult(List.of(
                new DiscoveredFeaturePlugin("trusted-theme", "trusted-theme", new ThrowingIdPlugin(),
                        getClass().getClassLoader())), List.of());

        assertThat(GuiLauncher.buildStartupThemePlugins(
                PluginEnabledSnapshot.ofDisabled(List.of("trusted-theme"), List.of()), discovery)).isEmpty();
    }

    private static final class ThrowingIdPlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            throw new IllegalStateException("id must not be read after discovery");
        }

        @Override
        public String displayName() {
            return "trusted-theme.name";
        }

        @Override
        public String description() {
            return "trusted-theme.description";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }
    }
}
