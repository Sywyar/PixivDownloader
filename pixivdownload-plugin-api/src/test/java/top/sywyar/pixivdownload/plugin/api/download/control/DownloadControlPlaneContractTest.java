package top.sywyar.pixivdownload.plugin.api.download.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("下载宿主控制面稳定契约")
class DownloadControlPlaneContractTest {

    @Test
    @DisplayName("扩展快照防御性复制 publication 列表")
    void extensionSnapshotDefensivelyCopiesPublications() {
        DownloadExtensionIdentity owner = new DownloadExtensionIdentity("demo", "demo", 3L, 7L);
        List<DownloadTypePublication> types = new ArrayList<>(List.of(
                new DownloadTypePublication(owner, descriptor("demo"))));
        List<DownloadUiSlotPublication> slots = new ArrayList<>(List.of(
                new DownloadUiSlotPublication(
                        owner, new WebUiSlotContribution("demo.slot", "settings-card", null, 5))));

        DownloadExtensionSnapshot snapshot =
                new DownloadExtensionSnapshot("epoch", 2L, types, slots);
        types.clear();
        slots.clear();

        assertThat(snapshot.downloadTypes()).singleElement();
        assertThat(snapshot.uiSlots()).singleElement();
        assertThatThrownBy(() -> snapshot.downloadTypes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.uiSlots().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("精确取消命令保留不透明作品键和完整 publication 身份")
    void exactCancelCommandPreservesOpaqueWorkKeyAndPublication() {
        String workKey = " opaque/path:part ? # 中文 ";
        DownloadExtensionIdentity owner = new DownloadExtensionIdentity(
                "download-workbench", "download-workbench", 9L, 31L);

        DownloadQueueCancelCommand command =
                new DownloadQueueCancelCommand("illust", workKey, owner);

        assertThat(command.workKey()).isEqualTo(workKey);
        assertThat(command.expectedPublication()).isSameAs(owner);
    }

    @Test
    @DisplayName("publication 身份拒绝空 owner 和非法代际")
    void publicationIdentityRejectsInvalidValues() {
        assertThatThrownBy(() -> new DownloadExtensionIdentity(" ", "demo", 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DownloadExtensionIdentity("demo", "demo", -1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DownloadExtensionIdentity("demo", "demo", 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DownloadTypeDescriptor descriptor(String type) {
        return new DownloadTypeDescriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                type,
                "demo",
                "demo.type",
                0,
                "download",
                "green",
                "/demo/" + type + ".js",
                List.of(),
                true,
                List.of(),
                List.of(),
                "demo");
    }
}
