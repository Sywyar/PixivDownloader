package top.sywyar.pixivdownload.core.pixiv;

/**
 * Pixiv JSON 请求跨越宿主适配层时可观察的稳定失败类别。
 */
public enum PixivAjaxFailure {
    INVALID_TARGET,
    HTTP_STATUS,
    TRANSPORT
}
