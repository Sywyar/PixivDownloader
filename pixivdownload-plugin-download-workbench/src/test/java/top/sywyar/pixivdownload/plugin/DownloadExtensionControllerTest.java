package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadControlPlane;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadExtensionIdentity;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadExtensionSnapshot;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadTypePublication;
import top.sywyar.pixivdownload.plugin.api.download.control.DownloadUiSlotPublication;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;
import top.sywyar.pixivdownload.download.web.DownloadExtensionController;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DownloadExtensionController} 单测：端点只投影稳定控制面提供的 owner-stamped 下载类型与 UI 槽位。
 */
@DisplayName("DownloadExtensionController 下载页扩展点端点")
class DownloadExtensionControllerTest {

    @Test
    @DisplayName("下载类型与 ui-slot 稳定排序并只投影收窄契约和可信 owner")
    void downloadTypesAndUiSlotsAreSortedAndProjected() {
        DownloadExtensionIdentity owner = new DownloadExtensionIdentity("demo", "demo", 0L, 7L);
        DownloadTypeDescriptor later = descriptor("later", 8);
        DownloadTypeDescriptor earlier = descriptor("earlier", 5);
        List<DownloadUiSlotPublication> slots = List.of(
                publishedSlot(owner, new WebUiSlotContribution(
                        "demo.z", "settings-card", null, 10)),
                publishedSlot(owner, new WebUiSlotContribution(
                        "demo.a", "import-hint", null, 10)),
                publishedSlot(owner, new WebUiSlotContribution(
                        "demo.m", "cookie-tools", null, 5)));
        DownloadExtensionSnapshot snapshot = new DownloadExtensionSnapshot(
                "demo-epoch",
                1L,
                List.of(
                        new DownloadTypePublication(owner, later),
                        new DownloadTypePublication(owner, earlier)),
                slots);

        ResponseEntity<DownloadExtensionController.DownloadExtensionsView> response = responseFor(snapshot);
        DownloadExtensionController.DownloadExtensionsView view = response.getBody();

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(view).isNotNull();
        assertThat(view.epoch()).isEqualTo("demo-epoch");
        assertThat(view.revision()).isEqualTo(1L);
        assertThat(view.downloadTypes())
                .extracting(DownloadExtensionController.DownloadTypeView::type)
                .containsExactly("earlier", "later");
        assertThat(view.uiSlots()).extracting(DownloadExtensionController.UiSlotView::slotId)
                .containsExactly("demo.m", "demo.a", "demo.z");

        DownloadExtensionController.UiSlotView first = view.uiSlots().get(0);
        assertThat(first.target()).isEqualTo("cookie-tools");
        assertThat(first.moduleUrl()).isNull();
        assertThat(first.owner().pluginId()).isEqualTo("demo");
        assertThat(first.owner().packageId()).isEqualTo("demo");
        assertThat(first.owner().generation()).isZero();
        assertThat(first.owner().publicationId()).isEqualTo(7L);
        assertThat(first.metadata()).isEqualTo(Map.of());
        assertThat(view.downloadTypes().get(0)).satisfies(type -> {
            assertThat(type.contractVersion()).isEqualTo(1);
            assertThat(type.displayNamespace()).isEqualTo("demo");
            assertThat(type.displayI18nKey()).isEqualTo("demo.kind");
            assertThat(type.moduleUrl()).isEqualTo("/demo/earlier.js");
            assertThat(type.acquisitionModes()).containsExactly("single-import");
            assertThat(type.cancelSupported()).isTrue();
            assertThat(type.owner().pluginId()).isEqualTo("demo");
            assertThat(type.owner().publicationId()).isEqualTo(7L);
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
    @DisplayName("控制面没有可用扩展时端点返回空列表")
    void emptyControlPlaneSnapshotProducesEmptyLists() {
        DownloadExtensionController.DownloadExtensionsView view = responseFor(
                new DownloadExtensionSnapshot("empty-epoch", 0L, List.of(), List.of())).getBody();

        assertThat(view).isNotNull();
        assertThat(view.downloadTypes()).isEmpty();
        assertThat(view.uiSlots()).isEmpty();
    }

    private static ResponseEntity<DownloadExtensionController.DownloadExtensionsView> responseFor(
            DownloadExtensionSnapshot snapshot) {
        DownloadControlPlane controlPlane = mock(DownloadControlPlane.class);
        when(controlPlane.extensions()).thenReturn(snapshot);
        return new DownloadExtensionController(controlPlane).extensions();
    }

    private static DownloadTypeDescriptor descriptor(String type, int order) {
        return new DownloadTypeDescriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                type,
                "demo",
                "demo.kind",
                order,
                "download",
                "neutral",
                "/demo/" + type + ".js",
                List.of(DownloadAcquisitionMode.SINGLE_IMPORT),
                true,
                List.of(),
                List.of(),
                "demo");
    }

    private static DownloadUiSlotPublication publishedSlot(
            DownloadExtensionIdentity owner,
            WebUiSlotContribution slot) {
        return new DownloadUiSlotPublication(owner, slot);
    }
}
