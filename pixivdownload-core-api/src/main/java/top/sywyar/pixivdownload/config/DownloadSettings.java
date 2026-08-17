package top.sywyar.pixivdownload.config;

/**
 * 跨插件边界共享的只读宿主下载设置。
 * 业务专用的执行设置仍由对应插件所有。
 */
public interface DownloadSettings {

    /**
     * 返回根目录目录。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    String getRootFolder();

    /**
     * 判断用户扁平目录目录是否满足条件。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isUserFlatFolder();

    /**
     * 返回最大值并发。
     *
     * @return 方法返回的数值
     */
    int getMaxConcurrent();
}
