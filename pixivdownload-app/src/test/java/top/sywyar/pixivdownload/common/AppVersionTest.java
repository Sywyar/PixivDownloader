package top.sywyar.pixivdownload.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AppVersion tests")
class AppVersionTest {

    private final String originalJpackageVersion = System.getProperty("jpackage.app-version");

    @AfterEach
    void tearDown() {
        restoreProperty("jpackage.app-version", originalJpackageVersion);
    }

    @Test
    @DisplayName("should prefer Maven filtered resource over truncated jpackage version")
    void shouldPreferMavenFilteredResourceOverJpackage() throws Exception {
        // jpackage 的 --app-version 只接受纯数字，预发布后缀（如 -rc1）会被打包脚本截断；
        // Maven 编译时写入的 app-version.properties 才保留完整发布版本，应当胜出。
        System.setProperty("jpackage.app-version", "1.2.3");

        assertThat(AppVersion.getDisplayVersionOrNull())
                .isEqualTo(mavenFilteredVersion())
                .isNotEqualTo("1.2.3");
    }

    @Test
    @DisplayName("should read version from Maven filtered resource")
    void shouldReadVersionFromMavenFilteredResource() throws Exception {
        System.clearProperty("jpackage.app-version");

        assertThat(mavenFilteredVersion())
                .isEqualTo(AppVersion.getDisplayVersionOrNull())
                .doesNotContain("@");
    }

    @Test
    @DisplayName("should normalize leading v prefix")
    void shouldNormalizeLeadingVPrefix() {
        assertThat(AppVersion.normalize("v2.0.1")).isEqualTo("2.0.1");
        assertThat(AppVersion.normalize("V2.0.1")).isEqualTo("2.0.1");
        assertThat(AppVersion.normalize("1.8.3-rc1")).isEqualTo("1.8.3-rc1");
        assertThat(AppVersion.normalize("version-x")).isEqualTo("version-x");
    }

    private static String mavenFilteredVersion() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = AppVersionTest.class.getResourceAsStream("/app-version.properties")) {
            properties.load(stream);
        }
        return AppVersion.normalize(properties.getProperty("app.version"));
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }
}
