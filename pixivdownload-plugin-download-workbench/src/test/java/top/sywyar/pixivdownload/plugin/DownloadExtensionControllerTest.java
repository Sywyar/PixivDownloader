package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionOwner;
import top.sywyar.pixivdownload.plugin.registry.DownloadExtensionRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.web.DownloadExtensionController;
import top.sywyar.pixivdownload.plugin.web.PluginOwnedWebAssetValidator;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DownloadExtensionController} 单测：端点仅投影 owner-stamped 下载类型与独立发布的 UI 槽位。
 * 重点验证响应字段收窄、稳定排序、可信 owner，以及 descriptor 不再携带 queue / schedule / gallery /
 * uiSlots / pluginId 等职责外数据。
 */
@DisplayName("DownloadExtensionController 下载页扩展点端点")
class DownloadExtensionControllerTest {

    private static DownloadExtensionController controllerFor(PluginRegistry registry) {
        StaticResourceRegistry staticResources = new StaticResourceRegistry(registry);
        PluginOwnedWebAssetValidator validator = new PluginOwnedWebAssetValidator(staticResources);
        return new DownloadExtensionController(new DownloadExtensionRegistry(
                registry, staticResources, validator));
    }

    private static ResponseEntity<DownloadExtensionController.DownloadExtensionsView> responseFor(
            PluginRegistry registry) {
        return controllerFor(registry).extensions();
    }

    private static ResponseEntity<DownloadExtensionController.DownloadExtensionsView> responseFor(
            DownloadExtensionRegistry.Snapshot snapshot) {
        DownloadExtensionRegistry registry = mock(DownloadExtensionRegistry.class);
        when(registry.snapshot()).thenReturn(snapshot);
        return new DownloadExtensionController(registry).extensions();
    }

    @Test
    @DisplayName("下载类型与 ui-slot 稳定排序并只投影收窄契约和可信 owner")
    void downloadTypesAndUiSlotsAreSortedAndProjected() {
        DownloadExtensionOwner owner = new DownloadExtensionOwner("demo", "demo", 0L);
        long publicationId = 7L;
        DownloadTypeDescriptor descriptor = new DownloadTypeDescriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                "demo",
                "demo",
                "demo.kind",
                5,
                "download",
                "neutral",
                "/pixiv-batch/pixiv-queue-type.js",
                List.of(DownloadAcquisitionMode.SINGLE_IMPORT),
                true,
                List.of(),
                List.of(),
                "demo");
        List<DownloadExtensionRegistry.RegisteredUiSlot> slots = List.of(
                registeredSlot(owner, publicationId,
                        new WebUiSlotContribution("demo.z", "settings-card", null, 10)),
                registeredSlot(owner, publicationId,
                        new WebUiSlotContribution("demo.a", "import-hint", null, 10)),
                registeredSlot(owner, publicationId,
                        new WebUiSlotContribution("demo.m", "cookie-tools", null, 5)));
        DownloadExtensionRegistry.Snapshot snapshot = new DownloadExtensionRegistry.Snapshot(
                "demo-epoch",
                1L,
                List.of(new DownloadExtensionRegistry.RegisteredDownloadType(
                        owner, publicationId, descriptor)),
                slots);
        ResponseEntity<DownloadExtensionController.DownloadExtensionsView> response = responseFor(snapshot);
        DownloadExtensionController.DownloadExtensionsView view = response.getBody();

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(view).isNotNull();
        assertThat(view.epoch()).isNotBlank();
        assertThat(view.revision()).isEqualTo(1L);
        assertThat(view.uiSlots()).extracting(DownloadExtensionController.UiSlotView::slotId)
                .containsExactly("demo.m", "demo.a", "demo.z");

        DownloadExtensionController.UiSlotView first = view.uiSlots().get(0);
        assertThat(first.target()).isEqualTo("cookie-tools");
        assertThat(first.moduleUrl()).isNull();
        assertThat(first.owner().pluginId()).isEqualTo("demo");
        assertThat(first.owner().packageId()).isEqualTo("demo");
        assertThat(first.owner().generation()).isZero();
        assertThat(first.owner().publicationId()).isPositive();
        assertThat(first.metadata()).isEqualTo(Map.of());
        assertThat(view.downloadTypes()).singleElement()
                .satisfies(type -> {
                    assertThat(type.type()).isEqualTo("demo");
                    assertThat(type.contractVersion()).isEqualTo(1);
                    assertThat(type.displayNamespace()).isEqualTo("demo");
                    assertThat(type.displayI18nKey()).isEqualTo("demo.kind");
                    assertThat(type.moduleUrl()).isEqualTo("/pixiv-batch/pixiv-queue-type.js");
                    assertThat(type.acquisitionModes()).containsExactly("single-import");
                    assertThat(type.cancelSupported()).isTrue();
                    assertThat(type.owner().pluginId()).isEqualTo("demo");
                    assertThat(type.owner().publicationId()).isPositive();
                });
    }

    @Test
    @DisplayName("扩展响应与下载类型视图只包含当前单一来源契约字段")
    void responseRecordsContainOnlyCurrentContractFields() {
        assertThat(DownloadExtensionController.DownloadExtensionsView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("epoch", "revision", "downloadTypes", "uiSlots");
        assertThat(DownloadExtensionController.DownloadTypeView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "contractVersion", "type", "displayNamespace", "displayI18nKey", "order",
                        "iconKey", "colorToken", "moduleUrl", "acquisitionModes", "cancelSupported",
                        "filters", "settings", "i18nNamespace", "owner")
                .doesNotContain("pluginId", "queue", "schedule", "gallery", "uiSlots");
        assertThat(DownloadExtensionController.UiSlotView.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("slotId", "target", "moduleUrl", "order", "metadata", "owner");
    }

    @Test
    @DisplayName("novel 插件缺席时端点不暴露小说 UI 槽位")
    void novelUiSlotsAreAbsentWithoutNovelPlugin() {
        DownloadExtensionController.DownloadExtensionsView view = responseFor(
                new PluginRegistry(BuiltInPlugins.createAll())).getBody();
        assertThat(view).isNotNull();
        assertThat(view.uiSlots()).extracting(DownloadExtensionController.UiSlotView::target)
                .doesNotContain("kind-option-user", "kind-option-search", "kind-option-quick",
                        "quick-actions-bookmarks", "quick-actions-mine",
                        "import-hint", "search-filter", "settings-card");
    }

    private static DownloadExtensionRegistry.RegisteredUiSlot registeredSlot(
            DownloadExtensionOwner owner,
            long publicationId,
            WebUiSlotContribution slot) {
        return new DownloadExtensionRegistry.RegisteredUiSlot(owner, publicationId, slot);
    }
}
