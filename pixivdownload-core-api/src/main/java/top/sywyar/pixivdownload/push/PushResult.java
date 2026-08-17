package top.sywyar.pixivdownload.push;

import java.util.Set;

/**
 * 单个通道一次发送的结果。广播派发器为每个参与的通道收集一条。
 * <p>
 * {@link #detail} 仅用于诊断，必须已脱敏且不含凭证；受控原因保存
 * {@link #DETAIL_MESSAGE_PREFIX i18n key}，外部响应等动态诊断保留脱敏原文，成功时通常为 {@code null}。
 *
 * @param channel 通道标识
 * @param status  结果状态
 * @param detail  诊断详情（失败原因 / 跳过原因），已脱敏；成功时可为 {@code null}
 */
public record PushResult(PushChannelId channel, Status status, String detail) {

    /**
     * 消息前缀详情代码。
     */
    public static final String DETAIL_MESSAGE_PREFIX = "push.result.detail.";
    /**
     * {@code CHANNEL_UNAVAILABLE} 对应的值详情代码。
     */
    public static final String DETAIL_CHANNEL_UNAVAILABLE = DETAIL_MESSAGE_PREFIX + "channel-unavailable";
    /**
     * 通道非空状态配置状态详情代码。
     */
    public static final String DETAIL_CHANNEL_NOT_CONFIGURED = DETAIL_MESSAGE_PREFIX + "channel-not-configured";
    /**
     * {@code SETTINGS_INCOMPLETE} 对应的值详情代码。
     */
    public static final String DETAIL_SETTINGS_INCOMPLETE = DETAIL_MESSAGE_PREFIX + "settings-incomplete";
    /**
     * {@code SETTINGS_TYPE_MISMATCH} 对应的值详情代码。
     */
    public static final String DETAIL_SETTINGS_TYPE_MISMATCH = DETAIL_MESSAGE_PREFIX + "settings-type-mismatch";
    /**
     * {@code UNEXPECTED_ERROR} 对应的值详情代码。
     */
    public static final String DETAIL_UNEXPECTED_ERROR = DETAIL_MESSAGE_PREFIX + "unexpected-error";
    /**
     * {@code SERIALIZATION_FAILED} 对应的值详情代码。
     */
    public static final String DETAIL_SERIALIZATION_FAILED = DETAIL_MESSAGE_PREFIX + "serialization-failed";
    /**
     * {@code SIGNING_FAILED} 对应的值详情代码。
     */
    public static final String DETAIL_SIGNING_FAILED = DETAIL_MESSAGE_PREFIX + "signing-failed";
    /**
     * {@code INVALID_CONTENT_TYPE} 对应的值详情代码。
     */
    public static final String DETAIL_INVALID_CONTENT_TYPE = DETAIL_MESSAGE_PREFIX + "invalid-content-type";
    /**
     * {@code INVALID_URL} 对应的值详情代码。
     */
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

    /**
     * 表示 {@code 该枚举值} 状态。
     */
    public enum Status {
        /** 已成功发送（HTTP 2xx）。 */
        OK,
        /** 发送失败（网络错误 / 非 2xx / 序列化失败等）。 */
        FAILED,
        /** 未发送（发送策略关闭或通道设置不完整）。 */
        SKIPPED
    }

    /**
     * 执行成功状态并返回结果。
     *
     * @param channel 通道
     * @return 方法返回的 {@code PushResult} 实例
     */
    public static PushResult ok(PushChannelId channel) {
        return new PushResult(channel, Status.OK, null);
    }

    /**
     * 执行对应操作并返回结果。
     *
     * @param channel 通道
     * @param detail 详情
     * @return 方法返回的 {@code PushResult} 实例
     */
    public static PushResult failed(PushChannelId channel, String detail) {
        return new PushResult(channel, Status.FAILED, detail);
    }

    /**
     * 执行已跳过并返回结果。
     *
     * @param channel 通道
     * @param reason 原因
     * @return 方法返回的 {@code PushResult} 实例
     */
    public static PushResult skipped(PushChannelId channel, String reason) {
        return new PushResult(channel, Status.SKIPPED, reason);
    }

    /**
     * 判断成功状态是否满足条件。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isOk() {
        return status == Status.OK;
    }

    /**
     * 返回对应值。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    public boolean detailIsMessageKey() {
        return detail != null && CONTROLLED_DETAIL_MESSAGE_KEYS.contains(detail);
    }
}
