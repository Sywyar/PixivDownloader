package top.sywyar.pixivdownload.download.request;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 布局偏好调查服务端状态写入请求：{@code state} 为调查去重状态
 * （submitted / never / snoozed 等），{@code seen} 为已体验布局清单。
 */
public record LayoutFeedbackStateRequest(JsonNode state, JsonNode seen) {
}
