package top.sywyar.pixivdownload.push;

/**
 * 单个通道一次发送的结果。{@link PushService} 的广播会为每个参与的通道收集一条。
 * <p>
 * {@link #detail} 仅用于诊断（日志 / 未来的测试入口回显），<b>已脱敏</b>、绝不含 token / device-key 等密钥；
 * 成功时通常为 {@code null}。
 *
 * @param channel 通道类型
 * @param status  结果状态
 * @param detail  诊断详情（失败原因 / 跳过原因），已脱敏；成功时可为 {@code null}
 */
public record PushResult(PushChannelType channel, Status status, String detail) {

    public enum Status {
        /** 已成功发送（HTTP 2xx）。 */
        OK,
        /** 发送失败（网络错误 / 非 2xx / 序列化失败等）。 */
        FAILED,
        /** 未发送（通道未启用 / 未配置完整 / master 关闭）。 */
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
}
