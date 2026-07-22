package top.sywyar.pixivdownload.plugin.api.download.type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("下载类型 descriptor 当前契约")
class DownloadTypeDescriptorTest {

    @Test
    @DisplayName("record 只保留单一下载类型事实源字段")
    void recordHasExactCurrentShape() {
        var components = Arrays.asList(DownloadTypeDescriptor.class.getRecordComponents());

        assertThat(components.stream()
                .map(component -> component.getName()).toList())
                .containsExactly(
                        "contractVersion",
                        "type",
                        "displayNamespace",
                        "displayI18nKey",
                        "order",
                        "iconKey",
                        "colorToken",
                        "moduleUrl",
                        "acquisitionModes",
                        "cancelSupported",
                        "filters",
                        "settings",
                        "i18nNamespace");
        assertThat(components.stream()
                .map(component -> component.getType().getName()).toList())
                .containsExactly(
                        "int",
                        String.class.getName(),
                        String.class.getName(),
                        String.class.getName(),
                        "int",
                        String.class.getName(),
                        String.class.getName(),
                        String.class.getName(),
                        List.class.getName(),
                        "boolean",
                        List.class.getName(),
                        List.class.getName(),
                        String.class.getName());
        assertThat(DownloadTypeDescriptor.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterCount()).isEqualTo(13));
        assertThat(DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION).isEqualTo(1);
        assertThat(DownloadTypeDescriptor.class.getDeclaredMethods())
                .extracting(method -> method.getName())
                .doesNotContain("pluginId", "queue", "schedule", "uiSlots", "gallery", "legacy");
    }

    @Test
    @DisplayName("无版本与负版本不能伪装成当前契约")
    void nonPositiveContractVersionIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> descriptor(0, "/example/type.js"));
        assertThatIllegalArgumentException().isThrownBy(() -> descriptor(-1, "/example/type.js"));
    }

    @Test
    @DisplayName("前端行为模块地址必须显式且非空白")
    void moduleUrlMustBeDeclared() {
        assertThatIllegalArgumentException().isThrownBy(() -> descriptor(1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> descriptor(1, "   "));
        assertThat(descriptor(1, " /example/type.js ").moduleUrl()).isEqualTo("/example/type.js");
    }

    @Test
    @DisplayName("取得模式与筛选设置列表均为防御性不可变副本")
    void listComponentsAreDefensivelyCopied() {
        List<DownloadAcquisitionMode> modes = new ArrayList<>(List.of(DownloadAcquisitionMode.SINGLE_IMPORT));
        List<String> filters = new ArrayList<>(List.of("example-filter"));
        List<String> settings = new ArrayList<>(List.of("example-setting"));

        DownloadTypeDescriptor descriptor = new DownloadTypeDescriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                "example",
                "example",
                "type.example",
                10,
                "download",
                "neutral",
                "/example/type.js",
                modes,
                true,
                filters,
                settings,
                "example");
        modes.clear();
        filters.clear();
        settings.clear();

        assertThat(descriptor.acquisitionModes()).containsExactly(DownloadAcquisitionMode.SINGLE_IMPORT);
        assertThat(descriptor.filters()).containsExactly("example-filter");
        assertThat(descriptor.settings()).containsExactly("example-setting");
        assertThatThrownBy(() -> descriptor.filters().add("other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("缺省列表规范化为空不可变列表")
    void nullListsAreNormalizedToEmptyLists() {
        DownloadTypeDescriptor descriptor = new DownloadTypeDescriptor(
                DownloadTypeDescriptor.CURRENT_CONTRACT_VERSION,
                "example",
                "example",
                "type.example",
                10,
                "download",
                "neutral",
                "/example/type.js",
                null,
                false,
                null,
                null,
                "example");

        assertThat(descriptor.acquisitionModes()).isEmpty();
        assertThat(descriptor.filters()).isEmpty();
        assertThat(descriptor.settings()).isEmpty();
    }

    private static DownloadTypeDescriptor descriptor(int contractVersion, String moduleUrl) {
        return new DownloadTypeDescriptor(
                contractVersion,
                "example",
                "example",
                "type.example",
                10,
                "download",
                "neutral",
                moduleUrl,
                List.of(DownloadAcquisitionMode.SINGLE_IMPORT),
                true,
                List.of(),
                List.of(),
                "example");
    }
}
