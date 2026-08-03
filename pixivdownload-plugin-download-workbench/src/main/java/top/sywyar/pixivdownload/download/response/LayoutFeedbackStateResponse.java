package top.sywyar.pixivdownload.download.response;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 布局偏好调查服务端状态响应。solo 模式返回 {@code available=true} 与安装身份
 * distinct_id 及服务端去重状态；multi 模式由控制器直接拒绝（403），前端回退
 * localStorage 实现。
 */
public record LayoutFeedbackStateResponse(boolean available, String distinctId, JsonNode state, JsonNode seen) {
}
