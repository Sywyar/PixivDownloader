package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("下载类型页面能力契约")
class DownloadGalleryCapabilitiesTest {

    @Test
    @DisplayName("易用工厂只声明插件自有独立页并保留可本地化边界说明")
    @SuppressWarnings("deprecation")
    void independentPageFactoryDoesNotAdvertiseDeprecatedUnifiedGallery() {
        DownloadGalleryCapabilities capabilities = DownloadGalleryCapabilities.independentPageOnly(
                "example", "gallery.independent-page");

        assertThat(capabilities.unifiedGallery()).isFalse();
        assertThat(capabilities.independentPage()).isTrue();
        assertThat(capabilities.reasonNamespace()).isEqualTo("example");
        assertThat(capabilities.reasonI18nKey()).isEqualTo("gallery.independent-page");
    }

    @Test
    @DisplayName("旧统一画廊访问器显式标记过时且没有虚构移除承诺")
    void unifiedGalleryAccessorIsDeprecatedWithoutRemovalSchedule() throws NoSuchMethodException {
        Deprecated annotation = DownloadGalleryCapabilities.class
                .getMethod("unifiedGallery")
                .getAnnotation(Deprecated.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.since()).isEqualTo("1.0.0");
        assertThat(annotation.forRemoval()).isFalse();
        assertThat(DownloadGalleryCapabilities.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("unifiedGallery", "independentPage", "reasonNamespace", "reasonI18nKey");
        assertThat(DownloadGalleryCapabilities.class.getDeclaredConstructor(
                boolean.class, boolean.class, String.class, String.class)).isNotNull();
    }
}
