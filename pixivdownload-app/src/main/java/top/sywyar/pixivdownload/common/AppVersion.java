package top.sywyar.pixivdownload.common;

import lombok.experimental.UtilityClass;
import top.sywyar.pixivdownload.config.DevelopmentBuildInfo;

import java.io.InputStream;
import java.util.Properties;

/**
 * 应用版本读取工具。
 */
@UtilityClass
public class AppVersion {

    private static final String JPACKAGE_APP_VERSION = "jpackage.app-version";
    private static final String APP_VERSION_PROPERTIES = "/app-version.properties";
    private static final String APP_VERSION_KEY = "app.version";
    private static final String KOTLIN_VERSION_KEY = "kotlin.version";
    private static final String MAVEN_POM_PROPERTIES = AppInfo.MAVEN_POM_PROPERTIES_RESOURCE;

    /**
     * 返回展示给用户的应用版本。
     * 优先读取 Maven 编译时写入的版本文件（保留 {@code -rc1} 等完整预发布后缀），
     * jpackage 注入的版本会被安装包约束强制截断为纯数字，仅作为兜底。
     */
    public static String getDisplayVersionOrNull() {
        String version = firstNonBlank(
                readVersionFromProperties(APP_VERSION_PROPERTIES, APP_VERSION_KEY),
                System.getProperty(JPACKAGE_APP_VERSION),
                AppVersion.class.getPackage().getImplementationVersion(),
                readVersionFromProperties(MAVEN_POM_PROPERTIES, "version")
        );
        return normalize(version);
    }

    public static String getDisplayVersionOrDefault(String defaultVersion) {
        String version = getDisplayVersionOrNull();
        return version != null ? version : defaultVersion;
    }

    /** 开发显示优先使用显式版本，其次为当前源码可达的最近发行标签。 */
    public static String getDevelopmentDisplayVersion() {
        String version = getDisplayVersionOrDefault("");
        var development = DevelopmentBuildInfo.current();
        if (development == null) return version;
        String explicit = normalize(System.getProperty("app.release.version"));
        String projectVersion = readVersionFromProperties(APP_VERSION_PROPERTIES, "app.project.version");
        if (explicit != null) version = explicit;
        else if ((version.isBlank() || version.equals(projectVersion)) && !development.baseVersion().isBlank()) {
            version = development.baseVersion();
        }
        return development.displayVersion(version);
    }

    public static String getKotlinVersionOrDefault(String defaultVersion) {
        String version = normalize(readVersionFromProperties(APP_VERSION_PROPERTIES, KOTLIN_VERSION_KEY));
        return version != null ? version : defaultVersion;
    }

    /** 返回构建渠道原始标记；缺失资源返回 {@code null}。 */
    public static String getBuildChannelMarker() {
        return readVersionFromProperties(APP_VERSION_PROPERTIES, "app.build.channel");
    }

    private static String readVersionFromProperties(String resourcePath, String key) {
        try (InputStream stream = AppVersion.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties.getProperty(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    static String normalize(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }

        String normalized = version.trim();
        if (normalized.length() > 1
                && (normalized.charAt(0) == 'v' || normalized.charAt(0) == 'V')
                && Character.isDigit(normalized.charAt(1))) {
            return normalized.substring(1);
        }
        return normalized;
    }
}
