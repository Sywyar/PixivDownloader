package top.sywyar.pixivdownload.core.pixiv.thumbnail;

/**
 * Pixiv 缩略图请求跨越宿主适配层时可观察的稳定失败类别。
 */
public enum PixivThumbnailFailure {
    /**
     * 表示 {@code INVALID_TARGET} 状态。
     */
    INVALID_TARGET,
    /**
     * 表示 {@code HTTP_STATUS} 状态。
     */
    HTTP_STATUS,
    /**
     * 表示 {@code TRANSPORT}。
     */
    TRANSPORT
}
