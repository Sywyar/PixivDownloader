package top.sywyar.pixivdownload.download.response;

import top.sywyar.pixivdownload.download.state.LayoutFeedbackSeenEntry;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateEntry;

import java.util.Map;

/**
 * 布局偏好调查服务端状态响应。solo 模式返回 {@code available=true}、调查作用域匿名
 * 身份 {@code distinctId}（{@code plf_} 前缀，绝不返回原始安装 UUID）与服务端去重状态；
 * multi 模式由控制器直接拒绝（403），前端回退 localStorage 实现。
 *
 * <p>GET 响应中的 {@code state} 只在服务端记录的 surveyId 与请求一致时返回，否则为 null；
 * {@code seen} 与 {@code revision} 始终返回。409 冲突响应携带当前完整快照。
 *
 * <p>{@code serverTime} 是服务端当前时间（Unix epoch 毫秒），供客户端估算服务端时钟，
 * 只用于跨时钟域比较；不写入状态文件、不参与 revision、不包含隐私信息。
 */
public record LayoutFeedbackStateResponse(
        boolean available,
        boolean stateAvailable,
        String distinctId,
        long serverTime,
        long revision,
        LayoutFeedbackStateEntry state,
        Map<String, LayoutFeedbackSeenEntry> seen
) {
}
