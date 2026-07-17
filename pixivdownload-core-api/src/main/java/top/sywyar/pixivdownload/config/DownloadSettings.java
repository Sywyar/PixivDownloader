package top.sywyar.pixivdownload.config;

/**
 * Read-only host download settings exposed across the plugin boundary.
 */
public interface DownloadSettings {

    String getRootFolder();

    boolean isUserFlatFolder();

    int getMaxConcurrent();

    int getNovelMaxConcurrent();

    int getNovelTranslateMaxConcurrent();
}
