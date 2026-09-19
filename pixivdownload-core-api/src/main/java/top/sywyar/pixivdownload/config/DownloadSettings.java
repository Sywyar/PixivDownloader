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
     * 作品目录模板：渲染变量与文件名模板一致，可用 {@code /} 分层，逐段做与文件名相同的安全清理。
     * 为空（或全空白）时沿用内置结构 {@code {rootFolder}[/{username}[/R18G|R18]]/{artworkId}/}；
     * 非空时作品直接落在 {@code {rootFolder}/{渲染结果}/}，不再追加 {@code {artworkId}} 层级。
     *
     * @return 作品目录模板；未配置时返回空串
     */
    default String getArtworkFolderTemplate() {
        return "";
    }

    /**
     * 返回最大值并发。
     *
     * @return 方法返回的数值
     */
    int getMaxConcurrent();
}
