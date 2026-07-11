package top.sywyar.pixivdownload.plugin.api.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PixivFeaturePlugin} 默认方法契约测试：带默认实现的方法不会破坏只实现抽象方法（id / displayName /
 * description / kind）的最小插件。
 */
@DisplayName("PixivFeaturePlugin 展示元数据默认方法契约")
class PixivFeaturePluginDefaultsTest {

    /** 只实现抽象方法的最小插件：展示 token、displayNamespace、GUI contribution 全部走 default。 */
    private static class MinimalPlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return "minimal";
        }

        @Override
        public String displayName() {
            return "plugin.label";
        }

        @Override
        public String description() {
            return "plugin.summary";
        }

        @Override
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }
    }

    /** 覆写展示 token 的插件：证明覆写生效、返回插件自己声明的受控 token。 */
    private static final class CustomizedPlugin extends MinimalPlugin {
        @Override
        public String iconKey() {
            return "download";
        }

        @Override
        public String colorToken() {
            return "blue";
        }
    }

    @Test
    @DisplayName("最小插件（仅实现抽象方法）取得默认 token 与空 GUI 主题贡献")
    void minimalPluginGetsDefaultTokens() {
        PixivFeaturePlugin plugin = new MinimalPlugin();

        assertThat(plugin.iconKey()).isEqualTo("puzzle").isEqualTo(PixivFeaturePlugin.DEFAULT_ICON_KEY);
        assertThat(plugin.colorToken()).isEqualTo("neutral").isEqualTo(PixivFeaturePlugin.DEFAULT_COLOR_TOKEN);
        // 既有的展示 default 方法不受影响：无 i18n 贡献 → displayNamespace 默认 null；FEATURE 默认可禁用。
        assertThat(plugin.displayNamespace()).isNull();
        assertThat(plugin.required()).isFalse();
        assertThat(plugin.guiThemes()).isEmpty();
        assertThat(plugin.guiConfigContributions()).isEmpty();
        assertThat(plugin.guiOnboardingSteps()).isEmpty();
        assertThat(plugin.scheduledSources()).isEmpty();
        assertThat(plugin.scheduledSourceDescriptors()).isEmpty();
    }

    @Test
    @DisplayName("覆写 iconKey/colorToken 的插件返回其自身受控 token")
    void overriddenTokensAreReturned() {
        PixivFeaturePlugin plugin = new CustomizedPlugin();

        assertThat(plugin.iconKey()).isEqualTo("download");
        assertThat(plugin.colorToken()).isEqualTo("blue");
    }
}
