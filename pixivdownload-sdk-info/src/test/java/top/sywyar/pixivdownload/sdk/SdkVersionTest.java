package top.sywyar.pixivdownload.sdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SdkVersionTest {

    @Test
    void metadataAndCompatibilityUseOneSdkVersion() {
        assertThat(SdkVersion.VERSION).isEqualTo("1.0.0");
        assertThat(SdkVersion.releaseId()).isEqualTo("sdk-api-v1.0.0-r1");
        assertThat(SdkVersion.isCompatibleWith(1, 0)).isTrue();
        assertThat(SdkVersion.isCompatible(1, 2, 1, 1)).isTrue();
        assertThat(SdkVersion.isCompatible(1, 0, 1, 1)).isFalse();
        assertThat(SdkVersion.isCompatible(2, 0, 1, 0)).isFalse();
    }
}
