package top.sywyar.pixivdownload.guicompose.model;

import java.io.InputStream;
import java.util.Properties;

/** Compose 关于页读取打包期应用元数据的最小 helper。 */
final class ComposeApplicationInfo {
    private ComposeApplicationInfo() {}

    static String kotlinVersion(String fallback) {
        try (InputStream stream = ComposeApplicationInfo.class.getResourceAsStream(
                "/app-version.properties")) {
            if (stream == null) return fallback;
            Properties properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty("kotlin.version");
            return version == null || version.isBlank() ? fallback : version.trim();
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
