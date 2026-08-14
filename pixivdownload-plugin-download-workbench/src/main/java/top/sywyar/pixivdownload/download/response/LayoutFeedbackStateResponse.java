package top.sywyar.pixivdownload.download.response;

import top.sywyar.pixivdownload.download.state.LayoutFeedbackDecision;

import java.util.List;

/**
 * 布局偏好调查服务端状态响应（服务端权威展示视图）。solo 模式返回
 * {@code available=true}、调查作用域匿名身份 {@code distinctId}（{@code plf_} 前缀，
 * 绝不返回原始安装 UUID）、稳定 {@code submissionId} 与服务端权威去重视图；multi
 * 模式只返回身份与提交 UUID，调查展示状态仍由浏览器本地维护。
 *
 * <p>浏览器只消费本视图的四个语义字段：
 * <ul>
 *   <li>{@code status}：服务端当前状态（null / submitted / never / snoozed）；</li>
 *   <li>{@code canShow}：服务端是否允许展示（只看 submitted / never / snooze 是否到期，
 *       不看页面可见性 / 其它弹窗 / DNT / Survey active）；</li>
 *   <li>{@code retryAfterMs}：status=snoozed 且 canShow=false 时的剩余毫秒
 *       （snoozedUntil - serverNow，钳制到 JavaScript 安全整数），其它情况为 0；</li>
 *   <li>{@code seenLayouts}：服务端已确认体验过的稳定布局 ID（固定顺序、无重复）。</li>
 * </ul>
 *
 * <p>响应不携带 serverTime / snoozedUntil / updatedAt / firstSeenAt / lastSeenAt /
 * 原始 install UUID / 状态文件内部 states map：服务端绝对时间点一律不进入浏览器，
 * 浏览器只把 retryAfterMs 转换为自己的本地临时截止时间。
 */
public record LayoutFeedbackStateResponse(
        boolean available,
        boolean stateAvailable,
        String distinctId,
        String submissionId,
        long revision,
        LayoutFeedbackDecision status,
        boolean canShow,
        long retryAfterMs,
        List<String> seenLayouts
) {
}
