package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.PluginRegistry;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebI18nService 经声明方插件 ClassLoader 解析 bundle")
class WebI18nServiceTest {

    private final WebI18nService service = new WebI18nService(
            new WebI18nBundleRegistry(new PluginRegistry(BuiltInPlugins.createAll())));

    @Test
    @DisplayName("核心 namespace common 经声明方 ClassLoader 解析到真实 properties（非空键值，行为不变）")
    void loadsCoreNamespaceThroughDeclaringClassLoader() {
        I18nBundleResponse response = service.loadBundle("common", Locale.SIMPLIFIED_CHINESE);
        assertThat(response.getNamespace()).isEqualTo("common");
        assertThat(response.getMessages()).isNotEmpty();
    }

    @Test
    @DisplayName("功能插件 namespace gallery 同样解析到真实 properties（页面跟插件走后行为不变）")
    void loadsFeaturePluginNamespace() {
        I18nBundleResponse response = service.loadBundle("gallery", Locale.SIMPLIFIED_CHINESE);
        assertThat(response.getNamespace()).isEqualTo("gallery");
        assertThat(response.getMessages()).isNotEmpty();
    }

    @Test
    @DisplayName("未注册的 namespace 抛 LocalizedException（400）")
    void unsupportedNamespaceThrows() {
        assertThatThrownBy(() -> service.loadBundle("nope", Locale.SIMPLIFIED_CHINESE))
                .isInstanceOf(LocalizedException.class);
    }
}
