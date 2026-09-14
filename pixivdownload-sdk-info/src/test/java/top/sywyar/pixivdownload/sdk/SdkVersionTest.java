package top.sywyar.pixivdownload.sdk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SdkVersionTest {

    @Test
    @DisplayName("SDK 元数据派生统一的预发布身份和兼容版本")
    void metadataAndCompatibilityUseOneSdkVersion() throws Exception {
        Properties properties = new Properties();
        try (var resource = getClass().getResourceAsStream("/META-INF/pixivdownload-sdk.properties")) {
            assertThat(resource).isNotNull();
            properties.load(resource);
        }
        var expected = SdkVersion.parse(properties.getProperty("version"));
        assertThat(SdkVersion.VERSION).isEqualTo(expected.version());
        assertThat(SdkVersion.MAJOR).isEqualTo(expected.major());
        assertThat(SdkVersion.MINOR).isEqualTo(expected.minor());
        assertThat(SdkVersion.PATCH).isEqualTo(expected.patch());
        assertThat(SdkVersion.PRERELEASE_CHANNEL).isEqualTo(expected.prereleaseChannel());
        assertThat(SdkVersion.PRERELEASE_SEQUENCE).isEqualTo(expected.prereleaseSequence());
        assertThat(SdkVersion.isPrerelease()).isEqualTo(!expected.prereleaseChannel().isEmpty());
        assertThat(SdkVersion.releaseId()).isEqualTo("sdk-api-v" + expected.version());
        assertThat(SdkVersion.isCompatibleWith(expected.major(), expected.minor())).isTrue();
        assertThat(SdkVersion.isCompatible(expected.major(), expected.minor() + 1, expected.major(), expected.minor())).isTrue();
        assertThat(SdkVersion.isCompatibleWith(expected.major(), expected.minor() + 1)).isFalse();
        assertThat(SdkVersion.isCompatibleWith(expected.major() + 1, expected.minor())).isFalse();
    }

    @Test
    @DisplayName("版本解析仅接受结构化预发布后缀")
    void versionParserAcceptsOnlyStructuredPrereleases() {
        String version = SdkVersion.MAJOR + "." + SdkVersion.MINOR + "." + SdkVersion.PATCH;
        int sequence = Math.max(1, SdkVersion.PRERELEASE_SEQUENCE);
        SdkVersion.Metadata stable = SdkVersion.parse(version);
        assertThat(stable.prereleaseChannel()).isEmpty();
        assertThat(stable.prereleaseSequence()).isZero();
        for (String channel : new String[]{"alpha", "beta", "rc"}) {
            for (String separator : new String[]{"", "."}) {
                String raw = version + "-" + channel + separator + sequence;
                var parsed = SdkVersion.parse(raw);
                assertThat(parsed.version()).isEqualTo(raw);
                assertThat(parsed.prereleaseChannel()).isEqualTo(channel);
                assertThat(parsed.prereleaseSequence()).isEqualTo(sequence);
            }
            for (String suffix : new String[]{".0", ".01", "..1", ".1.2", ".1+build", ".", ""}) {
                assertThatThrownBy(() -> SdkVersion.parse(version + "-" + channel + suffix))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
        assertThatThrownBy(() -> SdkVersion.parse(version + "-rc0"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SdkVersion.parse(version + "-r" + sequence))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SdkVersion.parse("0" + (SdkVersion.MAJOR + 1) + "." + SdkVersion.MINOR + "." + SdkVersion.PATCH))
                .isInstanceOf(IllegalStateException.class);
    }
}
