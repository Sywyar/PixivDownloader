package top.sywyar.pixivdownload.plugin.api.download.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

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
        assertThat(Arrays.stream(DownloadQueueCancelCommand.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("queueType", "workKey", "expectedPublication");
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

    @Test
    @DisplayName("控制面只暴露快照、精确取消与清空三类操作")
    void controlPlaneHasExactOperationSurface() throws NoSuchMethodException {
        assertThat(Arrays.stream(DownloadControlPlane.class.getDeclaredMethods())
                .map(method -> method.getName()))
                .containsExactlyInAnyOrder("extensions", "cancelExact", "clearQueues");
        assertMethod(
                "extensions",
                DownloadExtensionSnapshot.class);
        assertMethod(
                "cancelExact",
                DownloadQueueCancelResult.class,
                DownloadQueueCancelCommand.class,
                Supplier.class);
        assertMethod(
                "clearQueues",
                int.class,
                RequestOwnerIdentity.class);
        assertThat(DownloadQueueCancelResult.values())
                .containsExactly(
                        DownloadQueueCancelResult.CANCELLED,
                        DownloadQueueCancelResult.DESCRIPTOR_NOT_FOUND,
                        DownloadQueueCancelResult.DESCRIPTOR_STALE,
                        DownloadQueueCancelResult.UNSUPPORTED,
                        DownloadQueueCancelResult.OPERATION_UNAVAILABLE);
    }

    @Test
    @DisplayName("下载控制面纯值记录保持精确组件形状")
    void controlPlaneRecordsHaveExactComponents() {
        assertRecordShape(
                DownloadExtensionIdentity.class,
                new String[]{"pluginId", "packageId", "generation", "publicationId"},
                new Class<?>[]{String.class, String.class, long.class, long.class});
        assertRecordShape(
                DownloadExtensionSnapshot.class,
                new String[]{"epoch", "revision", "downloadTypes", "uiSlots"},
                new Class<?>[]{String.class, long.class, List.class, List.class});
        assertRecordShape(
                DownloadTypePublication.class,
                new String[]{"owner", "descriptor"},
                new Class<?>[]{DownloadExtensionIdentity.class, DownloadTypeDescriptor.class});
        assertRecordShape(
                DownloadUiSlotPublication.class,
                new String[]{"owner", "slot"},
                new Class<?>[]{DownloadExtensionIdentity.class, WebUiSlotContribution.class});
        assertRecordShape(
                DownloadQueueCancelCommand.class,
                new String[]{"queueType", "workKey", "expectedPublication"},
                new Class<?>[]{String.class, String.class, DownloadExtensionIdentity.class});
    }

    private static void assertMethod(
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = DownloadControlPlane.class.getDeclaredMethod(name, parameterTypes);
        assertThat(method.getReturnType()).isEqualTo(returnType);
    }

    private static void assertRecordShape(
            Class<?> recordType,
            String[] componentNames,
            Class<?>[] componentTypes) {
        assertThat(recordType.isRecord()).isTrue();
        assertThat(Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(componentNames);
        List<String> actualTypes = Arrays.stream(recordType.getRecordComponents())
                .map(component -> component.getType().getName())
                .toList();
        List<String> expectedTypes = Arrays.stream(componentTypes)
                .map(Class::getName)
                .toList();
        assertThat(actualTypes).isEqualTo(expectedTypes);
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
