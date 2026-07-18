package top.sywyar.pixivdownload.push;

import java.util.Set;

/**
 * 单个通道一次发送的结果。广播派发器为每个参与的通道收集一条。
 * <p>
 * {@link #detail} 仅用于诊断，必须已脱敏且不含凭证；受控原因保存
 * {@link #DETAIL_MESSAGE_PREFIX i18n key}，外部响应等动态诊断保留脱敏原文，成功时通常为 {@code null}。
 *
 * @param channel 通道类型
 * @param status  结果状态
 * @param detail  诊断详情（失败原因 / 跳过原因），已脱敏；成功时可为 {@code null}
 */
public record PushResult(PushChannelType channel, Status status, String detail) {

    public static final String DETAIL_MESSAGE_PREFIX = "push.result.detail.";
    public static final String DETAIL_CHANNEL_UNAVAILABLE = DETAIL_MESSAGE_PREFIX + "channel-unavailable";
    public static final String DETAIL_CHANNEL_NOT_CONFIGURED = DETAIL_MESSAGE_PREFIX + "channel-not-configured";
    public static final String DETAIL_SETTINGS_INCOMPLETE = DETAIL_MESSAGE_PREFIX + "settings-incomplete";
    public static final String DETAIL_SETTINGS_TYPE_MISMATCH = DETAIL_MESSAGE_PREFIX + "settings-type-mismatch";
    public static final String DETAIL_UNEXPECTED_ERROR = DETAIL_MESSAGE_PREFIX + "unexpected-error";
    public static final String DETAIL_SERIALIZATION_FAILED = DETAIL_MESSAGE_PREFIX + "serialization-failed";
    public static final String DETAIL_SIGNING_FAILED = DETAIL_MESSAGE_PREFIX + "signing-failed";
    public static final String DETAIL_INVALID_CONTENT_TYPE = DETAIL_MESSAGE_PREFIX + "invalid-content-type";
    public static final String DETAIL_INVALID_URL = DETAIL_MESSAGE_PREFIX + "invalid-url";

    private static final Set<String> CONTROLLED_DETAIL_MESSAGE_KEYS = Set.of(
            DETAIL_CHANNEL_UNAVAILABLE,
            DETAIL_CHANNEL_NOT_CONFIGURED,
            DETAIL_SETTINGS_INCOMPLETE,
            DETAIL_SETTINGS_TYPE_MISMATCH,
            DETAIL_UNEXPECTED_ERROR,
            DETAIL_SERIALIZATION_FAILED,
            DETAIL_SIGNING_FAILED,
            DETAIL_INVALID_CONTENT_TYPE,
            DETAIL_INVALID_URL);

    public enum Status {
        /** 已成功发送（HTTP 2xx）。 */
        OK,
        /** 发送失败（网络错误 / 非 2xx / 序列化失败等）。 */
        FAILED,
        /** 未发送（发送策略关闭或通道设置不完整）。 */
        SKIPPED
    }

    public static PushResult ok(PushChannelType channel) {
        return new PushResult(channel, Status.OK, null);
    }

    public static PushResult failed(PushChannelType channel, String detail) {
        return new PushResult(channel, Status.FAILED, detail);
    }

    public static PushResult skipped(PushChannelType channel, String reason) {
        return new PushResult(channel, Status.SKIPPED, reason);
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public boolean detailIsMessageKey() {
        return detail != null && CONTROLLED_DETAIL_MESSAGE_KEYS.contains(detail);
    }
}
