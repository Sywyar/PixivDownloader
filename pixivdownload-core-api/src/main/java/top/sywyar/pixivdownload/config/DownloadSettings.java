package top.sywyar.pixivdownload.config;

/**
 * Read-only host download settings shared across plugin boundaries.
 * Business-specific execution settings remain owned by their plugins.
 */
public interface DownloadSettings {

    String getRootFolder();

    boolean isUserFlatFolder();

    int getMaxConcurrent();
}
