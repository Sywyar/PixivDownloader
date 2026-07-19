package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("下载类型页面能力契约")
class DownloadGalleryCapabilitiesTest {

    @Test
    @DisplayName("易用工厂声明插件自有独立页并保留可本地化边界说明")
    void independentPageFactoryPreservesLocalizedBoundaryReason() {
        DownloadGalleryCapabilities capabilities = DownloadGalleryCapabilities.independentPageOnly(
                "example", "gallery.independent-page");

        assertThat(capabilities.independentPage()).isTrue();
        assertThat(capabilities.reasonNamespace()).isEqualTo("example");
        assertThat(capabilities.reasonI18nKey()).isEqualTo("gallery.independent-page");
    }

    @Test
    @DisplayName("页面能力只保留独立页与可本地化边界说明")
    void recordShapeContainsOnlyCurrentCapabilities() throws NoSuchMethodException {
        assertThat(DownloadGalleryCapabilities.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("independentPage", "reasonNamespace", "reasonI18nKey");
        assertThat(DownloadGalleryCapabilities.class.getDeclaredConstructor(
                boolean.class, String.class, String.class)).isNotNull();
        assertThat(DownloadGalleryCapabilities.class.getDeclaredMethods())
                .extracting(method -> method.getName())
                .doesNotContain("unifiedGallery");
    }
}
