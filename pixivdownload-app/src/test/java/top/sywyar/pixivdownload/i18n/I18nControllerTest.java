package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("/api/i18n/meta 元数据完全来自 LocaleCatalog")
class I18nControllerTest {

    private final LocaleCatalog catalog = LocaleCatalog.defaultCatalog();
    private final I18nController controller = new I18nController(
            new WebI18nBundleRegistry(new PluginRegistry(BuiltInPlugins.createAll())),
            new WebI18nService(new WebI18nBundleRegistry(new PluginRegistry(BuiltInPlugins.createAll()))),
            catalog);

    @Test
    @DisplayName("开发模式元数据提供当前实例名称，关闭后恢复原有名称")
    void cookieNameFollowsDevelopmentMode() {
        String previous = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        var request = new MockHttpServletRequest();
        request.setServerPort(8081);
        try {
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
            assertThat(controller.metadata(Locale.US, request).getLanguageCookieName()).isEqualTo("pixiv_dev_8081_pixiv_lang");
            System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "false");
            assertThat(controller.metadata(Locale.US, request).getLanguageCookieName()).isEqualTo(catalog.languageCookieName());
        } finally {
            if (previous == null) System.clearProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
            else System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previous);
        }
    }

    @Test
    @DisplayName("默认 / 未知语言时 currentLang 为默认语言 en-US")
    void currentLangDefaultsToEnglish() {
        I18nMetadataResponse meta = controller.metadata(null, new MockHttpServletRequest());
        assertThat(meta.getCurrentLang()).isEqualTo("en-US");
    }

    @Test
    @DisplayName("currentLang 按请求语言归一化")
    void currentLangNormalizesRequestLanguage() {
        I18nMetadataResponse meta = controller.metadata(Locale.US, new MockHttpServletRequest());
        assertThat(meta.getCurrentLang()).isEqualTo("en-US");
    }

    @Test
    @DisplayName("source/default/fallback/cookie/param 全部来自 catalog")
    void coreFieldsComeFromCatalog() {
        I18nMetadataResponse meta = controller.metadata(null, new MockHttpServletRequest());
        assertThat(meta.getSourceLang()).isEqualTo(catalog.sourceLocale().tag());
        assertThat(meta.getDefaultLang()).isEqualTo(catalog.defaultLocale().tag());
        assertThat(meta.getFallbackLang()).isEqualTo(catalog.fallbackLocale().tag());
        assertThat(meta.getLanguageCookieName()).isEqualTo(catalog.languageCookieName());
        assertThat(meta.getLanguageParamName()).isEqualTo(catalog.languageParameterName());
    }

    @Test
    @DisplayName("supportedLocales 为 catalog 可见语言且带 tag/nativeName/direction/status")
    void supportedLocalesMirrorCatalog() {
        I18nMetadataResponse meta = controller.metadata(null, new MockHttpServletRequest());
        assertThat(meta.getSupportedLocales()).hasSameSizeAs(catalog.visibleLocales());
        for (LocaleOptionResponse option : meta.getSupportedLocales()) {
            LocaleDescriptor descriptor = catalog.match(option.getTag()).orElseThrow();
            assertThat(option.getLabel()).isEqualTo(descriptor.nativeName());
            assertThat(option.getNativeName()).isEqualTo(descriptor.nativeName());
            assertThat(option.getDirection()).isEqualTo(descriptor.direction());
            assertThat(option.getStatus()).isEqualTo(descriptor.status().name());
        }
    }

    @Test
    @DisplayName("namespace 列表非空且含核心 namespace")
    void namespacesPresent() {
        I18nMetadataResponse meta = controller.metadata(null, new MockHttpServletRequest());
        assertThat(meta.getSupportedNamespaces()).contains("common");
    }
}
