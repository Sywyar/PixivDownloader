package top.sywyar.pixivdownload.plugin.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("下载类型 descriptor 当前契约")
class DownloadTypeDescriptorTest {

    @Test
    @DisplayName("无版本与负版本不能伪装成当前契约")
    void nonPositiveContractVersionIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> descriptor(0));
        assertThatIllegalArgumentException().isThrownBy(() -> descriptor(-1));
    }

    @Test
    @DisplayName("队列贡献只接受显式 descriptor 且不恢复旧兼容入口")
    void queueContributionHasOnlyCurrentDescriptorShape() {
        assertThat(QueueTypeContribution.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterCount()).isEqualTo(7));
        assertThat(QueueTypeContribution.class.getDeclaredMethods())
                .extracting(method -> method.getName())
                .doesNotContain("usesLegacyDescriptor");
        assertThat(DownloadTypeDescriptor.class.getDeclaredMethods())
                .extracting(method -> method.getName())
                .doesNotContain("legacy");
    }

    private static DownloadTypeDescriptor descriptor(int contractVersion) {
        return new DownloadTypeDescriptor(
                contractVersion,
                "example",
                "example",
                "example",
                "type.example",
                10,
                "download",
                "neutral",
                "/example/type.js",
                List.of(DownloadAcquisitionMode.SINGLE_IMPORT),
                DownloadQueueCapabilities.full(),
                DownloadScheduleCapabilities.notSaveable(),
                List.of(),
                List.of(),
                List.of(),
                "example",
                DownloadGalleryCapabilities.none());
    }
}
