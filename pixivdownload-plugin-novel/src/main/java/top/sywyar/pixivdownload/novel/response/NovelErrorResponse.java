package top.sywyar.pixivdownload.novel.response;

import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

/** 小说插件的插件内错误投影；对外稳定字段由 {@link ApiErrorResponse} 约束。 */
public record NovelErrorResponse(String code, String error) implements ApiErrorResponse {

    public NovelErrorResponse(String error) {
        this("novel.request.failed", error);
    }
}
