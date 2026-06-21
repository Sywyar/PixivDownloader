package top.sywyar.pixivdownload.common;

import lombok.experimental.UtilityClass;

/**
 * 应用级固定信息。
 */
@UtilityClass
public class AppInfo {

    public static final String NAME = "PixivDownloader";
    public static final String LEGACY_ARTIFACT_NAME = "PixivDownload";
    public static final String EXECUTABLE_NAME = LEGACY_ARTIFACT_NAME + ".exe";
    public static final String SHORTCUT_NAME = LEGACY_ARTIFACT_NAME + ".lnk";
    public static final String HIDDEN_DIRECTORY_NAME = ".pixivdownload";

    public static final String GITHUB_REPOSITORY = "Sywyar/" + NAME;
    public static final String GITHUB_URL = "https://github.com/" + GITHUB_REPOSITORY;
    public static final String RELEASES_URL = GITHUB_URL + "/releases";
    public static final String DOCS_URL = "https://sywyar.github.io/" + NAME + "/";
    public static final String LICENSE_URL = GITHUB_URL + "/blob/master/LICENSE";
    public static final String LATEST_RELEASE_DOWNLOAD_URL = RELEASES_URL + "/latest/download";
    public static final String NIGHTLY_RELEASE_DOWNLOAD_URL = RELEASES_URL + "/download/nightly";

    public static final String MAVEN_POM_PROPERTIES_RESOURCE =
            "/META-INF/maven/top.sywyar.lovepopup/" + LEGACY_ARTIFACT_NAME + "/pom.properties";

    public static String userAgent(String component) {
        return NAME + "/" + component;
    }

    public static String dashedUserAgent(String component) {
        return NAME + "-" + component;
    }

    /**
     * 当前进程是否由 jpackage 原生启动器（安装版 / 便携版的 {@code PixivDownload.exe}）拉起。
     * <p>jpackage 启动器会注入 {@code jpackage.app-version} 系统属性；以 {@code java -jar} 直接
     * 运行 fat-jar 时该属性缺失。在线更新「下载安装包并覆盖安装」只对 exe 启动有效——jar 启动
     * 需引导用户手动到 GitHub 发布页下载。
     */
    public static boolean isLaunchedFromExe() {
        String version = System.getProperty("jpackage.app-version");
        return version != null && !version.isBlank();
    }
}
