package top.sywyar.pixivdownload.download.response.error;

import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

/** 下载工作台的插件内错误投影；对外稳定字段由 {@link ApiErrorResponse} 约束。 */
public record ErrorResponse(String code, String error) implements ApiErrorResponse {

    public ErrorResponse(String error) {
        this("pixiv.proxy.request-failed", error);
    }
}
