package top.sywyar.pixivdownload.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkVersionTest {

    @Test
    @DisplayName("SDK 元数据派生统一的预发布身份和兼容版本")
    void metadataAndCompatibilityUseOneSdkVersion() {
        assertThat(SdkVersion.VERSION).isEqualTo("1.0.0-rc1");
        assertThat(SdkVersion.MAJOR).isEqualTo(1);
        assertThat(SdkVersion.MINOR).isZero();
        assertThat(SdkVersion.PATCH).isZero();
        assertThat(SdkVersion.PRERELEASE_CHANNEL).isEqualTo("rc");
        assertThat(SdkVersion.PRERELEASE_SEQUENCE).isEqualTo(1);
        assertThat(SdkVersion.isPrerelease()).isTrue();
        assertThat(SdkVersion.releaseId()).isEqualTo("sdk-api-v1.0.0-rc1");
        assertThat(SdkVersion.isCompatibleWith(1, 0)).isTrue();
        assertThat(SdkVersion.isCompatible(1, 2, 1, 1)).isTrue();
        assertThat(SdkVersion.isCompatible(1, 0, 1, 1)).isFalse();
        assertThat(SdkVersion.isCompatible(2, 0, 1, 0)).isFalse();
    }

    @Test
    @DisplayName("版本解析仅接受结构化预发布后缀")
    void versionParserAcceptsOnlyStructuredPrereleases() {
        SdkVersion.Metadata stable = SdkVersion.parse("2.3.4");
        SdkVersion.Metadata beta = SdkVersion.parse("2.3.4-beta12");

        assertThat(stable.prereleaseChannel()).isEmpty();
        assertThat(stable.prereleaseSequence()).isZero();
        assertThat(beta.prereleaseChannel()).isEqualTo("beta");
        assertThat(beta.prereleaseSequence()).isEqualTo(12);
        assertThatThrownBy(() -> SdkVersion.parse("1.0.0-rc.1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SdkVersion.parse("1.0.0-rc0"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SdkVersion.parse("1.0.0-r1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SdkVersion.parse("01.0.0"))
                .isInstanceOf(IllegalStateException.class);
    }
}
